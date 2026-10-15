package audiogear.search;

import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.*;
import audiogear.net.Http;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SearchTest {

    @TempDir Path dir;

    @Test
    void normalizesThreadUrls() {
        assertEquals("https://www.reddit.com/r/headphones/comments/abc123/",
                UrlNormalizer.normalize("https://old.reddit.com/r/headphones/comments/ABC123/hd650_thoughts/?utm_source=share&context=3"));
        assertEquals("https://www.head-fi.org/threads/sennheiser-hd650.12345/",
                UrlNormalizer.normalize("https://head-fi.org/threads/sennheiser-hd650.12345/page-30#post-999"));
        assertEquals("https://www.audiosciencereview.com/forum/index.php?threads/review-of-hd-6.5-amp.4567/",
                UrlNormalizer.normalize("https://www.audiosciencereview.com/forum/index.php?threads/review-of-hd-6.5-amp.4567/page-2"));
        assertEquals("https://www.superbestaudiofriends.org/index.php?threads/hd800.99/",
                UrlNormalizer.normalize("https://superbestaudiofriends.org/index.php?threads/hd800.99/post-123"));
        assertEquals("https://example.com/a?keep=1", UrlNormalizer.normalize("http://Example.com/a?utm_medium=x&keep=1&fbclid=z#frag"));
        assertEquals(ThreadSource.ASR, UrlNormalizer.classify("https://www.audiosciencereview.com/forum/"));
        assertEquals(ThreadSource.REDDIT, UrlNormalizer.classify("https://redd.it/xyz"));
        assertEquals(ThreadSource.OTHER, UrlNormalizer.classify("https://notreddit.example/"));
    }

    @Test
    void matchesGearNames() {
        var m = new GearNameMatcher(List.of(
                new GearRepository.GearRef(1, "Sennheiser", "HD 650", ""),
                new GearRepository.GearRef(2, "Sony", "Z1R", ""),
                new GearRepository.GearRef(3, "HiFiMAN", "Arya", ""),
                new GearRepository.GearRef(4, "Beyerdynamic", "DT 770 Pro", "")));
        assertEquals(Set.of(1L), m.match("HD650 vs the new one?"));
        assertEquals(Set.of(), m.match("Z1R impressions")); // short model needs the brand
        assertEquals(Set.of(2L), m.match("Sony Z1R impressions"));
        assertEquals(Set.of(), m.match("arya")); // no digit -> needs the brand
        assertEquals(Set.of(3L, 4L), m.match("Hifiman Arya or DT-770 Pro?"));
    }

    @Test
    void buildsBraveQueries() {
        assertEquals("\"HD 650\" site:head-fi.org", BraveSearchProvider.buildQuery("\"HD 650\"", EnumSet.of(ThreadSource.HEAD_FI)));
        assertEquals("x (site:reddit.com OR site:head-fi.org OR site:audiosciencereview.com OR site:superbestaudiofriends.org)",
                BraveSearchProvider.buildQuery("x", EnumSet.allOf(ThreadSource.class)));
        assertEquals("\"Sennheiser HD 650\" warmth", SearchService.queryText(
                new SearchService.Query(" warmth ", 1L, "Sennheiser \"HD 650\"", Set.of(), false)));
    }

    @Test
    void braveProviderSendsKeyAndParsesResults() throws Exception {
        try (StubServer stub = new StubServer()) {
            String q = Http.encode("hd650 (site:reddit.com OR site:head-fi.org)");
            stub.on("/res/v1/web/search?q=" + q + "&count=20", """
                    {"web": {"results": [
                      {"title": "<strong>HD650</strong> &amp; tubes", "url": "https://www.head-fi.org/threads/hd650.1/page-2",
                       "description": "Great with <strong>OTL</strong> amps&#39; warmth", "page_age": "2026-09-01T10:00:00"},
                      {"title": "Unrelated", "url": "https://example.com/x"},
                      {"title": "HD650 thoughts", "url": "https://www.reddit.com/r/headphones/comments/zz9/hd650/"}
                    ]}}""");
            var p = new BraveSearchProvider(new Http(), stub.base(), () -> "KEY", () -> false, new RateLimiter(0));
            List<ForumThread> r = p.search("hd650", EnumSet.of(ThreadSource.REDDIT, ThreadSource.HEAD_FI));
            assertEquals(2, r.size());
            assertEquals("HD650 & tubes", r.get(0).title());
            assertEquals("Great with OTL amps' warmth", r.get(0).snippet());
            assertEquals("https://www.head-fi.org/threads/hd650.1/", r.get(0).url());
            assertEquals("2026-09-01T10:00:00Z", r.get(0).postedAt());
            assertEquals(ThreadSource.REDDIT, r.get(1).source());
            assertEquals("KEY", stub.requests.getFirst().header("X-Subscription-Token"));
        }
    }

    @Test
    void braveErrorsBecomeReadableMessages() throws Exception {
        try (StubServer stub = new StubServer()) {
            String path = "/res/v1/web/search?q=" + Http.encode("x site:head-fi.org") + "&count=20";
            var p = new BraveSearchProvider(new Http(), stub.base(), () -> "KEY", () -> false, new RateLimiter(0));
            stub.on(path, new StubServer.Response(429, "slow down", Map.of("Retry-After", "30")));
            var e = assertThrows(SearchException.class, () -> p.search("x", EnumSet.of(ThreadSource.HEAD_FI)));
            assertEquals(30L, e.retryAfterSeconds());
            stub.on(path, new StubServer.Response(401, "bad key", Map.of()));
            var e2 = assertThrows(SearchException.class, () -> p.search("x", EnumSet.of(ThreadSource.HEAD_FI)));
            assertTrue(e2.getMessage().contains("rejected"));
            var none = new BraveSearchProvider(new Http(), stub.base(), () -> " ", () -> false, new RateLimiter(0));
            assertFalse(none.isConfigured());
        }
    }

    @Test
    void redditProviderAuthenticatesOnceAndParses() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/api/v1/access_token", "{\"access_token\": \"T1\", \"expires_in\": 3600}");
            stub.on("/r/" + RedditProvider.SUBREDDITS + "/search?q=" + Http.encode("\"HD 650\"") + "&restrict_sr=1&sort=relevance&limit=25&raw_json=1", """
                    {"data": {"children": [{"data": {"title": "HD 650 worth it?", "permalink": "/r/headphones/comments/q1/hd_650/",
                      "selftext": "Thinking about it", "author": "me", "created_utc": 1790000000, "score": 42, "num_comments": 17}}]}}""");
            var p = new RedditProvider(new Http(), stub.base(), stub.base(), () -> "id", () -> "secret", () -> "tester");
            List<ForumThread> r = p.search("\"HD 650\"", EnumSet.of(ThreadSource.REDDIT));
            p.search("\"HD 650\"", EnumSet.of(ThreadSource.REDDIT));
            assertEquals(1, stub.count("/api/v1/access_token"));
            var t = r.getFirst();
            assertEquals("https://www.reddit.com/r/headphones/comments/q1/", t.url());
            assertEquals(42, t.score());
            assertEquals(17, t.commentCount());
            assertEquals(Instant.ofEpochSecond(1790000000).toString(), t.postedAt());
            var tokenReq = stub.requests.getFirst();
            assertEquals("grant_type=client_credentials", tokenReq.body());
            assertTrue(tokenReq.header("Authorization").startsWith("Basic "));
            assertEquals("windows:audiogear:1.0 (by /u/tester)", stub.requests.get(1).header("User-Agent"));
        }
    }

    /** A provider that counts calls and returns a fixed answer or failure. */
    static final class FakeProvider implements SearchProvider {
        final String id;
        final AtomicInteger calls = new AtomicInteger();
        volatile List<ForumThread> answer = List.of();
        volatile SearchException failure;
        volatile boolean configured = true;
        volatile Set<ThreadSource> lastSources;

        FakeProvider(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public boolean isConfigured() { return configured; }
        @Override public List<ForumThread> search(String q, Set<ThreadSource> s) throws SearchException {
            calls.incrementAndGet();
            lastSources = s;
            if (failure != null) throw failure;
            return answer;
        }
    }

    private static ForumThread hit(ThreadSource s, String url, String title) {
        return new ForumThread(null, s, url, title, null, null, null, null, null, null, null);
    }

    @Test
    void serviceCachesLinksRoutesAndIsolatesFailures() {
        Database db = Database.open(dir.resolve("s.db"), dir.resolve("b"));
        GearRepository gear = new GearRepository(db);
        ThreadRepository threads = new ThreadRepository(db);
        long hd650 = gear.save(GearDetail.of(Gear.create(GearType.HEADPHONE, "Sennheiser", "HD 650")));
        long dt770 = gear.save(GearDetail.of(Gear.create(GearType.HEADPHONE, "Beyerdynamic", "DT 770")));

        FakeProvider web = new FakeProvider("web"), reddit = new FakeProvider("reddit");
        web.answer = List.of(hit(ThreadSource.HEAD_FI, "https://www.head-fi.org/threads/a.1/", "HD650 vs DT770"),
                hit(ThreadSource.ASR, "https://www.audiosciencereview.com/forum/index.php?threads/b.2/", "Amp review"));
        reddit.answer = List.of(hit(ThreadSource.REDDIT, "https://www.reddit.com/r/headphones/comments/c/", "Which one?"));
        Instant[] now = {Instant.parse("2026-10-15T12:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        SearchService service = new SearchService(web, reddit, threads, gear, () -> 7, clock);
        var q = new SearchService.Query("", hd650, "Sennheiser HD 650", EnumSet.allOf(ThreadSource.class), false);

        var r1 = service.search(q);
        assertEquals(3, r1.threads().size());
        assertEquals(EnumSet.of(ThreadSource.HEAD_FI, ThreadSource.ASR, ThreadSource.SBAF), web.lastSources);
        assertEquals(EnumSet.of(ThreadSource.REDDIT), reddit.lastSources);
        assertEquals(3, threads.forGear(hd650).size());          // every hit of a gear query links to it
        assertEquals(1, threads.forGear(dt770).size());          // name matcher found DT770 in a title
        assertTrue(r1.errors().isEmpty());

        // The cache answers the same query without calling providers (cached search runs on the real clock)
        var r2 = service.search(q);
        assertEquals(1, web.calls.get());
        assertEquals(2, r2.fromCache().size());
        assertEquals(3, r2.threads().size());

        // A failing provider doesn't hide the other's results; a rate limit starts a cool-down
        web.failure = new SearchException("web: rate limited", 120L, null);
        var forced = new SearchService.Query("", hd650, "Sennheiser HD 650", EnumSet.allOf(ThreadSource.class), true);
        var r3 = service.search(forced);
        assertEquals(1, r3.errors().size());
        assertEquals(1, r3.threads().size());
        var r4 = service.search(forced);
        assertTrue(r4.errors().getFirst().message().contains("try again in"));
        assertEquals(2, web.calls.get()); // cool-down skipped the call

        // Unconfigured Reddit falls back to the web provider for Reddit
        reddit.configured = false;
        now[0] = now[0].plus(Duration.ofHours(1));
        web.failure = null;
        service.search(forced);
        assertTrue(web.lastSources.contains(ThreadSource.REDDIT));

        assertEquals(1, service.searchSaved("amp review").size());
    }

    @Test
    void interleavesProviderResults() {
        assertEquals(List.of(1L, 10L, 2L, 3L), SearchService.interleave(List.of(List.of(1L, 2L, 3L), List.of(10L, 1L))));
    }
}
