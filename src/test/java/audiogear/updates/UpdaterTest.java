package audiogear.updates;

import audiogear.db.AppliedUpdateRepository;
import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.Progress;
import audiogear.net.Http;
import audiogear.testing.AutoEqStub;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
