package audiogear.updates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionsTest {

    @Test
    void comparesVersionsNumerically() {
        assertTrue(Versions.isNewer("1.10.0", "1.9.0"));
        assertTrue(Versions.isNewer("1.2", "1.1.9"));
        assertFalse(Versions.isNewer("1.1.0", "1.1.0"));
        assertFalse(Versions.isNewer("1.1.0", "1.2.0"));
        assertFalse(Versions.isNewer("2.0.0", "dev"));
        assertFalse(Versions.isNewer(null, "1.0.0"));
    }
}
