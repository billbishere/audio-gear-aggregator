package audiogear.updates;

import audiogear.db.AppliedUpdateRepository;
import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.LinkKind;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import audiogear.search.UrlNormalizer;
import audiogear.net.Http;
import audiogear.testing.AutoEqStub;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {

    @TempDir Path dir;
    Database db;
    Path backups;

    static final String RESEARCH = """
            { "title": "research", "gathered": "2026-10-15", "items": [
              { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 600" },
                "threads": [ { "url": "https://www.head-fi.org/threads/hd-600.1/", "title": "HD 600 thread" } ] } ] }""";
    static final String FEED = """
            { "title": "Daily feed 2026-10-17", "gathered": "2026-10-17", "window": { "from": "2026-10-16", "to": "2026-10-17" },
              "items": [ { "gear": { "type": "IEM", "brand": "Truthear", "model": "Pure" },
                "threads": [ { "url": "https://www.reddit.com/r/iems/comments/x1/pure/", "title": "Truthear Pure", "posted": "2026-10-17" } ] } ] }""";

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("catalog.db"), dir.resolve("schema-backups"));
        backups = dir.resolve("backups");
    }

    static String file(String name, String kind, String sha) {
        return "{ \"name\": \"" + name + "\", \"kind\": \"" + kind + "\", \"sha256\": \"" + sha + "\" }";
    }

    static void manifest(StubServer stub, String latestVersion, List<String> exclude, String... files) {
        String ex = String.join(",", exclude.stream().map(s -> "\"" + s + "\"").toList());
        stub.on("/data/manifest.json", "{ \"generated\": \"2026-10-17T06:00:00Z\", \"latestApp\": { \"version\": \"" + latestVersion
                + "\", \"url\": \"https://example.test/r\" }, \"excludeSources\": [" + ex + "], \"files\": [" + String.join(",", files) + "] }");
    }

    Updater updater(StubServer stub, AutoEqClient autoEq, String version) {
        AutoEqImporter importer = autoEq == null ? null
                : new AutoEqImporter(autoEq, new GearRepository(db), new CurveRepository(db), BrandParser.standard());
        return new Updater(db, backups, new UpdateFeed(new Http(), stub.base() + "/data/"), autoEq, importer, version);
    }

    static List<Path> updateBackups(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("catalog-update-")).sorted().toList();
        }
    }

    @Test
    void appliesPendingFilesOnceAndRecordsThem() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/data/research/r.json", RESEARCH).on("/data/feeds/2026-10-17.json", FEED);
            manifest(stub, "1.0.0", List.of(), file("research/r.json", "research", UpdateFeedTest.sha256(RESEARCH)),
                    file("feeds/2026-10-17.json", "feed", UpdateFeedTest.sha256(FEED)));

            UpdateResult first = updater(stub, null, "1.1.0").run(Progress.NONE);
            UpdateResult second = updater(stub, null, "1.1.0").run(Progress.NONE);

            assertNull(first.skippedReason());
            assertEquals(2, first.files());
            assertEquals(2, first.threads());
            assertEquals("Updated: +2 threads", first.summary());
            assertEquals(0, second.files());
            assertEquals("Up to date", second.summary());
            assertEquals(2, new GearRepository(db).count());
        }
    }

    @Test
    void skipsFileWithBadChecksumAndRetriesLater() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/data/feeds/2026-10-17.json", FEED);
            manifest(stub, "1.0.0", List.of(), file("feeds/2026-10-17.json", "feed", UpdateFeedTest.sha256("something else")));

            UpdateResult r = updater(stub, null, "1.1.0").run(Progress.NONE);

            assertEquals(0, r.files());
            assertTrue(r.problems().stream().anyMatch(p -> p.contains("checksum")), r.problems().toString());
            assertFalse(new AppliedUpdateRepository(db).names().contains("feeds/2026-10-17.json"));
        }
    }

    @Test
    void missingFileIsSkippedOthersApplied() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/data/feeds/2026-10-17.json", FEED);
            manifest(stub, "1.0.0", List.of(), file("feeds/2026-10-16.json", "feed", "00"),
                    file("feeds/2026-10-17.json", "feed", UpdateFeedTest.sha256(FEED)));

            UpdateResult r = updater(stub, null, "1.1.0").run(Progress.NONE);

            assertEquals(1, r.files());
            assertEquals(1, r.problems().size(), r.problems().toString());
            assertEquals(java.util.Set.of("feeds/2026-10-17.json"), new AppliedUpdateRepository(db).names());
        }
    }

    @Test
    void offlineReturnsSkippedWithoutChanges() throws Exception {
        Updater u = new Updater(db, backups, new UpdateFeed(new Http(), "http://127.0.0.1:1/data/"), null, null, "1.1.0");
        UpdateResult r = u.run(Progress.NONE);
        assertNotNull(r.skippedReason());
        assertEquals(List.of(), updateBackups(backups));
    }

    @Test
    void backsUpBeforeChangesAndKeepsThree() throws Exception {
        Files.createDirectories(backups);
        for (int i = 1; i <= 4; i++) Files.writeString(backups.resolve("catalog-update-20260101-00000" + i + ".db"), "old");
        Files.writeString(backups.resolve("catalog-20261015-180357.db"), "schema upgrade backup");
        try (StubServer stub = new StubServer()) {
            stub.on("/data/feeds/2026-10-17.json", FEED);
            manifest(stub, "1.0.0", List.of(), file("feeds/2026-10-17.json", "feed", UpdateFeedTest.sha256(FEED)));

            UpdateResult first = updater(stub, null, "1.1.0").run(Progress.NONE);
            UpdateResult second = updater(stub, null, "1.1.0").run(Progress.NONE);

            List<Path> kept = updateBackups(backups);
            assertEquals(3, kept.size());
            assertNotNull(first.backup());
            assertTrue(kept.contains(first.backup()));
            assertNull(second.backup()); // nothing pending, so no backup
            assertTrue(Files.exists(backups.resolve("catalog-20261015-180357.db")));
        }
    }

    @Test
    void reportsNewerVersionOnlyWhenNewer() throws Exception {
        try (StubServer stub = new StubServer()) {
            manifest(stub, "1.2.0", List.of());
            assertEquals("1.2.0", updater(stub, null, "1.1.0").run(Progress.NONE).newerVersion().version());
            assertNull(updater(stub, null, "1.2.0").run(Progress.NONE).newerVersion());
        }
    }

    @Test
    void secondConcurrentRunIsSkipped() throws Exception {
        try (StubServer stub = new StubServer();
             FileChannel ch = FileChannel.open(dir.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            manifest(stub, "1.0.0", List.of());
            var held = ch.lock();
            UpdateResult r = updater(stub, null, "1.1.0").run(Progress.NONE);
            held.release();
            assertEquals("another update is running", r.skippedReason());
        }
    }

    // --- update safety: the daily update only ever adds ---

    ResearchImporter direct() {
        return new ResearchImporter(new GearRepository(db), new ThreadRepository(db), new CurveRepository(db), null, null);
    }

    long threadId(String url) {
        return db.jdbi().withHandle(h -> h.createQuery("SELECT id FROM thread WHERE url = :u")
                .bind("u", UrlNormalizer.normalize(url)).mapTo(Long.class).one());
    }

    String linkKind(long threadId, long gearId) {
        return db.jdbi().withHandle(h -> h.createQuery("SELECT link_kind FROM thread_gear WHERE thread_id = :t AND gear_id = :g")
                .bind("t", threadId).bind("g", gearId).mapTo(String.class).one());
    }

    static final String HD600_FEED = """
            { "title": "Daily feed 2026-10-18", "gathered": "2026-10-18", "window": { "from": "2026-10-17", "to": "2026-10-18" },
              "items": [ { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 600" },
                "threads": [ { "url": "https://www.head-fi.org/threads/hd-600.1/", "title": "Changed title" },
                             { "url": "https://www.head-fi.org/threads/hd-600-new.2/", "title": "Sennheiser HD 600 new thread" } ] } ] }""";

    @Test
    void rejectedLinkAndThreadTitleSurviveUpdate() throws Exception {
        direct().importJson(new StringReader(RESEARCH), Progress.NONE);
        long gearId = new GearRepository(db).findId("Sennheiser", "HD 600", "").orElseThrow();
        long thread = threadId("https://www.head-fi.org/threads/hd-600.1/");
        new ThreadRepository(db).link(thread, gearId, LinkKind.REJECTED);
        try (StubServer stub = new StubServer()) {
            stub.on("/data/feeds/2026-10-18.json", HD600_FEED);
            manifest(stub, "1.0.0", List.of(), file("feeds/2026-10-18.json", "feed", UpdateFeedTest.sha256(HD600_FEED)));

            UpdateResult r = updater(stub, null, "1.1.0").run(Progress.NONE);

            assertEquals(1, r.files());
            assertEquals("REJECTED", linkKind(thread, gearId));
            String title = db.jdbi().withHandle(h -> h.createQuery("SELECT title FROM thread WHERE id = :i").bind("i", thread).mapTo(String.class).one());
            assertEquals("HD 600 thread", title);
        }
    }

    @Test
    void researchAlreadyInCatalogIsRecordedNotReapplied() throws Exception {
        direct().importJson(new StringReader(RESEARCH), Progress.NONE);
        long gearId = new GearRepository(db).findId("Sennheiser", "HD 600", "").orElseThrow();
        long thread = threadId("https://www.head-fi.org/threads/hd-600.1/");
        new ThreadRepository(db).link(thread, gearId, LinkKind.REJECTED);
        try (StubServer stub = new StubServer()) {
            stub.on("/data/research/r.json", RESEARCH);
            manifest(stub, "1.0.0", List.of(), file("research/r.json", "research", UpdateFeedTest.sha256(RESEARCH)));

            UpdateResult r = updater(stub, null, "1.1.0").run(Progress.NONE);

            assertEquals(0, r.threads());
            assertTrue(new AppliedUpdateRepository(db).names().contains("research/r.json"));
            assertEquals("REJECTED", linkKind(thread, gearId));
        }
    }

    @Test
    void feedThreadsAreNotCrossLinkedByLooseNameMatching() throws Exception {
        direct().importJson(new StringReader("""
                { "items": [ { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 660S" } } ] }"""), Progress.NONE);
        String feed = """
                { "title": "f", "gathered": "2026-10-18", "items": [ { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 660S2" },
                  "threads": [ { "url": "https://www.head-fi.org/threads/hd-660s2.7/", "title": "Sennheiser HD 660S2 review" } ] } ] }""";
        try (StubServer stub = new StubServer()) {
            stub.on("/data/feeds/2026-10-18.json", feed);
            manifest(stub, "1.0.0", List.of(), file("feeds/2026-10-18.json", "feed", UpdateFeedTest.sha256(feed)));
            updater(stub, null, "1.1.0").run(Progress.NONE);
        }
        long thread = threadId("https://www.head-fi.org/threads/hd-660s2.7/");
        int links = db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM thread_gear WHERE thread_id = :t").bind("t", thread).mapTo(Integer.class).one());
        assertEquals(1, links); // only the HD 660S2, not the HD 660S
    }

    @Test
    void deletedGearStaysDeleted() throws Exception {
        try (StubServer stub = new StubServer()) {
            AutoEqStub.serve(stub, Map.of("A", List.of("data/over-ear/Sennheiser HD 600.csv", "data/in-ear/Moondrop Chu.csv")));
            manifest(stub, "1.0.0", List.of());
            AutoEqClient client = AutoEqStub.client(stub, dir.resolve("cache"));
            updater(stub, client, "1.1.0").run(Progress.NONE);
            GearRepository gear = new GearRepository(db);
            gear.delete(gear.findId("Sennheiser", "HD 600", "").orElseThrow());

            UpdateResult second = updater(stub, client, "1.1.0").run(Progress.NONE);

            assertEquals(0, second.measurements());
            assertTrue(gear.findId("Sennheiser", "HD 600", "").isEmpty());
        }
    }

    @Test
    void collidingAutoEqFilesAreStableAcrossRuns() throws Exception {
        try (StubServer stub = new StubServer()) {
            AutoEqStub.serve(stub, Map.of("A", List.of("data/in-ear/Kiwi Ears KE4.csv", "data/over-ear/Kiwi Ears KE4.csv")));
            manifest(stub, "1.0.0", List.of());
            AutoEqClient client = AutoEqStub.client(stub, dir.resolve("cache"));
            updater(stub, client, "1.1.0").run(Progress.NONE);

            UpdateResult second = updater(stub, client, "1.1.0").run(Progress.NONE);

            assertEquals(0, second.measurements());
            assertEquals("Up to date", second.summary());
        }
    }

    @Test
    void pruningNeverDeletesTheBackupJustTaken() throws Exception {
        Files.createDirectories(backups);
        for (int i = 1; i <= 3; i++) Files.writeString(backups.resolve("catalog-update-2099010" + i + "-000000.db"), "future-dated");
        Path justTaken = Files.writeString(backups.resolve("catalog-update-20261017-060000.db"), "new");
        Updater.pruneBackups(backups, "catalog-update-", 3, justTaken);
        assertTrue(Files.exists(justTaken));
        assertEquals(3, updateBackups(backups).size());
    }

    @Test
    void newDataSinceComparesTheManifestDate() throws Exception {
        try (StubServer stub = new StubServer()) {
            manifest(stub, "1.0.0", List.of()); // generated 2026-10-17T06:00:00Z
            Updater u = updater(stub, null, "1.1.0");
            assertTrue(u.newDataSince(java.time.Instant.parse("2026-10-17T00:53:00Z")));
            assertFalse(u.newDataSince(java.time.Instant.parse("2026-10-17T07:00:00Z")));
            assertTrue(u.newDataSince(null));
        }
        Updater offline = new Updater(db, backups, new UpdateFeed(new Http(), "http://127.0.0.1:1/data/"), null, null, "1.1.0");
        assertFalse(offline.newDataSince(java.time.Instant.parse("2026-10-17T00:53:00Z")));
    }

    @Test
    void importsOnlyNewAutoEqMeasurementsAndHonoursExclusions() throws Exception {
        try (StubServer stub = new StubServer()) {
            AutoEqStub.serve(stub, Map.of(
                    "A", List.of("data/over-ear/Sennheiser HD 600.csv", "data/in-ear/Moondrop Chu.csv"),
                    "B", List.of("data/over-ear/Koss Porta Pro.csv")));
            manifest(stub, "1.0.0", List.of("B"));
            AutoEqClient client = AutoEqStub.client(stub, dir.resolve("cache"));

            UpdateResult first = updater(stub, client, "1.1.0").run(Progress.NONE);
            UpdateResult second = updater(stub, client, "1.1.0").run(Progress.NONE);

            assertEquals(2, first.measurements());
            assertEquals("Updated: +2 measurements", first.summary());
            assertEquals(0, second.measurements());
            assertTrue(new GearRepository(db).findId("Koss", "Porta Pro", "").isEmpty());
        }
    }
}
