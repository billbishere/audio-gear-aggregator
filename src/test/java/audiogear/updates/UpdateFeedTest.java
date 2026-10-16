package audiogear.updates;

import audiogear.net.Http;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpdateFeedTest {

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsManifest() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/manifest.json", """
                    { "generated": "2026-10-17T06:12:00Z",
                      "latestApp": { "version": "1.2.0", "url": "https://example.test/v1.2.0" },
                      "excludeSources": ["Rtings"],
                      "files": [ { "name": "feeds/2026-10-17.json", "kind": "feed", "sha256": "ab" } ],
                      "extra": 1 }""");
            Manifest m = new UpdateFeed(new Http(), stub.base() + "/").manifest();
            assertEquals("1.2.0", m.latestApp().version());
            assertEquals(List.of("Rtings"), m.excludeSources());
            assertEquals(List.of(new Manifest.FileEntry("feeds/2026-10-17.json", "feed", "ab")), m.files());
        }
    }

    @Test
    void missingListsBecomeEmpty() {
        Manifest m = Manifest.parse("{ \"generated\": \"x\" }");
        assertEquals(List.of(), m.files());
        assertEquals(List.of(), m.excludeSources());
    }

    @Test
    void downloadVerifiesChecksum() throws Exception {
        try (StubServer stub = new StubServer()) {
            String body = "{ \"title\": \"Daily feed\" }";
            stub.on("/feeds/2026-10-17.json", body);
            String got = new UpdateFeed(new Http(), stub.base() + "/")
                    .download(new Manifest.FileEntry("feeds/2026-10-17.json", "feed", sha256(body)));
            assertEquals(body, got);
        }
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/feeds/2026-10-17.json", "tampered");
            var e = assertThrows(java.io.IOException.class, () -> new UpdateFeed(new Http(), stub.base() + "/")
                    .download(new Manifest.FileEntry("feeds/2026-10-17.json", "feed", sha256("original"))));
            assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
        }
    }

    @Test
    void missingFileThrowsHttpStatus() throws Exception {
        try (StubServer stub = new StubServer()) {
            var e = assertThrows(Http.HttpStatusException.class, () -> new UpdateFeed(new Http(), stub.base() + "/")
                    .download(new Manifest.FileEntry("feeds/gone.json", "feed", "00")));
            assertEquals(404, e.status());
        }
    }
}
