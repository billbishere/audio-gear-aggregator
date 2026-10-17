package audiogear.app;

import audiogear.db.CableRepository;
import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.db.WishlistRepository;
import audiogear.domain.GearSummary;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import audiogear.net.Http;
import audiogear.search.BraveSearchProvider;
import audiogear.search.RedditProvider;
import audiogear.search.SearchService;
import audiogear.settings.AppPaths;
import audiogear.settings.Settings;
import audiogear.updates.AppVersion;
import audiogear.updates.Manifest;
import audiogear.updates.UpdateFeed;
import audiogear.updates.UpdateSchedule;
import audiogear.updates.Updater;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Wires the app together and runs work off the FX thread. */
public final class AppContext {

    private static final Logger LOG = Logger.getLogger(AppContext.class.getName());

    public final AppPaths paths;
    public final Settings settings;
    public final Database db;
    public final GearRepository gear;
    public final CurveRepository curves;
    public final WishlistRepository wishlist;
    public final ThreadRepository threads;
    public final CableRepository cables;
    public final SearchService search;
    public final AutoEqClient autoEq;
    public final AutoEqImporter autoEqImporter;
    public final ResearchImporter research;
    public final Updater updater;
    public final StatusModel status = new StatusModel();

    /** The whole catalog as summary rows, shared by the catalog table and every gear picker. */
    public final ObservableList<GearSummary> catalog = FXCollections.observableArrayList();

    private final HostServices host;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "worker");
        t.setDaemon(true);
        return t;
    });
    private final List<Runnable> catalogListeners = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "update-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean updating = new AtomicBoolean();

    public AppContext(AppPaths paths, Settings settings, Database db, HostServices host) {
        this.paths = paths;
        this.settings = settings;
        this.db = db;
        this.host = host;
        this.gear = new GearRepository(db);
        this.curves = new CurveRepository(db);
        this.wishlist = new WishlistRepository(db);
        this.threads = new ThreadRepository(db);
        this.cables = new CableRepository(db);
        Http http = new Http();
        this.search = new SearchService(
                BraveSearchProvider.create(http, settings::braveApiKey, settings::bravePerSiteQueries),
                RedditProvider.create(http, settings::redditClientId, settings::redditClientSecret, settings::redditUsername),
                threads, gear, settings::cacheTtlDays, Clock.systemUTC());
        this.autoEq = AutoEqClient.github(http, settings::githubToken, paths.cache());
        this.autoEqImporter = new AutoEqImporter(autoEq, gear, curves, BrandParser.standard());
        this.research = new ResearchImporter(gear, threads, curves, autoEq, autoEqImporter);
        this.updater = new Updater(db, paths.backups(), new UpdateFeed(http, settings.updatesBaseUrl()), autoEq, autoEqImporter,
                AppVersion.current());
    }

    /** Runs work in the background; done runs on the FX thread. Failures go to the status bar and the log. */
    public <T> void run(String what, Callable<T> work, Consumer<T> done) {
        run(what, work, done, null);
    }

    public <T> void run(String what, Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        status.running.set(status.running.get() + 1);
        if (what != null) status.info(what + "…");
        executor.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> {
                    finish();
                    if (what != null && !status.error.get()) status.info("Ready");
                    if (done != null) done.accept(result);
                });
            } catch (Throwable e) {
                LOG.log(Level.WARNING, what == null ? "Background task failed" : what + " failed", e);
                Platform.runLater(() -> {
                    finish();
                    status.fail((what == null ? "Error" : what + " failed") + ": " + message(e));
                    if (failed != null) failed.accept(e);
                });
            }
        });
    }

    /** A long task with a progress bar and a Cancel button in the status bar. */
    public <T> void runWithProgress(String what, Function<Progress, T> work, Consumer<T> done) {
        AtomicBoolean cancelled = new AtomicBoolean();
        status.cancel.set(() -> cancelled.set(true));
        status.progress.set(-1);
        Progress progress = new Progress() {
            @Override public void update(long d, long total, String message) {
                Platform.runLater(() -> {
                    status.progress.set(total <= 0 ? -1 : (double) d / total);
                    status.info(what + ": " + d + " / " + total + (message == null ? "" : " — " + message));
                });
            }
            @Override public boolean isCancelled() { return cancelled.get(); }
        };
        run(what, () -> work.apply(progress), r -> {
            status.cancel.set(null);
            status.progress.set(-1);
            done.accept(r);
        }, e -> {
            status.cancel.set(null);
            status.progress.set(-1);
        });
    }

    private void finish() {
        status.running.set(Math.max(0, status.running.get() - 1));
    }

    public static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** Reloads catalog summaries in the background, then notifies listeners. */
    public void reloadCatalog() {
        run(null, gear::listSummaries, rows -> {
            catalog.setAll(rows);
            for (Runnable r : List.copyOf(catalogListeners)) r.run();
        });
    }

    public void onCatalogReloaded(Runnable r) { catalogListeners.add(r); }

    public void openUrl(String url) {
        if (url != null && !url.isBlank()) host.showDocument(url);
    }

    /** Checks hourly and runs the update when it is due or new data was published (unless daily updates are off). */
    public void startDailyUpdates() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (!settings.updatesDaily()) return;
            Instant last = settings.updatesLastRun();
            // Daily, and also as soon as new data is published (checked hourly with one small request)
            if (UpdateSchedule.isDue(last, Instant.now()) || updater.newDataSince(last)) Platform.runLater(this::updateNow);
        }, 10, UpdateSchedule.CHECK_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    /** Runs one update now, unless one is already running in this window. Call on the FX thread. */
    public void updateNow() {
        if (!updating.compareAndSet(false, true)) return;
        runWithProgress("Updating catalog", p -> {
            try {
                return updater.run(p);
            } finally {
                updating.set(false);
            }
        }, result -> {
            result.problems().forEach(problem -> LOG.warning("Update: " + problem));
            if (result.skippedReason() == null) {
                settings.setUpdatesLastRun(Instant.now());
                run(null, () -> { settings.save(); return null; }, x -> {});
            }
            status.info(result.summary());
            if (result.changedCatalog()) reloadCatalog();
            Manifest.LatestApp v = result.newerVersion();
            if (v != null && !v.version().equals(settings.updatesDismissedVersion())) status.newVersion.set(v);
        });
    }

    /** Stops announcing the release shown in the status bar. */
    public void dismissNewVersion() {
        Manifest.LatestApp v = status.newVersion.get();
        if (v == null) return;
        settings.setUpdatesDismissedVersion(v.version());
        status.newVersion.set(null);
        run(null, () -> { settings.save(); return null; }, x -> {});
    }

    public void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }
}
