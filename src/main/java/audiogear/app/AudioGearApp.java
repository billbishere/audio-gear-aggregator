package audiogear.app;

import atlantafx.base.theme.PrimerDark;
import audiogear.db.Database;
import audiogear.settings.AppPaths;
import audiogear.settings.Settings;
import audiogear.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AudioGearApp extends Application {

    private static final Logger LOG = Logger.getLogger(AudioGearApp.class.getName());

    private AppContext ctx;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        AppPaths paths = AppPaths.standard();
        Logging.init(paths.logs());
        LOG.info("Starting; data folder " + paths.home());

        Database db;
        try {
            db = Database.open(paths.database(), paths.backups());
        } catch (Database.MigrationException e) {
            LOG.log(Level.SEVERE, "Database could not be opened", e);
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setHeaderText("The catalog database could not be opened");
            a.setContentText(e.getMessage() + (e.backup() == null ? "" : "\n\nA backup taken just before was saved to:\n" + e.backup()));
            a.showAndWait();
            Platform.exit();
            return;
        }

        ctx = new AppContext(paths, Settings.load(paths.settings()), db, getHostServices());
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOG.log(Level.SEVERE, "Uncaught exception on " + t.getName(), e);
            Platform.runLater(() -> ctx.status.fail("Unexpected error: " + AppContext.message(e)));
        });

        MainWindow window = new MainWindow(ctx);
        Scene scene = new Scene(window, 1440, 900);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/audiogear/ui/app.css")).toExternalForm());
        stage.setTitle("Audio Gear Aggregator");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();
        ctx.reloadCatalog();
    }

    @Override
    public void stop() {
        if (ctx != null) ctx.shutdown();
    }
}
