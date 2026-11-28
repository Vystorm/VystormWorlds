package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The settings Vystorm Worlds manages for a world, as one short line for the world list (1.21.0): only what differs
 * from a plain vanilla world, e.g. "Noon, Clear, Border 2000, Creative, PvP off, Hidden". Pure.
 */
final class WorldBadges {
    private WorldBadges() {}

    /** @param text language lookup (key -> plain text) */
    static String summary(WorldSettings w, Function<String, String> text) {
        List<String> parts = new ArrayList<>();
        if (!"off".equals(w.timeLock)) parts.add(WorldSettings.TIME_LOCKS.contains(w.timeLock) ? text.apply("panel-option-" + w.timeLock) : text.apply("w-time-lock") + " " + w.timeLock);
        if (!"off".equals(w.weatherLock)) parts.add(text.apply("panel-option-" + w.weatherLock));
        if (!w.gameMode.isEmpty()) parts.add(text.apply("mode-" + w.gameMode));
        if (!w.difficulty.isEmpty()) parts.add(text.apply("panel-option-" + w.difficulty));
        if (w.border > 0) parts.add(text.apply("badge-border") + " " + w.border);
        if (!w.pvp) parts.add(text.apply("badge-no-pvp"));
        if (w.fly) parts.add(text.apply("badge-fly"));
        if (w.hidden) parts.add(text.apply("badge-hidden"));
        if (w.permission) parts.add(text.apply("badge-permission"));
        if (w.price > 0) parts.add(text.apply("badge-price"));
        if (w.playerLimit > 0) parts.add(text.apply("badge-limit") + " " + w.playerLimit);
        if (!"off".equals(w.reset)) parts.add(text.apply("badge-reset"));
        if (w.customHeight()) parts.add(text.apply("badge-height"));
        return String.join(", ", parts);
    }
}
