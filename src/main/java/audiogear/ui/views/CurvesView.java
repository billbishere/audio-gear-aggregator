package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.analysis.CurveMath;
import audiogear.analysis.PreferenceScore;
import audiogear.app.AppContext;
import audiogear.domain.*;
import audiogear.ui.CurveChart;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import audiogear.ui.View;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Curve workspace: overlay FR or impedance curves, align, smooth, subtract, compare with a target, score. */
public final class CurvesView implements View {

    private enum Mode { OVERLAY("Overlay"), DIFFERENCE("Difference (first − second)"), DEVIATION("Deviation from target");
        final String label; Mode(String l) { label = l; } }

    private static final Map<Integer, String> SMOOTHING = new java.util.LinkedHashMap<>(Map.of());
    static {
        SMOOTHING.put(0, "No smoothing");
        SMOOTHING.put(3, "1/3 octave");
        SMOOTHING.put(6, "1/6 octave");
        SMOOTHING.put(12, "1/12 octave");
        SMOOTHING.put(24, "1/24 octave");
    }

    /** A curve on the chart. inEar picks the in-ear preference model. */
    private record Active(CurveInfo info, Curve curve, Color color, BooleanProperty visible, boolean inEar) {}

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ObservableList<Active> active = FXCollections.observableArrayList();
    private final ListView<Active> activeList = new ListView<>(active);
    private final ListView<CurveInfo> available = new ListView<>();
    private final GearPicker picker;
    private final CurveChart chart = new CurveChart();
    private final ToggleGroup kindGroup = new ToggleGroup();
    private final ToggleButton frButton = new ToggleButton("Frequency response"), zButton = new ToggleButton("Impedance");
    private final ComboBox<Double> align = new ComboBox<>();
    private final ComboBox<Integer> smoothing = new ComboBox<>();
    private final ComboBox<Mode> mode = new ComboBox<>();
    private final ComboBox<CurveInfo> target = new ComboBox<>();
    private final CheckBox showTarget = new CheckBox("Show target");
    private Curve targetCurve;
    /** All target curves, loaded once per visit; used to score each curve against the matching target. */
    private List<Curve> targets = List.of();
    private int colorIndex;

    public CurvesView(AppContext ctx) {
        this.ctx = ctx;
        picker = new GearPicker("Find gear with curves", () -> ctx.catalog, g -> g.curveCount() > 0);
        picker.valueProperty().addListener((o, a, g) -> loadAvailable(g));
        available.setPlaceholder(new Label("Pick gear above to list its curves"));
        available.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        available.setPrefHeight(170);
        available.setOnMouseClicked(e -> { if (e.getClickCount() == 2) addSelected(); });
        Button add = new Button("Add to chart");
        add.getStyleClass().add(Styles.ACCENT);
        add.disableProperty().bind(available.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(e -> addSelected());
        Button clear = new Button("Remove all");
        clear.getStyleClass().add(Styles.FLAT);
        clear.setOnAction(e -> { active.clear(); colorIndex = 0; render(); });

        activeList.setCellFactory(lv -> new ActiveCell());
        activeList.setPlaceholder(new Label("No curves on the chart"));
        VBox.setVgrow(activeList, Priority.ALWAYS);
        VBox left = new VBox(8, Fx.heading("Add curves"), picker, available, add, new Separator(),
                Fx.row(8, Fx.heading("On the chart"), Fx.grow(), clear), activeList);
        left.setPadding(new Insets(0, 10, 0, 0));
        left.setPrefWidth(360);
        left.setMinWidth(300);

        frButton.setToggleGroup(kindGroup);
        zButton.setToggleGroup(kindGroup);
        frButton.getStyleClass().add(Styles.LEFT_PILL);
        zButton.getStyleClass().add(Styles.RIGHT_PILL);
        frButton.setSelected(true);
        kindGroup.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); else render(); });

        align.getItems().setAll(0.0, 1000.0, 500.0);
        align.setConverter(Fx.converter(v -> v == null || v == 0 ? "No alignment" : "Align at " + (v >= 1000 ? Fx.num(v / 1000, 1) + " kHz" : Fx.num(v, 0) + " Hz")));
        align.setValue(1000.0);
        smoothing.getItems().setAll(SMOOTHING.keySet());
        smoothing.setConverter(Fx.converter(SMOOTHING::get));
        smoothing.setValue(0);
        mode.getItems().setAll(Mode.values());
        mode.setConverter(Fx.converter(m -> m.label));
        mode.setValue(Mode.OVERLAY);
        target.setPromptText("Target curve");
        target.setConverter(Fx.converter(t -> t == null ? "No target" : t.label()));
        target.valueProperty().addListener((o, a, t) -> loadTarget(t));
        Button export = new Button("Export PNG…");
        export.setOnAction(e -> exportPng());
        for (ComboBox<?> c : List.of(align, smoothing, mode)) c.valueProperty().addListener((o, a, b) -> render());
        showTarget.selectedProperty().addListener((o, a, b) -> render());

        HBox bar = Fx.row(8, Fx.title("Curves"), Fx.grow(), new HBox(frButton, zButton), align, smoothing, mode, target, showTarget, export);
        bar.getStyleClass().add("toolbar-row");
        BorderPane center = new BorderPane(chart);
        HBox.setHgrow(center, Priority.ALWAYS);
        HBox main = new HBox(left, center);
        VBox.setVgrow(main, Priority.ALWAYS);
        VBox body = new VBox(10, bar, main);
        body.getStyleClass().add("view");
        root.setCenter(body);
        render();
    }

    @Override public String title() { return "Curves"; }
    @Override public Node root() { return root; }

    @Override public void onShow() {
        ctx.run(null, () -> {
            List<CurveInfo> infos = ctx.curves.byKind(CurveKind.TARGET);
            List<Curve> curves = new ArrayList<>();
            for (CurveInfo i : infos) ctx.curves.load(i.id()).ifPresent(curves::add);
            return Map.entry(infos, curves);
        }, loaded -> {
            List<CurveInfo> infos = loaded.getKey();
            targets = loaded.getValue();
            CurveInfo keep = target.getValue();
            List<CurveInfo> items = new ArrayList<>();
            items.add(null);
            items.addAll(infos);
            target.getItems().setAll(items);
            if (keep != null) infos.stream().filter(t -> t.id() == keep.id()).findFirst().ifPresent(target::setValue);
            else infos.stream().filter(t -> t.label().toLowerCase(java.util.Locale.ROOT).contains("over-ear")).findFirst()
                    .or(() -> infos.stream().findFirst()).ifPresent(target::setValue);
            render();
        });
    }

    /** Adds each gear's first FR curve (or its impedance curves when it has no FR). */
    public void addGear(List<Long> gearIds) {
        if (gearIds.isEmpty()) return;
        ctx.run("Loading curves", () -> {
            List<Active> out = new ArrayList<>();
            for (long id : gearIds) {
                List<CurveInfo> infos = ctx.curves.forGear(id);
                List<CurveInfo> pick = infos.stream().filter(i -> i.kind() == CurveKind.FR).limit(1).toList();
                if (pick.isEmpty()) pick = infos.stream().filter(i -> i.kind() == CurveKind.IMPEDANCE).toList();
                for (CurveInfo i : pick) ctx.curves.load(i.id()).ifPresent(c -> out.add(toActive(i, c)));
            }
            return out;
        }, list -> {
            addActive(list);
            if (!list.isEmpty() && list.stream().allMatch(a -> a.info().kind() == CurveKind.IMPEDANCE)) zButton.setSelected(true);
            else if (!list.isEmpty()) frButton.setSelected(true);
        });
    }

    private Active toActive(CurveInfo info, Curve c) {
        boolean inEar = ctx.catalog.stream().filter(g -> info.gearId() != null && g.id() == info.gearId()).findFirst()
                .map(g -> g.type() == GearType.IEM || g.form() == Form.IN_EAR || g.form() == Form.EARBUD).orElse(false);
        return new Active(info, c, Color.WHITE, new SimpleBooleanProperty(true), inEar);
    }

    private void addActive(List<Active> list) {
        for (Active a : list) {
            if (active.stream().anyMatch(x -> x.info().id() == a.info().id())) continue;
            Active colored = new Active(a.info(), a.curve(), CurveChart.color(colorIndex++), a.visible(), a.inEar());
            colored.visible().addListener((o, x, y) -> render());
            active.add(colored);
        }
        render();
    }

    private void loadAvailable(GearSummary g) {
        if (g == null) { available.getItems().clear(); return; }
        ctx.run(null, () -> ctx.curves.forGear(g.id()), infos -> {
            available.getItems().setAll(infos);
            available.getSelectionModel().selectFirst();
        });
    }

    private void addSelected() {
        List<CurveInfo> sel = List.copyOf(available.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) return;
        ctx.run("Loading curves", () -> {
            List<Active> out = new ArrayList<>();
            for (CurveInfo i : sel) ctx.curves.load(i.id()).ifPresent(c -> out.add(toActive(i, c)));
            return out;
        }, list -> {
            addActive(list);
            if (list.getFirst().info().kind() == CurveKind.IMPEDANCE) zButton.setSelected(true);
            else frButton.setSelected(true);
        });
    }

    private void loadTarget(CurveInfo t) {
        if (t == null) { targetCurve = null; render(); return; }
        ctx.run(null, () -> ctx.curves.load(t.id()).orElse(null), c -> { targetCurve = c; render(); });
    }

    /** FR processing shared by all modes: resample to the standard grid, smooth, align. */
    private double[] process(Curve c) {
        double[] grid = CurveMath.STANDARD_GRID;
        double[] y = CurveMath.resample(c.freqHz(), c.value(), grid);
        int s = smoothing.getValue() == null ? 0 : smoothing.getValue();
        if (s > 0) y = CurveMath.smooth(grid, y, s);
        double ref = align.getValue() == null ? 0 : align.getValue();
        if (ref > 0) y = CurveMath.normalize(grid, y, ref);
        return y;
    }

    private void render() {
        boolean impedance = zButton.isSelected();
        List<CurveChart.Series> series = new ArrayList<>();
        double[] grid = CurveMath.STANDARD_GRID;
        if (impedance) {
            boolean anyPhase = false;
            double lo = Double.POSITIVE_INFINITY, hi = 0;
            for (Active a : active) {
                if (!a.visible().get() || a.info().kind() != CurveKind.IMPEDANCE) continue;
                Curve c = a.curve();
                series.add(new CurveChart.Series(a.info().title(), c.freqHz(), c.value(), a.color()));
                for (double v : c.value()) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
                if (c.hasPhase()) {
                    anyPhase = true;
                    series.add(new CurveChart.Series(a.info().title() + " phase", c.freqHz(), c.phaseDeg(), a.color(), true, true));
                }
            }
            chart.setLogY(hi > 0 && lo > 0 && hi / lo > 4);
            chart.setYAxis("Impedance (Ω)", null, null);
            chart.setSecondaryAxis(anyPhase ? "Phase (°)" : null, -90, 90);
            chart.setEmptyMessage("Add impedance curves (import them per gear in the Catalog)");
        } else {
            chart.setLogY(false);
            chart.setSecondaryAxis(null, -90, 90);
            List<Active> fr = active.stream().filter(a -> a.visible().get() && a.info().kind() == CurveKind.FR).toList();
            Mode m = mode.getValue();
            double[] t = targetCurve == null ? null : process(targetCurve);
            if (m == Mode.OVERLAY) {
                for (Active a : fr) series.add(new CurveChart.Series(a.info().title(), grid, process(a.curve()), a.color()));
                if (showTarget.isSelected() && t != null)
                    series.add(new CurveChart.Series(targetCurve.label(), grid, t, Color.web("#c9d1d9"), true, false));
                chart.setYAxis(align.getValue() != null && align.getValue() > 0 ? "dB (aligned)" : "dB", null, null);
                chart.setEmptyMessage("Add frequency-response curves from the list on the left");
            } else if (m == Mode.DIFFERENCE) {
                if (fr.size() >= 2) {
                    series.add(new CurveChart.Series(fr.get(0).info().title() + " − " + fr.get(1).info().title(), grid,
                            CurveMath.difference(process(fr.get(0).curve()), process(fr.get(1).curve())), fr.get(0).color()));
                }
                chart.setYAxis("Difference (dB)", null, null);
                chart.setEmptyMessage("Show at least two FR curves to see their difference");
            } else {
                if (t != null) for (Active a : fr) series.add(new CurveChart.Series(a.info().title(), grid,
                        CurveMath.difference(process(a.curve()), t), a.color()));
                chart.setYAxis("Deviation from target (dB)", null, null);
                chart.setEmptyMessage(t == null ? "Choose a target curve (import Harman targets under Data & Settings)" : "Add FR curves to compare with the target");
            }
        }
        chart.setSeries(series);
        activeList.refresh();
    }

    /**
     * The Harman model only makes sense against its own target: in-ear curves against an in-ear target and
     * over-ear curves against an over-ear one. Prefers the chosen target when it matches, else any matching one.
     */
    private Curve scoringTarget(Active a) {
        String want = a.inEar() ? "in-ear" : "over-ear";
        if (targetCurve != null && targetCurve.label().toLowerCase(java.util.Locale.ROOT).contains(want)) return targetCurve;
        return targets.stream().filter(t -> t.label().toLowerCase(java.util.Locale.ROOT).contains(want)).findFirst().orElse(null);
    }

    private String scoreText(Active a) {
        Curve t = a.info().kind() == CurveKind.FR ? scoringTarget(a) : null;
        if (t == null) return "";
        Curve c = a.curve();
        PreferenceScore.Score s = a.inEar()
                ? PreferenceScore.inEar(c.freqHz(), c.value(), t.freqHz(), t.value())
                : PreferenceScore.overEar(c.freqHz(), c.value(), t.freqHz(), t.value());
        return s == null ? "" : "Score " + Fx.num(s.score(), 1);
    }

    private void exportPng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export chart");
        fc.setInitialFileName("curves.png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        File f = fc.showSaveDialog(root.getScene().getWindow());
        if (f == null) return;
        try {
            Fx.savePng(chart, f);
            ctx.status.info("Saved " + f.getName());
        } catch (Exception e) {
            Fx.error("Could not save the image", AppContext.message(e));
        }
    }

    private final class ActiveCell extends ListCell<Active> {
        @Override protected void updateItem(Active a, boolean empty) {
            super.updateItem(a, empty);
            if (empty || a == null) { setGraphic(null); setText(null); return; }
            Region swatch = new Region();
            swatch.getStyleClass().add("swatch");
            swatch.setStyle("-fx-background-color: " + CompareView.toWeb(a.color()) + ";");
            CheckBox vis = new CheckBox();
            vis.selectedProperty().bindBidirectional(a.visible());
            Label name = new Label(a.info().title() + (a.info().kind() == CurveKind.IMPEDANCE ? "  (Ω)" : ""));
            name.setWrapText(true);
            name.setMaxWidth(220);
            String score = scoreText(a);
            Label sc = new Label(score);
            sc.getStyleClass().add(Styles.TEXT_SMALL);
            if (!score.isEmpty()) {
                double v = Double.parseDouble(score.substring(6));
                sc.getStyleClass().add(v >= 80 ? "score-good" : v >= 60 ? "score-mid" : "score-low");
                sc.setTooltip(new Tooltip("Predicted preference (Harman/Olive " + (a.inEar() ? "in-ear" : "over-ear") + " model) against "
                        + scoringTarget(a).label()));
            }
            Button remove = new Button("✕");
            remove.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
            remove.setOnAction(e -> { active.remove(a); render(); });
            HBox row = Fx.row(6, vis, swatch, new VBox(2, name, sc), Fx.grow(), remove);
            setGraphic(row);
            setText(null);
        }
    }
}
