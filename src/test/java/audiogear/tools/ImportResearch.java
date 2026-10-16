package audiogear.tools;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.BrandParser;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import audiogear.net.Http;
import audiogear.settings.AppPaths;
import audiogear.settings.Settings;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line version of Data & Settings → Import research file, for loading a research bundle without opening
 * the app. Backs up the database first. Usage: ImportResearch &lt;file.json&gt; (uses the normal data folder, or
 * -Daudiogear.home).
 */
public final class ImportResearch {
    private ImportResearch() {}

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        AppPaths paths = AppPaths.standard();
        System.out.println("Data folder: " + paths.home());
        Database db = Database.open(paths.database(), paths.backups());
        System.out.println("Backup: " + db.backupNow(paths.backups()));
        Settings settings = Settings.load(paths.settings());
        GearRepository gear = new GearRepository(db);
        CurveRepository curves = new CurveRepository(db);
        AutoEqClient client = AutoEqClient.github(new Http(), settings::githubToken, paths.cache());
        ResearchImporter importer = new ResearchImporter(gear, new ThreadRepository(db), curves, client,
                new AutoEqImporter(client, gear, curves, BrandParser.standard()));
        Progress progress = new Progress() {
            private String last = "";
            @Override public void update(long done, long total, String message) {
                if (message != null && !message.equals(last)) System.out.println("  " + (total > 0 ? done + "/" + total + " " : "") + message);
                last = message;
            }
            @Override public boolean isCancelled() { return false; }
        };
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ResearchImporter.Result result = importer.importJson(r, progress);
            System.out.println(result.headline());
            result.summary().problems().forEach(p -> System.out.println("PROBLEM: " + p));
            result.notes().forEach(n -> System.out.println("NOTE: " + n));
        }
    }
}
