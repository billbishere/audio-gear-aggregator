package audiogear.settings;

import audiogear.updates.UpdateFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @TempDir Path dir;

    @Test
    void updateSettingsDefaults() {
        Settings s = Settings.load(dir.resolve("settings.properties"));
        assertTrue(s.updatesDaily());
        assertNull(s.updatesLastRun());
        assertNull(s.updatesDismissedVersion());
        assertEquals(UpdateFeed.DEFAULT_BASE, s.updatesBaseUrl());
    }

    @Test
    void updateSettingsSurviveSaveAndLoad() {
        Path file = dir.resolve("settings.properties");
        Settings s = Settings.load(file);
        s.setUpdatesDaily(false);
        s.setUpdatesLastRun(Instant.parse("2026-10-17T06:30:00Z"));
        s.setUpdatesDismissedVersion("1.2.0");
        s.save();

        Settings again = Settings.load(file);
        assertFalse(again.updatesDaily());
        assertEquals(Instant.parse("2026-10-17T06:30:00Z"), again.updatesLastRun());
        assertEquals("1.2.0", again.updatesDismissedVersion());
    }

    @Test
    void saveKeepsChangesSavedByAnotherWindow() {
        Path file = dir.resolve("settings.properties");
        Settings a = Settings.load(file), b = Settings.load(file);
        a.setBraveApiKey("brave-key");
        a.save();
        b.setUpdatesLastRun(Instant.parse("2026-10-17T06:30:00Z"));
        b.save();

        Settings c = Settings.load(file);
        assertEquals("brave-key", c.braveApiKey());
        assertEquals(Instant.parse("2026-10-17T06:30:00Z"), c.updatesLastRun());
    }
}
