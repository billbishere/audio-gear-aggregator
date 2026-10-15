package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.analysis.CurveMath;
import audiogear.analysis.ImpedanceShift;
import audiogear.analysis.Pairing;
import audiogear.analysis.Sensitivity;
import audiogear.app.AppContext;
import audiogear.domain.*;
import audiogear.ui.CurveChart;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import audiogear.ui.Navigator;
import audiogear.ui.View;
import audiogear.ui.dialogs.CableDialogs;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Can this amp drive this headphone, and how does its output impedance change the sound? */
public final class PairingView implements View {

    private record Loaded(GearDetail headphone, GearDetail amp, Curve impedance) {}

    private final AppContext ctx;
    private final Navigator nav;
    private final BorderPane root = new BorderPane();
    private final GearPicker headphone, amp;
    private final ComboBox<AmpOutput> output = Fx.enumCombo(AmpOutput.class, null);
    private final ComboBox<Cable> cable = new ComboBox<>();
    private final Spinner<Double> spl = new Spinner<>(70.0, 130.0, 110.0, 1.0);
    private final VBox results = new VBox(10);
    private final CurveChart chart = new CurveChart();
    private Loaded loaded;
    private long seq;

    public PairingView(AppContext ctx, Navigator nav) {
        this.ctx = ctx;
        this.nav = nav;
        headphone = new GearPicker("Headphone or IEM", () -> ctx.catalog, g -> g.type().isTransducer());
        amp = new GearPicker("Amplifier or amp/DAC", () -> ctx.catalog, g -> g.type().isAmp());
        output.setValue(AmpOutput.SE);
        cable.setConverter(Fx.converter(c -> c == null ? "No cable / stock" : c.name() + (c.loopResistanceOhm() == null ? "" : " (" + Fx.num(c.loopResistanceOhm(), 2) + " Ω)")));
        cable.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Cable c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty ? "No cable / stock" : cable.getConverter().toString(c));
            }
        });
        spl.setEditable(true);
        spl.setPrefWidth(100);
        spl.getValueFactory().setValue(ctx.settings.targetSplDb());
        Button cables = new Button("Cables…");
        cables.setOnAction(e -> CableDialogs.manage(ctx, this::loadCables));

        headphone.valueProperty().addListener((o, a, b) -> reload());
        amp.valueProperty().addListener((o, a, b) -> reload());
        output.valueProperty().addListener((o, a, b) -> render());
        cable.valueProperty().addListener((o, a, b) -> render());
        spl.valueProperty().addListener((o, a, b) -> render());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.addRow(0, new Label("Headphone / IEM"), headphone);
        form.addRow(1, new Label("Amplifier"), amp);
        form.addRow(2, new Label("Output"), output);
        form.addRow(3, new Label("Cable"), Fx.row(6, cable, cables));
        form.addRow(4, new Label("Target peak level"), Fx.row(6, spl, new Label("dB SPL")));
        VBox left = new VBox(14, Fx.section("Pairing", form,
                Fx.muted("Peaks in music run 15–20 dB above the average level, so 110 dB peak suits loud listening around 90 dB.")), results);
        left.setPrefWidth(470);
        left.setMinWidth(420);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setMinWidth(440);

        chart.setYAxis("Level change (dB, 0 at 1 kHz)", null, null);
        chart.setEmptyMessage("Add an impedance curve to the headphone to see how this amp changes its response");
        VBox right = new VBox(8, Fx.heading("Frequency-response change from output impedance"), chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox main = new HBox(14, leftScroll, right);
        VBox.setVgrow(main, Priority.ALWAYS);
        VBox body = new VBox(10, Fx.row(8, Fx.title("Pairing")), main);
        body.getStyleClass().add("view");
        root.setCenter(body);
        render();
    }

    @Override public String title() { return "Pairing"; }
    @Override public Node root() { return root; }

    @Override public void onShow() { loadCables(); }

    public void preset(Long transducerId, Long ampId) {
        if (transducerId != null) headphone.selectId(transducerId);
        if (ampId != null) amp.selectId(ampId);
    }

    private void loadCables() {
        ctx.run(null, ctx.cables::list, list -> {
            Cable keep = cable.getValue();
            List<Cable> items = new ArrayList<>();
            items.add(null);
            items.addAll(list);
            cable.getItems().setAll(items);
            cable.setValue(keep == null ? null : list.stream().filter(c -> c.id().equals(keep.id())).findFirst().orElse(null));
        });
    }

    private void reload() {
        GearSummary h = headphone.getValue(), a = amp.getValue();
        long my = ++seq;
        ctx.run(null, () -> {
            GearDetail hd = h == null ? null : ctx.gear.get(h.id()).orElse(null);
            GearDetail ad = a == null ? null : ctx.gear.get(a.id()).orElse(null);
            Curve z = null;
            if (h != null) {
                CurveInfo zi = ctx.curves.forGear(h.id()).stream().filter(i -> i.kind() == CurveKind.IMPEDANCE).findFirst().orElse(null);
                if (zi != null) z = ctx.curves.load(zi.id()).orElse(null);
            }
            return new Loaded(hd, ad, z);
        }, l -> {
            if (my != seq) return;
            loaded = l;
            if (l.amp() != null && l.amp().powers().stream().noneMatch(p -> p.output() == output.getValue())
                    && !l.amp().powers().isEmpty()) output.setValue(l.amp().powers().getFirst().output());
            render();
        });
    }

    private void render() {
        results.getChildren().clear();
        if (loaded == null || loaded.headphone() == null) {
            results.getChildren().add(Fx.muted("Pick a headphone or IEM to begin. Its impedance and sensitivity come from the catalog."));
            chart.setSeries(List.of());
            return;
        }
        GearDetail hd = loaded.headphone();
        TransducerSpec t = hd.transducer();
        Double z = t == null ? null : t.impedanceOhm();
        if (z == null && loaded.impedance() != null) z = CurveMath.valueAtClamped(loaded.impedance().freqHz(), loaded.impedance().value(), 1000);
        Double sens = t == null ? null : t.sensitivity();
        SensitivityUnit unit = t == null ? null : t.sensitivityUnit();

        List<String> missing = new ArrayList<>();
        if (z == null) missing.add("impedance");
        if (sens == null || unit == null) missing.add("sensitivity");
        if (!missing.isEmpty()) {
            Hyperlink fix = new Hyperlink("Add " + String.join(" and ", missing) + " to " + hd.gear().displayName());
            fix.setOnAction(e -> nav.catalog(hd.gear().id()));
            results.getChildren().add(Fx.section("Missing specs", fix));
            renderShift(null);
            return;
        }

        GearDetail ad = loaded.amp();
        AmpSpec a = ad == null ? null : ad.amp();
        Cable c = cable.getValue();
        double cableR = c == null || c.loopResistanceOhm() == null ? 0 : c.loopResistanceOhm();
        Pairing.Result r = Pairing.evaluate(new Pairing.Input(z, sens, unit, spl.getValue(),
                a == null ? null : a.outputImpedanceOhm(), a == null ? null : a.maxVoltageVrms(),
                a == null ? null : a.maxCurrentMa(), ad == null ? List.of() : ad.powers(), output.getValue(), cableR));

        GridPane need = grid();
        int row = 0;
        addRow(need, row++, "Impedance", Fx.num(z, 1, "Ω") + (t.impedanceOhm() == null ? " (from curve at 1 kHz)" : ""));
        addRow(need, row++, "Sensitivity", Fx.num(r.sensitivityDbPerV(), 1, "dB/V") + "  ·  "
                + Fx.num(Sensitivity.toDbPerMilliwatt(sens, unit, z), 1, "dB/mW"));
        addRow(need, row++, "Needs for " + Fx.num(spl.getValue(), 0) + " dB", Fx.num(r.requiredVrms(), 3, "Vrms") + "  ·  " + power(r.requiredMw()));
        results.getChildren().add(Fx.section("What " + hd.gear().displayName() + " needs", need));

        if (ad == null) {
            results.getChildren().add(Fx.muted("Pick an amplifier to check headroom and output impedance."));
            renderShift(null);
            return;
        }
        Label verdict = new Label(switch (r.verdict()) {
            case COMFORTABLE -> "Comfortable";
            case MARGINAL -> "Marginal";
            case NOT_ENOUGH -> "Not enough power";
            case UNKNOWN -> "Unknown — add power specs";
        });
        verdict.getStyleClass().addAll("verdict", "verdict-" + r.verdict().name().toLowerCase(Locale.ROOT));
        GridPane amps = grid();
        row = 0;
        addRow(amps, row++, "Verdict", verdict);
        addRow(amps, row++, "Available", r.availableMw() == null ? Fx.DASH : power(r.availableMw()) + "  (" + basis(r.basis()) + ")");
        addRow(amps, row++, "Headroom", r.headroomDb() == null ? Fx.DASH : (r.headroomDb() >= 0 ? "+" : "") + Fx.num(r.headroomDb(), 1) + " dB");
        addRow(amps, row++, "Source impedance", Fx.num(r.sourceImpedanceOhm(), 2, "Ω")
                + (cableR > 0 ? "  (amp " + Fx.num(a.outputImpedanceOhm() == null ? 0.0 : a.outputImpedanceOhm(), 2) + " + cable " + Fx.num(cableR, 2) + ")" : ""));
        addRow(amps, row++, "Load / source ratio", r.impedanceRatio() == null ? "∞ (ideal)" : Fx.num(r.impedanceRatio(), 1) + " : 1");
        Label rule = new Label(r.meetsEighthRule() ? "Meets the 1/8 rule" : "Breaks the 1/8 rule — expect a changed sound and looser bass");
        rule.getStyleClass().add(r.meetsEighthRule() ? Styles.SUCCESS : Styles.WARNING);
        rule.setWrapText(true);
        addRow(amps, row++, "Output impedance", rule);
        if (a.outputImpedanceOhm() == null) results.getChildren().add(Fx.muted("The amp has no output impedance on file; 0 Ω was assumed."));
        results.getChildren().add(Fx.section(ad.gear().displayName() + " · " + Enums.label(output.getValue()), amps));
        renderShift(r.sourceImpedanceOhm());
    }

    private void renderShift(Double sourceOhm) {
        Curve zc = loaded == null ? null : loaded.impedance();
        if (zc == null || sourceOhm == null) {
            chart.setSeries(List.of());
            return;
        }
        double[] shift = ImpedanceShift.compute(zc.freqHz(), zc.value(), zc.phaseDeg(), sourceOhm);
        double max = CurveMath.maxAbs(shift);
        chart.setYAxis("Level change (dB, 0 at 1 kHz) — max " + Fx.num(max, 2) + " dB", max < 1 ? -1.0 : null, max < 1 ? 1.0 : null);
        chart.setSeries(List.of(new CurveChart.Series("Shift with " + Fx.num(sourceOhm, 2) + " Ω source", zc.freqHz(), shift, CurveChart.color(1))));
        Label note = Fx.muted(max < 0.5 ? "Under 0.5 dB anywhere: inaudible for practical purposes."
                : max < 1.5 ? "Up to " + Fx.num(max, 1) + " dB: subtle, may be audible on careful listening."
                : "Up to " + Fx.num(max, 1) + " dB: clearly audible. A lower-impedance output keeps the intended tuning.");
        results.getChildren().add(Fx.section("Output impedance effect", note));
    }

    private static String power(double mw) {
        return mw >= 1000 ? Fx.num(mw / 1000, 2, "W") : Fx.num(mw, mw < 10 ? 2 : 1, "mW");
    }

    private static String basis(Pairing.PowerBasis b) {
        return switch (b) {
            case VOLTAGE_AND_CURRENT_LIMITS -> "from voltage and current limits";
            case RATED_POWER -> "from rated power";
            case VOLTAGE_LIMIT -> "from voltage limit";
            case CURRENT_LIMIT -> "from current limit";
            case UNKNOWN -> "unknown";
        };
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(7);
        g.setPadding(new Insets(2, 0, 0, 0));
        return g;
    }

    private static void addRow(GridPane g, int row, String key, String value) {
        Label v = new Label(value);
        v.setWrapText(true);
        addRow(g, row, key, v);
    }

    private static void addRow(GridPane g, int row, String key, Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("spec-key");
        k.setMinWidth(140);
        g.add(k, 0, row);
        g.add(value, 1, row);
    }
}
