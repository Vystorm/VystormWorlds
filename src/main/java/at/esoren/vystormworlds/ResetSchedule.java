package at.esoren.vystormworlds;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * When a world is reset automatically ({@code reset} world property): {@code off}, {@code daily HH:MM},
 * {@code weekly DAY HH:MM} (mon…sun) or {@code every N days HH:MM} (N 2-90, counted from 1970-01-01 so the days are
 * the same after restarts). Times are server local time. Pure value type.
 */
record ResetSchedule(Kind kind, int days, DayOfWeek day, LocalTime time) {
    enum Kind { OFF, DAILY, WEEKLY, EVERY }

    static final ResetSchedule OFF = new ResetSchedule(Kind.OFF, 0, null, null);

    /** Parses a schedule; null when the text is not valid. */
    static ResetSchedule parse(String raw) {
        if (raw == null) return OFF;
        String[] p = raw.strip().toLowerCase(Locale.ROOT).split("\\s+");
        try {
            if (p.length == 1 && (p[0].isEmpty() || p[0].equals("off") || p[0].equals("none"))) return OFF;
            if (p.length == 2 && p[0].equals("daily")) return new ResetSchedule(Kind.DAILY, 1, null, LocalTime.parse(pad(p[1])));
            if (p.length == 3 && p[0].equals("weekly")) return new ResetSchedule(Kind.WEEKLY, 7, day(p[1]), LocalTime.parse(pad(p[2])));
            if (p.length == 4 && p[0].equals("every") && p[2].startsWith("day")) {
                int n = Integer.parseInt(p[1]);
                if (n < 2 || n > 90) return null;
                return new ResetSchedule(Kind.EVERY, n, null, LocalTime.parse(pad(p[3])));
            }
        } catch (RuntimeException invalid) { return null; }
        return null;
    }

    private static String pad(String time) { return time.length() == 4 && time.charAt(1) == ':' ? "0" + time : time; }

    private static DayOfWeek day(String text) {
        for (DayOfWeek d : DayOfWeek.values()) if (d.name().toLowerCase(Locale.ROOT).startsWith(text) && text.length() >= 2) return d;
        throw new IllegalArgumentException("day");
    }

    /** The next reset strictly after {@code now}, or null when off. */
    ZonedDateTime next(ZonedDateTime now) {
        if (kind == Kind.OFF) return null;
        ZonedDateTime candidate = now.toLocalDate().atTime(time).atZone(now.getZone());
        for (int i = 0; i < 400; i++) {
            if (candidate.isAfter(now) && matches(candidate.toLocalDate())) return candidate;
            candidate = candidate.toLocalDate().plusDays(1).atTime(time).atZone(now.getZone());
        }
        return null;
    }

    private boolean matches(LocalDate date) {
        return switch (kind) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == day;
            case EVERY -> Math.floorMod(date.toEpochDay(), days) == 0;
            case OFF -> false;
        };
    }

    /** The canonical text, as stored in worlds.yml. */
    String text() {
        return switch (kind) {
            case OFF -> "off";
            case DAILY -> "daily " + time;
            case WEEKLY -> "weekly " + day.name().toLowerCase(Locale.ROOT).substring(0, 3) + " " + time;
            case EVERY -> "every " + days + " days " + time;
        };
    }
}
