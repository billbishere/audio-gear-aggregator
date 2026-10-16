package audiogear.updates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SeedInstallerTest {

    @TempDir Path dir;

    @Test
    void copiesIntoMissingFolder() throws Exception {
        Path seed = Files.writeString(dir.resolve("seed.db"), "full catalog");
        Path database = dir.resolve("fresh").resolve("AudioGearAggregator").resolve("catalog.db");

        assertTrue(SeedInstaller.installIfMissing(seed, database));
        assertEquals("full catalog", Files.readString(database));
        assertFalse(Files.exists(database.resolveSibling("catalog.db.seed-tmp")));
    }

    @Test
    void neverOverwritesExisting() throws Exception {
        Path seed = Files.writeString(dir.resolve("seed.db"), "full catalog");
        Path database = Files.writeString(dir.resolve("catalog.db"), "my catalog");

        assertFalse(SeedInstaller.installIfMissing(seed, database));
        assertEquals("my catalog", Files.readString(database));
    }

    @Test
    void leavesNoPartialFileOnFailure() {
        Path database = dir.resolve("catalog.db");
        assertThrows(IOException.class, () -> SeedInstaller.installIfMissing(dir.resolve("missing-seed.db"), database));
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(dir.resolve("catalog.db.seed-tmp")));
    }

    @Test
    void findsSeedBesideOrAboveTheCodeFolder() throws Exception {
        Path app = Files.createDirectories(dir.resolve("image").resolve("app"));
        assertEquals(Optional.empty(), SeedInstaller.bundledSeed(app));

        Path above = Files.createDirectories(dir.resolve("image").resolve("seed")).resolve("catalog.db");
        Files.writeString(above, "x");
        assertEquals(Optional.of(above), SeedInstaller.bundledSeed(app));

        Path beside = Files.createDirectories(app.resolve("seed")).resolve("catalog.db");
        Files.writeString(beside, "x");
        assertEquals(Optional.of(beside), SeedInstaller.bundledSeed(app));
    }
}
