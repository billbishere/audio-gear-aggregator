package audiogear.analysis;

/**
 * Harman/Olive predicted preference scores, following AutoEq's frequency_response.py:
 * error = FR - target on the 121-point preference frequency list, slope fitted against ln(f),
 * standard deviation with n-1. Both scores are unaffected by a constant level offset.
 */
public final class PreferenceScore {
    private PreferenceScore() {}

    /** meanAbs is only used by the in-ear model. */
    public record Score(double score, double sd, double slope, Double meanAbs) {}

    static final double[] OVER_EAR_FREQUENCIES = {
            20, 21, 22, 24, 25, 27, 28, 30, 32, 34, 36, 38, 40, 43, 45, 48, 50, 53, 56, 60, 63, 67, 71, 75, 80, 85, 90,
            95, 100, 106, 112, 118, 125, 132, 140, 150, 160, 170, 180, 190, 200, 212, 224, 236, 250, 265, 280, 300,
            315, 335, 355, 375, 400, 425, 450, 475, 500, 530, 560, 600, 630, 670, 710, 750, 800, 850, 900, 950, 1000,
            1060, 1120, 1180, 1250, 1320, 1400, 1500, 1600, 1700, 1800, 1900, 2000, 2120, 2240, 2360, 2500, 2650,
            2800, 3000, 3150, 3350, 3550, 3750, 4000, 4250, 4500, 4750, 5000, 5300, 5600, 6000, 6300, 6700, 7100,
            7500, 8000, 8500, 9000, 9500, 10000, 10600, 11200, 11800, 12500, 13200, 14000, 15000, 16000, 17000,
            18000, 19000, 20000};

    static final double[] IN_EAR_FREQUENCIES = {
            20, 21.2, 22.4, 23.6, 25, 26.5, 28, 30, 31.5, 33.5, 35.5, 37.5, 40, 42.5, 45, 47.5, 50, 53, 56, 60, 63, 67,
            71, 75, 80, 85, 90, 95, 100, 106, 112, 118, 125, 132, 140, 150, 160, 170, 180, 190, 200, 212, 224, 236,
            250, 265, 280, 300, 315, 335, 355, 375, 400, 425, 450, 475, 500, 530, 560, 600, 630, 670, 710, 750, 800,
            850, 900, 950, 1000, 1060, 1120, 1180, 1250, 1320, 1400, 1500, 1600, 1700, 1800, 1900, 2000, 2120, 2240,
            2360, 2500, 2650, 2800, 3000, 3150, 3350, 3550, 3750, 4000, 4250, 4500, 4750, 5000, 5300, 5600, 6000,
            6300, 6700, 7100, 7500, 8000, 8500, 9000, 9500, 10000, 10600, 11200, 11800, 12500, 13200, 14000, 15000,
            16000, 17000, 18000, 19000, 20000};

    private static final int MIN_POINTS = 10;

    /** @return null when the curves don't cover enough of 50 Hz - 10 kHz */
    public static Score overEar(double[] fx, double[] fy, double[] tx, double[] ty) {
        double[] err = error(OVER_EAR_FREQUENCIES, fx, fy, tx, ty);
        Fit fit = fit(OVER_EAR_FREQUENCIES, err, 50, 10000);
        if (fit == null) return null;
        double score = 114.490443008238 - 12.62 * fit.sd - 15.5163857197367 * Math.abs(fit.slope);
        return new Score(score, fit.sd, fit.slope, null);
    }

    /** @return null when the curves don't cover enough of 20 Hz - 10 kHz or lack a value at 500 Hz */
    public static Score inEar(double[] fx, double[] fy, double[] tx, double[] ty) {
        double[] f = IN_EAR_FREQUENCIES;
        double[] err = error(f, fx, fy, tx, ty);
        Fit fit = fit(f, err, 20, 10000);
        if (fit == null) return null;
        double delta = Double.NaN;
        for (int i = 0; i < f.length; i++) if (f[i] == 500.0) delta = err[i];
        if (Double.isNaN(delta)) return null;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < f.length; i++) {
            if (f[i] >= 40 && f[i] <= 10000 && !Double.isNaN(err[i])) { sum += Math.abs(err[i] - delta); n++; }
        }
        if (n < MIN_POINTS) return null;
        double mean = sum / n;
        double score = 100.0795 - 8.5 * fit.sd - 6.796 * Math.abs(fit.slope) - 3.475 * mean;
        return new Score(score, fit.sd, fit.slope, mean);
    }

    private static double[] error(double[] freqs, double[] fx, double[] fy, double[] tx, double[] ty) {
        double[] err = new double[freqs.length];
        for (int i = 0; i < freqs.length; i++) {
            err[i] = CurveMath.valueAt(fx, fy, freqs[i]) - CurveMath.valueAt(tx, ty, freqs[i]);
        }
        return err;
    }

    private record Fit(double sd, double slope) {}

    private static Fit fit(double[] freqs, double[] err, double fLo, double fHi) {
        int n = 0;
        double sx = 0, sy = 0;
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] >= fLo && freqs[i] <= fHi && !Double.isNaN(err[i])) { n++; sx += Math.log(freqs[i]); sy += err[i]; }
        }
        if (n < MIN_POINTS) return null;
        double mx = sx / n, my = sy / n, sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] >= fLo && freqs[i] <= fHi && !Double.isNaN(err[i])) {
                double dx = Math.log(freqs[i]) - mx, dy = err[i] - my;
                sxx += dx * dx; sxy += dx * dy; syy += dy * dy;
            }
        }
        return new Fit(Math.sqrt(syy / (n - 1)), sxy / sxx);
    }
}
