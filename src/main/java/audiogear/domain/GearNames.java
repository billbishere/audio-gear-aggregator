package audiogear.domain;

import java.util.Locale;

public final class GearNames {
    private GearNames() {}

    public static String display(String brand, String model, String variant) {
        String base = ((brand == null ? "" : brand) + " " + (model == null ? "" : model)).trim();
        return variant == null || variant.isBlank() ? base : base + " (" + variant + ")";
    }

    /** Lowercase, letters and digits only: "HD-650" and "HD 650" both become "hd650". */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }
}
