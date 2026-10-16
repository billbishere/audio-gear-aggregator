package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.GearRepository;
import audiogear.domain.Channel;
import audiogear.domain.Curve;
import audiogear.domain.CurveKind;
import audiogear.domain.Form;
import audiogear.domain.Gear;
import audiogear.domain.GearDetail;
import audiogear.domain.GearNames;
import audiogear.domain.GearType;
import audiogear.domain.TransducerSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Imports AutoEq measurements as FR curves. Gear is matched by normalised brand + model, so re-imports add or
 * replace curves rather than duplicating gear. Downloads run four at a time; database writes stay on one thread.
 */
public final class AutoEqImporter {

    private static final int PARALLEL_DOWNLOADS = 4;

    private final AutoEqClient client;
    private final GearRepository gear;
    private final CurveRepository curves;
    private final BrandParser brands;

    public AutoEqImporter(AutoEqClient client, GearRepository gear, CurveRepository curves, BrandParser brands) {
        this.client = client;
        this.gear = gear;
        this.curves = curves;
        this.brands = brands;
    }

    public ImportSummary importEntries(List<AutoEqClient.Entry> entries, Progress progress) {
        // Files already in the catalog are left out entirely (neither imported nor skipped), so importing twice adds nothing
        Set<String> have = curves.origins();
        entries = entries.stream().filter(e -> !have.contains(client.rawUrl(e.path()))).toList();
        Map<String, Long> known = new HashMap<>();
        for (var ref : gear.listRefs()) {
            if (ref.variant() == null || ref.variant().isEmpty()) known.put(key(ref.brand(), ref.model()), ref.id());
        }
        int imported = 0, skipped = 0;
        List<String> problems = new ArrayList<>();
        boolean cancelled = false;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, r -> {
            Thread t = new Thread(r, "autoeq-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<String>> downloads = new ArrayList<>(entries.size());
            for (var e : entries) downloads.add(pool.submit(() -> client.download(e.path())));
            for (int i = 0; i < entries.size(); i++) {
                if (progress.isCancelled()) { cancelled = true; break; }
                var e = entries.get(i);
                progress.update(i, entries.size(), e.name());
                try {
                    String text = downloads.get(i).get();
                    CurveFileParser.Parsed parsed = CurveFileParser.parse(text);
                    BrandParser.Name name = brands.parse(e.name());
                    long gearId = known.computeIfAbsent(key(name.brand(), name.model()),
                            k -> createGear(name, "over-ear".equals(e.form()) ? GearType.HEADPHONE : GearType.IEM, formOf(e.form())));
                    curves.save(new Curve(null, gearId, CurveKind.FR, e.sourceLabel(), name.label(), Channel.AVG,
                            parsed.freqHz(), parsed.value(), null, client.rawUrl(e.path()), null));
                    curves.markAutoEqSeen(client.rawUrl(e.path()));
                    imported++;
                } catch (ExecutionException ex) {
                    skipped++;
                    problems.add(e.name() + " [" + e.sourceLabel() + "]: download failed: " + ex.getCause().getMessage());
                } catch (CurveFileParser.ParseException | RuntimeException ex) {
                    skipped++;
                    problems.add(e.name() + " [" + e.sourceLabel() + "]: " + ex.getMessage());
                    curves.markAutoEqSeen(client.rawUrl(e.path())); // unreadable file: don't retry it every day
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                }
            }
            progress.update(entries.size(), entries.size(), "Done");
        } finally {
            pool.shutdownNow();
        }
        return new ImportSummary(imported, skipped, problems, cancelled);
    }

    /** Imports one measurement as an FR curve of the given gear item, without name matching. */
    public void importCurve(AutoEqClient.Entry e, long gearId) throws IOException, CurveFileParser.ParseException {
        CurveFileParser.Parsed parsed = CurveFileParser.parse(client.download(e.path()));
        curves.save(new Curve(null, gearId, CurveKind.FR, e.sourceLabel(), brands.parse(e.name()).label(), Channel.AVG,
                parsed.freqHz(), parsed.value(), null, client.rawUrl(e.path()), null));
    }

    /** Imports the Harman over-ear 2018 and in-ear 2019 targets as TARGET curves. */
    public ImportSummary importTargets() {
        int imported = 0;
        List<String> problems = new ArrayList<>();
        for (String path : List.of(AutoEqClient.TARGET_OVER_EAR, AutoEqClient.TARGET_IN_EAR)) {
            String label = path.substring(path.lastIndexOf('/') + 1).replace(".csv", "");
            try {
                CurveFileParser.Parsed p = CurveFileParser.parse(client.download(path));
                curves.save(new Curve(null, null, CurveKind.TARGET, "AutoEq", label, Channel.AVG, p.freqHz(), p.value(), null,
                        client.rawUrl(path), null));
                imported++;
            } catch (Exception e) {
                problems.add(label + ": " + e.getMessage());
            }
        }
        return new ImportSummary(imported, problems.size(), problems, false);
    }

    private long createGear(BrandParser.Name name, GearType type, Form form) {
        Gear g = Gear.create(type, name.brand(), name.model());
        TransducerSpec t = new TransducerSpec(null, form, null, null, null, null, null, null, null, null, null);
        return gear.findId(name.brand(), name.model(), "")
                .orElseGet(() -> gear.save(new GearDetail(g, t, null, List.of(), null, List.of())));
    }

    private static Form formOf(String folder) {
        return switch (folder) {
            case "in-ear" -> Form.IN_EAR;
            case "earbud" -> Form.EARBUD;
            default -> Form.OVER_EAR;
        };
    }

    static String key(String brand, String model) {
        return GearNames.normalize(brand) + "|" + GearNames.normalize(model);
    }
}
