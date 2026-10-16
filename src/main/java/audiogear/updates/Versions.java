package audiogear.updates;

/** Compares dotted version numbers numerically, so 1.10.0 is newer than 1.9.0. */
public final class Versions {
    private Versions() {}

    /** False when either side is missing or isn't numeric (e.g. a "dev" build never gets update notices). */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate), b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null || v.isBlank()) return null;
        String[] parts = v.strip().split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
