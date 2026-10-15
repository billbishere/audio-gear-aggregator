package audiogear.ui.model;

import audiogear.analysis.Pairing;
import audiogear.analysis.Sensitivity;
import audiogear.domain.AmpOutput;
import audiogear.domain.Enums;
import audiogear.domain.GearDetail;
import audiogear.domain.GearSummary;
import audiogear.domain.SensitivityUnit;
import audiogear.domain.TransducerSpec;
import audiogear.ui.Fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Builds the side-by-side spec grid, marking the best value where "better" has a clear direction. */
public final class CompareModel {
    private CompareModel() {}

    public enum Better { LOWER, HIGHER, NONE }

    public record Item(GearDetail detail, GearSummary summary) {}

    /** best is the column index of the best value, or -1. */
    public record Row(String section, String label, List<String> values, int best) {}

    private record Field(String section, String label, Better better, Function<Item, Object> get, Function<Object, String> fmt) {}

    /** Reference loudness for the "drive needed" row. */
    public static final double DRIVE_SPL_DB = 110;

    private static Function<Object, String> num(int decimals, String unit) {
        return o -> Fx.num((Double) o, decimals, unit);
    }

    private static final Function<Object, String> TEXT = o -> o == null ? Fx.DASH : o instanceof Enum<?> e ? Enums.label(e) : o.toString();

    private static TransducerSpec t(Item i) { return i.detail().transducer(); }

    private static final List<Field> FIELDS = List.of(
            new Field("General", "Type", Better.NONE, i -> i.detail().gear().type(), TEXT),
            new Field("General", "Status", Better.NONE, i -> i.detail().gear().status(), TEXT),
            new Field("General", "Best price / MSRP", Better.LOWER, i -> i.summary().effectivePrice(), o -> Fx.money((Double) o, null)),
            new Field("General", "Release year", Better.NONE, i -> i.detail().gear().releaseYear(), TEXT),
            new Field("General", "Tags", Better.NONE, i -> i.detail().tags().isEmpty() ? null : String.join(", ", i.detail().tags()), TEXT),

            new Field("Headphone / IEM", "Form", Better.NONE, i -> t(i) == null ? null : t(i).form(), TEXT),
            new Field("Headphone / IEM", "Back", Better.NONE, i -> t(i) == null ? null : t(i).back(), TEXT),
            new Field("Headphone / IEM", "Driver", Better.NONE, i -> t(i) == null ? null : t(i).driverType(), TEXT),
            new Field("Headphone / IEM", "Driver layout", Better.NONE, i -> t(i) == null ? null : t(i).driverConfig(), TEXT),
            new Field("Headphone / IEM", "Driver size", Better.NONE, i -> t(i) == null ? null : t(i).driverSizeMm(), num(1, "mm")),
            new Field("Headphone / IEM", "Impedance", Better.NONE, i -> t(i) == null ? null : t(i).impedanceOhm(), num(1, "Ω")),
            new Field("Headphone / IEM", "Sensitivity (dB/V)", Better.NONE, CompareModel::sensitivityDbV, num(1, "dB/V")),
            new Field("Headphone / IEM", "Power for " + (int) DRIVE_SPL_DB + " dB", Better.LOWER, CompareModel::driveMw, num(2, "mW")),
            new Field("Headphone / IEM", "Weight", Better.LOWER, i -> t(i) == null ? null : t(i).weightG(), num(0, "g")),
            new Field("Headphone / IEM", "Connector", Better.NONE, i -> t(i) == null ? null : t(i).connector(), TEXT),

            new Field("Amplifier", "Output impedance", Better.LOWER, i -> i.detail().amp() == null ? null : i.detail().amp().outputImpedanceOhm(), num(2, "Ω")),
            new Field("Amplifier", "Max voltage", Better.HIGHER, i -> i.detail().amp() == null ? null : i.detail().amp().maxVoltageVrms(), num(2, "Vrms")),
            new Field("Amplifier", "Max current", Better.HIGHER, i -> i.detail().amp() == null ? null : i.detail().amp().maxCurrentMa(), num(0, "mA")),
            new Field("Amplifier", "Power @ 32 Ω (SE)", Better.HIGHER, i -> power(i, AmpOutput.SE, 32), num(0, "mW")),
            new Field("Amplifier", "Power @ 32 Ω (bal.)", Better.HIGHER, i -> power(i, AmpOutput.BALANCED, 32), num(0, "mW")),
            new Field("Amplifier", "Power @ 300 Ω (SE)", Better.HIGHER, i -> power(i, AmpOutput.SE, 300), num(0, "mW")),
            new Field("Amplifier", "Gain", Better.NONE, i -> i.detail().amp() == null ? null : i.detail().amp().gainSettings(), TEXT),
            new Field("Amplifier", "Topology", Better.NONE, i -> i.detail().amp() == null ? null : i.detail().amp().topology(), TEXT),
            new Field("Amplifier", "Outputs", Better.NONE, i -> i.detail().amp() == null ? null : i.detail().amp().outputs(), TEXT),

            new Field("DAC", "Chip", Better.NONE, i -> i.detail().dac() == null ? null : i.detail().dac().chip(), TEXT),
            new Field("DAC", "SINAD", Better.HIGHER, i -> i.detail().dac() == null ? null : i.detail().dac().sinadDb(), num(1, "dB")),
            new Field("DAC", "Output voltage", Better.NONE, i -> i.detail().dac() == null ? null : i.detail().dac().outputVoltageVrms(), num(2, "Vrms")),
            new Field("DAC", "Max sample rate", Better.HIGHER, i -> i.detail().dac() == null ? null : i.detail().dac().maxSampleRateKhz(), num(1, "kHz")),
            new Field("DAC", "DSD", Better.NONE, i -> i.detail().dac() == null || i.detail().dac().dsd() == null ? null : (i.detail().dac().dsd() ? "Yes" : "No"), TEXT),
            new Field("DAC", "Inputs", Better.NONE, i -> i.detail().dac() == null ? null : i.detail().dac().inputs(), TEXT),
            new Field("DAC", "Outputs", Better.NONE, i -> i.detail().dac() == null ? null : i.detail().dac().outputs(), TEXT),

            new Field("Community", "Linked threads", Better.NONE, i -> (double) i.summary().threadCount(), num(0, "")),
            new Field("Community", "Recent threads (90 days)", Better.NONE, i -> (double) i.summary().recentThreadCount(), num(0, "")),
            new Field("Community", "Curves", Better.NONE, i -> (double) i.summary().curveCount(), num(0, ""))
    );

    static Double sensitivityDbV(Item i) {
        TransducerSpec t = t(i);
        if (t == null || t.sensitivity() == null || t.sensitivityUnit() == null) return null;
        if (t.sensitivityUnit() == SensitivityUnit.DB_PER_V) return t.sensitivity();
        return t.impedanceOhm() == null ? null : Sensitivity.toDbPerVolt(t.sensitivity(), t.sensitivityUnit(), t.impedanceOhm());
    }

    static Double driveMw(Item i) {
        TransducerSpec t = t(i);
        Double sensV = sensitivityDbV(i);
        if (sensV == null || t.impedanceOhm() == null) return null;
        double v = Math.pow(10, (DRIVE_SPL_DB - sensV) / 20);
        return v * v / t.impedanceOhm() * 1000;
    }

    private static Double power(Item i, AmpOutput out, double z) {
        return i.detail().amp() == null ? null : Pairing.ratedPowerAt(i.detail().powers(), out, z);
    }

    public static List<Row> rows(List<Item> items, boolean differencesOnly) {
        List<Row> out = new ArrayList<>();
        for (Field f : FIELDS) {
            List<Object> raw = new ArrayList<>();
            for (Item i : items) raw.add(f.get().apply(i));
            if (raw.stream().allMatch(Objects::isNull)) continue;
            List<String> values = raw.stream().map(o -> o == null ? Fx.DASH : f.fmt().apply(o)).toList();
            if (differencesOnly && items.size() > 1 && values.stream().distinct().count() == 1) continue;
            out.add(new Row(f.section(), f.label(), values, best(raw, f.better())));
        }
        return out;
    }

    static int best(List<Object> raw, Better better) {
        if (better == Better.NONE) return -1;
        int best = -1, count = 0;
        double bestV = 0;
        boolean allSame = true;
        Double first = null;
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Double d)) continue;
            count++;
            if (first == null) first = d;
            else if (Double.compare(first, d) != 0) allSame = false;
            if (best < 0 || (better == Better.LOWER ? d < bestV : d > bestV)) { best = i; bestV = d; }
        }
        return count >= 2 && !allSame ? best : -1;
    }
}
