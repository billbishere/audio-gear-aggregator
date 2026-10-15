package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.domain.Enums;
import audiogear.domain.ForumThread;
import audiogear.domain.GearSummary;
import audiogear.domain.LinkKind;
import audiogear.domain.ThreadSource;
import audiogear.search.SearchService;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import audiogear.ui.Navigator;
import audiogear.ui.View;
import audiogear.ui.dialogs.PickGearDialog;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Searches community sites for a gear item or free text, and searches every stored thread offline. */
public final class DiscoverView implements View {

    private final AppContext ctx;
    private final Navigator nav;
    private final BorderPane root = new BorderPane();
    private final GearPicker gear;
    private final TextField extra = new TextField();
    private final CheckBox force = new CheckBox("Ignore cache");
    private final Map<ThreadSource, CheckBox> sources = new EnumMap<>(ThreadSource.class);
    private final VBox banners = new VBox(6);
    private final Label summary = new Label();
    private final ListView<ForumThread> results = new ListView<>();
    private final Map<Long, List<String>> linked = new HashMap<>();
    private final Button searchButton = new Button("Search");

    public DiscoverView(AppContext ctx, Navigator nav) {
        this.ctx = ctx;
        this.nav = nav;
        gear = new GearPicker("Gear (optional)", () -> ctx.catalog, g -> true);
        extra.setPromptText("Extra words, e.g. \"vs\" or \"amp pairing\"; or a free-text search");
        extra.setPrefColumnCount(26);
        extra.setOnAction(e -> search());
        searchButton.getStyleClass().add(Styles.ACCENT);
        searchButton.setOnAction(e -> search());
        Button saved = new Button("Search saved threads");
        saved.setTooltip(new Tooltip("Full-text search over every thread already stored. Free, works offline."));
        saved.setOnAction(e -> searchSaved());
        HBox sourceRow = Fx.row(12, new Label("Sources:"));
        for (ThreadSource s : List.of(ThreadSource.REDDIT, ThreadSource.HEAD_FI, ThreadSource.ASR, ThreadSource.SBAF)) {
            CheckBox cb = new CheckBox(Enums.label(s));
            cb.setSelected(true);
            sources.put(s, cb);
            sourceRow.getChildren().add(cb);
        }
        sourceRow.getChildren().addAll(Fx.grow(), force);

        results.setCellFactory(lv -> new ThreadCell(linked, true));
        results.setPlaceholder(new Label("Search for a model to pull in community threads. Results are stored and linked to your catalog."));
        results.setOnMouseClicked(e -> {
            ForumThread t = results.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && t != null) ctx.openUrl(t.url());
        });
        results.setContextMenu(contextMenu());
        VBox.setVgrow(results, Priority.ALWAYS);
        summary.getStyleClass().add(Styles.TEXT_MUTED);

        HBox bar = Fx.row(8, Fx.title("Discover"), Fx.grow(), gear, extra, searchButton, saved);
        bar.getStyleClass().add("toolbar-row");
        VBox body = new VBox(10, bar, sourceRow, banners, summary, results);
        body.getStyleClass().add("view");
        root.setCenter(body);
    }

    @Override public String title() { return "Discover"; }
    @Override public Node root() { return root; }

    /** Shows the threads already stored for this gear (no API call) and readies a search for it. */
    public void showGear(Long gearId) {
        gear.selectId(gearId);
        GearSummary g = gear.getValue();
        if (g == null) return;
        banners.getChildren().clear();
        ctx.run(null, () -> ctx.threads.forGear(g.id()), list -> {
            show(list);
            summary.setText(list.size() + " saved thread(s) for " + g.displayName() + ". Press Search to look for new ones.");
        });
    }

    private Set<ThreadSource> chosen() {
        Set<ThreadSource> s = EnumSet.noneOf(ThreadSource.class);
        sources.forEach((k, cb) -> { if (cb.isSelected()) s.add(k); });
        return s;
    }

    private void search() {
        GearSummary g = gear.getValue();
        String text = extra.getText() == null ? "" : extra.getText().strip();
        if (g == null && text.isEmpty()) { summary.setText("Pick a gear item or type something to search for."); return; }
        if (chosen().isEmpty()) { summary.setText("Choose at least one source."); return; }
        var q = new SearchService.Query(text, g == null ? null : g.id(), g == null ? null : g.displayName(), chosen(), force.isSelected());
        searchButton.setDisable(true);
        banners.getChildren().clear();
        ctx.run("Searching", () -> ctx.search.search(q), r -> {
            searchButton.setDisable(false);
            for (SearchService.ProviderError e : r.errors()) banner(e.message(), false);
            if (!r.fromCache().isEmpty()) banner("From the local cache (" + String.join(", ", r.fromCache()) + "). Tick Ignore cache to fetch fresh results.", true);
            show(r.threads());
            summary.setText(r.threads().size() + " result(s)" + (g == null ? "" : " for " + g.displayName()));
            ctx.reloadCatalog();
        }, e -> searchButton.setDisable(false));
    }

    private void searchSaved() {
        String text = (gear.getValue() == null ? "" : gear.getValue().displayName() + " ") + (extra.getText() == null ? "" : extra.getText());
        if (text.isBlank()) { summary.setText("Type words to search the stored threads."); return; }
        banners.getChildren().clear();
        ctx.run(null, () -> ctx.search.searchSaved(text), list -> {
            show(list);
            summary.setText(list.size() + " stored thread(s) match \"" + text.strip() + "\"");
        });
    }

    private void show(List<ForumThread> list) {
        ctx.run(null, () -> ctx.threads.linkedGearNames(list.stream().map(ForumThread::id).toList()), names -> {
            linked.clear();
            linked.putAll(names);
            results.getItems().setAll(list);
            results.refresh();
        });
    }

    private void banner(String text, boolean info) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("banner");
        if (info) l.getStyleClass().add("banner-info");
        l.setMaxWidth(Double.MAX_VALUE);
        banners.getChildren().add(l);
    }

    private ContextMenu contextMenu() {
        MenuItem open = new MenuItem("Open in browser");
        open.setOnAction(e -> { ForumThread t = results.getSelectionModel().getSelectedItem(); if (t != null) ctx.openUrl(t.url()); });
        MenuItem copy = new MenuItem("Copy link");
        copy.setOnAction(e -> {
            ForumThread t = results.getSelectionModel().getSelectedItem();
            if (t == null) return;
            ClipboardContent c = new ClipboardContent();
            c.putString(t.url());
            Clipboard.getSystemClipboard().setContent(c);
        });
        MenuItem link = new MenuItem("Link to gear…");
        link.setOnAction(e -> {
            ForumThread t = results.getSelectionModel().getSelectedItem();
            if (t == null) return;
            new PickGearDialog("Link thread", "Which catalog item is this thread about?", () -> ctx.catalog).showAndWait()
                    .ifPresent(g -> ctx.run(null, () -> { ctx.threads.link(t.id(), g.id(), LinkKind.MANUAL); return null; },
                            x -> { show(List.copyOf(results.getItems())); ctx.reloadCatalog(); }));
        });
        MenuItem unlink = new MenuItem("Not about the selected gear");
        unlink.setOnAction(e -> {
            ForumThread t = results.getSelectionModel().getSelectedItem();
            GearSummary g = gear.getValue();
            if (t == null || g == null) return;
            ctx.run(null, () -> { ctx.threads.link(t.id(), g.id(), LinkKind.REJECTED); return null; },
                    x -> { results.getItems().remove(t); ctx.reloadCatalog(); });
        });
        MenuItem catalog = new MenuItem("Show linked gear in catalog");
        catalog.setOnAction(e -> { if (gear.getValue() != null) nav.catalog(gear.getValue().id()); });
        return new ContextMenu(open, copy, new SeparatorMenuItem(), link, unlink, catalog);
    }
}
