package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.domain.*;
import audiogear.net.Http;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImportersIntegrationTest {

    @TempDir Path dir;
    StubServer stub;
    Database db;
    GearRepository gear;
    CurveRepository curves;
    AutoEqClient client;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubServer();
        db = Database.open(dir.resolve("c.db"), dir.resolve("b"));
        gear = new GearRepository(db);
        curves = new CurveRepository(db);
        client = new AutoEqClient(new Http(), stub.base(), stub.base(), () -> "tok", dir.resolve("cache"));
        String api = "/repos/jaakkopasanen/AutoEq/git/trees/";
        stub.on(api + "master", "{\"tree\":[{\"path\":\"measurements\",\"type\":\"tree\",\"sha\":\"m1\"},{\"path\":\"README.md\",\"type\":\"blob\",\"sha\":\"r\"}]}");
        stub.on(api + "m1", "{\"tree\":[{\"path\":\"oratory1990\",\"type\":\"tree\",\"sha\":\"o1\"},{\"path\":\"crinacle\",\"type\":\"tree\",\"sha\":\"c1\"}]}");
        stub.on(api + "o1?recursive=1", """
                {"truncated": false, "tree": [
                  {"path": "data", "type": "tree"},
                  {"path": "data/over-ear/Sennheiser HD 650.csv", "type": "blob"},
                  {"path": "data/over-ear/Sennheiser HD 650 (sample 2).csv", "type": "blob"},
                  {"path": "data/in-ear/Broken IEM.csv", "type": "blob"},
                  {"path": "README.md", "type": "blob"}]}""");
        String raw = "/jaakkopasanen/AutoEq/master/measurements/oratory1990/data/";
        stub.on(raw + "over-ear/Sennheiser%20HD%20650.csv", "frequency,raw\n20,-6\n1000,0\n20000,-10\n");
        stub.on(raw + "over-ear/Sennheiser%20HD%20650%20%28sample%202%29.csv", "frequency,raw\n20,-5\n1000,0\n20000,-9\n");
        stub.on(raw + "in-ear/Broken%20IEM.csv", "frequency,raw\nnot,numbers\n");
        stub.on("/jaakkopasanen/AutoEq/master/targets/Harman%20over-ear%202018.csv", "frequency,raw\n20,3\n1000,0\n20000,-8\n");
        stub.on("/jaakkopasanen/AutoEq/master/targets/Harman%20in-ear%202019.csv", "frequency,raw\n20,8\n1000,0\n20000,-6\n");
    }

    @AfterEach
    void tearDown() { stub.close(); }

    @Test
    void listsSourcesAndCachesEntriesByTreeHash() throws Exception {
        List<AutoEqClient.Source> sources = client.sources();
        assertEquals(List.of("crinacle", "oratory1990"), sources.stream().map(AutoEqClient.Source::name).toList());
        var ora = sources.get(1);
        assertEquals(3, client.entries(ora).size());
        assertEquals(3, client.entries(ora).size());
        assertEquals(1, stub.count("/repos/jaakkopasanen/AutoEq/git/trees/o1?recursive=1"));
        assertEquals("Bearer tok", stub.requests.getFirst().header("Authorization"));
    }

    @Test
    void importsMeasurementsGroupsSamplesAndReportsBadFiles() throws Exception {
        var entries = client.entries(new AutoEqClient.Source("oratory1990", "o1"));
        var importer = new AutoEqImporter(client, gear, curves, BrandParser.standard());
        ImportSummary s = importer.importEntries(entries, Progress.NONE);
        assertEquals(2, s.imported());
        assertEquals(1, s.skipped());
        assertTrue(s.problems().getFirst().startsWith("Broken IEM [oratory1990]"));

        long hd650 = gear.findId("Sennheiser", "HD 650", "").orElseThrow();
        List<CurveInfo> c = curves.forGear(hd650);
        assertEquals(2, c.size()); // both samples on the same gear
        assertTrue(c.stream().anyMatch(i -> i.label().equals("sample 2")));
        assertEquals(Form.OVER_EAR, gear.get(hd650).orElseThrow().transducer().form());

        // Re-import: no duplicate gear or curves
        importer.importEntries(entries, Progress.NONE);
        assertEquals(1, gear.count());
        assertEquals(2, curves.forGear(hd650).size());

        ImportSummary t = importer.importTargets();
        assertEquals(2, t.imported());
        assertEquals(2, curves.byKind(CurveKind.TARGET).size());
    }

    @Test
    void cancellingStopsTheImport() throws Exception {
        var entries = client.entries(new AutoEqClient.Source("oratory1990", "o1"));
        Progress cancelled = new Progress() {
            @Override public void update(long done, long total, String message) {}
            @Override public boolean isCancelled() { return true; }
        };
        ImportSummary s = new AutoEqImporter(client, gear, curves, BrandParser.standard()).importEntries(entries, cancelled);
        assertTrue(s.cancelled());
        assertEquals(0, s.imported());
    }

    @Test
    void catalogCsvRoundTripsAndMerges() throws Exception {
        Gear amp = Gear.create(GearType.AMP_DAC, "Topping", "DX5");
        long id = gear.save(new GearDetail(amp, null,
                new AmpSpec(null, 0.1, 7.0, null, "low, high", null, null),
                List.of(new AmpPower(null, null, AmpOutput.BALANCED, 32, 4000)),
                new DacSpec(null, "ES9068AS", 120.0, null, null, true, null, null), List.of("desk")));

        StringWriter out = new StringWriter();
        CatalogCsv.write(List.of(gear.get(id).orElseThrow()), out);
        String csv = out.toString();
        assertTrue(csv.contains("\"low, high\""));
        assertTrue(csv.contains("BALANCED:32=4000"));

        Database other = Database.open(dir.resolve("other.db"), dir.resolve("b2"));
        GearRepository otherGear = new GearRepository(other);
        assertEquals(1, CatalogCsv.read(new StringReader(csv), otherGear).imported());
        GearDetail copy = otherGear.get(otherGear.findId("Topping", "DX5", "").orElseThrow()).orElseThrow();
        assertEquals(4000, copy.powers().getFirst().powerMw());
        assertTrue(copy.dac().dsd());
        assertEquals(List.of("desk"), copy.tags());

        // Partial row: only SINAD changes, everything else is kept; a bad row is reported, not fatal
        String patch = "type,brand,model,sinad_db\nAMP_DAC,topping,dx5,121\n";
        String patch2 = "type,brand,model,impedance_ohm\nIEM,Bad,Row,abc\n";
        ImportSummary s = CatalogCsv.read(new StringReader(patch), otherGear);
        assertEquals(1, s.imported());
        ImportSummary s2 = CatalogCsv.read(new StringReader(patch2), otherGear);
        assertEquals(1, s2.skipped());
        assertTrue(s2.problems().getFirst().contains("impedance_ohm must be a number"));
        GearDetail merged = otherGear.get(copy.gear().id()).orElseThrow();
        assertEquals(121.0, merged.dac().sinadDb());
        assertEquals(0.1, merged.amp().outputImpedanceOhm());
        assertEquals(1, merged.powers().size());
    }

    @Test
    void dacRowWithoutDsdImports() throws Exception {
        String csv = "type,brand,model,chip\nAMP_DAC,Schiit,Magni Unity,Optional DAC card\n";
        ImportSummary s = CatalogCsv.read(new StringReader(csv), gear);
        assertEquals(1, s.imported(), s.problems().toString());
        assertNull(gear.get(gear.findId("Schiit", "Magni Unity", "").orElseThrow()).orElseThrow().dac().dsd());
    }

    @Test
    void reportsHttpErrorsWithStatus() {
        stub.on("/repos/jaakkopasanen/AutoEq/git/trees/master",
                new StubServer.Response(403, "rate limited", Map.of("Retry-After", "60")));
        var e = assertThrows(Http.HttpStatusException.class, () -> client.sources());
        assertEquals(403, e.status());
        assertEquals(60L, e.retryAfterSeconds());
    }
}
