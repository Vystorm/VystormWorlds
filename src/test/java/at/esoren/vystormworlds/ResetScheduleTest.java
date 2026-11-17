package at.esoren.vystormworlds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ResetScheduleTest {
    private static final ZoneId Z = ZoneId.of("Europe/Vienna");
    @TempDir Path folder;

    @Test
    void givenTexts_whenParsed_thenCanonicalOrRejected() {
        assertEquals("off", ResetSchedule.parse("off").text());
        assertEquals("daily 04:00", ResetSchedule.parse("Daily 4:00").text());
        assertEquals("weekly mon 04:30", ResetSchedule.parse("weekly monday 04:30").text());
        assertEquals("every 3 days 05:00", ResetSchedule.parse("every 3 days 05:00").text());
        assertNull(ResetSchedule.parse("every 1 days 05:00"));
        assertNull(ResetSchedule.parse("weekly x 05:00"));
        assertNull(ResetSchedule.parse("daily 25:00"));
        assertNull(ResetSchedule.parse("hourly"));
    }

    @Test
    void givenDaily_whenNext_thenTodayOrTomorrow() {
        ResetSchedule s = ResetSchedule.parse("daily 04:00");
        ZonedDateTime before = ZonedDateTime.of(2026, 11, 11, 3, 0, 0, 0, Z);
        assertEquals(ZonedDateTime.of(2026, 11, 11, 4, 0, 0, 0, Z), s.next(before));
        assertEquals(ZonedDateTime.of(2026, 11, 12, 4, 0, 0, 0, Z), s.next(before.withHour(4)));
    }

    @Test
    void givenWeeklyAndEvery_whenNext_thenRightDay() {
        ZonedDateTime wed = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, Z);
        assertEquals(DayOfWeek.MONDAY, ResetSchedule.parse("weekly mon 04:00").next(wed).getDayOfWeek());
        ZonedDateTime every = ResetSchedule.parse("every 3 days 04:00").next(wed);
        assertEquals(0, Math.floorMod(every.toLocalDate().toEpochDay(), 3));
        assertTrue(every.isAfter(wed));
        assertNull(ResetSchedule.OFF.next(wed));
    }

    @Test
    void givenBackups_whenPruned_thenNewestKept() throws Exception {
        for (String stamp : new String[] {"2026-11-01_040000-1111", "2026-11-02_040000-2222", "2026-11-03_040000-3333", "2026-11-04_040000-4444"})
            Files.createDirectories(folder.resolve("farm-regenerated-" + stamp).resolve("region"));
        Files.createDirectories(folder.resolve("other-regenerated-2026-11-01_040000-1111"));
        assertEquals(2, WorldResets.pruneBackups(folder, "farm", 2));
        assertTrue(Files.exists(folder.resolve("farm-regenerated-2026-11-04_040000-4444")));
        assertFalse(Files.exists(folder.resolve("farm-regenerated-2026-11-01_040000-1111")));
        assertTrue(Files.exists(folder.resolve("other-regenerated-2026-11-01_040000-1111")));
    }
}
