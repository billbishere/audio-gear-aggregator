package audiogear.importers;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads curve text files: AutoEq/CSV ("frequency,raw"), REW text exports ("*" comment lines, whitespace columns),
 * squig.link .txt, TSV, and semicolon files with decimal commas. Columns are frequency, value and optionally phase.
 */
public final class CurveFileParser {
    private CurveFileParser() {}

    public static final double MIN_HZ = 1, MAX_HZ = 100_000;

    /** phase is null when the file has only two columns. */
    public record Parsed(double[] freqHz, double[] value, double[] phaseDeg) {
        public int size() { return freqHz.length; }
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) { super(message); }
    }

    public static Parsed parse(String text) throws ParseException {
        List<double[]> rows = new ArrayList<>();
        boolean allHavePhase = true;
        String[] lines = text.split("\\R");
        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln].strip();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) continue;
            String[] tokens = tokenize(line);
            double[] nums = new double[Math.min(tokens.length, 3)];
            boolean numeric = tokens.length >= 2;
            for (int i = 0; i < nums.length && numeric; i++) {
                try {
                    nums[i] = Double.parseDouble(tokens[i]);
                    if (!Double.isFinite(nums[i])) numeric = false;
                } catch (NumberFormatException e) {
                    numeric = false;
                }
            }
            if (!numeric) {
                if (rows.isEmpty()) continue; // header line(s)
                throw new ParseException("Line " + (ln + 1) + " is not numeric: " + abbreviate(line));
            }
            if (nums.length < 3) allHavePhase = false;
            rows.add(nums);
        }
        if (rows.size() < 2) throw new ParseException("Need at least 2 data points, found " + rows.size());

        int n = rows.size();
        double[] f = new double[n], v = new double[n], p = allHavePhase ? new double[n] : null;
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            f[i] = r[0];
            v[i] = r[1];
            if (p != null) p[i] = r[2];
            if (f[i] < MIN_HZ || f[i] > MAX_HZ) throw new ParseException("Frequency " + f[i] + " Hz is outside 1 Hz - 100 kHz");
            if (i > 0 && f[i] <= f[i - 1]) throw new ParseException("Frequencies must increase (" + f[i - 1] + " then " + f[i] + ")");
        }
        return new Parsed(f, v, p);
    }

    static String[] tokenize(String line) {
        if (line.indexOf('\t') >= 0) return clean(line.split("\t"), false);
        if (line.indexOf(';') >= 0) return clean(line.split(";"), true);
        if (line.indexOf(',') >= 0) return clean(line.split(","), false);
        return line.split("\\s+");
    }

    private static String[] clean(String[] parts, boolean decimalComma) {
        List<String> out = new ArrayList<>(parts.length);
        for (String s : parts) {
            String t = s.strip();
            if (t.isEmpty()) continue;
            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
            out.add(decimalComma ? t.replace(',', '.') : t);
        }
        return out.toArray(String[]::new);
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
