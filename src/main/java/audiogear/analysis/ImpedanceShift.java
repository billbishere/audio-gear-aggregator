package audiogear.analysis;

/**
 * How a source (amp output + cable) impedance reshapes frequency response. The amp and headphone form a
 * voltage divider, so the gain at each frequency is |Z(f) / (Z(f) + Zs)|. The result is normalised at 1 kHz.
 */
public final class ImpedanceShift {
    private ImpedanceShift() {}

    public static final double REFERENCE_HZ = 1000;

    /**
     * @param magnitudeOhm |Z| per frequency
     * @param phaseDeg phase per frequency, or null to treat the load as purely resistive
     * @return dB shift per input frequency, 0 at 1 kHz
     */
    public static double[] compute(double[] freqHz, double[] magnitudeOhm, double[] phaseDeg, double sourceOhm) {
        double[] db = new double[freqHz.length];
        for (int i = 0; i < freqHz.length; i++) {
            double mag = magnitudeOhm[i];
            double phi = phaseDeg == null ? 0 : Math.toRadians(phaseDeg[i]);
            double re = mag * Math.cos(phi) + sourceOhm;
            double im = mag * Math.sin(phi);
            db[i] = 20 * Math.log10(mag / Math.hypot(re, im));
        }
        double ref = CurveMath.valueAtClamped(freqHz, db, REFERENCE_HZ);
        for (int i = 0; i < db.length; i++) db[i] -= ref;
        return db;
    }
}
