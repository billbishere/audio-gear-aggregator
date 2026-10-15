package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.analysis.CurveMath;
import audiogear.app.AppContext;
import audiogear.domain.*;
import audiogear.importers.CurveFileParser;
import audiogear.ui.CurveChart;
import audiogear.ui.Fx;
import audiogear.ui.Navigator;
import audiogear.ui.dialogs.CurveImportDialog;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/** Right-hand pane of the catalog: specs, curves, linked threads and price for one item. */
final class GearDetailPane {

    private record Bundle(GearDetail detail, GearSummary summary, List<CurveInfo> curves, List<Curve> chartCurves, List<ForumThread> threads) {}

    private final AppContext ctx;
    private final Navigator nav;
    private final LongConsumer edit;
    private final ScrollPane root = new ScrollPane();
    private final VBox body = new VBox(12);
    private final ListView<CurveInfo> curveList = new ListView<>();
    private final ListView<ForumThread> threadList = new ListView<>();
    private final CurveChart chart = new CurveChart();
    private GearSummary current;
    private long requestSeq;

    GearDetailPane(AppContext ctx, Navigator nav, LongConsumer edit) {
        this.ctx = ctx;
        this.nav = nav;
        this.edit = edit;
        body.setPadding(new Insets(4, 8, 12, 12));
        root.setContent(body);
        root.setFitToWidth(true);
        root.getStyleClass().add("detail-pane");
        root.setMinWidth(380);
        curveList.setPrefHeight(118);
        curveList.setPlaceholder(new Label("No curves yet"));
        threadList.setPrefHeight(190);
        threadList.setPlaceholder(new Label("No linked threads"));
        threadList.setCellFactory(lv -> new ThreadCell(null, false));
        threadList.setOnMouseClicked(e -> {
            ForumThread t = threadList.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && t != null) ctx.openUrl(t.url());
        });
        chart.setPrefHeight(230);
        chart.setMinHeight(200);
        show(null);
    }

    Node root() { return root; }

    void show(GearSummary g) {
        current = g;
        long seq = ++requestSeq;
        if (g == null) {
            body.getChildren().setAll(Fx.muted("Select an item to see its details."));
            return;
        }
        ctx.run(null, () -> load(g), b -> {
            if (seq == requestSeq) render(b);
        });
    }

    private Bundle load(GearSummary g) {
        GearDetail d = ctx.gear.get(g.id()).orElseThrow();
        List<CurveInfo> infos = ctx.curves.forGear(g.id());
        boolean anyFr = infos.stream().anyMatch(i -> i.kind() == CurveKind.FR);
        CurveKind show = anyFr ? CurveKind.FR : CurveKind.IMPEDANCE;
        List<Curve> curves = new ArrayList<>();
        for (CurveInfo i : infos) {
            if (i.kind() == show && curves.size() < 6) ctx.curves.load(i.id()).ifPresent(curves::add);
        }
        return new Bundle(d, ctx.gear.summary(g.id()).orElse(g), infos, curves, ctx.threads.forGear(g.id()));
    }

    private void render(Bundle b) {
        GearDetail d = b.detail();
        Gear g = d.gear();
        Label title = Fx.title(g.displayName());
        title.setWrapText(true);
        Label sub = Fx.muted(Enums.label(g.type()) + (g.status() == GearStatus.NONE ? "" : " · " + Enums.label(g.status())));
        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> edit.accept(g.id()));
        Button wish = new Button(g.status() == GearStatus.WISHLIST ? "Open in wishlist" : "Add to wishlist");
        wish.setOnAction(e -> {
            if (g.status() == GearStatus.WISHLIST) nav.wishlist(g.id());
            else ctx.run("Adding to wishlist", () -> { ctx.gear.setStatus(g.id(), GearStatus.WISHLIST); return null; }, x -> ctx.reloadCatalog());
        });
        VBox header = new VBox(4, title, sub, Fx.row(8, editBtn, wish));

        GridPane specs = new GridPane();
        specs.setHgap(14);
        specs.setVgap(5);
        int r = 0;
        for (var e : specPairs(d, b.summary()).entrySet()) {
            Label k = new Label(e.getKey());
            k.getStyleClass().add("spec-key");
            Label v = new Label(e.getValue());
            v.getStyleClass().add("spec-value");
            v.setWrapText(true);
            specs.add(k, 0, r);
            specs.add(v, 1, r++);
        }

        curveList.getItems().setAll(b.curves());
        Button importCurve = new Button("Import file…");
        importCurve.setOnAction(e -> importCurve(g.id()));
        Button delCurve = new Button("Delete");
        delCurve.getStyleClass().add(Styles.DANGER);
        delCurve.disableProperty().bind(curveList.getSelectionModel().selectedItemProperty().isNull());
        delCurve.setOnAction(e -> {
            CurveInfo c = curveList.getSelectionModel().getSelectedItem();
            if (c != null && Fx.confirm("Delete this curve?", c.title()))
                ctx.run("Deleting curve", () -> { ctx.curves.delete(c.id()); return null; }, x -> { show(current); ctx.reloadCatalog(); });
        });
        Button openCurves = new Button("Open in Curves");
        openCurves.setDisable(b.curves().isEmpty());
        openCurves.setOnAction(e -> nav.curves(List.of(g.id())));

        List<CurveChart.Series> series = new ArrayList<>();
        boolean fr = !b.chartCurves().isEmpty() && b.chartCurves().getFirst().kind() == CurveKind.FR;
        for (int i = 0; i < b.chartCurves().size(); i++) {
            Curve c = b.chartCurves().get(i);
            double[] y = fr ? CurveMath.normalize(c.freqHz(), c.value(), 1000) : c.value();
            series.add(new CurveChart.Series(c.source() + (c.label().isBlank() ? "" : " · " + c.label()), c.freqHz(), y, CurveChart.color(i)));
        }
        chart.setYAxis(fr ? "dB (0 at 1 kHz)" : "Ω", null, null);
        chart.setEmptyMessage("Import a curve to see it here");
        chart.setSeries(series);

        threadList.getItems().setAll(b.threads());
        Button more = new Button("Find threads");
        more.setOnAction(e -> nav.discover(g.id()));
        Button unlink = new Button("Unlink");
        unlink.disableProperty().bind(threadList.getSelectionModel().selectedItemProperty().isNull());
        unlink.setOnAction(e -> {
            ForumThread t = threadList.getSelectionModel().getSelectedItem();
            if (t != null) ctx.run(null, () -> { ctx.threads.link(t.id(), g.id(), LinkKind.REJECTED); return null; }, x -> { show(current); ctx.reloadCatalog(); });
        });

        body.getChildren().setAll(header, specs,
                Fx.heading("Curves"), chart, curveList, Fx.row(8, importCurve, openCurves, delCurve),
                Fx.heading("Community threads (" + b.threads().size() + ")"), threadList, Fx.row(8, more, unlink));
        if (g.notes() != null && !g.notes().isBlank()) {
            Label notes = new Label(g.notes());
            notes.setWrapText(true);
            body.getChildren().addAll(Fx.heading("Notes"), notes);
        }
    }

    static Map<String, String> specPairs(GearDetail d, GearSummary s) {
        Map<String, String> m = new LinkedHashMap<>();
        Gear g = d.gear();
        if (s.bestPrice() != null) m.put("Best price", Fx.money(s.bestPrice(), s.bestPriceCurrency()));
        if (g.msrp() != null) m.put("MSRP", Fx.money(g.msrp(), g.msrpCurrency()));
        if (g.releaseYear() != null) m.put("Released", g.releaseYear().toString());
        if (!d.tags().isEmpty()) m.put("Tags", String.join(", ", d.tags()));
        TransducerSpec t = d.transducer();
        if (t != null) {
            put(m, "Form", Enums.label(t.form()));
            put(m, "Back", Enums.label(t.back()));
            put(m, "Driver", join(Enums.label(t.driverType()), t.driverConfig(), t.driverSizeMm() == null ? null : Fx.num(t.driverSizeMm(), 1) + " mm"));
            if (t.impedanceOhm() != null) m.put("Impedance", Fx.num(t.impedanceOhm(), 1, "Ω"));
            if (t.sensitivity() != null) m.put("Sensitivity", Fx.num(t.sensitivity(), 1) + " " + Enums.label(t.sensitivityUnit()));
            if (t.weightG() != null) m.put("Weight", Fx.num(t.weightG(), 0, "g"));
            put(m, "Connector", t.connector());
        }
        AmpSpec a = d.amp();
        if (a != null) {
            if (a.outputImpedanceOhm() != null) m.put("Output impedance", Fx.num(a.outputImpedanceOhm(), 2, "Ω"));
            if (a.maxVoltageVrms() != null) m.put("Max voltage", Fx.num(a.maxVoltageVrms(), 2, "Vrms"));
            if (a.maxCurrentMa() != null) m.put("Max current", Fx.num(a.maxCurrentMa(), 0, "mA"));
            if (!d.powers().isEmpty()) {
                List<String> p = new ArrayList<>();
                for (AmpPower ap : d.powers()) p.add(Fx.num(ap.powerMw(), 0) + " mW @ " + Fx.num(ap.loadOhm(), 0) + " Ω " + Enums.label(ap.output()));
                m.put("Rated power", String.join("\n", p));
            }
            put(m, "Gain", a.gainSettings());
            put(m, "Topology", a.topology());
            put(m, "Outputs", a.outputs());
        }
        DacSpec x = d.dac();
        if (x != null) {
            put(m, "Chip", x.chip());
            if (x.sinadDb() != null) m.put("SINAD", Fx.num(x.sinadDb(), 1, "dB"));
            if (x.outputVoltageVrms() != null) m.put("Output voltage", Fx.num(x.outputVoltageVrms(), 2, "Vrms"));
            if (x.maxSampleRateKhz() != null) m.put("Max sample rate", Fx.num(x.maxSampleRateKhz(), 1, "kHz"));
            if (x.dsd() != null) m.put("DSD", x.dsd() ? "Yes" : "No");
            put(m, "Inputs", x.inputs());
            put(m, "DAC outputs", x.outputs());
        }
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return String.join(" · ", out);
    }

    private void importCurve(long gearId) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import curve file");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Curve files", "*.csv", "*.txt", "*.tsv", "*.frd", "*.zma"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = fc.showOpenDialog(root.getScene().getWindow());
        if (f == null) return;
        importCurveFile(ctx, f, gearId, () -> { show(current); ctx.reloadCatalog(); });
    }

    /** Parses the file in the background, asks what it is, then saves it. Shared with the Data screen. */
    static void importCurveFile(AppContext ctx, File f, Long gearId, Runnable after) {
        ctx.run("Reading " + f.getName(), () -> CurveFileParser.parse(Files.readString(f.toPath())), parsed ->
                new CurveImportDialog(f.toPath(), parsed, () -> ctx.catalog, gearId).showAndWait().ifPresent(c ->
                        ctx.run("Saving curve", () -> ctx.curves.save(c), id -> after.run())),
                e -> Fx.error("Could not read " + f.getName(), AppContext.message(e)));
    }
}
