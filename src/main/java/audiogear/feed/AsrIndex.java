package audiogear.feed;

import audiogear.domain.GearType;
import audiogear.importers.ResearchImporter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Audio Science Review's measurement database (asrdata/ElectronicsallList): every reviewed device with its type,
 * SINAD, price and review link. Turns the amps and DACs in it into research-file items for the catalog.
 */
public final class AsrIndex {
    private AsrIndex() {}

    public static final String URL = "https://www.audiosciencereview.com/asrdata/ElectronicsallList?recperpage=ALL";

    /** One review row. sinad and priceUsd are null when ASR lists none. */
    public record Device(String type, String brand, String model, Double sinad, Double priceUsd, String reviewer,
                         boolean recommended, LocalDate reviewed, String reviewUrl) {}

    private record Kind(GearType type, String tag) {}

    /** ASR device types that belong in the catalog, with an extra tag that tells similar devices apart. */
    private static final Map<String, Kind> KINDS = Map.ofEntries(
            entry("DAC+Headphone AMP", new Kind(GearType.AMP_DAC, "dac-amp")),
            entry("Headphone Amp (Dongle)", new Kind(GearType.AMP_DAC, "dongle")),
            entry("USB Dongle", new Kind(GearType.AMP_DAC, "dongle")),
            entry("Cable DAC", new Kind(GearType.AMP_DAC, "dongle")),
            entry("DAP", new Kind(GearType.AMP_DAC, "dap")),
            entry("DAC+Speaker AMP", new Kind(GearType.AMP_DAC, "speaker-amp")),
            entry("Streamer Amplifier", new Kind(GearType.AMP_DAC, "speaker-amp")),
            entry("DAC (Dedicated)", new Kind(GearType.DAC, null)),
            entry("DAC", new Kind(GearType.DAC, null)),
            entry("DAC (Multifunction)", new Kind(GearType.DAC, "multifunction")),
            entry("DAC+Preamp", new Kind(GearType.DAC, "preamp")),
            entry("DAC+Digital Source", new Kind(GearType.DAC, "streamer")),
            entry("ADC/DAC Interface", new Kind(GearType.DAC, "interface")),
            entry("ADC/DAC (Thumb Drive)", new Kind(GearType.DAC, "interface")),
            entry("Headphone AMP", new Kind(GearType.AMP, null)),
            entry("Headphone Amp (Dedicated)", new Kind(GearType.AMP, null)),
            entry("Headphone Amp (Multifunction)", new Kind(GearType.AMP, "multifunction")),
            entry("Headphone Amp (Electrostatic)", new Kind(GearType.AMP, "electrostatic")),
            entry("Power AMP", new Kind(GearType.AMP, "speaker-amp")),
            entry("Speaker AMP", new Kind(GearType.AMP, "speaker-amp")),
            entry("Amplifier", new Kind(GearType.AMP, "speaker-amp")));

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td data-name=\"(\\w+)\"[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href=['\"]([^'\"]+)['\"]");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d/yy");

    public static List<Device> parse(String html) {
        List<Device> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            Map<String, String> cells = new LinkedHashMap<>();
            String link = null;
            Matcher c = CELL.matcher(rows.group(1));
            while (c.find()) {
                cells.put(c.group(1), text(c.group(2)));
                if (c.group(1).equals("ReviewLink")) {
                    Matcher h = HREF.matcher(c.group(2));
                    if (h.find()) link = unescape(h.group(1));
                }
            }
            if (!cells.containsKey("DeviceType")) continue;
            out.add(new Device(cells.get("DeviceType"), cells.getOrDefault("Brand", ""), cells.getOrDefault("Model", ""),
                    number(cells.get("SINAD")), number(cells.get("Price_Each_USD")), cells.getOrDefault("Reviewer", ""),
                    "yes".equalsIgnoreCase(cells.get("Recommended")), date(cells.get("ReviewDate")), link));
        }
        return out;
    }

    public static List<Device> reviewedSince(List<Device> devices, LocalDate from) {
        return devices.stream().filter(d -> d.reviewed() != null && !d.reviewed().isBefore(from)).toList();
    }

    /** The amps and DACs as research items: one per model, specs from its newest review, every review as a thread. */
    public static List<ResearchImporter.ResearchItem> toItems(List<Device> devices) {
        Map<String, List<Device>> byModel = new LinkedHashMap<>();
        for (Device d : devices) {
            if (!KINDS.containsKey(d.type()) || d.brand().isBlank() || d.model().isBlank() || d.reviewUrl() == null) continue;
            byModel.computeIfAbsent((d.brand() + "\u0000" + d.model()).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(d);
        }
        List<ResearchImporter.ResearchItem> items = new ArrayList<>();
        for (List<Device> reviews : byModel.values()) {
            reviews.sort(Comparator.comparing(Device::reviewed, Comparator.nullsLast(Comparator.reverseOrder())));
            Device latest = reviews.getFirst();
            Kind kind = KINDS.get(latest.type());
            Map<String, String> gear = new LinkedHashMap<>();
            gear.put("type", kind.type().name());
            gear.put("brand", latest.brand());
            gear.put("model", latest.model());
            if (kind.type().isDac() && latest.sinad() != null) gear.put("sinad_db", format(latest.sinad()));
            if (latest.priceUsd() != null) {
                gear.put("msrp", format(latest.priceUsd()));
                gear.put("msrp_currency", "USD");
            }
            Set<String> tags = new LinkedHashSet<>(List.of("asr-reviewed"));
            if (latest.recommended()) tags.add("asr-recommended");
            if (kind.tag() != null) tags.add(kind.tag());
            gear.put("tags", String.join(", ", tags));
            gear.put("notes", "Measured by Audio Science Review" + (latest.sinad() == null ? "" : ": SINAD " + format(latest.sinad()) + " dB")
                    + " (" + latest.reviewer() + (latest.reviewed() == null ? "" : ", " + latest.reviewed()) + ")"
                    + (latest.recommended() ? ", recommended." : ".")
                    + (reviews.size() > 1 ? " " + reviews.size() + " ASR reviews." : ""));

            List<ResearchImporter.ResearchThread> threads = new ArrayList<>();
            Set<String> urls = new LinkedHashSet<>();
            for (Device r : reviews) {
                if (!urls.add(r.reviewUrl())) continue;
                threads.add(new ResearchImporter.ResearchThread(r.reviewUrl(), "ASR review: " + r.brand() + " " + r.model(), "ASR",
                        r.reviewed() == null ? null : r.reviewed().toString(),
                        "Measured by " + r.reviewer() + (r.sinad() == null ? "" : ": SINAD " + format(r.sinad()) + " dB")
                                + (r.recommended() ? ", recommended." : ".")));
            }
            items.add(new ResearchImporter.ResearchItem(gear, null, threads));
        }
        items.sort(Comparator.comparing((ResearchImporter.ResearchItem i) -> i.gear().get("brand").toLowerCase(Locale.ROOT))
                .thenComparing(i -> i.gear().get("model").toLowerCase(Locale.ROOT)));
        return items;
    }

    private static String text(String cellHtml) {
        return unescape(cellHtml.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").strip();
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))));
        m.appendTail(b);
        return b.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static Double number(String s) {
        if (s == null) return null;
        String v = s.replace("$", "").replace(",", "").strip();
        try {
            double d = Double.parseDouble(v);
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.strip(), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String format(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
