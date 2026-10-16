package audiogear.snapshot;

import audiogear.db.AppliedUpdateRepository;
import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.domain.CurveKind;
import audiogear.importers.Progress;
import audiogear.net.Http;
import audiogear.testing.AutoEqStub;
import audiogear.testing.StubServer;
import audiogear.updates.UpdateFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotBuilderTest {

    @TempDir Path dir;

    static final String RESEARCH = """
            { "title": "research", "gathered": "2026-10-15", "items": [
              { "gear": { "type": "AMP_DAC", "brand": "Topping", "model": "DX1 II" },
                "threads": [ { "url": "https://www.audiosciencereview.com/forum/index.php?threads/dx1.1/", "title": "Topping DX1 II review" } ] } ] }""";

    @Test
    void snapshotHasNonExcludedMeasurementsResearchAndTargets() throws Exception {
        try (StubServer stub = new StubServer()) {
            AutoEqStub.serve(stub, Map.of(
                    "A", List.of("data/over-ear/Sennheiser HD 600.csv", "data/in-ear/Moondrop Chu.csv"),
                    "B", List.of("data/over-ear/Koss Porta Pro.csv")));
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RESEARCH.getBytes(StandardCharsets.UTF_8)));
            stub.on("/data/research/r.json", RESEARCH);
            stub.on("/data/manifest.json", "{ \"generated\": \"x\", \"excludeSources\": [\"B\"], \"files\": ["
                    + "{ \"name\": \"research/r.json\", \"kind\": \"research\", \"sha256\": \"" + sha + "\" } ] }");
            Path out = dir.resolve("seed").resolve("catalog.db");

            SnapshotBuilder.build(out, new UpdateFeed(new Http(), stub.base() + "/data/"), AutoEqStub.client(stub, dir.resolve("cache")), Progress.NONE);

            try (Stream<Path> s = Files.list(out.getParent())) {
                assertEquals(List.of(out), s.toList()); // no lock or journal files shipped
            }
            Database db = Database.open(out, dir.resolve("b"));
            GearRepository gear = new GearRepository(db);
            assertTrue(gear.findId("Sennheiser", "HD 600", "").isPresent());
            assertTrue(gear.findId("Moondrop", "Chu", "").isPresent());
            assertTrue(gear.findId("Topping", "DX1 II", "").isPresent());
            assertTrue(gear.findId("Koss", "Porta Pro", "").isEmpty());
            assertEquals(2, new CurveRepository(db).byKind(CurveKind.TARGET).size());
            assertTrue(new AppliedUpdateRepository(db).names().contains("research/r.json"));
            String integrity = db.jdbi().withHandle(h -> h.createQuery("PRAGMA integrity_check").mapTo(String.class).one());
            assertEquals("ok", integrity);
        }
    }

    @Test
    void failsWhenTheManifestIsUnreachable() {
        assertThrows(java.io.IOException.class, () -> SnapshotBuilder.build(dir.resolve("seed").resolve("catalog.db"),
                new UpdateFeed(new Http(), "http://127.0.0.1:1/data/"), null, Progress.NONE));
    }
}
