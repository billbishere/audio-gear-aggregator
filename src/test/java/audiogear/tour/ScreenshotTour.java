package audiogear.tour;

import atlantafx.base.theme.PrimerDark;
import audiogear.app.AppContext;
import audiogear.db.Database;
import audiogear.domain.*;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.Progress;
import audiogear.net.Http;
import audiogear.settings.AppPaths;
import audiogear.settings.Settings;
import audiogear.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Dev tool, not a test: seeds a throwaway data folder (real AutoEq imports plus clearly labelled sample specs,
 * prices and threads) and renders every screen to PNG. Usage: ScreenshotTour <dataDir> <outDir>
 */
public final class ScreenshotTour {

    private static AppContext ctx;
    private static MainWindow window;
    private static Scene scene;

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        AppPaths paths = new AppPaths(home);
        Database db = Database.open(paths.database(), paths.backups());
        Settings settings = Settings.load(paths.settings());
        seed(db, paths);

        Platform.startup(() -> {});
        fx(() -> {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            ctx = new AppContext(paths, settings, db, null);
            window = new MainWindow(ctx);
            scene = new Scene(window, 1440, 900);
            scene.getStylesheets().add(ScreenshotTour.class.getResource("/audiogear/ui/app.css").toExternalForm());
            ctx.reloadCatalog();
            return null;
        });
        idle();
        long hd650 = id(db, "Sennheiser", "HD 650"), hd600 = id(db, "Sennheiser", "HD 600"), exs = id(db, "HIFIMAN", "Edition XS");
        long tube = id(db, "Sample", "Tube OTL"), desk = id(db, "Sample", "Desktop Amp/DAC"), hd800 = id(db, "Sennheiser", "HD 800 S");

        fx(() -> { window.catalog(hd650); return null; });
        snap(out.resolve("1-catalog.png"));
        fx(() -> { window.compare(List.of(hd600, hd650, exs)); return null; });
        snap(out.resolve("2-compare.png"));
        fx(() -> { window.curves(List.of(hd600, hd650, exs)); return null; });
        snap(out.resolve("3-curves.png"));
        fx(() -> { window.pairing(hd600, tube); return null; });
        snap(out.resolve("4-pairing-tube.png"));
        fx(() -> { window.pairing(hd600, desk); return null; });
        snap(out.resolve("5-pairing-desktop.png"));
        fx(() -> { window.discover(hd650); return null; });
        snap(out.resolve("6-discover.png"));
        fx(() -> { window.wishlist(hd800); return null; });
        snap(out.resolve("7-wishlist.png"));
        fx(() -> { window.showScreen("Data & Settings"); return null; });
        snap(out.resolve("8-data.png"));
        Platform.exit();
        System.exit(0);
    }

    private static long id(Database db, String brand, String model) {
        return new audiogear.db.GearRepository(db).findId(brand, model, "").orElseThrow(() -> new IllegalStateException(brand + " " + model));
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { f.complete(c.call()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f.get();
    }

    /** Waits until no background task has run for half a second. */
    private static void idle() throws Exception {
        int quiet = 0;
        while (quiet < 5) {
            Thread.sleep(100);
            quiet = fx(() -> ctx.status.running.get()) == 0 ? quiet + 1 : 0;
        }
    }

    private static void snap(Path file) throws Exception {
        idle();
        WritableImage img = fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return scene.snapshot(null);
        });
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", file.toFile());
        System.out.println("wrote " + file);
    }

    // --- seed data -------------------------------------------------------------------------------------------

    private static void seed(Database db, AppPaths paths) throws Exception {
        var gear = new audiogear.db.GearRepository(db);
        if (gear.count() > 0) return;
        var curves = new audiogear.db.CurveRepository(db);

        // Real AutoEq measurements through the production import path
        AutoEqClient client = AutoEqClient.github(new Http(), () -> null, paths.cache());
        AutoEqImporter importer = new AutoEqImporter(client, gear, curves, BrandParser.standard());
        AutoEqClient.Source ora = client.sources().stream().filter(s -> s.name().equals("oratory1990")).findFirst().orElseThrow();
        Set<String> wanted = Set.of("sennheiser hd 600", "sennheiser hd 650", "hifiman edition xs", "sennheiser hd 800 s", "moondrop blessing 2");
        var entries = client.entries(ora).stream().filter(e -> wanted.contains(e.name().toLowerCase(Locale.ROOT))).toList();
        System.out.println("AutoEq import: " + importer.importEntries(entries, Progress.NONE).headline() + " from " + entries.size() + " entries");
        System.out.println("Targets: " + importer.importTargets().headline());

        // Sample specs (approximate, for screenshots only)
        spec(gear, "Sennheiser", "HD 600", 300, 97, SensitivityUnit.DB_PER_MW, 260, Back.OPEN, List.of("reference", "home"), 399.0);
        spec(gear, "Sennheiser", "HD 650", 300, 103, SensitivityUnit.DB_PER_V, 260, Back.OPEN, List.of("home"), 499.0);
        spec(gear, "HIFIMAN", "Edition XS", 18, 92, SensitivityUnit.DB_PER_MW, 405, Back.OPEN, List.of("planar"), 299.0);
        spec(gear, "Sennheiser", "HD 800 S", 300, 102, SensitivityUnit.DB_PER_MW, 330, Back.OPEN, List.of(), 1699.0);

        long tube = gear.save(new GearDetail(Gear.create(GearType.AMP, "Sample", "Tube OTL"), null,
                new AmpSpec(null, 100.0, null, null, "fixed", "OTL tube", "6.35 mm"),
                List.of(new AmpPower(null, null, AmpOutput.SE, 300, 120), new AmpPower(null, null, AmpOutput.SE, 32, 15)), null, List.of("sample")));
        gear.save(new GearDetail(Gear.create(GearType.AMP_DAC, "Sample", "Desktop Amp/DAC"), null,
                new AmpSpec(null, 0.1, 7.0, 600.0, "low / high", "solid state", "6.35 mm, 4.4 mm"),
                List.of(new AmpPower(null, null, AmpOutput.SE, 32, 1500), new AmpPower(null, null, AmpOutput.BALANCED, 32, 4000)),
                new DacSpec(null, "Sample DAC chip", 118.0, 4.0, 768.0, true, "USB, optical", "RCA, XLR"), List.of("sample", "desk")));

        // Synthetic dynamic-driver impedance curve: 300 ohm with a bass resonance peak near 90 Hz
        long hd600 = gear.findId("Sennheiser", "HD 600", "").orElseThrow();
        double[] f = audiogear.analysis.CurveMath.logGrid(10, 20000, 24), z = new double[f.length], ph = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            double x = Math.log(f[i] / 90) / 0.45;
            z[i] = 300 + 180 * Math.exp(-x * x) + 12 * Math.log10(Math.max(1, f[i] / 2000));
            ph[i] = -35 * x * Math.exp(-x * x);
        }
        curves.save(new Curve(null, hd600, CurveKind.IMPEDANCE, "synthetic demo", "impedance", Channel.AVG, f, z, ph, null, null));

        // Wishlist with sample prices
        long hd800 = gear.findId("Sennheiser", "HD 800 S", "").orElseThrow();
        gear.setStatus(hd800, GearStatus.WISHLIST);
        var wish = new audiogear.db.WishlistRepository(db);
        wish.saveItem(new WishlistItem(hd800, 1, 1300.0, "USD", "Wait for a B-stock deal"));
        Instant now = Instant.now();
        wish.addPrice(new PriceEntry(null, hd800, "Shop A", 1699, "USD", Condition.NEW, null, now.minus(60, ChronoUnit.DAYS).toString()));
        wish.addPrice(new PriceEntry(null, hd800, "Shop A", 1499, "USD", Condition.NEW, null, now.minus(20, ChronoUnit.DAYS).toString()));
        wish.addPrice(new PriceEntry(null, hd800, "Shop B", 1279, "USD", Condition.B_STOCK, null, now.minus(3, ChronoUnit.DAYS).toString()));
        long exs = gear.findId("HIFIMAN", "Edition XS", "").orElseThrow();
        gear.setStatus(exs, GearStatus.WISHLIST);
        wish.saveItem(new WishlistItem(exs, 2, 199.0, "USD", null));
        wish.addPrice(new PriceEntry(null, exs, "Shop C", 249, "USD", Condition.NEW, null, now.minus(5, ChronoUnit.DAYS).toString()));
        gear.setStatus(hd600, GearStatus.OWNED);

        // Sample threads, clearly marked, linked to HD 650
        var threads = new audiogear.db.ThreadRepository(db);
        long hd650 = gear.findId("Sennheiser", "HD 650", "").orElseThrow();
        String[][] samples = {
                {"REDDIT", "https://www.reddit.com/r/headphones/comments/sample1/", "[sample] HD 650 vs HD 600 for a first open-back?", "Placeholder snippet for the screenshot tour.", "128", "64"},
                {"HEAD_FI", "https://www.head-fi.org/threads/sample-hd650-thread.1/", "[sample] Sennheiser HD 650 impressions thread", "Placeholder snippet for the screenshot tour.", null, null},
                {"ASR", "https://www.audiosciencereview.com/forum/index.php?threads/sample.2/", "[sample] Sennheiser HD 650 measurements", "Placeholder snippet for the screenshot tour.", null, null}};
        int day = 3;
        for (String[] s : samples) {
            long t = threads.upsert(new ForumThread(null, ThreadSource.valueOf(s[0]), s[1], s[2], s[3], null,
                    now.minus(day, ChronoUnit.DAYS).toString(), s[4] == null ? null : Integer.parseInt(s[4]), s[5] == null ? null : Integer.parseInt(s[5]), null, null));
            threads.link(t, hd650, LinkKind.AUTO);
            day += 40;
        }
    }

    private static void spec(audiogear.db.GearRepository gear, String brand, String model, double z, double sens, SensitivityUnit unit,
                             double weight, Back back, List<String> tags, Double msrp) {
        long id = gear.findId(brand, model, "").orElseThrow(() -> new IllegalStateException("not imported: " + brand + " " + model));
        GearDetail d = gear.get(id).orElseThrow();
        Gear g = d.gear();
        Gear g2 = new Gear(g.id(), g.type(), g.brand(), g.model(), g.variant(), g.releaseYear(), msrp, "USD", g.status(), g.notes(), g.createdAt(), g.updatedAt());
        TransducerSpec t = d.transducer();
        TransducerSpec t2 = new TransducerSpec(id, t.form(), back, DriverType.DYNAMIC, model.contains("XS") ? "planar" : "1DD",
                null, z, sens, unit, weight, null);
        if (model.contains("XS")) t2 = new TransducerSpec(id, t.form(), back, DriverType.PLANAR, null, null, z, sens, unit, weight, null);
        gear.save(new GearDetail(g2, t2, null, List.of(), null, tags));
    }
}
