package audiogear.updates;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UpdateScheduleTest {

    @Test
    void dueWhenNeverRunOrAtLeastTwentyHoursAgo() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        assertTrue(UpdateSchedule.isDue(null, now));
        assertFalse(UpdateSchedule.isDue(now.minus(Duration.ofHours(19)), now));
        assertTrue(UpdateSchedule.isDue(now.minus(Duration.ofHours(20)), now));
    }
}
