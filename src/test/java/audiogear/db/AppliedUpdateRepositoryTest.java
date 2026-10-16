package audiogear.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AppliedUpdateRepositoryTest {

    @TempDir Path dir;

    @Test
    void recordIsIdempotent() {
        AppliedUpdateRepository applied = new AppliedUpdateRepository(Database.open(dir.resolve("a.db"), dir.resolve("b")));
        applied.record("feeds/2026-10-17.json", "feed");
        applied.record("feeds/2026-10-17.json", "feed");
        assertEquals(Set.of("feeds/2026-10-17.json"), applied.names());
    }
}
