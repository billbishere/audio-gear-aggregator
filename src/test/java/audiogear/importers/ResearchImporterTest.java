package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.*;
import audiogear.net.Http;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearchImporterTest {

    @TempDir Path dir;

    private static final String JSON = """
            { "title": "test", "gathered": "2026-10-15", "items": [
              { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 600", "impedance_ohm": "300",
                          "sensitivity": "97", "sensitivity_unit": "DB_PER_V", "form": "OVER_EAR", "tags": "2026-hot" },
                "autoeq": ["Sennheiser HD 600"],
                "threads": [
                  { "url": "https://www.reddit.com/r/HeadphoneAdvice/comments/1pkhfp1/edition_xs_vs_sennheiser_hd_600/",
                    "title": "HIFIMAN Edition XS vs Sennheiser HD 600", "snippet": "Which to buy for everything" },
                  { "url": "https://youtu.be/abcdefgh?si=x", "title": "HD 600 video review", "posted": "2026-04-25" } ] },
              { "gear": { "type": "HEADPHONE", "brand": "HIFIMAN", "model": "Edition XS", "impedance_ohm": "18" },
                "autoeq": ["HIFIMAN Edition XS", "Hifiman Edition XS"],
                "threads": [] },
              { "gear": { "type": "IEM", "brand": "Brandless" },
                "threads": [] }
            ] }""";

    @Test
    void importsGearThreadsCurvesAndCrossLinks() throws Exception {
        try (StubServer stub = new StubServer()) {
            String api = "/repos/jaakkopasanen/AutoEq/git/trees/";
            stub.on(api + "master", "{\"tree\":[{\"path\":\"measurements\",\"type\":\"tree\",\"sha\":\"m\"}]}");
            stub.on(api + "m", "{\"tree\":[{\"path\":\"Rtings\",\"type\":\"tree\",\"sha\":\"r\"},{\"path\":\"oratory1990\",\"type\":\"tree\",\"sha\":\"o\"}]}");
            stub.on(api + "o?recursive=1", "{\"tree\":[{\"path\":\"data/over-ear/Sennheiser HD 600.csv\",\"type\":\"blob\"},"
                    + "{\"path\":\"data/over-ear/Sennheiser HD 600 (sample 2).csv\",\"type\":\"blob\"}]}");
            stub.on(api + "r?recursive=1", "{\"tree\":[{\"path\":\"data/over-ear/Sennheiser HD 600.csv\",\"type\":\"blob\"},"
                    + "{\"path\":\"data/over-ear/Sennheiser HD 650.csv\",\"type\":\"blob\"}]}");
            String raw = "/jaakkopasanen/AutoEq/master/measurements/";
            stub.on(raw + "oratory1990/data/over-ear/Sennheiser%20HD%20600.csv", "frequency,raw\n20,-6\n1000,0\n20000,-10\n");
            stub.on(raw + "Rtings/data/over-ear/Sennheiser%20HD%20600.csv", "frequency,raw\n20,-7\n1000,0\n20000,-9\n");
            stub.on("/jaakkopasanen/AutoEq/master/targets/Harman%20over-ear%202018.csv", "frequency,raw\n20,3\n1000,0\n20000,-8\n");
            stub.on("/jaakkopasanen/AutoEq/master/targets/Harman%20in-ear%202019.csv", "frequency,raw\n20,8\n1000,0\n20000,-6\n");

            Database db = Database.open(dir.resolve("r.db"), dir.resolve("b"));
            GearRepository gear = new GearRepository(db);
            ThreadRepository threads = new ThreadRepository(db);
            CurveRepository curves = new CurveRepository(db);
            AutoEqClient client = new AutoEqClient(new Http(), stub.base(), stub.base(), () -> null, dir.resolve("cache"));
            AutoEqImporter aeq = new AutoEqImporter(client, gear, curves, BrandParser.standard());
            ResearchImporter importer = new ResearchImporter(gear, threads, curves, client, aeq);

            ResearchImporter.Result r = importer.importJson(new StringReader(JSON), Progress.NONE);
            assertEquals(2, r.summary().imported());
            assertEquals(1, r.summary().skipped()); // model missing
            assertTrue(r.summary().problems().getFirst().contains("model is required"));
            assertEquals(2, r.threads());
            assertEquals(4, r.curves()); // HD 600 from oratory1990 + Rtings, plus 2 targets
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("HIFIMAN Edition XS: no AutoEq measurement")));

            long hd600 = gear.findId("Sennheiser", "HD 600", "").orElseThrow();
            long exs = gear.findId("HIFIMAN", "Edition XS", "").orElseThrow();
            GearDetail d = gear.get(hd600).orElseThrow();
            assertEquals(SensitivityUnit.DB_PER_V, d.transducer().sensitivityUnit());
            assertEquals(List.of("2026-hot"), d.tags());

            List<CurveInfo> hdCurves = curves.forGear(hd600);
            assertEquals(2, hdCurves.size());
            assertEquals("oratory1990", hdCurves.stream().map(CurveInfo::source).sorted().toList().getLast());

            List<ForumThread> hdThreads = threads.forGear(hd600);
            assertEquals(2, hdThreads.size());
            assertTrue(hdThreads.stream().anyMatch(t -> t.source() == ThreadSource.YOUTUBE
                    && t.url().equals("https://www.youtube.com/watch?v=abcdefgh") && "2026-04-25T12:00:00Z".equals(t.postedAt())));
            // the comparison thread also lands on the Edition XS through the name matcher
            assertEquals(1, threads.forGear(exs).size());

            // Re-import is idempotent
            importer.importJson(new StringReader(JSON), Progress.NONE);
            assertEquals(2, gear.count());
            assertEquals(2, threads.forGear(hd600).size());
            assertEquals(2, curves.forGear(hd600).size());
        }
    }

    @Test
    void reimportOnlyFillsBlanksAndMergesTags() throws Exception {
        Database db = Database.open(dir.resolve("m.db"), dir.resolve("b"));
        GearRepository gear = new GearRepository(db);
        ResearchImporter importer = new ResearchImporter(gear, new ThreadRepository(db), new CurveRepository(db), null, null);
        importer.importJson(new StringReader("""
                { "items": [ { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 600", "sensitivity": "97",
                                         "sensitivity_unit": "DB_PER_V", "tags": "most-discussed-2026, reference",
                                         "notes": "Edited by hand" } } ] }"""), Progress.NONE);

        ResearchImporter.Result r = importer.importJson(new StringReader("""
                { "items": [ { "gear": { "type": "HEADPHONE", "brand": "Sennheiser", "model": "HD 600", "sensitivity": "104",
                                         "weight_g": "260", "tags": "most-discussed-2026-11, reference",
                                         "notes": "November summary" } } ] }"""), Progress.NONE);

        assertEquals(1, r.summary().imported());
        assertEquals(1, gear.count());
        GearDetail d = gear.get(gear.findId("Sennheiser", "HD 600", "").orElseThrow()).orElseThrow();
        assertEquals(97.0, d.transducer().sensitivity());   // existing value wins
        assertEquals(260.0, d.transducer().weightG());      // empty field is filled
        assertEquals("Edited by hand", d.gear().notes());
        assertEquals(List.of("most-discussed-2026", "most-discussed-2026-11", "reference"), d.tags()); // stored sorted
    }

    @Test
    void picksPlainNamesFirstAndAtMostTwoSources() {
        var idx = List.of(
                new AutoEqClient.Entry("Rtings", "over-ear", null, "Koss Porta Pro", "p1"),
                new AutoEqClient.Entry("crinacle", "over-ear", "GRAS", "Koss Porta Pro (sample 2)", "p2"),
                new AutoEqClient.Entry("oratory1990", "over-ear", null, "Koss Porta Pro", "p3"),
                new AutoEqClient.Entry("Super Review", "over-ear", null, "Koss Porta Pro", "p4"));
        var picked = ResearchImporter.pickEntries(idx, List.of("koss porta-pro"));
        assertEquals(List.of("oratory1990", "crinacle"), picked.stream().map(AutoEqClient.Entry::source).toList());
    }
}
