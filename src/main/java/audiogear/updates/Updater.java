package audiogear.updates;

import audiogear.db.AppliedUpdateRepository;
import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.LinkKind;
import audiogear.importers.AutoEqClient;
import audiogear.importers.AutoEqImporter;
import audiogear.importers.ImportSummary;
import audiogear.importers.Progress;
import audiogear.importers.ResearchImporter;
import audiogear.search.UrlNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * One daily update: applies new feed and research files from the data branch, imports AutoEq measurements not in
 * the catalog yet, and reports a newer app release. Only ever adds data or fills empty fields; backs up first.
 * The snapshot builder runs the same code on an empty catalog.
 */
public final class Updater {
    private static final Logger LOG = Logger.getLogger(Updater.class.getName());
    static final String BACKUP_PREFIX = "catalog-update-";
    static final int BACKUPS_KEPT = 3;
    /** Problem prefix when AutoEq couldn't be listed; the snapshot builder treats it as fatal. */
    public static final String AUTOEQ_UNAVAILABLE = "AutoEq measurements unavailable: ";

    private final Database db;
    private final Path backupDir;
    private final UpdateFeed feed;
    private final AutoEqClient autoEq;
    private final AutoEqImporter autoEqImporter;
    private final String currentVersion;
    private final GearRepository gear;
    private final ThreadRepository threads;
    private final CurveRepository curves;
    private final AppliedUpdateRepository applied;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** backupDir null = no backups (snapshot builds); autoEq null = no measurements. */
    public Updater(Database db, Path backupDir, UpdateFeed feed, AutoEqClient autoEq, AutoEqImporter autoEqImporter,
                   String currentVersion) {
        this.db = db;
        this.backupDir = backupDir;
        this.feed = feed;
        this.autoEq = autoEq;
        this.autoEqImporter = autoEqImporter;
        this.currentVersion = currentVersion;
        this.gear = new GearRepository(db);
        this.threads = new ThreadRepository(db);
        this.curves = new CurveRepository(db);
        this.applied = new AppliedUpdateRepository(db);
    }

    /** Never throws for network problems: they come back as skippedReason or problems, to retry next run. */
    public UpdateResult run(Progress progress) {
        Path lockFile = db.file().resolveSibling("update.lock");
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held elsewhere in this JVM
            }
            if (lock == null) return UpdateResult.skipped("another update is running");
            try {
                return runLocked(progress);
            } finally {
                lock.release();
            }
        } catch (IOException e) {
            return UpdateResult.skipped("update lock unavailable: " + e.getMessage());
        }
    }

    private UpdateResult runLocked(Progress progress) {
        Manifest manifest;
        try {
            progress.update(0, 0, "Checking for updates");
            manifest = feed.manifest();
        } catch (IOException | UncheckedIOException e) {
            LOG.info("Update check skipped: " + e.getMessage());
            return UpdateResult.skipped(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        List<String> problems = new ArrayList<>();
        Set<String> done = applied.names();
        List<Manifest.FileEntry> pendingFiles = manifest.files().stream().filter(f -> !done.contains(f.name())).toList();
        List<AutoEqClient.Entry> pendingEntries = pendingMeasurements(manifest, problems, progress);

        Path backup = null;
        if (backupDir != null && (!pendingFiles.isEmpty() || !pendingEntries.isEmpty())) {
            backup = db.backupNow(backupDir, BACKUP_PREFIX);
            try {
                pruneBackups(backupDir, BACKUP_PREFIX, BACKUPS_KEPT, backup);
            } catch (IOException e) {
                problems.add("Could not prune old update backups: " + e.getMessage());
            }
        }

        int files = 0, threadCount = 0, measurements = 0;
        boolean cancelled = false;
        // Add-only: never changes stored threads or the user's link choices; curves come from AutoEq below
        ResearchImporter importer = new ResearchImporter(gear, threads, curves, null, null).addOnly();
        for (int i = 0; i < pendingFiles.size(); i++) {
            if (progress.isCancelled()) { cancelled = true; break; }
            Manifest.FileEntry f = pendingFiles.get(i);
            progress.update(i, pendingFiles.size(), f.name());
            try {
                String text = feed.download(f);
                if (alreadyInCatalog(text)) {
                    // e.g. a research file the user imported by hand before daily updates existed: nothing new to add
                    applied.record(f.name(), f.kind());
                    continue;
                }
                ResearchImporter.Result r = importer.importJson(new StringReader(text), Progress.NONE,
                        "feed".equals(f.kind()) ? LinkKind.AUTO : LinkKind.MANUAL);
                problems.addAll(r.summary().problems());
                applied.record(f.name(), f.kind());
                files++;
                threadCount += r.threads();
            } catch (Exception e) {
                problems.add(f.name() + ": " + e.getMessage());
                LOG.log(Level.WARNING, "Update file " + f.name() + " not applied", e);
            }
        }

        if (!cancelled && !pendingEntries.isEmpty() && autoEqImporter != null) {
            ImportSummary s = autoEqImporter.importEntries(pendingEntries, progress);
            measurements = s.imported();
            problems.addAll(s.problems());
        }

        Manifest.LatestApp latest = manifest.latestApp();
        Manifest.LatestApp newer = latest != null && Versions.isNewer(latest.version(), currentVersion) ? latest : null;
        return new UpdateResult(files, threadCount, measurements, newer, backup, problems, null);
    }

    private List<AutoEqClient.Entry> pendingMeasurements(Manifest manifest, List<String> problems, Progress progress) {
        if (autoEq == null) return List.of();
        try {
            progress.update(0, 0, "Checking AutoEq for new measurements");
            Set<String> excluded = new HashSet<>(manifest.excludeSources());
            Set<String> have = curves.seenAutoEq(); // not curve origins: deleted curves and gear must stay deleted
            List<AutoEqClient.Entry> out = new ArrayList<>();
            for (AutoEqClient.Source s : autoEq.sources()) {
                if (excluded.contains(s.name())) continue;
                for (AutoEqClient.Entry e : autoEq.entries(s)) if (!have.contains(autoEq.rawUrl(e.path()))) out.add(e);
            }
            return out;
        } catch (IOException e) {
            problems.add(AUTOEQ_UNAVAILABLE + e.getMessage());
            return List.of();
        }
    }

    /**
     * Keeps keepAlways plus the newest other backups whose name starts with prefix, keep in total (names sort by
     * time). keepAlways survives even if the clock jumped back and its name sorts oldest. Other backups untouched.
     */
    static void pruneBackups(Path dir, String prefix, int keep, Path keepAlways) throws IOException {
        List<Path> mine;
        try (Stream<Path> s = Files.list(dir)) {
            mine = s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".db"))
                    .filter(p -> keepAlways == null || !p.equals(keepAlways))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        int others = Math.max(0, keepAlways == null ? keep : keep - 1);
        for (Path old : mine.subList(Math.min(others, mine.size()), mine.size())) Files.deleteIfExists(old);
    }

    /** True when the file names threads and every one of them is already in the catalog. */
    private boolean alreadyInCatalog(String text) throws IOException {
        Set<String> urls = new HashSet<>();
        for (JsonNode item : JSON.readTree(text).path("items")) {
            for (JsonNode t : item.path("threads")) {
                String url = t.path("url").asText("");
                if (!url.isBlank()) urls.add(UrlNormalizer.normalize(url));
            }
        }
        return !urls.isEmpty() && threads.existingUrls(urls).size() == urls.size();
    }
}
