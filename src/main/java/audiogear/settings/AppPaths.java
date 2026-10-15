package audiogear.settings;

import java.nio.file.Path;

/** Where the app keeps its files: %APPDATA%\AudioGearAggregator on Windows, ~/.audio-gear-aggregator elsewhere. */
public record AppPaths(Path home) {

    public static AppPaths standard() {
        String override = System.getProperty("audiogear.home");
        if (override != null && !override.isBlank()) return new AppPaths(Path.of(override));
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return new AppPaths(Path.of(appData, "AudioGearAggregator"));
        return new AppPaths(Path.of(System.getProperty("user.home"), ".audio-gear-aggregator"));
    }

    public Path database() { return home.resolve("catalog.db"); }
    public Path backups() { return home.resolve("backups"); }
    public Path settings() { return home.resolve("settings.properties"); }
    public Path logs() { return home.resolve("logs"); }
    public Path cache() { return home.resolve("cache"); }
}
