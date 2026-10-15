package audiogear.ui.views;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.domain.GearDetail;
import audiogear.importers.AutoEqClient;
import audiogear.importers.CatalogCsv;
import audiogear.ui.Fx;
import audiogear.ui.View;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bulk imports (AutoEq, files, CSV), backups and settings. */
public final class DataView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final Map<AutoEqClient.Source, BooleanProperty> sourceChecks = new LinkedHashMap<>();
    private final ListView<AutoEqClient.Source> sourceList = new ListView<>();
    private final CheckBox overEar = new CheckBox("Over-ear"), inEar = new CheckBox("In-ear"), earbud = new CheckBox("Earbud");
    private final ObservableList<AutoEqClient.Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<AutoEqClient.Entry> shownEntries = new FilteredList<>(entries, e -> true);
    private final ListView<AutoEqClient.Entry> entryList = new ListView<>(shownEntries);
    private final TextField entryFilter = new TextField();
    private final Label entryCount = new Label();

    public DataView(AppContext ctx) {
        this.ctx = ctx;
        VBox body = new VBox(14, Fx.title("Data & Settings"), autoEqSection(), filesSection(), settingsSection());
        body.setPadding(new Insets(14, 16, 16, 16));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);
    }

    @Override public String title() { return "Data & Settings"; }
    @Override public Node root() { return root; }

    private Node autoEqSection() {
        Button load = new Button("Load reviewer list");
        load.getStyleClass().add(Styles.ACCENT);
        load.setOnAction(e -> ctx.run("Loading AutoEq reviewers", ctx.autoEq::sources, list -> {
            sourceChecks.clear();
            for (var s : list) sourceChecks.put(s, new SimpleBooleanProperty(s.name().equals("oratory1990") || s.name().equals("crinacle")));
            sourceList.getItems().setAll(list);
        }));
        sourceList.setCellFactory(CheckBoxListCell.forListView(s -> sourceChecks.get(s), Fx.converter(AutoEqClient.Source::name)));
        sourceList.setPrefHeight(200);
        sourceList.setPlaceholder(new Label("Press Load reviewer list (uses 2 GitHub requests)"));
        overEar.setSelected(true);
        inEar.setSelected(true);
        earbud.setSelected(true);

        Button list = new Button("List measurement files");
        list.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(sourceList.getItems()));
        list.setOnAction(e -> listEntries());
        entryFilter.setPromptText("Filter by name, e.g. Sennheiser");
        entryFilter.setPrefColumnCount(28);
        entryFilter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.toLowerCase(Locale.ROOT).strip();
            shownEntries.setPredicate(x -> q.isEmpty() || x.name().toLowerCase(Locale.ROOT).contains(q));
            updateCount();
        });
        entryList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        entryList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(AutoEqClient.Entry x, boolean empty) {
                super.updateItem(x, empty);
                setText(empty || x == null ? null : x.name() + "   ·  " + x.sourceLabel() + " · " + x.form());
            }
        });
        entryList.setPrefHeight(200);
        entryList.setPlaceholder(new Label("Choose reviewers and forms, then List measurement files"));
        Button importShown = new Button("Import all listed");
        importShown.getStyleClass().add(Styles.ACCENT);
        importShown.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(shownEntries));
        importShown.setOnAction(e -> runImport(List.copyOf(shownEntries)));
        Button importSel = new Button("Import selected");
        importSel.disableProperty().bind(entryList.getSelectionModel().selectedItemProperty().isNull());
        importSel.setOnAction(e -> runImport(List.copyOf(entryList.getSelectionModel().getSelectedItems())));
        Button targets = new Button("Import Harman targets");
        targets.setOnAction(e -> ctx.run("Importing targets", ctx.autoEqImporter::importTargets, s -> Fx.showSummary("Harman targets", s)));
        entryCount.getStyleClass().add(Styles.TEXT_MUTED);

        VBox left = new VBox(8, Fx.row(8, load), sourceList, Fx.row(12, overEar, inEar, earbud), list);
        left.setPrefWidth(320);
        VBox right = new VBox(8, Fx.row(8, entryFilter, Fx.grow(), entryCount), entryList, Fx.row(8, importShown, importSel, Fx.grow(), targets));
        HBox.setHgrow(right, Priority.ALWAYS);
        return Fx.section("AutoEq measurements",
                Fx.muted("Thousands of frequency-response measurements from oratory1990, crinacle, Rtings and others, via the AutoEq project on GitHub. "
                        + "Each file becomes an FR curve; gear is matched by brand and model so re-imports don't duplicate. "
                        + "The measurements belong to their reviewers: fine for personal use, don't redistribute them."),
                new HBox(14, left, right));
    }

    private void listEntries() {
        List<AutoEqClient.Source> chosen = sourceChecks.entrySet().stream().filter(e -> e.getValue().get()).map(Map.Entry::getKey).toList();
        if (chosen.isEmpty()) { ctx.status.info("Tick at least one reviewer"); return; }
        Set<String> forms = new java.util.HashSet<>();
        if (overEar.isSelected()) forms.add("over-ear");
        if (inEar.isSelected()) forms.add("in-ear");
        if (earbud.isSelected()) forms.add("earbud");
        ctx.run("Listing AutoEq files", () -> {
            List<AutoEqClient.Entry> out = new ArrayList<>();
            for (var s : chosen) for (var e : ctx.autoEq.entries(s)) if (forms.contains(e.form())) out.add(e);
            out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            return out;
        }, list -> {
            entries.setAll(list);
            updateCount();
        });
    }

    private void updateCount() {
        entryCount.setText(shownEntries.size() == entries.size() ? entries.size() + " files" : shownEntries.size() + " of " + entries.size() + " files");
    }

    private void runImport(List<AutoEqClient.Entry> list) {
        if (list.size() > 300 && !Fx.confirm("Import " + list.size() + " measurements?",
                "This downloads about " + (list.size() * 25 / 1000) + " MB and can take a few minutes. You can cancel from the status bar.")) return;
        ctx.runWithProgress("Importing AutoEq", p -> ctx.autoEqImporter.importEntries(list, p), s -> {
            ctx.reloadCatalog();
            Fx.showSummary("AutoEq import", s);
        });
    }

    private Node filesSection() {
        Button curve = new Button("Import curve file…");
        curve.setOnAction(e -> {
            File f = chooser("Import curve file", "Curve files", "*.csv", "*.txt", "*.tsv", "*.frd", "*.zma").showOpenDialog(window());
            if (f != null) GearDetailPane.importCurveFile(ctx, f, null, ctx::reloadCatalog);
        });
        Button csvIn = new Button("Import catalog CSV…");
        csvIn.setOnAction(e -> {
            File f = chooser("Import catalog CSV", "CSV", "*.csv").showOpenDialog(window());
            if (f == null) return;
            ctx.run("Importing " + f.getName(), () -> {
                try (var r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    return CatalogCsv.read(r, ctx.gear);
                }
            }, s -> { ctx.reloadCatalog(); Fx.showSummary("Catalog import", s); });
        });
        Button csvOut = new Button("Export catalog CSV…");
        csvOut.setOnAction(e -> {
            FileChooser fc = chooser("Export catalog", "CSV", "*.csv");
            fc.setInitialFileName("audio-gear-catalog.csv");
            File f = fc.showSaveDialog(window());
            if (f == null) return;
            ctx.run("Exporting catalog", () -> {
                List<GearDetail> all = new ArrayList<>();
                for (var ref : ctx.gear.listRefs()) ctx.gear.get(ref.id()).ifPresent(all::add);
                try (var w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
                    CatalogCsv.write(all, w);
                }
                return all.size();
            }, n -> ctx.status.info("Exported " + n + " items to " + f.getName()));
        });
        Button backup = new Button("Back up database now");
        backup.setOnAction(e -> ctx.run("Backing up", () -> ctx.db.backupNow(ctx.paths.backups()),
                p -> ctx.status.info("Backup saved: " + p)));
        Button folder = new Button("Open data folder");
        folder.getStyleClass().add(Styles.FLAT);
        folder.setOnAction(e -> ctx.openUrl(ctx.paths.home().toUri().toString()));
        return Fx.section("Files and backups",
                Fx.muted("Curve files: CSV/TSV, REW text exports, squig.link .txt (frequency, value, optional phase). "
                        + "Catalog CSV: one row per item; empty cells keep existing values. Data folder: " + ctx.paths.home()),
                Fx.row(8, curve, csvIn, csvOut, backup, folder));
    }

    private Node settingsSection() {
        var s = ctx.settings;
        PasswordField brave = new PasswordField();
        brave.setText(nz(s.braveApiKey()));
        CheckBox perSite = new CheckBox("One request per site (uses more credit)");
        perSite.setSelected(s.bravePerSiteQueries());
        TextField redditId = new TextField(nz(s.redditClientId()));
        PasswordField redditSecret = new PasswordField();
        redditSecret.setText(nz(s.redditClientSecret()));
        TextField redditUser = new TextField(nz(s.redditUsername()));
        PasswordField github = new PasswordField();
        github.setText(nz(s.githubToken()));
        TextField currency = new TextField(s.currency());
        currency.setPrefColumnCount(5);
        Spinner<Integer> ttl = new Spinner<>(0, 90, s.cacheTtlDays());
        ttl.setEditable(true);
        Spinner<Double> spl = new Spinner<>(70.0, 130.0, s.targetSplDb(), 1.0);
        spl.setEditable(true);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        int r = 0;
        g.addRow(r++, new Label("Brave Search API key"), brave, link("Get a key", "https://brave.com/search/api/"));
        g.addRow(r++, new Label(""), perSite);
        g.addRow(r++, new Label("Reddit client ID"), redditId, link("Reddit apps", "https://www.reddit.com/prefs/apps"));
        g.addRow(r++, new Label("Reddit client secret"), redditSecret);
        g.addRow(r++, new Label("Reddit username"), redditUser);
        g.addRow(r++, new Label("GitHub token (optional)"), github, link("Tokens", "https://github.com/settings/tokens"));
        g.addRow(r++, new Label("Currency"), currency);
        g.addRow(r++, new Label("Search cache (days)"), ttl);
        g.addRow(r++, new Label("Default target peak SPL"), spl);
        for (Node n : List.of(brave, redditId, redditSecret, redditUser, github)) ((Region) n).setPrefWidth(380);

        Button save = new Button("Save settings");
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> {
            s.setBraveApiKey(brave.getText());
            s.setBravePerSiteQueries(perSite.isSelected());
            s.setRedditClientId(redditId.getText());
            s.setRedditClientSecret(redditSecret.getText());
            s.setRedditUsername(redditUser.getText());
            s.setGithubToken(github.getText());
            s.setCurrency(currency.getText() == null || currency.getText().isBlank() ? "USD" : currency.getText().strip().toUpperCase());
            s.setCacheTtlDays(ttl.getValue());
            s.setTargetSplDb(spl.getValue());
            ctx.run("Saving settings", () -> { s.save(); return null; }, x -> ctx.status.info("Settings saved"));
        });
        return Fx.section("Settings",
                Fx.muted("Keys are stored only in " + ctx.paths.settings() + ". Brave covers Head-Fi, ASR, SBAF and Reddit. "
                        + "A direct Reddit connection is optional: Reddit approves new API apps individually, and until then Reddit is searched through Brave. "
                        + "A GitHub token raises the AutoEq listing limit from 60 to 5,000 requests an hour."),
                g, Fx.row(8, save));
    }

    private Hyperlink link(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setOnAction(e -> ctx.openUrl(url));
        return h;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private FileChooser chooser(String title, String desc, String... patterns) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(desc, patterns), new FileChooser.ExtensionFilter("All files", "*.*"));
        return fc;
    }

    private javafx.stage.Window window() { return root.getScene().getWindow(); }
}
