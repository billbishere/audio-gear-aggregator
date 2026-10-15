package audiogear.search;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Clean-up for text coming back from search APIs. */
final class Text {
    private Text() {}

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ");

    /** Removes HTML tags (Brave wraps matches in &lt;strong&gt;) and decodes common entities. */
    static String plain(String html) {
        if (html == null) return null;
        String s = TAG.matcher(html).replaceAll("");
        Matcher m = ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#x") || e.startsWith("#X")) r = codePoint(e.substring(2), 16, m.group());
            else if (e.startsWith("#")) r = codePoint(e.substring(1), 10, m.group());
            else r = NAMED.getOrDefault(e.toLowerCase(), m.group());
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    private static String codePoint(String digits, int radix, String fallback) {
        try {
            return new String(Character.toChars(Integer.parseInt(digits, radix)));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }

    /** ISO date-time with or without offset, as a UTC instant string; null when unparseable. */
    static String isoInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toString();
            } catch (DateTimeParseException ignored2) {
                return null;
            }
        }
    }

    static String epochSeconds(double seconds) {
        return Instant.ofEpochSecond((long) seconds).toString();
    }
}
