package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.db.WishlistRow;
import audiogear.domain.Enums;
import audiogear.domain.GearStatus;
import audiogear.domain.PriceEntry;
import audiogear.domain.WishlistItem;
import audiogear.ui.Fx;
import audiogear.ui.Navigator;
import audiogear.ui.NumberField;
import audiogear.ui.Sparkline;
import audiogear.ui.View;
import audiogear.ui.dialogs.PriceDialog;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Wishlist with best current prices, target-price gaps and price history per item. */
public final class WishlistView implements View {

    private final AppContext ctx;
    private final Navigator nav;
    private final BorderPane root = new BorderPane();
    private final TableView<WishlistRow> table = new TableView<>();
    private final VBox side = new VBox(10);
    private final Spinner<Integer> priority = new Spinner<>(1, 5, 3);
    private final NumberField target = NumberField.nonNegative("target price");
    private final TextField currency = new TextField();
    private final TextArea notes = new TextArea();
    private final TableView<PriceEntry> prices = new TableView<>();
    private final Sparkline spark = new Sparkline();
    private Long pendingSelect;

    public WishlistView(AppContext ctx, Navigator nav) {
        this.ctx = ctx;
        this.nav = nav;
        table.getStyleClass().add(Styles.DENSE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Nothing on the wishlist. In the Catalog, select gear and choose Add to wishlist."));
        table.getColumns().add(col("Priority", r -> (double) r.priority(), 0, 72));
        TableColumn<WishlistRow, String> name = new TableColumn<>("Item");
        name.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().displayName()));
        name.setPrefWidth(210);
        table.getColumns().add(name);
        table.getColumns().add(money("Target", WishlistRow::targetPrice, WishlistRow::currency));
        table.getColumns().add(money("Best now", WishlistRow::bestPrice, WishlistRow::bestCurrency));
        TableColumn<WishlistRow, String> retailer = new TableColumn<>("Where");
        retailer.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().bestRetailer() == null ? "" : cd.getValue().bestRetailer()));
        retailer.setPrefWidth(130);
        table.getColumns().add(retailer);
        table.getColumns().add(money("Gap", WishlistRow::gapToTarget, WishlistRow::currency));
        TableColumn<WishlistRow, String> seen = new TableColumn<>("Seen");
        seen.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(Fx.date(cd.getValue().bestSeenAt())));
        seen.setPrefWidth(96);
        seen.setMinWidth(90);
        table.getColumns().add(seen);
        table.getColumns().add(money("MSRP", WishlistRow::msrp, r -> null));
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(WishlistRow r, boolean empty) {
                super.updateItem(r, empty);
                getStyleClass().remove("below-target");
                if (!empty && r != null && r.belowTarget()) getStyleClass().add("below-target");
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> showSide(r));

        prices.getStyleClass().add(Styles.DENSE);
        prices.setPrefHeight(160);
        prices.setPlaceholder(new Label("No prices yet"));
        prices.getColumns().add(pcol("Date", p -> Fx.date(p.seenAt())));
        prices.getColumns().add(pcol("Retailer", PriceEntry::retailer));
        prices.getColumns().add(pcol("Price", p -> Fx.money(p.price(), p.currency())));
        prices.getColumns().add(pcol("Condition", p -> Enums.label(p.condition())));
        prices.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        prices.setOnMouseClicked(e -> {
            PriceEntry p = prices.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && p != null && p.url() != null) ctx.openUrl(p.url());
        });
        currency.setPrefColumnCount(5);
        notes.setPrefRowCount(3);
        notes.setWrapText(true);
        side.setPrefWidth(420);
        side.setMinWidth(380);
        showSide(null);

        SplitPane split = new SplitPane(table, new ScrollPane(side));
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);
        Label hint = Fx.muted("Rows turn green when the best current price is at or below your target. The best price is the cheapest of each retailer's latest entry.");
        VBox body = new VBox(10, Fx.row(8, Fx.title("Wishlist")), hint, split);
        body.getStyleClass().add("view");
        root.setCenter(body);
        ctx.onCatalogReloaded(this::reload);
    }

    @Override public String title() { return "Wishlist"; }
    @Override public Node root() { return root; }
    @Override public void onShow() { reload(); }

    public void select(Long gearId) {
        pendingSelect = gearId;
        reload();
    }

    private void reload() {
        Long keep = pendingSelect != null ? pendingSelect
                : table.getSelectionModel().getSelectedItem() == null ? null : table.getSelectionModel().getSelectedItem().gearId();
        ctx.run(null, ctx.wishlist::list, rows -> {
            table.getItems().setAll(rows);
            pendingSelect = null;
            if (keep != null) rows.stream().filter(r -> r.gearId() == keep).findFirst().ifPresent(r -> table.getSelectionModel().select(r));
        });
    }

    private void showSide(WishlistRow r) {
        if (r == null) {
            side.getChildren().setAll(Fx.muted("Select a wishlist item to set its target price and record prices."));
            return;
        }
        priority.getValueFactory().setValue(r.priority());
        target.setValue(r.targetPrice());
        currency.setText(r.currency() == null ? ctx.settings.currency() : r.currency());
        notes.setText(r.notes());
        Button save = new Button("Save");
        save.getStyleClass().add(Styles.ACCENT);
        save.disableProperty().bind(target.validProperty().not());
        save.setOnAction(e -> ctx.run("Saving wishlist item", () -> {
            ctx.wishlist.saveItem(new WishlistItem(r.gearId(), priority.getValue(), target.getValue(),
                    currency.getText() == null || currency.getText().isBlank() ? null : currency.getText().strip().toUpperCase(),
                    notes.getText() == null || notes.getText().isBlank() ? null : notes.getText().strip()));
            return null;
        }, x -> reload()));
        Button addPrice = new Button("Add price…");
        addPrice.setOnAction(e -> {
            List<String> retailers = prices.getItems().stream().map(PriceEntry::retailer).distinct().toList();
            new PriceDialog(r.gearId(), r.displayName(), currency.getText() == null || currency.getText().isBlank() ? ctx.settings.currency() : currency.getText(), retailers)
                    .showAndWait().ifPresent(p -> ctx.run("Saving price", () -> ctx.wishlist.addPrice(p), id -> ctx.reloadCatalog()));
        });
        Button delPrice = new Button("Delete price");
        delPrice.getStyleClass().add(Styles.DANGER);
        delPrice.disableProperty().bind(prices.getSelectionModel().selectedItemProperty().isNull());
        delPrice.setOnAction(e -> {
            PriceEntry p = prices.getSelectionModel().getSelectedItem();
            if (p != null) ctx.run(null, () -> { ctx.wishlist.deletePrice(p.id()); return null; }, x -> ctx.reloadCatalog());
        });
        Button remove = new Button("Remove from wishlist");
        remove.getStyleClass().add(Styles.FLAT);
        remove.setOnAction(e -> ctx.run(null, () -> { ctx.gear.setStatus(r.gearId(), GearStatus.WATCHING); return null; }, x -> ctx.reloadCatalog()));
        remove.setTooltip(new Tooltip("Moves it to Watching; its prices are kept."));
        Button bought = new Button("Bought it");
        bought.getStyleClass().add(Styles.SUCCESS);
        bought.setOnAction(e -> ctx.run(null, () -> { ctx.gear.setStatus(r.gearId(), GearStatus.OWNED); return null; }, x -> ctx.reloadCatalog()));
        Hyperlink catalog = new Hyperlink("Show in catalog");
        catalog.setOnAction(e -> nav.catalog(r.gearId()));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.addRow(0, fixed("Priority (1 = top)"), priority);
        form.addRow(1, fixed("Target price"), Fx.row(6, target, currency));
        form.add(fixed("Notes"), 0, 2);
        form.add(notes, 1, 2);
        side.getChildren().setAll(Fx.title(r.displayName()), catalog,
                Fx.section("Target", form, Fx.row(8, save)),
                Fx.section("Price history", spark, prices, Fx.row(8, addPrice, delPrice)),
                Fx.row(8, bought, remove));
        ctx.run(null, () -> ctx.wishlist.prices(r.gearId()), list -> {
            prices.getItems().setAll(list);
            spark.set(list.stream().map(p -> new Sparkline.Point(Instant.parse(p.seenAt()).toEpochMilli(), p.price())).toList(), r.targetPrice());
        });
    }

    /** A form label that never shrinks to an ellipsis. */
    private static Label fixed(String text) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private static TableColumn<WishlistRow, Double> col(String title, Function<WishlistRow, Double> f, int decimals, double width) {
        TableColumn<WishlistRow, Double> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setCellFactory(x -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : Fx.num(v, decimals));
            }
        });
        c.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        c.setPrefWidth(width);
        return c;
    }

    private static TableColumn<WishlistRow, Double> money(String title, Function<WishlistRow, Double> f, Function<WishlistRow, String> cur) {
        TableColumn<WishlistRow, Double> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setCellFactory(x -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                WishlistRow row = getTableRow() == null ? null : getTableRow().getItem();
                setText(empty || v == null || row == null ? null : (title.equals("Gap") && v > 0 ? "+" : "") + Fx.money(v, cur.apply(row)));
            }
        });
        c.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setPrefWidth(105);
        return c;
    }

    private static TableColumn<PriceEntry, String> pcol(String title, Function<PriceEntry, String> f) {
        TableColumn<PriceEntry, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        return c;
    }
}
