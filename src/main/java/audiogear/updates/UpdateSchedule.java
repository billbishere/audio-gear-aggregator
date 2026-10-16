package audiogear.updates;

import java.time.Duration;
import java.time.Instant;

/** When the daily update runs: the app checks every hour and updates once the last run is 20 hours old. */
public final class UpdateSchedule {
    private UpdateSchedule() {}

    public static final Duration DUE_AFTER = Duration.ofHours(20);
    public static final Duration CHECK_EVERY = Duration.ofHours(1);

    public static boolean isDue(Instant lastRun, Instant now) {
        // A lastRun in the future was saved while the clock was wrong; waiting for it could stop updates for years
        return lastRun == null || lastRun.isAfter(now) || Duration.between(lastRun, now).compareTo(DUE_AFTER) >= 0;
    }
}
