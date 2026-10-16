package audiogear.ui;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.ui.views.CatalogView;
import audiogear.ui.views.CompareView;
import audiogear.ui.views.CurvesView;
import audiogear.ui.views.DataView;
import audiogear.ui.views.DiscoverView;
import audiogear.ui.views.PairingView;
import audiogear.ui.views.WishlistView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Navigation rail on the left, the current screen in the middle, the status bar at the bottom. */
public final class MainWindow extends BorderPane implements Navigator {

    private final StackPane content = new StackPane();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final Map<View, ToggleButton> buttons = new LinkedHashMap<>();

    private final CatalogView catalog;
    private final CompareView compare;
    private final CurvesView curves;
    private final PairingView pairing;
    private final DiscoverView discover;
    private final WishlistView wishlist;
    private final DataView data;

    public MainWindow(AppContext ctx) {
        catalog = new CatalogView(ctx, this);
        compare = new CompareView(ctx, this);
        curves = new CurvesView(ctx);
        pairing = new PairingView(ctx, this);
        discover = new DiscoverView(ctx, this);
        wishlist = new WishlistView(ctx, this);
        data = new DataView(ctx);

        VBox nav = new VBox(4);
        nav.getStyleClass().add("nav-rail");
        nav.setPadding(new Insets(16, 10, 16, 10));
        Label app = new Label("Audio Gear");
        app.getStyleClass().addAll(Styles.TITLE_3, "app-name");
        Label sub = new Label("Aggregator");
        sub.getStyleClass().add(Styles.TEXT_MUTED);
        nav.getChildren().addAll(app, sub, spacer(14));
        for (View v : List.of(catalog, compare, curves, pairing, discover, wishlist, data)) {
            ToggleButton b = new ToggleButton(v.title());
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.getStyleClass().add("nav-button");
            b.setOnAction(e -> show(v));
            buttons.put(v, b);
            nav.getChildren().add(b);
        }
        // Keep one item selected even when the active button is clicked again
        navGroup.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null && old != null) old.setSelected(true);
        });
        setLeft(nav);
        setCenter(content);
        setBottom(statusBar(ctx));
        show(catalog);
    }

    private static VBox spacer(double h) {
        VBox v = new VBox();
        v.setMinHeight(h);
        return v;
    }

    private HBox statusBar(AppContext ctx) {
        Label msg = new Label();
        msg.textProperty().bind(ctx.status.message);
        ctx.status.error.addListener((o, a, err) -> msg.pseudoClassStateChanged(Styles.STATE_DANGER, err));
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(180);
        bar.progressProperty().bind(ctx.status.progress);
        bar.visibleProperty().bind(ctx.status.running.greaterThan(0));
        bar.managedProperty().bind(bar.visibleProperty());
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add(Styles.SMALL);
        cancel.visibleProperty().bind(ctx.status.cancel.isNotNull());
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.setOnAction(e -> {
            Runnable r = ctx.status.cancel.get();
            if (r != null) r.run();
            cancel.setDisable(true);
        });
        ctx.status.cancel.addListener((o, a, b) -> cancel.setDisable(false));
        Label newVersion = new Label();
        newVersion.getStyleClass().add(Styles.ACCENT);
        Button download = new Button("Download");
        download.getStyleClass().addAll(Styles.SMALL, Styles.ACCENT);
        download.setOnAction(e -> { var v = ctx.status.newVersion.get(); if (v != null) ctx.openUrl(v.url()); });
        Button dismiss = new Button("Dismiss");
        dismiss.getStyleClass().add(Styles.SMALL);
        dismiss.setOnAction(e -> ctx.dismissNewVersion());
        HBox notice = new HBox(6, newVersion, download, dismiss);
        notice.setAlignment(Pos.CENTER_LEFT);
        notice.visibleProperty().bind(ctx.status.newVersion.isNotNull());
        notice.managedProperty().bind(notice.visibleProperty());
        ctx.status.newVersion.addListener((o, a, v) -> newVersion.setText(v == null ? "" : "Version " + v.version() + " is available"));
        Label counts = new Label();
        ctx.onCatalogReloaded(() -> counts.setText(ctx.catalog.size() + " items in catalog"));
        counts.getStyleClass().add(Styles.TEXT_MUTED);
        HBox bar2 = new HBox(10, msg, Fx.grow(), notice, counts, bar, cancel);
        HBox.setHgrow(msg, Priority.SOMETIMES);
        bar2.setAlignment(Pos.CENTER_LEFT);
        bar2.setPadding(new Insets(6, 12, 6, 12));
        bar2.getStyleClass().add("status-bar");
        return bar2;
    }

    /** Shows the screen with this navigation title, e.g. "Data & Settings". */
    public void showScreen(String title) {
        buttons.keySet().stream().filter(v -> v.title().equals(title)).findFirst().ifPresent(this::show);
    }

    private void show(View v) {
        content.getChildren().setAll(v.root());
        buttons.get(v).setSelected(true);
        v.onShow();
    }

    @Override public void catalog(Long selectId) { show(catalog); catalog.select(selectId); }
    @Override public void compare(List<Long> ids) { compare.setItems(ids); show(compare); }
    @Override public void curves(List<Long> ids) { curves.addGear(ids); show(curves); }
    @Override public void pairing(Long transducerId, Long ampId) { pairing.preset(transducerId, ampId); show(pairing); }
    @Override public void discover(Long gearId) { discover.showGear(gearId); show(discover); }
    @Override public void wishlist(Long selectId) { show(wishlist); wishlist.select(selectId); }
}
