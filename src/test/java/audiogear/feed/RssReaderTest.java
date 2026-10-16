package audiogear.feed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RssReaderTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = RssReaderTest.class.getResourceAsStream("/audiogear/feed/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesXenForoRss() throws Exception {
        List<FeedItem> items = RssReader.parse(fixture("xenforo.rss"));
        assertEquals(2, items.size());
        assertEquals(new FeedItem("https://forum.example.test/threads/hd-600-vs-hd-650.101/",
                "Sennheiser HD 600 vs HD 650 for a first open-back", "2026-10-16"), items.getFirst());
    }

    @Test
    void parsesRedditAtom() throws Exception {
        assertEquals(List.of(new FeedItem("https://www.reddit.com/r/headphones/comments/abc123/hifiman_edition_xs/",
                "HIFIMAN Edition XS long-term impressions", "2026-10-15")), RssReader.parse(fixture("reddit.atom")));
    }

    @Test
    void parsesYouTubeAtomPreferringPublished() throws Exception {
        assertEquals(List.of(new FeedItem("https://www.youtube.com/watch?v=abcdefghijk",
                "Truthear Pure review: tuning and fit", "2026-10-14")), RssReader.parse(fixture("youtube.atom")));
    }

    @Test
    void itemWithoutDateHasNullPublished() throws Exception {
        assertNull(RssReader.parse(fixture("xenforo.rss")).get(1).published());
    }

    @Test
    void rejectsDoctype() {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY e SYSTEM "file:///c:/windows/win.ini">]>
                <rss version="2.0"><channel><item><title>&e;</title><link>https://x.test/</link></item></channel></rss>""";
        assertThrows(IOException.class, () -> RssReader.parse(evil));
    }
}
