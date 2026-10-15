package audiogear.analysis;

import audiogear.domain.SensitivityUnit;

/** Converts between dB SPL per milliwatt and dB SPL per volt. At 1 V into Z ohms the power is 1000/Z mW. */
public final class Sensitivity {
    private Sensitivity() {}

    public static double toDbPerVolt(double sensitivity, SensitivityUnit unit, double impedanceOhm) {
        return unit == SensitivityUnit.DB_PER_V ? sensitivity : sensitivity + 10 * Math.log10(1000 / impedanceOhm);
    }

    public static double toDbPerMilliwatt(double sensitivity, SensitivityUnit unit, double impedanceOhm) {
        return unit == SensitivityUnit.DB_PER_MW ? sensitivity : sensitivity - 10 * Math.log10(1000 / impedanceOhm);
    }
}
