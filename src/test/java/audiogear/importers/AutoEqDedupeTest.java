package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.domain.CurveKind;
import audiogear.testing.AutoEqStub;
import audiogear.testing.StubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutoEqDedupeTest {

    @TempDir Path dir;

    @Test
    void importingTheSameEntriesTwiceAddsNoCurves() throws Exception {
        try (StubServer stub = new StubServer()) {
            AutoEqStub.serve(stub, Map.of("oratory1990",
                    List.of("data/over-ear/Sennheiser HD 600.csv", "data/over-ear/Sennheiser HD 650.csv")));
            Database db = Database.open(dir.resolve("d.db"), dir.resolve("b"));
            CurveRepository curves = new CurveRepository(db);
            AutoEqClient client = AutoEqStub.client(stub, dir.resolve("cache"));
            AutoEqImporter importer = new AutoEqImporter(client, new GearRepository(db), curves, BrandParser.standard());
            List<AutoEqClient.Entry> entries = client.entries(client.sources().getFirst());

            ImportSummary first = importer.importEntries(entries, Progress.NONE);
            ImportSummary second = importer.importEntries(entries, Progress.NONE);

            assertEquals(2, first.imported());
            assertEquals(0, second.imported());
            assertEquals(0, second.skipped()); // already-imported files are neither imported nor skipped
            assertEquals(2, curves.byKind(CurveKind.FR).size());
        }
    }

    @Test
    void migrationCreatesAppliedUpdateTable() {
        Database db = Database.open(dir.resolve("m.db"), dir.resolve("b"));
        int tables = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM sqlite_master WHERE name = 'applied_update'").mapTo(Integer.class).one());
        assertEquals(1, tables);
    }
}
