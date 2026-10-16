package audiogear.domain;

import java.util.Locale;

/** Human-readable labels for enum constants: OVER_EAR -> "Over-ear", AMP_DAC -> "Amp/DAC". */
public final class Enums {
    private Enums() {}

    public static String label(Enum<?> e) {
        if (e == null) return "";
        return switch (e.name()) {
            case "AMP_DAC" -> "Amp/DAC";
            case "DAC", "BA", "SE", "IEM", "FR", "ASR", "SBAF", "AVG", "L", "R" -> e.name();
            case "HEAD_FI" -> "Head-Fi";
            case "YOUTUBE" -> "YouTube";
            case "DB_PER_MW" -> "dB/mW";
            case "DB_PER_V" -> "dB/V";
            case "B_STOCK" -> "B-stock";
            default -> {
                String s = e.name().replace('_', '-').toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        };
    }

    /** Accepts the constant name, its label, or either with separators removed ("over ear", "OVER_EAR", "Over-ear"). */
    public static <E extends Enum<E>> E parse(Class<E> type, String text) {
        if (text == null || text.isBlank()) return null;
        String t = squash(text);
        for (E c : type.getEnumConstants()) {
            if (squash(c.name()).equals(t) || squash(label(c)).equals(t)) return c;
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + text);
    }

    private static String squash(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[-_ /]", "");
    }
}
