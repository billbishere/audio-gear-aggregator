package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.analysis.CurveMath;
import audiogear.app.AppContext;
import audiogear.domain.Curve;
import audiogear.domain.CurveInfo;
import audiogear.domain.CurveKind;
import audiogear.domain.GearDetail;
import audiogear.domain.GearSummary;
import audiogear.ui.CurveChart;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import audiogear.ui.Navigator;
import audiogear.ui.View;
import audiogear.ui.model.CompareModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/** Up to six items side by side: one row per spec, best values highlighted, FR curves overlaid. */
public final class CompareView implements View {

    public static final int MAX_ITEMS = 6;

    private record Loaded(List<CompareModel.Item> items, List<Curve> curves) {}

    private final AppContext ctx;
    private final Navigator nav;
    private final BorderPane root = new BorderPane();
    private final List<Long> ids = new ArrayList<>();
    private final CheckBox differencesOnly = new CheckBox("Differences only");
    private final GridPane grid = new GridPane();
    private final CurveChart chart = new CurveChart();
    private final GearPicker adder;
    private Loaded loaded = new Loaded(List.of(), List.of());

    public CompareView(AppContext ctx, Navigator nav) {
        this.ctx = ctx;
        this.nav = nav;
        adder = new GearPicker("Add an item to compare", () -> ctx.catalog, g -> !ids.contains(g.id()));
        adder.valueProperty().addListener((o, a, g) -> {
            if (g != null && ids.size() < MAX_ITEMS && !ids.contains(g.id())) {
                ids.add(g.id());
                adder.select(null);
                reload();
            }
        });
        differencesOnly.selectedProperty().addListener((o, a, b) -> render());
        Button clear = new Button("Clear");
        clear.getStyleClass().add(Styles.FLAT);
        clear.setOnAction(e -> { ids.clear(); reload(); });
        Button curves = new Button("Open in Curves");
        curves.setOnAction(e -> nav.curves(List.copyOf(ids)));

        HBox bar = Fx.row(8, Fx.title("Compare"), Fx.grow(), adder, differencesOnly, curves, clear);
        bar.getStyleClass().add("toolbar-row");
        grid.getStyleClass().add("compare-grid");
        grid.setHgap(0);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        chart.setYAxis("dB (0 at 1 kHz)", null, null);
        chart.setEmptyMessage("Frequency response of the compared items appears here");
        chart.setPrefHeight(300);
        SplitPane split = new SplitPane(scroll, chart);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox body = new VBox(10, bar, split);
        body.getStyleClass().add("view");
        root.setCenter(body);
        ctx.onCatalogReloaded(() -> { if (!ids.isEmpty()) reload(); });
        render();
    }

    @Override public String title() { return "Compare"; }
    @Override public Node root() { return root; }

    public void setItems(List<Long> gearIds) {
        ids.clear();
        for (Long id : gearIds) if (ids.size() < MAX_ITEMS && !ids.contains(id)) ids.add(id);
        reload();
    }

    private void reload() {
        List<Long> snapshot = List.copyOf(ids);
        ctx.run(snapshot.isEmpty() ? null : "Loading comparison", () -> {
            List<CompareModel.Item> items = new ArrayList<>();
            List<Curve> curves = new ArrayList<>();
            for (long id : snapshot) {
                var d = ctx.gear.get(id);
                var s = ctx.gear.summary(id);
                if (d.isEmpty() || s.isEmpty()) continue;
                items.add(new CompareModel.Item(d.get(), s.get()));
                CurveInfo fr = ctx.curves.forGear(id).stream().filter(i -> i.kind() == CurveKind.FR).findFirst().orElse(null);
                curves.add(fr == null ? null : ctx.curves.load(fr.id()).orElse(null));
            }
            return new Loaded(items, curves);
        }, l -> {
            loaded = l;
            ids.retainAll(l.items().stream().map(i -> i.detail().gear().id()).toList());
            render();
        });
    }

    private void render() {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        List<CompareModel.Item> items = loaded.items();
        if (items.isEmpty()) {
            Label hint = Fx.muted("Pick items in the Catalog and press Compare, or add them with the search box above (up to " + MAX_ITEMS + ").");
            hint.setPadding(new Insets(20));
            grid.add(hint, 0, 0);
            chart.setSeries(List.of());
            return;
        }
        ColumnConstraints labelCol = new ColumnConstraints(190);
        grid.getColumnConstraints().add(labelCol);
        for (int i = 0; i < items.size(); i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setHgrow(Priority.ALWAYS);
            c.setMinWidth(150);
            grid.getColumnConstraints().add(c);
        }
        for (int i = 0; i < items.size(); i++) grid.add(header(items.get(i), i), i + 1, 0);

        int row = 1;
        String section = null;
        for (CompareModel.Row r : CompareModel.rows(items, differencesOnly.isSelected())) {
            if (!r.section().equals(section)) {
                section = r.section();
                Label s = new Label(section);
                s.getStyleClass().add("section-label");
                grid.add(s, 0, row++, items.size() + 1, 1);
            }
            grid.add(cell(r.label(), false, true), 0, row);
            for (int i = 0; i < r.values().size(); i++) grid.add(cell(r.values().get(i), i == r.best(), false), i + 1, row);
            row++;
        }

        List<CurveChart.Series> series = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Curve c = i < loaded.curves().size() ? loaded.curves().get(i) : null;
            if (c == null) continue;
            series.add(new CurveChart.Series(items.get(i).detail().gear().displayName(), c.freqHz(),
                    CurveMath.normalize(c.freqHz(), c.value(), 1000), CurveChart.color(i)));
        }
        chart.setSeries(series);
    }

    private Node header(CompareModel.Item item, int index) {
        Region swatch = new Region();
        swatch.getStyleClass().add("swatch");
        swatch.setStyle("-fx-background-color: " + toWeb(CurveChart.color(index)) + ";");
        Label name = new Label(item.detail().gear().displayName());
        name.getStyleClass().add("compare-header");
        name.setWrapText(true);
        Button remove = new Button("✕");
        remove.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
        remove.setTooltip(new Tooltip("Remove from comparison"));
        long id = item.detail().gear().id();
        remove.setOnAction(e -> { ids.remove(id); reload(); });
        Hyperlink open = new Hyperlink("Show in catalog");
        open.setOnAction(e -> nav.catalog(id));
        HBox top = new HBox(6, swatch, name, Fx.grow(), remove);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(2, top, open);
        box.getStyleClass().add("cell-box");
        return box;
    }

    private static Node cell(String text, boolean best, boolean key) {
        Label l = new Label(text);
        l.setWrapText(true);
        if (key) l.getStyleClass().add("spec-key");
        HBox box = new HBox(l);
        box.getStyleClass().add("cell-box");
        if (best) box.getStyleClass().add("best");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    static String toWeb(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }
}
