package audiogear.updates;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
     * Copies seed to database when database doesn't exist yet (creating its folder). Goes through a temporary file
     * and an atomic rename, so a failed copy never leaves a half-written catalog behind.
     */
    public static boolean installIfMissing(Path seed, Path database) throws IOException {
        if (Files.exists(database)) return false;
        Files.createDirectories(database.toAbsolutePath().getParent());
        Path tmp = database.resolveSibling(database.getFileName() + ".seed-tmp");
        try {
            Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, database, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
