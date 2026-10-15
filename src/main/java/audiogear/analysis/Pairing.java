package audiogear.analysis;

import audiogear.domain.AmpOutput;
import audiogear.domain.AmpPower;
import audiogear.domain.SensitivityUnit;

import java.util.Comparator;
import java.util.List;

/** Can this amp drive this headphone to the target loudness, and is its source impedance low enough? */
public final class Pairing {
    private Pairing() {}

    public static final double COMFORTABLE_HEADROOM_DB = 6.0;

    public enum Verdict { COMFORTABLE, MARGINAL, NOT_ENOUGH, UNKNOWN }

    public enum PowerBasis { VOLTAGE_AND_CURRENT_LIMITS, RATED_POWER, VOLTAGE_LIMIT, CURRENT_LIMIT, UNKNOWN }

    /**
     * @param ampOutputImpedanceOhm null treated as 0
     * @param powers rated power points; only those for {@code output} are used
     */
    public record Input(double impedanceOhm, double sensitivity, SensitivityUnit sensitivityUnit, double targetSplDb,
                        Double ampOutputImpedanceOhm, Double maxVoltageVrms, Double maxCurrentMa,
                        List<AmpPower> powers, AmpOutput output, double cableLoopResistanceOhm) {}

    public record Result(double sensitivityDbPerV, double requiredVrms, double requiredMw,
                         Double availableMw, PowerBasis basis, Double headroomDb, Verdict verdict,
                         double sourceImpedanceOhm, Double impedanceRatio, boolean meetsEighthRule) {}

    public static Result evaluate(Input in) {
        double z = in.impedanceOhm();
        if (!(z > 0)) throw new IllegalArgumentException("Impedance must be positive");
        double sensV = Sensitivity.toDbPerVolt(in.sensitivity(), in.sensitivityUnit(), z);
        double vrms = Math.pow(10, (in.targetSplDb() - sensV) / 20);
        double mw = vrms * vrms / z * 1000;

        double zs = (in.ampOutputImpedanceOhm() == null ? 0 : in.ampOutputImpedanceOhm()) + in.cableLoopResistanceOhm();
        Available avail = availablePower(z, zs, in.maxVoltageVrms(), in.maxCurrentMa(), in.powers(), in.output());

        Double headroom = avail.mw == null ? null : 10 * Math.log10(avail.mw / mw);
        Verdict verdict = headroom == null ? Verdict.UNKNOWN
                : headroom >= COMFORTABLE_HEADROOM_DB ? Verdict.COMFORTABLE
                : headroom >= 0 ? Verdict.MARGINAL : Verdict.NOT_ENOUGH;
        Double ratio = zs > 0 ? z / zs : null;
        return new Result(sensV, vrms, mw, avail.mw, avail.basis, headroom, verdict, zs, ratio, zs <= z / 8);
    }

    private record Available(Double mw, PowerBasis basis) {}

    private static Available availablePower(double z, double zs, Double vMax, Double iMaxMa, List<AmpPower> powers,
                                            AmpOutput output) {
        Double fromV = vMax == null ? null : voltageLimitedMw(z, zs, vMax);
        Double fromI = iMaxMa == null ? null : currentLimitedMw(z, iMaxMa);
        if (fromV != null && fromI != null) return new Available(Math.min(fromV, fromI), PowerBasis.VOLTAGE_AND_CURRENT_LIMITS);
        List<AmpPower> rated = powers == null ? List.of()
                : powers.stream().filter(p -> p.output() == output && p.loadOhm() > 0 && p.powerMw() > 0)
                        .sorted(Comparator.comparingDouble(AmpPower::loadOhm)).toList();
        if (!rated.isEmpty()) return new Available(interpolateRated(rated, z), PowerBasis.RATED_POWER);
        if (fromV != null) return new Available(fromV, PowerBasis.VOLTAGE_LIMIT);
        if (fromI != null) return new Available(fromI, PowerBasis.CURRENT_LIMIT);
        return new Available(null, PowerBasis.UNKNOWN);
    }

    /** Open-circuit voltage divided across the source impedance, then P = V^2 / Z. */
    static double voltageLimitedMw(double z, double zs, double vOpen) {
        double vLoad = vOpen * z / (z + zs);
        return vLoad * vLoad / z * 1000;
    }

    static double currentLimitedMw(double z, double iMaxMa) {
        double i = iMaxMa / 1000;
        return i * i * z * 1000;
    }

    /** Rated power for this output at load z (see {@link #interpolateRated}), or null when none is rated. */
    public static Double ratedPowerAt(List<AmpPower> powers, AmpOutput output, double z) {
        List<AmpPower> rated = powers.stream().filter(p -> p.output() == output && p.loadOhm() > 0 && p.powerMw() > 0)
                .sorted(Comparator.comparingDouble(AmpPower::loadOhm)).toList();
        return rated.isEmpty() ? null : interpolateRated(rated, z);
    }

    /**
     * Log-log interpolation between rated loads. Above the highest load the amp is assumed voltage-limited
     * (P falls as 1/Z); below the lowest it is assumed current-limited (P falls as Z).
     */
    static double interpolateRated(List<AmpPower> sorted, double z) {
        AmpPower first = sorted.getFirst(), last = sorted.getLast();
        if (z <= first.loadOhm()) return first.powerMw() * z / first.loadOhm();
        if (z >= last.loadOhm()) return last.powerMw() * last.loadOhm() / z;
        for (int i = 1; i < sorted.size(); i++) {
            AmpPower a = sorted.get(i - 1), b = sorted.get(i);
            if (z <= b.loadOhm()) {
                if (b.loadOhm() == a.loadOhm()) return Math.max(a.powerMw(), b.powerMw());
                double t = (Math.log(z) - Math.log(a.loadOhm())) / (Math.log(b.loadOhm()) - Math.log(a.loadOhm()));
                return Math.exp(Math.log(a.powerMw()) + t * (Math.log(b.powerMw()) - Math.log(a.powerMw())));
            }
        }
        return last.powerMw();
    }
}
