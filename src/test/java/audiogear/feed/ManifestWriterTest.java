package audiogear.feed;

import audiogear.net.Http;
import audiogear.testing.StubServer;
import audiogear.updates.Manifest;
import audiogear.updates.UpdateFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestWriterTest {

    @TempDir Path dir;
    Path data, research;
    static final Instant NOW = Instant.parse("2026-10-17T06:12:00Z");
    static final Manifest.LatestApp LATEST = new Manifest.LatestApp("1.2.0", "https://example.test/v1.2.0");

    @BeforeEach
    void files() throws Exception {
        data = Files.createDirectories(dir.resolve("data"));
        research = Files.createDirectories(dir.resolve("research"));
        Files.createDirectories(data.resolve("feeds"));
        Files.writeString(data.resolve("feeds/2026-10-17.json"), "{ \"title\": \"feed 17\" }\n");
        Files.writeString(data.resolve("feeds/2026-10-16.json"), "{ \"title\": \"feed 16\" }\n");
        Files.writeString(research.resolve("b.json"), "{ \"title\": \"b\" }\n");
        Files.writeString(research.resolve("a.json"), "{\r\n  \"title\": \"a\"\r\n}\r\n"); // as checked out on Windows
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    @Test
    void writesResearchThenFeedsWithChecksums() throws Exception {
        ManifestWriter.write(data, research, LATEST, List.of("Rtings"), NOW);
        Manifest m = Manifest.parse(Files.readString(data.resolve("manifest.json")));

        assertEquals(List.of("research/a.json", "research/b.json", "feeds/2026-10-16.json", "feeds/2026-10-17.json"),
                m.files().stream().map(Manifest.FileEntry::name).toList());
        assertEquals(List.of("research", "research", "feed", "feed"), m.files().stream().map(Manifest.FileEntry::kind).toList());
        for (Manifest.FileEntry f : m.files()) assertEquals(sha256(data.resolve(f.name())), f.sha256(), f.name());
        assertEquals(LATEST, m.latestApp());
        assertEquals(List.of("Rtings"), m.excludeSources());
        assertEquals("2026-10-17T06:12:00Z", m.generated());
    }

    @Test
    void copiesResearchFilesWithUnixLineEndings() throws Exception {
        ManifestWriter.write(data, research, LATEST, List.of(), NOW);
        assertEquals("{\n  \"title\": \"a\"\n}\n", Files.readString(data.resolve("research/a.json")));
        assertEquals("{ \"title\": \"b\" }\n", Files.readString(data.resolve("research/b.json")));
    }

    @Test
    void excludeSourcesIgnoresCommentsAndBlanks() throws Exception {
        Path f = Files.writeString(dir.resolve("exclude.txt"), "# reviewers left out\n\n  Rtings  \n#crinacle\nSuper Review\n");
        assertEquals(List.of("Rtings", "Super Review"), ManifestWriter.excludeSources(f));
        assertEquals(List.of(), ManifestWriter.excludeSources(dir.resolve("missing.txt")));
    }

    @Test
    void writtenFilesPassUpdateFeedChecksum() throws Exception {
        ManifestWriter.write(data, research, LATEST, List.of(), NOW);
        Manifest m = Manifest.parse(Files.readString(data.resolve("manifest.json")));
        try (StubServer stub = new StubServer()) {
            for (Manifest.FileEntry f : m.files()) stub.on("/data/" + f.name(), Files.readString(data.resolve(f.name())));
            UpdateFeed feed = new UpdateFeed(new Http(), stub.base() + "/data/");
            for (Manifest.FileEntry f : m.files()) assertDoesNotThrow(() -> feed.download(f), f.name());
        }
    }
}
