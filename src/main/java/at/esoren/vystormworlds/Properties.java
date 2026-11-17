package at.esoren.vystormworlds;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.bukkit.Material;

/**
 * The editable world properties by name, shared by {@code /worlds set}, the world menu and the settings dialog.
 * {@link #set} validates and changes the settings object only; the caller saves and applies.
 */
final class Properties {
    static final List<String> KEYS = List.of("display-name", "description", "icon", "game-mode", "difficulty", "pvp", "monsters", "animals",
            "hunger", "fly", "keep-spawn-loaded", "autoload", "hidden", "time-lock", "weather-lock", "respawn-world", "permission", "price",
            "price-mode", "player-limit", "group", "nether", "end", "remember-position", "reset", "reset-seed", "border", "border-center", "border-event");

    /** Values offered by tab completion. */
    static List<String> suggestions(String key) {
        return switch (key) {
            case "game-mode" -> List.of("none", "survival", "creative", "adventure", "spectator");
            case "difficulty" -> List.of("none", "peaceful", "easy", "normal", "hard");
            case "time-lock" -> List.of("off", "day", "noon", "sunset", "night", "midnight", "6000");
            case "weather-lock" -> WorldSettings.WEATHER_LOCKS;
            case "price-mode" -> WorldSettings.PRICE_MODES;
            case "pvp", "monsters", "animals", "hunger", "fly", "keep-spawn-loaded", "autoload", "hidden", "permission", "remember-position" -> List.of("true", "false");
            case "respawn-world", "nether", "end" -> List.of("none");
            case "reset" -> List.of("off", "daily 04:00", "weekly mon 04:00", "every 3 days 04:00");
            case "reset-seed" -> List.of("same", "random");
            case "border" -> List.of("off", "1000", "2000", "5000");
            case "border-center" -> List.of("spawn", "0 0");
            case "border-event" -> List.of("off", "daily 20:00", "weekly sat 18:00");
            case "icon" -> List.of("grass_block", "netherrack", "end_stone", "diamond_pickaxe", "map");
            default -> List.of();
        };
    }

    private Properties() {}

    /**
     * @param worldExists checks other world names (links, respawn world)
     * @return null on success, else a lang key ({@code bad-value}, {@code unknown-property}, {@code unknown-world}, ...)
     */
    static String set(WorldSettings w, String key, String raw, Predicate<String> worldExists) {
        String value = raw == null ? "" : raw.strip();
        String lower = value.toLowerCase(Locale.ROOT);
        boolean none = lower.isEmpty() || lower.equals("none") || lower.equals("off") && !key.endsWith("-lock");
        switch (key) {
            case "display-name" -> { if (value.length() > 64) return "bad-value"; w.displayName = value; }
            case "description" -> { if (value.length() > 120) return "bad-value"; w.description = value; }
            case "icon" -> {
                if (none) { w.icon = ""; return null; }
                Material material = Material.matchMaterial(value);
                if (material == null || !material.isItem() || material.isAir()) return "bad-value";
                w.icon = material.name().toLowerCase(Locale.ROOT);
            }
            case "game-mode" -> { String v = none ? "" : lower; if (!WorldSettings.GAME_MODES.contains(v)) return "bad-value"; w.gameMode = v; }
            case "difficulty" -> { String v = none ? "" : lower; if (!WorldSettings.DIFFICULTIES.contains(v)) return "bad-value"; w.difficulty = v; }
            case "pvp", "monsters", "animals", "hunger", "fly", "keep-spawn-loaded", "autoload", "hidden", "permission", "remember-position" -> {
                Boolean b = bool(lower);
                if (b == null) return "bad-value";
                switch (key) {
                    case "pvp" -> w.pvp = b;
                    case "monsters" -> w.monsters = b;
                    case "animals" -> w.animals = b;
                    case "hunger" -> w.hunger = b;
                    case "fly" -> w.fly = b;
                    case "keep-spawn-loaded" -> w.keepSpawnLoaded = b;
                    case "autoload" -> w.autoload = b;
                    case "hidden" -> w.hidden = b;
                    case "permission" -> w.permission = b;
                    default -> w.rememberPosition = b;
                }
            }
            case "time-lock" -> { String v = lower.isEmpty() ? "off" : lower; if (!WorldSettings.TIME_LOCKS.contains(v) && WorldSettings.lockTicks(v) < 0) return "bad-value"; w.timeLock = v; }
            case "weather-lock" -> { String v = lower.isEmpty() ? "off" : lower; if (!WorldSettings.WEATHER_LOCKS.contains(v)) return "bad-value"; w.weatherLock = v; }
            case "respawn-world" -> {
                if (none) { w.respawnWorld = ""; return null; }
                if (lower.equals("vanilla")) { w.respawnWorld = "vanilla"; return null; }
                if (!worldExists.test(value)) return "unknown-world";
                w.respawnWorld = value;
            }
            case "nether", "end" -> {
                if (none) { if (key.equals("nether")) w.nether = ""; else w.end = ""; return null; }
                if (value.equalsIgnoreCase(w.name) || !worldExists.test(value)) return "unknown-world";
                if (key.equals("nether")) w.nether = value; else w.end = value;
            }
            case "price" -> {
                try {
                    double price = Double.parseDouble(value.replace(',', '.'));
                    if (!Double.isFinite(price) || price < 0 || price > 1e12) return "bad-value";
                    w.price = price;
                } catch (NumberFormatException bad) { return "bad-value"; }
            }
            case "price-mode" -> { if (!WorldSettings.PRICE_MODES.contains(lower)) return "bad-value"; w.priceMode = lower; }
            case "player-limit" -> {
                try {
                    int limit = none ? 0 : Integer.parseInt(value);
                    if (limit < 0 || limit > 10000) return "bad-value";
                    w.playerLimit = limit;
                } catch (NumberFormatException bad) { return "bad-value"; }
            }
            case "group" -> { if (!WorldSettings.validGroup(lower)) return "bad-group"; w.group = lower; }
            case "reset" -> { ResetSchedule s = ResetSchedule.parse(value); if (s == null) return "bad-reset"; if (w.serverWorld && s.kind() != ResetSchedule.Kind.OFF) return "server-world"; w.reset = s.text(); }
            case "reset-seed" -> { if (!lower.equals("same") && !lower.equals("random")) return "bad-value"; w.resetSeed = lower; }
            case "border" -> {
                if (none) { w.border = 0; return null; }
                try {
                    int size = Integer.parseInt(value.replace("_", ""));
                    if (size < 16 || size > 59_999_968) return "bad-value";
                    w.border = size;
                } catch (NumberFormatException bad) { return "bad-value"; }
            }
            case "border-center" -> {
                if (lower.isEmpty() || lower.equals("spawn")) { w.borderCenter = "spawn"; return null; }
                if (BorderEvents.center(value, null) == null) return "bad-value";
                w.borderCenter = value;
            }
            case "border-event" -> { ResetSchedule s = ResetSchedule.parse(value); if (s == null) return "bad-reset"; w.borderEvent = s.text(); }
            default -> { return "unknown-property"; }
        }
        return null;
    }

    static Boolean bool(String value) {
        return switch (value) {
            case "true", "yes", "on", "1", "ja", "an" -> true;
            case "false", "no", "off", "0", "nein", "aus" -> false;
            default -> null;
        };
    }

    /** Current value as text (for the property list and the settings dialog). */
    static String get(WorldSettings w, String key) {
        return switch (key) {
            case "display-name" -> w.displayName;
            case "description" -> w.description;
            case "icon" -> w.icon;
            case "game-mode" -> w.gameMode.isEmpty() ? "none" : w.gameMode;
            case "difficulty" -> w.difficulty.isEmpty() ? "none" : w.difficulty;
            case "pvp" -> Boolean.toString(w.pvp);
            case "monsters" -> Boolean.toString(w.monsters);
            case "animals" -> Boolean.toString(w.animals);
            case "hunger" -> Boolean.toString(w.hunger);
            case "fly" -> Boolean.toString(w.fly);
            case "keep-spawn-loaded" -> Boolean.toString(w.keepSpawnLoaded);
            case "autoload" -> Boolean.toString(w.autoload);
            case "hidden" -> Boolean.toString(w.hidden);
            case "time-lock" -> w.timeLock;
            case "weather-lock" -> w.weatherLock;
            case "respawn-world" -> w.respawnWorld.isEmpty() ? "none" : w.respawnWorld;
            case "permission" -> Boolean.toString(w.permission);
            case "price" -> String.format(Locale.ROOT, "%.2f", w.price);
            case "price-mode" -> w.priceMode;
            case "player-limit" -> Integer.toString(w.playerLimit);
            case "group" -> w.group;
            case "nether" -> w.nether.isEmpty() ? "none" : w.nether;
            case "end" -> w.end.isEmpty() ? "none" : w.end;
            case "remember-position" -> Boolean.toString(w.rememberPosition);
            case "reset" -> w.reset;
            case "reset-seed" -> w.resetSeed;
            case "border" -> w.border > 0 ? Integer.toString(w.border) : "off";
            case "border-center" -> w.borderCenter;
            case "border-event" -> w.borderEvent;
            default -> "";
        };
    }
}
