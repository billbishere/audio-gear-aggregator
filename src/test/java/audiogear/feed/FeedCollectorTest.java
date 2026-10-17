package audiogear.feed;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.GearType;
import audiogear.domain.LinkKind;
import audiogear.domain.ThreadSource;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import audiogear.net.Http;
import audiogear.search.UrlNormalizer;
import audiogear.testing.StubServer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FeedCollectorTest {

    @TempDir Path dir;

    static final LocalDate TODAY = LocalDate.parse("2026-10-17");
    static final List<KnownGear> GEAR = List.of(
            new KnownGear(GearType.HEADPHONE, "Sennheiser", "HD 600"),
            new KnownGear(GearType.HEADPHONE, "Sennheiser", "HD 650"),
            new KnownGear(GearType.HEADPHONE, "HIFIMAN", "Edition XS"),
            new KnownGear(GearType.IEM, "Truthear", "Pure"));

    static List<FeedSource> sources(StubServer stub) throws IOException {
        stub.on("/asr.rss", RssReaderTest.fixture("xenforo.rss"))
                .on("/reddit.atom", RssReaderTest.fixture("reddit.atom"))
                .on("/yt.atom", RssReaderTest.fixture("youtube.atom"));
        return List.of(new FeedSource("ASR", stub.base() + "/asr.rss", ThreadSource.ASR),
                new FeedSource("r/headphones", stub.base() + "/reddit.atom", ThreadSource.REDDIT),
                new FeedSource("Example Reviews", stub.base() + "/yt.atom", ThreadSource.YOUTUBE));
    }

    /** gear "Brand Model" → its thread titles */
    static Map<String, List<String>> titlesByGear(FeedFile f) {
        return f.items().stream().collect(Collectors.toMap(i -> i.gear().get("brand") + " " + i.gear().get("model"),
                i -> i.threads().stream().map(ResearchImporter.ResearchThread::title).toList()));
    }

    @Test
    void collectsMatchedThreadsGroupedByGear() throws Exception {
        try (StubServer stub = new StubServer()) {
            List<String> log = new ArrayList<>();
            FeedFile f = new FeedCollector(new Http(), sources(stub), GEAR).collect(TODAY, TODAY.minusDays(3), Set.of(), log);

            Map<String, List<String>> got = titlesByGear(f);
            assertEquals(Set.of("Sennheiser HD 600", "Sennheiser HD 650", "HIFIMAN Edition XS", "Truthear Pure"), got.keySet());
            assertEquals(List.of("Sennheiser HD 600 vs HD 650 for a first open-back"), got.get("Sennheiser HD 600"));
            assertEquals(List.of("Sennheiser HD 600 vs HD 650 for a first open-back"), got.get("Sennheiser HD 650"));
            assertFalse(got.values().stream().flatMap(List::stream).anyMatch(t -> t.contains("under $50")));
            assertEquals("2026-10-17", f.gathered());
            assertEquals(new FeedFile.Window("2026-10-14", "2026-10-17"), f.window());
            var thread = f.items().stream().filter(i -> i.gear().get("model").equals("Pure")).findFirst().orElseThrow().threads().getFirst();
            assertEquals("YOUTUBE", thread.source());
            assertEquals("2026-10-14", thread.posted());
            assertNull(thread.snippet());
            assertEquals(List.of(), log);
        }
    }

    @Test
    void dropsTitlesMatchingThreeOrMoreGear() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/round.rss", """
                    <rss version="2.0"><channel><item><title>Sennheiser HD 600 vs HD 650 vs HIFIMAN Edition XS</title>
                    <link>https://forum.example.test/threads/roundup.9/</link></item></channel></rss>""");
            FeedFile f = new FeedCollector(new Http(), List.of(new FeedSource("ASR", stub.base() + "/round.rss", ThreadSource.ASR)), GEAR)
                    .collect(TODAY, TODAY.minusDays(1), Set.of(), new ArrayList<>());
            assertEquals(List.of(), f.items());
        }
    }

    @Test
    void ignoresModelNamesInsideOtherWords() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/asr2.rss", """
                    <rss version="2.0"><channel>
                    <item><title>Moondrop Para 2 Planar Headphone Review</title><link>https://forum.example.test/threads/para2.1/</link></item>
                    <item><title>Sony WF-1000XM6 first impressions</title><link>https://forum.example.test/threads/xm6.2/</link></item>
                    <item><title>Sennheiser HD600 vs HD650</title><link>https://forum.example.test/threads/hd600.3/</link></item>
                    </channel></rss>""");
            List<KnownGear> gear = List.of(new KnownGear(GearType.HEADPHONE, "Moondrop", "LAN"),
                    new KnownGear(GearType.HEADPHONE, "Moondrop", "Para"),
                    new KnownGear(GearType.IEM, "Sony", "WF-1000X"),
                    new KnownGear(GearType.HEADPHONE, "Sennheiser", "HD 600"),
                    new KnownGear(GearType.HEADPHONE, "Sennheiser", "HD 650"));
            FeedFile f = new FeedCollector(new Http(), List.of(new FeedSource("ASR", stub.base() + "/asr2.rss", ThreadSource.ASR)), gear)
                    .collect(TODAY, TODAY.minusDays(1), Set.of(), new ArrayList<>());
            assertEquals(Set.of("Moondrop Para", "Sennheiser HD 600", "Sennheiser HD 650"), titlesByGear(f).keySet());
        }
    }

    @Test
    void keepsOnlyWebLinks() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.on("/odd.rss", """
                    <rss version="2.0"><channel>
                    <item><title>Sennheiser HD 600 thread</title><link>javascript:alert(1)</link></item>
                    <item><title>Sennheiser HD 650 thread</title><link>file:///c:/windows/win.ini</link></item>
                    </channel></rss>""");
            FeedFile f = new FeedCollector(new Http(), List.of(new FeedSource("ASR", stub.base() + "/odd.rss", ThreadSource.ASR)), GEAR)
                    .collect(TODAY, TODAY.minusDays(1), Set.of(), new ArrayList<>());
            assertEquals(List.of(), f.items());
        }
    }

    @Test
    void mergeAddsNewDevicesJoinsKnownGearAndSkipsSeenLinks() {
        var hd600 = new ResearchImporter.ResearchItem(Map.of("type", "HEADPHONE", "brand", "Sennheiser", "model", "HD 600"), null,
                List.of(new ResearchImporter.ResearchThread("https://forum.example.test/threads/hd600.1/", "HD 600 thread", "ASR", null, null)));
        FeedFile feed = new FeedFile("Daily feed", "2026-10-17", new FeedFile.Window("2026-10-16", "2026-10-17"), "m", List.of(hd600));
        var sameGear = new ResearchImporter.ResearchItem(Map.of("type", "HEADPHONE", "brand", "sennheiser", "model", "HD 600"), null,
                List.of(new ResearchImporter.ResearchThread("https://forum.example.test/threads/hd600.1/", "dup", "ASR", null, null),
                        new ResearchImporter.ResearchThread("https://forum.example.test/threads/hd600-asr.2/", "ASR review", "ASR", null, null)));
        var seen = new ResearchImporter.ResearchItem(Map.of("type", "DAC", "brand", "SMSL", "model", "R100"), null,
                List.of(new ResearchImporter.ResearchThread("https://forum.example.test/threads/r100.3/", "ASR review: SMSL R100", "ASR", null, null)));
        var fresh = new ResearchImporter.ResearchItem(Map.of("type", "AMP", "brand", "Schiit", "model", "Magni Unity"), null,
                List.of(new ResearchImporter.ResearchThread("https://forum.example.test/threads/magni.4/", "ASR review: Schiit Magni Unity", "ASR", null, null)));

        FeedFile merged = FeedCollector.merge(feed, List.of(sameGear, seen, fresh),
                Set.of(UrlNormalizer.normalize("https://forum.example.test/threads/r100.3/")));

        Map<String, List<String>> got = titlesByGear(merged);
        assertEquals(Set.of("Sennheiser HD 600", "Schiit Magni Unity"), got.keySet());
        assertEquals(List.of("HD 600 thread", "ASR review"), got.get("Sennheiser HD 600"));
        assertEquals(feed.window(), merged.window());
    }

    @Test
    void dropsSeenUrlsAndItemsBeforeWindow() throws Exception {
        try (StubServer stub = new StubServer()) {
            Set<String> seen = Set.of(UrlNormalizer.normalize("https://forum.example.test/threads/hd-600-vs-hd-650.101/"));
            FeedFile f = new FeedCollector(new Http(), sources(stub), GEAR).collect(TODAY, LocalDate.parse("2026-10-15"), seen, new ArrayList<>());
            assertEquals(Set.of("HIFIMAN Edition XS"), titlesByGear(f).keySet()); // HD 600/650 seen, YouTube video before the window
        }
    }

    @Test
    void oneFailingSourceIsLoggedOthersContinue() throws Exception {
        try (StubServer stub = new StubServer()) {
            List<FeedSource> all = new ArrayList<>(sources(stub));
            all.add(new FeedSource("Broken", stub.base() + "/missing.rss", ThreadSource.SBAF));
            List<String> log = new ArrayList<>();
            FeedFile f = new FeedCollector(new Http(), all, GEAR).collect(TODAY, TODAY.minusDays(3), Set.of(), log);
            assertEquals(1, log.size(), log.toString());
            assertTrue(log.getFirst().startsWith("Broken"));
            assertEquals(4, f.items().size());
        }
    }

    @Test
    void allSourcesFailingThrows() throws Exception {
        try (StubServer stub = new StubServer()) {
            var collector = new FeedCollector(new Http(), List.of(new FeedSource("Broken", stub.base() + "/missing.rss", ThreadSource.ASR)), GEAR);
            assertThrows(IOException.class, () -> collector.collect(TODAY, TODAY.minusDays(1), Set.of(), new ArrayList<>()));
        }
    }

    @Test
    void windowFromUsesPreviousFileOrYesterday() throws Exception {
        Path feeds = Files.createDirectories(dir.resolve("feeds"));
        assertEquals(LocalDate.parse("2026-10-16"), FeedCollector.windowFrom(feeds, TODAY));
        Files.writeString(feeds.resolve("2026-10-15.json"), "{ \"title\": \"t\", \"gathered\": \"2026-10-15\", \"window\": { \"from\": \"2026-10-14\", \"to\": \"2026-10-15\" }, \"items\": [] }");
        assertEquals(LocalDate.parse("2026-10-15"), FeedCollector.windowFrom(feeds, TODAY));
    }

    @Test
    void seenUrlsCoverOnlyTheLastFourteenDays() throws Exception {
        Path feeds = Files.createDirectories(dir.resolve("feeds"));
        String item = "{ \"title\": \"t\", \"gathered\": \"x\", \"items\": [ { \"gear\": { \"type\": \"IEM\", \"brand\": \"B\", \"model\": \"M\" }, \"threads\": [ { \"url\": \"%s\", \"title\": \"t\" } ] } ] }";
        Files.writeString(feeds.resolve("2026-10-10.json"), item.formatted("https://forum.example.test/threads/recent.1/"));
        Files.writeString(feeds.resolve("2026-09-01.json"), item.formatted("https://forum.example.test/threads/old.2/"));
        assertEquals(Set.of(UrlNormalizer.normalize("https://forum.example.test/threads/recent.1/")), FeedCollector.seenUrls(feeds, TODAY));
    }

    @Test
    void outputImportsCleanly() throws Exception {
        try (StubServer stub = new StubServer()) {
            FeedFile f = new FeedCollector(new Http(), sources(stub), GEAR).collect(TODAY, TODAY.minusDays(3), Set.of(), new ArrayList<>());
            String json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(f);
            Database db = Database.open(dir.resolve("i.db"), dir.resolve("b"));
            ResearchImporter.Result r = new ResearchImporter(new GearRepository(db), new ThreadRepository(db), new CurveRepository(db), null, null)
                    .importJson(new StringReader(json), Progress.NONE, LinkKind.AUTO);
            assertEquals(List.of(), r.summary().problems());
            assertTrue(r.threads() > 0);
        }
    }
}
