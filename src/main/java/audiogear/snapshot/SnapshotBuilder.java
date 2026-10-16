package audiogear.snapshot;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.ImportSummary;
import audiogear.importers.Progress;
import audiogear.updates.UpdateFeed;
import audiogear.updates.UpdateResult;
import audiogear.updates.Updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the full catalog that ships in the installer: an empty catalog plus one run of the same Updater the app
 * uses daily (every non-excluded AutoEq measurement, every research and feed file), plus the Harman targets.
 */
public final class SnapshotBuilder {
    private SnapshotBuilder() {}

    /** gear: how many items the snapshot holds, counted before it is written out. */
    public record Result(UpdateResult update, int gear) {}

    /**
     * Writes a single self-contained file to database (no lock or journal files beside it). Fails rather than ship a
     * partial catalog when AutoEq couldn't be listed or no measurement was imported.
     */
    public static Result build(Path database, UpdateFeed feed, AutoEqClient autoEq, Progress progress) throws IOException {
        Path work = Files.createTempDirectory("catalog-snapshot");
        Database db = Database.open(work.resolve("catalog.db"), work.resolve("backups"));
        AutoEqImporter importer = autoEq == null ? null
                : new AutoEqImporter(autoEq, new GearRepository(db), new CurveRepository(db), BrandParser.standard());

        UpdateResult result = new Updater(db, null, feed, autoEq, importer, "snapshot").run(progress);
        if (result.skippedReason() != null) throw new IOException("Snapshot not built: " + result.skippedReason());
        if (autoEq != null) {
            for (String p : result.problems()) {
                if (p.startsWith(Updater.AUTOEQ_UNAVAILABLE)) throw new IOException("Snapshot not built: " + p);
            }
            if (result.measurements() == 0) throw new IOException("Snapshot not built: no AutoEq measurements were imported");
        }
        if (importer != null) {
            ImportSummary targets = importer.importTargets();
            if (!targets.problems().isEmpty()) throw new IOException("Harman targets failed: " + targets.problems());
        }

        Files.createDirectories(database.toAbsolutePath().getParent());
        Files.deleteIfExists(database);
        String target = database.toAbsolutePath().toString().replace("'", "''");
        int gear = new GearRepository(db).count();
        db.jdbi().useHandle(h -> h.execute("VACUUM INTO '" + target + "'"));
        return new Result(result, gear);
    }
}
