package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.domain.*;
import audiogear.ui.Fx;
import audiogear.ui.Navigator;
import audiogear.ui.NumberField;
import audiogear.ui.View;
import audiogear.ui.dialogs.GearEditorDialog;
import audiogear.ui.model.CatalogFilter;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** The whole catalog in one filterable, sortable table, with a detail pane for the selected item. */
public final class CatalogView implements View {

    private final AppContext ctx;
    private final Navigator nav;
    private final BorderPane root = new BorderPane();
    private final FilteredList<GearSummary> filtered;
    private final TableView<GearSummary> table = new TableView<>();
    private final GearDetailPane detail;
    private final Label count = new Label();

    private final ToggleGroup tabs = new ToggleGroup();
    private final TextField search = new TextField();
    private final ComboBox<GearStatus> status = Fx.enumCombo(GearStatus.class, "Any status");
    private final ComboBox<DriverType> driver = Fx.enumCombo(DriverType.class, "Any driver");
    private final ComboBox<Form> form = Fx.enumCombo(Form.class, "Any form");
    private final NumberField minZ = NumberField.nonNegative("min Ω"), maxZ = NumberField.nonNegative("max Ω");
    private final NumberField minPrice = NumberField.nonNegative("min price"), maxPrice = NumberField.nonNegative("max price");
    private final ComboBox<String> tag = new ComboBox<>();
    private Long pendingSelect;

    public CatalogView(AppContext ctx, Navigator nav) {
        this.ctx = ctx;
        this.nav = nav;
        this.filtered = new FilteredList<>(ctx.catalog, g -> true);
        this.detail = new GearDetailPane(ctx, nav, this::edit);

        SortedList<GearSummary> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setTableMenuButtonVisible(true);
        table.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE);
        table.setPlaceholder(new Label("No gear matches. Add some with New, or import AutoEq data under Data & Settings."));
        buildColumns();
        table.getSelectionModel().selectedItemProperty().addListener((o, a, g) -> detail.show(g));
        table.setRowFactory(tv -> {
            TableRow<GearSummary> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) edit(row.getItem().id());
            });
            return row;
        });
        table.setContextMenu(contextMenu());

        filtered.addListener((javafx.collections.ListChangeListener<GearSummary>) c -> updateCount());
        ctx.onCatalogReloaded(this::afterReload);

        VBox top = new VBox(8, toolbar(), filterBar());
        SplitPane split = new SplitPane(table, detail.root());
        split.setDividerPositions(0.64);
        SplitPane.setResizableWithParent(detail.root(), false);
        VBox.setVgrow(split, javafx.scene.layout.Priority.ALWAYS);
        VBox body = new VBox(10, top, split);
        body.getStyleClass().add("view");
        root.setCenter(body);
    }

    @Override public String title() { return "Catalog"; }
    @Override public Node root() { return root; }

    /** Selects the item with this id once it is in the table. */
    public void select(Long id) {
        if (id == null) return;
        pendingSelect = id;
        applySelect();
    }

    private void applySelect() {
        if (pendingSelect == null) return;
        for (GearSummary g : table.getItems()) {
            if (g.id() == pendingSelect) {
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(g);
                table.scrollTo(g);
                pendingSelect = null;
                return;
            }
        }
    }

    private void afterReload() {
        List<Long> before = selectedIds();
        List<String> tags = new ArrayList<>(ctx.catalog.stream().flatMap(g -> g.tags() == null ? java.util.stream.Stream.<String>empty()
                : java.util.Arrays.stream(g.tags().split(",")).map(String::strip)).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        String current = tag.getValue();
        tags.addFirst("Any tag");
        tag.getItems().setAll(tags);
        tag.setValue(current != null && tags.contains(current) ? current : "Any tag");
        if (pendingSelect == null && !before.isEmpty()) pendingSelect = before.getFirst();
        applySelect();
        GearSummary sel = table.getSelectionModel().getSelectedItem();
        detail.show(sel);
        updateCount();
    }

    private void updateCount() {
        count.setText(filtered.size() == ctx.catalog.size() ? ctx.catalog.size() + " items" : filtered.size() + " of " + ctx.catalog.size());
    }

    private Node toolbar() {
        MenuButton add = new MenuButton("New");
        add.getStyleClass().add(Styles.ACCENT);
        for (GearType t : GearType.values()) {
            MenuItem mi = new MenuItem(Enums.label(t));
            mi.setOnAction(e -> create(t));
            add.getItems().add(mi);
        }
        Button edit = new Button("Edit");
        edit.setOnAction(e -> { if (!selectedIds().isEmpty()) edit(selectedIds().getFirst()); });
        Button delete = new Button("Delete");
        delete.getStyleClass().add(Styles.DANGER);
        delete.setOnAction(e -> deleteSelected());
        Button compare = new Button("Compare");
        compare.setOnAction(e -> nav.compare(selectedIds()));
        Button curves = new Button("Overlay curves");
        curves.setOnAction(e -> nav.curves(selectedIds()));
        Button pairing = new Button("Check pairing");
        pairing.setOnAction(e -> openPairing());
        Button threads = new Button("Find threads");
        threads.setOnAction(e -> { if (!selectedIds().isEmpty()) nav.discover(selectedIds().getFirst()); });
        MenuButton statusMenu = new MenuButton("Set status");
        for (GearStatus s : GearStatus.values()) {
            MenuItem mi = new MenuItem(Enums.label(s));
            mi.setOnAction(e -> setStatus(s));
            statusMenu.getItems().add(mi);
        }
        for (Control c : List.of(edit, delete, compare, curves, pairing, threads, statusMenu)) {
            c.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
        HBox bar = Fx.row(8, Fx.title("Catalog"), Fx.grow(), add, edit, delete, new Separator(javafx.geometry.Orientation.VERTICAL),
                compare, curves, pairing, threads, statusMenu);
        bar.getStyleClass().add("toolbar-row");
        return bar;
    }

    private Node filterBar() {
        HBox pills = new HBox();
        CatalogFilter.Tab[] all = CatalogFilter.Tab.values();
        for (int i = 0; i < all.length; i++) {
            ToggleButton b = new ToggleButton(all[i].label);
            b.setUserData(all[i]);
            b.setToggleGroup(tabs);
            b.getStyleClass().add(i == 0 ? Styles.LEFT_PILL : i == all.length - 1 ? Styles.RIGHT_PILL : Styles.CENTER_PILL);
            if (i == 0) b.setSelected(true);
            pills.getChildren().add(b);
        }
        tabs.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true);
            else applyFilter();
        });
        search.setPromptText("Search brand, model, chip, tags…");
        search.setPrefColumnCount(20);
        tag.getItems().setAll("Any tag");
        tag.setValue("Any tag");
        for (NumberField f : List.of(minZ, maxZ, minPrice, maxPrice)) f.setPrefColumnCount(6);
        search.textProperty().addListener((o, a, b) -> applyFilter());
        for (ComboBox<?> c : List.of(status, driver, form, tag)) c.valueProperty().addListener((o, a, b) -> applyFilter());
        for (NumberField f : List.of(minZ, maxZ, minPrice, maxPrice)) f.textProperty().addListener((o, a, b) -> applyFilter());
        Button clear = new Button("Clear");
        clear.getStyleClass().add(Styles.FLAT);
        clear.setOnAction(e -> {
            search.clear();
            status.getSelectionModel().selectFirst();
            driver.getSelectionModel().selectFirst();
            form.getSelectionModel().selectFirst();
            tag.setValue("Any tag");
            for (NumberField f : List.of(minZ, maxZ, minPrice, maxPrice)) f.clear();
            tabs.getToggles().getFirst().setSelected(true);
        });
        count.getStyleClass().add(Styles.TEXT_MUTED);
        HBox first = Fx.row(8, pills, search, clear, Fx.grow(), count);
        HBox second = Fx.row(8, status, driver, form, tag, new Label("Impedance"), minZ, new Label("–"), maxZ,
                new Label("Price"), minPrice, new Label("–"), maxPrice);
        first.getStyleClass().add("toolbar-row");
        second.getStyleClass().add("toolbar-row");
        return new VBox(8, first, second);
    }

    private void applyFilter() {
        CatalogFilter.Tab tab = tabs.getSelectedToggle() == null ? CatalogFilter.Tab.ALL : (CatalogFilter.Tab) tabs.getSelectedToggle().getUserData();
        String t = "Any tag".equals(tag.getValue()) ? null : tag.getValue();
        filtered.setPredicate(new CatalogFilter(tab, search.getText(), status.getValue(), driver.getValue(), form.getValue(),
                minZ.getValue(), maxZ.getValue(), minPrice.getValue(), maxPrice.getValue(), t));
    }

    private void buildColumns() {
        TableColumn<GearSummary, String> name = text("Name", GearSummary::displayName, 230);
        table.getColumns().add(name);
        table.getColumns().add(text("Type", g -> Enums.label(g.type()), 80));
        table.getColumns().add(text("Status", g -> g.status() == GearStatus.NONE ? "" : Enums.label(g.status()), 80));
        table.getColumns().add(text("Form", g -> Enums.label(g.form()), 80));
        TableColumn<GearSummary, String> back = text("Back", g -> Enums.label(g.back()), 80);
        table.getColumns().add(back);
        table.getColumns().add(text("Driver", g -> Enums.label(g.driverType()), 90));
        TableColumn<GearSummary, String> config = text("Layout", GearSummary::driverConfig, 80);
        table.getColumns().add(config);
        table.getColumns().add(number("Impedance Ω", GearSummary::impedanceOhm, 1, 90));
        table.getColumns().add(text("Sensitivity", g -> g.sensitivity() == null ? "" : Fx.num(g.sensitivity(), 1) + " " + Enums.label(g.sensitivityUnit()), 100));
        table.getColumns().add(number("Weight g", GearSummary::weightG, 0, 70));
        table.getColumns().add(number("Out Z Ω", GearSummary::outputImpedanceOhm, 2, 70));
        table.getColumns().add(number("SINAD dB", GearSummary::sinadDb, 1, 75));
        TableColumn<GearSummary, String> chip = text("Chip", GearSummary::chip, 90);
        table.getColumns().add(chip);
        TableColumn<GearSummary, Double> price = number("Price", GearSummary::effectivePrice, 2, 85);
        table.getColumns().add(price);
        table.getColumns().add(number("Curves", g -> (double) g.curveCount(), 0, 60));
        table.getColumns().add(number("Threads", g -> (double) g.threadCount(), 0, 65));
        TableColumn<GearSummary, Double> recent = number("Recent", g -> (double) g.recentThreadCount(), 0, 60);
        table.getColumns().add(recent);
        table.getColumns().add(text("Tags", GearSummary::tags, 120));
        TableColumn<GearSummary, Double> year = number("Year", g -> g.releaseYear() == null ? null : g.releaseYear().doubleValue(), 0, 55);
        table.getColumns().add(year);
        for (TableColumn<?, ?> c : List.of(back, config, chip, recent, year)) c.setVisible(false);
        table.getSortOrder().add(name);
    }

    private static TableColumn<GearSummary, String> text(String title, Function<GearSummary, String> f, double width) {
        TableColumn<GearSummary, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue()) == null ? "" : f.apply(cd.getValue())));
        c.setComparator(String.CASE_INSENSITIVE_ORDER);
        c.setPrefWidth(width);
        return c;
    }

    private static TableColumn<GearSummary, Double> number(String title, Function<GearSummary, Double> f, int decimals, double width) {
        TableColumn<GearSummary, Double> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setComparator(Comparator.nullsLast(Comparator.naturalOrder()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : Fx.num(v, decimals));
            }
        });
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setPrefWidth(width);
        return c;
    }

    private ContextMenu contextMenu() {
        MenuItem edit = new MenuItem("Edit…");
        edit.setOnAction(e -> { if (!selectedIds().isEmpty()) edit(selectedIds().getFirst()); });
        MenuItem compare = new MenuItem("Compare selected");
        compare.setOnAction(e -> nav.compare(selectedIds()));
        MenuItem curves = new MenuItem("Overlay curves");
        curves.setOnAction(e -> nav.curves(selectedIds()));
        MenuItem pairing = new MenuItem("Check pairing");
        pairing.setOnAction(e -> openPairing());
        MenuItem threads = new MenuItem("Find threads");
        threads.setOnAction(e -> { if (!selectedIds().isEmpty()) nav.discover(selectedIds().getFirst()); });
        MenuItem wish = new MenuItem("Add to wishlist");
        wish.setOnAction(e -> setStatus(GearStatus.WISHLIST));
        MenuItem delete = new MenuItem("Delete…");
        delete.setOnAction(e -> deleteSelected());
        return new ContextMenu(edit, compare, curves, pairing, threads, wish, new SeparatorMenuItem(), delete);
    }

    private List<Long> selectedIds() {
        return table.getSelectionModel().getSelectedItems().stream().filter(java.util.Objects::nonNull).map(GearSummary::id).toList();
    }

    private void openPairing() {
        Long t = null, a = null;
        for (GearSummary g : table.getSelectionModel().getSelectedItems()) {
            if (g == null) continue;
            if (t == null && g.type().isTransducer()) t = g.id();
            if (a == null && g.type().isAmp()) a = g.id();
        }
        nav.pairing(t, a);
    }

    private void create(GearType type) {
        new GearEditorDialog(GearDetail.of(Gear.create(type, "", ""))).showAndWait().ifPresent(this::save);
    }

    void edit(long id) {
        ctx.run(null, () -> ctx.gear.get(id).orElseThrow(), d -> new GearEditorDialog(d).showAndWait().ifPresent(this::save));
    }

    private void save(GearDetail d) {
        ctx.run("Saving " + d.gear().displayName(), () -> ctx.gear.save(d), id -> {
            pendingSelect = id;
            ctx.reloadCatalog();
        }, e -> {
            if (String.valueOf(AppContext.message(e)).contains("UNIQUE")) {
                Fx.error("Already in the catalog", d.gear().displayName() + " already exists. Use a variant to tell them apart.");
            }
        });
    }

    private void deleteSelected() {
        List<GearSummary> sel = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) return;
        String what = sel.size() == 1 ? sel.getFirst().displayName() : sel.size() + " items";
        if (!Fx.confirm("Delete " + what + "?", "Their curves, prices and thread links are deleted too. This can't be undone.")) return;
        ctx.run("Deleting " + what, () -> {
            for (GearSummary g : sel) ctx.gear.delete(g.id());
            return null;
        }, x -> ctx.reloadCatalog());
    }

    private void setStatus(GearStatus s) {
        List<Long> ids = selectedIds();
        ctx.run("Updating status", () -> {
            for (long id : ids) ctx.gear.setStatus(id, s);
            return null;
        }, x -> ctx.reloadCatalog());
    }
}
