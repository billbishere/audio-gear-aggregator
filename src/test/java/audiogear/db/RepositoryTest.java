package audiogear.db;

import audiogear.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {

    @TempDir Path dir;
    Database db;
    GearRepository gear;
    CurveRepository curves;
    WishlistRepository wishlist;
    ThreadRepository threads;
    CableRepository cables;

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("catalog.db"), dir.resolve("backups"));
        gear = new GearRepository(db);
        curves = new CurveRepository(db);
        wishlist = new WishlistRepository(db);
        threads = new ThreadRepository(db);
        cables = new CableRepository(db);
    }

    private long headphone(String brand, String model) {
        Gear g = Gear.create(GearType.HEADPHONE, brand, model);
        TransducerSpec t = new TransducerSpec(null, Form.OVER_EAR, Back.OPEN, DriverType.DYNAMIC, "1DD", 40.0,
                300.0, 97.0, SensitivityUnit.DB_PER_MW, 260.0, "2-pin");
        return gear.save(new GearDetail(g, t, null, List.of(), null, List.of("reference", "home")));
    }

    @Test
    void migrationIsIdempotentAndReopeningTakesNoBackup() throws Exception {
        headphone("Sennheiser", "HD 600");
        Database again = Database.open(dir.resolve("catalog.db"), dir.resolve("backups"));
        assertEquals(1, new GearRepository(again).count());
        assertFalse(Files.exists(dir.resolve("backups")));
        Path b = again.backupNow(dir.resolve("backups"));
        assertTrue(Files.size(b) > 0);
    }

    @Test
    void upgradingFromV1KeepsThreadsLinksAndSearchAndAllowsYouTube() throws Exception {
        Path file = dir.resolve("old.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var st = c.createStatement()) { st.execute("PRAGMA foreign_keys = ON"); }
            Migrator.migrate(c, 1);
            try (var st = c.createStatement()) {
                st.execute("INSERT INTO gear (id, type, brand, model, created_at, updated_at) VALUES (1, 'HEADPHONE', 'Sennheiser', 'HD 600', 'x', 'x')");
                st.execute("INSERT INTO thread (id, source, url, title, first_seen_at, last_seen_at) VALUES (7, 'HEAD_FI', 'https://www.head-fi.org/threads/a.1/', 'HD600 warm tubes', 'x', 'x')");
                st.execute("INSERT INTO thread (id, source, url, title, first_seen_at, last_seen_at) VALUES (8, 'OTHER', 'https://www.youtube.com/watch?v=abcdefgh', 'HD600 video', 'x', 'x')");
                st.execute("INSERT INTO thread_gear (thread_id, gear_id, link_kind) VALUES (7, 1, 'MANUAL')");
            }
        }
        Database upgraded = Database.open(file, dir.resolve("upgrade-backups"));
        ThreadRepository t = new ThreadRepository(upgraded);
        assertEquals(1, t.forGear(1).size()); // the link to thread 7 survived the table rebuild
        assertEquals(1, t.search("tubes", 10).size());
        assertEquals(ThreadSource.YOUTUBE, t.byIds(List.of(8L)).getFirst().source());
        long y = t.upsert(new ForumThread(null, ThreadSource.YOUTUBE, "https://www.youtube.com/watch?v=zzzzzzzz", "Review video",
                null, null, null, null, null, null, null));
        t.link(y, 1, LinkKind.MANUAL);
        assertEquals(2, t.forGear(1).size());
        assertEquals(1, t.search("review video", 10).size());
        assertTrue(Files.list(dir.resolve("upgrade-backups")).findAny().isPresent());
        new GearRepository(upgraded).delete(1); // cascades still work after the rebuild
        assertTrue(t.forGear(1).isEmpty());
    }

    @Test
    void splitsTriggersAsSingleStatements() {
        List<String> parts = Migrator.split("CREATE TABLE a (x);\nCREATE TRIGGER t AFTER INSERT ON a BEGIN\n  SELECT 1;\n  SELECT 2;\nEND;\n-- c\nCREATE TABLE b (y);");
        assertEquals(3, parts.size());
        assertTrue(parts.get(1).endsWith("END;"));
    }

    @Test
    void savesAndLoadsFullDetail() {
        long id = headphone("Sennheiser", "HD 600");
        GearDetail d = gear.get(id).orElseThrow();
        assertEquals("HD 600", d.gear().model());
        assertEquals(300.0, d.transducer().impedanceOhm());
        assertEquals(SensitivityUnit.DB_PER_MW, d.transducer().sensitivityUnit());
        assertEquals(List.of("home", "reference"), d.tags());
        assertNull(d.amp());

        GearSummary s = gear.summary(id).orElseThrow();
        assertEquals("home, reference", s.tags());
        assertEquals(Form.OVER_EAR, s.form());
        assertEquals(0, s.curveCount());

        // case-insensitive identity lookup
        assertEquals(id, gear.findId("sennheiser", "hd 600", null).orElseThrow());
    }

    @Test
    void ampDacKeepsBothSpecsAndPowers() {
        Gear g = Gear.create(GearType.AMP_DAC, "Topping", "DX5");
        AmpSpec a = new AmpSpec(null, 0.1, 7.0, 500.0, "low/high", "solid state", "6.35, 4.4");
        DacSpec dac = new DacSpec(null, "ES9068AS", 120.0, 4.0, 768.0, true, "USB, optical", "RCA, XLR");
        List<AmpPower> p = List.of(new AmpPower(null, null, AmpOutput.SE, 32, 1800), new AmpPower(null, null, AmpOutput.BALANCED, 32, 4000));
        long id = gear.save(new GearDetail(g, null, a, p, dac, List.of()));
        GearDetail d = gear.get(id).orElseThrow();
        assertEquals(2, d.powers().size());
        assertTrue(d.dac().dsd());
        assertEquals(0.1, gear.summary(id).orElseThrow().outputImpedanceOhm());

        // Changing the type to DAC drops the amp parts
        gear.save(new GearDetail(new Gear(id, GearType.DAC, "Topping", "DX5", "", null, null, null, GearStatus.NONE, null, null, null),
                null, null, List.of(), dac, List.of()));
        GearDetail after = gear.get(id).orElseThrow();
        assertNull(after.amp());
        assertTrue(after.powers().isEmpty());
    }

    @Test
    void curvesRoundTripAndReplaceSameIdentity() {
        long id = headphone("HiFiMAN", "Edition XS");
        double[] f = {20, 1000, 20000};
        Curve c = new Curve(null, id, CurveKind.FR, "oratory1990", "Edition XS", Channel.AVG, f, new double[]{-2, 0, -5.5}, null, "x", null);
        curves.save(c);
        curves.save(new Curve(null, id, CurveKind.FR, "oratory1990", "Edition XS", Channel.AVG, f, new double[]{-1, 0, -5}, null, "x", null));
        List<CurveInfo> infos = curves.forGear(id);
        assertEquals(1, infos.size());
        Curve loaded = curves.load(infos.getFirst().id()).orElseThrow();
        assertArrayEquals(new double[]{-1, 0, -5}, loaded.value(), 1e-6);
        assertFalse(infos.getFirst().hasPhase());

        Curve z = new Curve(null, id, CurveKind.IMPEDANCE, "user", "Z", Channel.L, f, new double[]{18, 18.5, 19}, new double[]{1, 0, -2}, null, null);
        curves.save(z);
        Curve target = new Curve(null, null, CurveKind.TARGET, "AutoEq", "Harman over-ear 2018", Channel.AVG, f, new double[]{3, 0, -8}, null, null, null);
        curves.save(target);
        curves.save(target); // targets (no gear) also replace
        assertEquals(1, curves.byKind(CurveKind.TARGET).size());
        assertEquals(2, gear.summary(id).orElseThrow().curveCount());
        assertTrue(curves.forGear(id).stream().anyMatch(CurveInfo::hasPhase));
    }

    @Test
    void wishlistBestPriceUsesEachRetailersLatestEntry() {
        long id = headphone("Focal", "Clear MG");
        gear.setStatus(id, GearStatus.WISHLIST);
        wishlist.saveItem(new WishlistItem(id, 1, 1100.0, "USD", null));
        wishlist.addPrice(new PriceEntry(null, id, "Shop A", 900, "USD", Condition.USED, null, "2026-09-01T00:00:00Z"));
        wishlist.addPrice(new PriceEntry(null, id, "Shop A", 1200, "USD", Condition.NEW, null, "2026-10-01T00:00:00Z"));
        wishlist.addPrice(new PriceEntry(null, id, "Shop B", 1150, "USD", Condition.NEW, null, "2026-09-15T00:00:00Z"));

        WishlistRow row = wishlist.list().getFirst();
        // Shop A's old 900 is superseded by its newer 1200, so Shop B's 1150 is the best current price
        assertEquals(1150, row.bestPrice());
        assertEquals("Shop B", row.bestRetailer());
        assertEquals(50, row.gapToTarget(), 1e-9);
        assertFalse(row.belowTarget());

        wishlist.addPrice(new PriceEntry(null, id, "Shop B", 1050, "USD", Condition.B_STOCK, null, "2026-10-10T00:00:00Z"));
        assertTrue(wishlist.list().getFirst().belowTarget());
        assertEquals(1050, gear.summary(id).orElseThrow().bestPrice());
    }

    @Test
    void threadsUpsertLinkSearchAndCache() {
        long hd = headphone("Sennheiser", "HD 650");
        ForumThread t = new ForumThread(null, ThreadSource.HEAD_FI, "https://www.head-fi.org/threads/hd650.1/", "HD650 impressions",
                "Warm and smooth", null, "2026-10-01T00:00:00Z", null, null, null, null);
        long id = threads.upsert(t);
        long again = threads.upsert(new ForumThread(null, ThreadSource.HEAD_FI, t.url(), "HD650 impressions (updated)",
                null, "someone", null, null, null, null, null));
        assertEquals(id, again);

        threads.link(id, hd, LinkKind.AUTO);
        assertEquals(1, threads.forGear(hd).size());
        assertEquals("Warm and smooth", threads.forGear(hd).getFirst().snippet());
        assertEquals(1, gear.summary(hd).orElseThrow().recentThreadCount());

        threads.link(id, hd, LinkKind.REJECTED);
        threads.link(id, hd, LinkKind.AUTO); // must not resurrect
        assertTrue(threads.forGear(hd).isEmpty());

        assertEquals(1, threads.search("updated impress", 10).size()); // prefix on last word
        assertEquals(1, threads.search("\"smooth\" (warm* -", 10).size()); // syntax characters are neutralised
        assertTrue(threads.search("planar", 10).isEmpty());

        threads.saveCache("brave", "hd 650", List.of(id));
        var entry = threads.findCache("brave", "hd 650").orElseThrow();
        assertEquals(1, entry.resultCount());
        assertEquals(id, threads.cachedThreads(entry.id()).getFirst().id());
        assertTrue(Instant.parse(entry.fetchedAt()).isAfter(Instant.now().minusSeconds(60)));
    }

    @Test
    void cablesSaveAndUpdate() {
        long id = cables.save(new Cable(null, null, "4.4 mm balanced", "4.4", "2-pin", 1.2, "OCC copper", 0.3, true));
        cables.save(new Cable(id, null, "4.4 mm balanced", "4.4", "2-pin", 1.2, "OCC copper", 0.4, true));
        assertEquals(0.4, cables.list().getFirst().loopResistanceOhm());
        cables.delete(id);
        assertTrue(cables.list().isEmpty());
    }

    @Test
    void deletingGearCascadesAndRemovesUnusedTags() {
        long id = headphone("Sennheiser", "HD 600");
        gear.delete(id);
        assertEquals(0, gear.count());
        assertTrue(gear.allTags().isEmpty());
    }
}
