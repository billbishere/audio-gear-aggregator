package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.CurveKind;
import audiogear.domain.Enums;
import audiogear.domain.ForumThread;
import audiogear.domain.GearDetail;
import audiogear.domain.GearNames;
import audiogear.domain.LinkKind;
import audiogear.domain.ThreadSource;
import audiogear.search.GearNameMatcher;
import audiogear.search.UrlNormalizer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Imports a research bundle (JSON): gear with specs, the community threads that discuss each item, and the names
 * of AutoEq measurements to attach as FR curves. This is how research gathered by hand or by an assistant gets
 * into the catalog without any search API.
 *
 * <pre>
 * { "title": "...", "gathered": "2026-10-15",
 *   "items": [ { "gear": { "type": "HEADPHONE", "brand": "...", "model": "...", ...catalog CSV columns... },
 *                "autoeq": ["Sennheiser HD 600"],
 *                "threads": [ { "url": "...", "title": "...", "posted": "2026-06-24", "snippet": "..." } ] } ] }
 * </pre>
 */
public final class ResearchImporter {

    public record ResearchThread(String url, String title, String source, String posted, String snippet) {}

    public record ResearchItem(Map<String, String> gear, List<String> autoeq, List<ResearchThread> threads) {}

    public record ResearchFile(String title, String gathered, List<ResearchItem> items) {}

    /** summary counts gear items; notes are informational (e.g. no AutoEq measurement yet). */
    public record Result(ImportSummary summary, int threads, int curves, List<String> notes) {
        public String headline() {
            return summary.imported() + " gear items, " + threads + " threads, " + curves + " curves imported"
                    + (summary.skipped() > 0 ? ", " + summary.skipped() + " skipped" : "");
        }

        /** Problems first, then notes, for the summary dialog. */
        public ImportSummary forDialog() {
            List<String> lines = new ArrayList<>(summary.problems());
            lines.addAll(notes);
            return new ImportSummary(summary.imported(), summary.skipped(), lines, summary.cancelled());
        }
    }

    /** Reviewers tried first when several have measured the same model. */
    static final List<String> PREFERRED_SOURCES = List.of("oratory1990", "crinacle", "Rtings", "Super Review", "Kuulokenurkka");
    static final int MAX_CURVES_PER_ITEM = 2;

    private final GearRepository gear;
    private final ThreadRepository threads;
    private final CurveRepository curves;
    private final AutoEqClient autoEq;
    private final AutoEqImporter autoEqImporter;
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** autoEq and autoEqImporter may be null to skip curve import. */
    public ResearchImporter(GearRepository gear, ThreadRepository threads, CurveRepository curves,
                            AutoEqClient autoEq, AutoEqImporter autoEqImporter) {
        this.gear = gear;
        this.threads = threads;
        this.curves = curves;
        this.autoEq = autoEq;
        this.autoEqImporter = autoEqImporter;
    }

    public Result importJson(Reader reader, Progress progress) throws IOException {
        return importJson(reader, progress, LinkKind.MANUAL);
    }

    /** linkKind marks how the file's threads attach to their gear: MANUAL for curated research, AUTO for matched feeds. */
    public Result importJson(Reader reader, Progress progress, LinkKind linkKind) throws IOException {
        ResearchFile file = json.readValue(reader, ResearchFile.class);
        List<ResearchItem> items = file.items() == null ? List.of() : file.items();
        List<String> problems = new ArrayList<>(), notes = new ArrayList<>();
        int imported = 0, skipped = 0, threadCount = 0, curveCount = 0;
        boolean cancelled = false;

        List<AutoEqClient.Entry> index = items.stream().anyMatch(i -> i.autoeq() != null && !i.autoeq().isEmpty())
                ? loadAutoEqIndex(progress, notes) : List.of();
        List<Long> newThreadIds = new ArrayList<>();

        for (int n = 0; n < items.size(); n++) {
            if (progress.isCancelled()) { cancelled = true; break; }
            ResearchItem item = items.get(n);
            Map<String, String> row = lowerKeys(item.gear());
            String name = GearNames.display(row.get("brand"), row.get("model"), row.get("variant"));
            progress.update(n, items.size(), name);
            long gearId;
            try {
                GearDetail base = gear.findId(require(row, "brand"), require(row, "model"), row.getOrDefault("variant", ""))
                        .flatMap(gear::get).orElse(null);
                gearId = gear.save(CatalogCsv.apply(base, base == null ? row : fillBlanksOnly(base, row)));
                imported++;
            } catch (RuntimeException e) {
                skipped++;
                problems.add(name + ": " + e.getMessage());
                continue;
            }

            for (ResearchThread t : item.threads() == null ? List.<ResearchThread>of() : item.threads()) {
                try {
                    long id = threads.upsert(toThread(t));
                    threads.link(id, gearId, linkKind);
                    newThreadIds.add(id);
                    threadCount++;
                } catch (RuntimeException e) {
                    problems.add(name + ": thread " + t.url() + ": " + e.getMessage());
                }
            }

            if (item.autoeq() != null && !item.autoeq().isEmpty() && autoEqImporter != null && !index.isEmpty()) {
                List<AutoEqClient.Entry> matches = pickEntries(index, item.autoeq());
                if (matches.isEmpty()) notes.add(name + ": no AutoEq measurement yet (looked for " + String.join(", ", item.autoeq()) + ")");
                for (AutoEqClient.Entry e : matches) {
                    try {
                        autoEqImporter.importCurve(e, gearId);
                        curveCount++;
                    } catch (Exception ex) {
                        problems.add(name + ": AutoEq " + e.sourceLabel() + ": " + ex.getMessage());
                    }
                }
            }
        }

        // A thread comparing two catalog items should show up under both
        GearNameMatcher matcher = new GearNameMatcher(gear.listRefs());
        for (ForumThread t : threads.byIds(new HashSet<>(newThreadIds))) {
            for (long g : matcher.match(t.title() + " " + (t.snippet() == null ? "" : t.snippet()))) threads.link(t.id(), g, LinkKind.AUTO);
        }

        if (autoEqImporter != null && curves.byKind(CurveKind.TARGET).isEmpty() && !index.isEmpty()) {
            ImportSummary targets = autoEqImporter.importTargets();
            curveCount += targets.imported();
            problems.addAll(targets.problems());
        }
        progress.update(items.size(), items.size(), "Done");
        return new Result(new ImportSummary(imported, skipped, problems, cancelled), threadCount, curveCount, notes);
    }

    private List<AutoEqClient.Entry> loadAutoEqIndex(Progress progress, List<String> notes) {
        if (autoEq == null) return List.of();
        try {
            progress.update(0, 0, "Listing AutoEq measurements");
            List<AutoEqClient.Source> sources = new ArrayList<>(autoEq.sources());
            sources.sort(Comparator.comparingInt(s -> {
                int i = PREFERRED_SOURCES.indexOf(s.name());
                return i < 0 ? PREFERRED_SOURCES.size() : i;
            }));
            List<AutoEqClient.Entry> all = new ArrayList<>();
            for (AutoEqClient.Source s : sources) {
                if (progress.isCancelled()) break;
                progress.update(0, 0, "Listing AutoEq measurements: " + s.name());
                all.addAll(autoEq.entries(s));
            }
            return all;
        } catch (IOException e) {
            notes.add("AutoEq measurements unavailable, so no curves were added: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Up to {@link #MAX_CURVES_PER_ITEM} measurements whose name (ignoring a trailing "(sample 2)"-style label)
     * matches one of the aliases, from different reviewers in preference order; plain names beat labelled ones.
     */
    static List<AutoEqClient.Entry> pickEntries(List<AutoEqClient.Entry> index, List<String> aliases) {
        Set<String> wanted = new HashSet<>();
        for (String a : aliases) wanted.add(GearNames.normalize(a));
        Map<String, AutoEqClient.Entry> bySource = new LinkedHashMap<>();
        for (int pass = 0; pass < 2; pass++) {
            for (AutoEqClient.Entry e : index) {
                String full = GearNames.normalize(e.name());
                String base = GearNames.normalize(e.name().replaceAll("\\s*\\([^()]*\\)\\s*$", ""));
                boolean match = pass == 0 ? wanted.contains(full) : wanted.contains(base);
                if (match) bySource.putIfAbsent(e.source(), e);
            }
        }
        List<AutoEqClient.Entry> out = new ArrayList<>(bySource.values());
        out.sort(Comparator.comparingInt(e -> {
            int i = PREFERRED_SOURCES.indexOf(e.source());
            return i < 0 ? PREFERRED_SOURCES.size() : i;
        }));
        return out.size() > MAX_CURVES_PER_ITEM ? out.subList(0, MAX_CURVES_PER_ITEM) : out;
    }

    static ForumThread toThread(ResearchThread t) {
        if (t.url() == null || t.url().isBlank()) throw new IllegalArgumentException("url is required");
        if (t.title() == null || t.title().isBlank()) throw new IllegalArgumentException("title is required");
        String url = UrlNormalizer.normalize(t.url());
        ThreadSource source = t.source() == null || t.source().isBlank() ? UrlNormalizer.classify(url) : Enums.parse(ThreadSource.class, t.source());
        return new ForumThread(null, source, url, t.title().strip(), t.snippet() == null || t.snippet().isBlank() ? null : t.snippet().strip(),
                null, parseDate(t.posted()), null, null, null, null);
    }

    /**
     * "2026-06-24" or a full ISO instant; null when blank. A bare date is stored as midday UTC so it shows as the
     * same calendar date in every time zone from UTC-11 to UTC+11.
     */
    static String parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.strip()).atTime(12, 0).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            return Instant.parse(s.strip()).toString();
        }
    }

    /**
     * Research describes gear from the outside, so for gear already in the catalog it only fills fields that are
     * still empty and adds its tags to the existing ones. Values already there (often corrected by hand) win.
     */
    static Map<String, String> fillBlanksOnly(GearDetail base, Map<String, String> row) {
        Map<String, String> existing = CatalogCsv.toRow(base);
        Map<String, String> out = new LinkedHashMap<>();
        row.forEach((col, value) -> {
            if (col.equals("tags")) {
                Set<String> tags = new LinkedHashSet<>(base.tags());
                for (String t : value.split("\\s*,\\s*")) if (!t.isBlank()) tags.add(t);
                out.put(col, String.join(", ", tags));
            } else if (existing.getOrDefault(col, "").isBlank()) {
                out.put(col, value);
            }
        });
        return out;
    }

    private static Map<String, String> lowerKeys(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT).strip(), v == null ? "" : v.strip()));
        return out;
    }

    private static String require(Map<String, String> row, String col) {
        String v = row.get(col);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(col + " is required");
        return v;
    }
}
