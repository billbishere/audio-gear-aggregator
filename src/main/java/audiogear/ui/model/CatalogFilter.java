package audiogear.ui.model;

import audiogear.domain.DriverType;
import audiogear.domain.Form;
import audiogear.domain.GearNames;
import audiogear.domain.GearStatus;
import audiogear.domain.GearSummary;
import audiogear.domain.GearType;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;

/** The catalog's filter bar as a predicate. Null criteria match everything. */
public record CatalogFilter(Tab tab, String text, GearStatus status, DriverType driverType, Form form,
                            Double minImpedance, Double maxImpedance, Double minPrice, Double maxPrice, String tag)
        implements Predicate<GearSummary> {

    public enum Tab {
        ALL("All"), HEADPHONES("Headphones"), IEMS("IEMs"), AMPS("Amps"), DACS("DACs");

        public final String label;

        Tab(String label) { this.label = label; }

        /** Amp/DAC combos appear under both Amps and DACs. */
        public boolean includes(GearType t) {
            return switch (this) {
                case ALL -> true;
                case HEADPHONES -> t == GearType.HEADPHONE;
                case IEMS -> t == GearType.IEM;
                case AMPS -> t.isAmp();
                case DACS -> t.isDac();
            };
        }
    }

    public static CatalogFilter none() {
        return new CatalogFilter(Tab.ALL, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public boolean test(GearSummary g) {
        if (tab != null && !tab.includes(g.type())) return false;
        if (status != null && g.status() != status) return false;
        if (driverType != null && g.driverType() != driverType) return false;
        if (form != null && g.form() != form) return false;
        if (!inRange(g.impedanceOhm(), minImpedance, maxImpedance)) return false;
        if (!inRange(g.effectivePrice(), minPrice, maxPrice)) return false;
        if (tag != null && !tag.isBlank()) {
            if (g.tags() == null) return false;
            boolean found = Arrays.stream(g.tags().split(",")).anyMatch(t -> t.strip().equalsIgnoreCase(tag.strip()));
            if (!found) return false;
        }
        if (text != null && !text.isBlank()) {
            String hay = GearNames.normalize(String.join(" ", nz(g.brand()), nz(g.model()), nz(g.variant()),
                    nz(g.driverConfig()), nz(g.tags()), nz(g.chip())));
            for (String word : text.toLowerCase(Locale.ROOT).split("\\s+")) {
                String w = GearNames.normalize(word);
                if (!w.isEmpty() && !hay.contains(w)) return false;
            }
        }
        return true;
    }

    private static boolean inRange(Double v, Double min, Double max) {
        if (min == null && max == null) return true;
        if (v == null) return false;
        return (min == null || v >= min) && (max == null || v <= max);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
