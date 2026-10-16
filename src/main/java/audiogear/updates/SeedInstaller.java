package audiogear.updates;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * On first launch, copies the full catalog that ships inside the installer (seed/catalog.db) into the data folder.
 * An existing catalog is never touched.
 */
public final class SeedInstaller {
    private SeedInstaller() {}

    /** The bundled seed next to the app's jars (jpackage puts --app-content there), if this install has one. */
    public static Optional<Path> bundledSeed() {
        try {
            Path code = Path.of(SeedInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return bundledSeed(Files.isDirectory(code) ? code : code.getParent());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Looks in codeDir/seed, then codeDir/../seed. */
    static Optional<Path> bundledSeed(Path codeDir) {
        Path beside = codeDir.resolve("seed").resolve("catalog.db");
        if (Files.isRegularFile(beside)) return Optional.of(beside);
        Path parent = codeDir.toAbsolutePath().getParent();
        Path above = parent == null ? null : parent.resolve("seed").resolve("catalog.db");
        return above != null && Files.isRegularFile(above) ? Optional.of(above) : Optional.empty();
    }

    /**
     * Copies seed to database when database doesn't exist yet (creating its folder). The copy goes to a temporary
     * file that is then renamed without replacing anything, so an existing catalog is never overwritten, not even
     * one that appears while copying, and a failed copy leaves nothing half-written behind.
     */
    public static boolean installIfMissing(Path seed, Path database) throws IOException {
        return installIfMissing(seed, database, () -> {});
    }

    static boolean installIfMissing(Path seed, Path database, Runnable beforeMove) throws IOException {
        if (Files.exists(database)) return false;
        Path dir = Files.createDirectories(database.toAbsolutePath().getParent());
        setAsideStaleJournals(database);
        Path tmp = Files.createTempFile(dir, database.getFileName().toString(), ".seed-tmp");
        try {
            Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
            beforeMove.run();
            Files.move(tmp, database); // no REPLACE_EXISTING: refuses if a catalog exists after all
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Leftover SQLite journal files of a deleted catalog would be replayed onto the seed; keep them, renamed. */
    private static void setAsideStaleJournals(Path database) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
            Path j = database.resolveSibling(database.getFileName() + suffix);
            if (Files.exists(j)) Files.move(j, j.resolveSibling(j.getFileName() + ".stale-" + stamp));
        }
    }
}
