package audiogear.analysis;

import java.util.Arrays;

/**
 * Curve operations on log-frequency data. Missing values are NaN; nothing is extrapolated.
 * All interpolation is linear in log-frequency, matching how AutoEq resamples curves.
 */
public final class CurveMath {
    private CurveMath() {}

    /** The shared analysis grid: 1/48-octave steps from 20 Hz to 20 kHz (inclusive). */
    public static final double[] STANDARD_GRID = logGrid(20, 20000, 48);

    /** Points spaced 1/pointsPerOctave octave apart from fMin, ending exactly at fMax. */
    public static double[] logGrid(double fMin, double fMax, int pointsPerOctave) {
        if (fMin <= 0 || fMax <= fMin || pointsPerOctave <= 0) throw new IllegalArgumentException("bad grid");
        double octaves = Math.log(fMax / fMin) / Math.log(2);
        int steps = (int) Math.ceil(octaves * pointsPerOctave - 1e-9);
        double[] g = new double[steps + 1];
        for (int i = 0; i < steps; i++) g[i] = fMin * Math.pow(2, (double) i / pointsPerOctave);
        g[steps] = fMax;
        return g;
    }

    /** Value of the curve (xs ascending) at f, or NaN when f is outside the curve's range. */
    public static double valueAt(double[] xs, double[] ys, double f) {
        int n = xs.length;
        if (n == 0 || Double.isNaN(f)) return Double.NaN;
        double tol = 1e-9 * f;
        if (f < xs[0] - tol || f > xs[n - 1] + tol) return Double.NaN;
        if (n == 1) return ys[0];
        int i = Arrays.binarySearch(xs, f);
        if (i >= 0) return ys[i];
        int hi = Math.min(Math.max(-i - 1, 1), n - 1);
        int lo = hi - 1;
        double t = (Math.log(f) - Math.log(xs[lo])) / (Math.log(xs[hi]) - Math.log(xs[lo]));
        return ys[lo] + t * (ys[hi] - ys[lo]);
    }

    /** Value at f; if f is outside the range, the value at the nearest end instead. */
    public static double valueAtClamped(double[] xs, double[] ys, double f) {
        if (xs.length == 0) return Double.NaN;
        if (f <= xs[0]) return ys[0];
        if (f >= xs[xs.length - 1]) return ys[ys.length - 1];
        return valueAt(xs, ys, f);
    }

    /** Resamples the curve onto grid. Grid points outside the curve's range become NaN. */
    public static double[] resample(double[] xs, double[] ys, double[] grid) {
        double[] out = new double[grid.length];
        for (int i = 0; i < grid.length; i++) out[i] = valueAt(xs, ys, grid[i]);
        return out;
    }

    /** Shifts the curve so its value at refHz is 0 dB. Returns an unchanged copy if refHz is not covered. */
    public static double[] normalize(double[] grid, double[] ys, double refHz) {
        double ref = valueAtIgnoringNaN(grid, ys, refHz);
        double[] out = ys.clone();
        if (Double.isNaN(ref)) return out;
        for (int i = 0; i < out.length; i++) out[i] -= ref;
        return out;
    }

    /** Moving average over a 1/fraction-octave window centred on each point (NaN points are skipped). */
    public static double[] smooth(double[] grid, double[] ys, int fraction) {
        if (fraction <= 0) return ys.clone();
        double half = 0.5 / fraction;
        double[] log2 = new double[grid.length];
        for (int i = 0; i < grid.length; i++) log2[i] = Math.log(grid[i]) / Math.log(2);
        double[] out = new double[ys.length];
        int lo = 0, hi = 0;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < grid.length; i++) {
            while (hi < grid.length && log2[hi] <= log2[i] + half + 1e-12) {
                if (!Double.isNaN(ys[hi])) { sum += ys[hi]; count++; }
                hi++;
            }
            while (log2[lo] < log2[i] - half - 1e-12) {
                if (!Double.isNaN(ys[lo])) { sum -= ys[lo]; count--; }
                lo++;
            }
            out[i] = Double.isNaN(ys[i]) || count == 0 ? Double.NaN : sum / count;
        }
        return out;
    }

    /** a - b point by point; NaN where either is missing. */
    public static double[] difference(double[] a, double[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("length mismatch");
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    /** Largest absolute value, ignoring NaN; NaN if there are no values. */
    public static double maxAbs(double[] ys) {
        double m = Double.NaN;
        for (double y : ys) if (!Double.isNaN(y) && (Double.isNaN(m) || Math.abs(y) > m)) m = Math.abs(y);
        return m;
    }

    private static double valueAtIgnoringNaN(double[] grid, double[] ys, double f) {
        int count = 0;
        for (double y : ys) if (!Double.isNaN(y)) count++;
        if (count == ys.length) return valueAt(grid, ys, f);
        double[] gx = new double[count], gy = new double[count];
        int k = 0;
        for (int i = 0; i < ys.length; i++) if (!Double.isNaN(ys[i])) { gx[k] = grid[i]; gy[k++] = ys[i]; }
        return valueAt(gx, gy, f);
    }
}
