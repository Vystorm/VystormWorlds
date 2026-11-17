package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything Vystorm Worlds knows about one world; one entry in worlds.yml. Mutable, server thread only. Invalid values
 * in the file fall back to defaults and are reported through {@link #problems}.
 */
final class WorldSettings {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,40}");
    static final Pattern GROUP = Pattern.compile("[a-z0-9_\\-]{1,32}");
    static final List<String> GAME_MODES = List.of("", "survival", "creative", "adventure", "spectator");
    static final List<String> DIFFICULTIES = List.of("", "peaceful", "easy", "normal", "hard");
    static final List<String> TIME_LOCKS = List.of("off", "day", "noon", "sunset", "night", "midnight");
    static final List<String> WEATHER_LOCKS = List.of("off", "clear", "rain", "thunder");
    static final List<String> PRICE_MODES = List.of("once", "every-visit");

    record Spawn(double x, double y, double z, float yaw, float pitch) {
        String text() { return String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch); }

        static Spawn parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String[] p = raw.strip().split("[ ,;]+");
            if (p.length < 3) throw new IllegalArgumentException("spawn needs x y z");
            double x = Double.parseDouble(p[0]), y = Double.parseDouble(p[1]), z = Double.parseDouble(p[2]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("spawn not finite");
            return new Spawn(x, y, z, p.length > 3 ? Float.parseFloat(p[3]) : 0f, p.length > 4 ? Float.parseFloat(p[4]) : 0f);
        }
    }

    final String name;
    String displayName = "";
    String description = "";
    String icon = "";
    WorldKind kind = WorldKind.NORMAL;
    Long seed;
    GeneratorSpec generator = GeneratorSpec.NONE;
    String biomeProvider = "";
    boolean structures = true;
    boolean autoload = true;
    boolean serverWorld;
    boolean hidden;
    Spawn spawn;
    String gameMode = "";
    String difficulty = "";
    boolean pvp = true, monsters = true, animals = true, hunger = true, fly, keepSpawnLoaded;
    String timeLock = "off";
    String weatherLock = "off";
    String respawnWorld = "";
    boolean permission;
    double price;
    String priceMode = "once";
    int playerLimit;
    String group = "default";
    String nether = "", end = "";
    boolean rememberPosition = true;
    /** Automatic reset schedule ({@link ResetSchedule}), "off" = none; seed for it: "same" or "random". */
    String reset = "off", resetSeed = "same";
    /** World border size in blocks (0 = leave the border alone), its centre ("spawn" or "x z") and the shrinking event schedule. */
    int border;
    String borderCenter = "spawn", borderEvent = "off";
    /** Custom build height (data pack dimension type); null = vanilla height. Only set when the world is created. */
    Integer minY, height;
    final List<String> problems = new ArrayList<>();

    WorldSettings(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid world name: " + name);
        this.name = name;
    }

    /** Worlds with a custom build height are created by the server from the Vystorm Worlds data pack. */
    boolean customHeight() { return minY != null && height != null; }

    static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    static boolean validGroup(String group) { return group != null && GROUP.matcher(group).matches(); }

    /** The configured display name (MiniMessage) or the world name. */
    String title() { return displayName.isBlank() ? name : displayName; }

    // ------------------------------------------------------------------ yaml

    void write(ConfigurationSection s) {
        s.set("display-name", displayName);
        s.set("description", description);
        s.set("icon", icon);
        s.set("type", kind.id());
        s.set("seed", seed == null ? "" : seed.toString());
        s.set("generator", generator.toString());
        s.set("biome-provider", biomeProvider);
        s.set("structures", structures);
        s.set("autoload", autoload);
        s.set("server-world", serverWorld);
        s.set("hidden", hidden);
        s.set("spawn", spawn == null ? "" : spawn.text());
        s.set("game-mode", gameMode);
        s.set("difficulty", difficulty);
        s.set("pvp", pvp);
        s.set("monsters", monsters);
        s.set("animals", animals);
        s.set("hunger", hunger);
        s.set("fly", fly);
        s.set("keep-spawn-loaded", keepSpawnLoaded);
        s.set("time-lock", timeLock);
        s.set("weather-lock", weatherLock);
        s.set("respawn-world", respawnWorld);
        s.set("entry.permission", permission);
        s.set("entry.price", price);
        s.set("entry.price-mode", priceMode);
        s.set("entry.player-limit", playerLimit);
        s.set("group", group);
        s.set("links.nether", nether);
        s.set("links.end", end);
        s.set("remember-position", rememberPosition);
        s.set("reset.schedule", reset.equals("off") ? null : reset);
        s.set("reset.seed", reset.equals("off") ? null : resetSeed);
        s.set("border.size", border > 0 ? border : null);
        s.set("border.center", border > 0 && !borderCenter.equals("spawn") ? borderCenter : null);
        s.set("border.event", border > 0 && !borderEvent.equals("off") ? borderEvent : null);
        s.set("build-height", null);
        if (customHeight()) { s.set("build-height.min-y", minY); s.set("build-height.height", height); }
    }

    static WorldSettings read(String name, ConfigurationSection s) {
        WorldSettings w = new WorldSettings(name);
        if (s == null) return w;
        w.displayName = s.getString("display-name", "");
        w.description = s.getString("description", "");
        w.icon = s.getString("icon", "");
        WorldKind kind = WorldKind.parse(s.getString("type", "normal"));
        if (kind == null) w.problems.add("type: " + s.getString("type"));
        else w.kind = kind;
        String seed = s.getString("seed", "");
        if (seed != null && !seed.isBlank()) {
            try { w.seed = Long.parseLong(seed.strip()); } catch (NumberFormatException bad) { w.problems.add("seed: " + seed); }
        }
        try { w.generator = GeneratorSpec.parse(s.getString("generator", "")); }
        catch (IllegalArgumentException bad) {
            // never drop a generator silently: keep the raw text so the world is refused instead of loaded as vanilla
            w.generator = new GeneratorSpec(GeneratorSpec.Type.PLUGIN, "<invalid>", s.getString("generator", ""));
            w.problems.add("generator: " + bad.getMessage());
        }
        w.biomeProvider = s.getString("biome-provider", "");
        w.structures = s.getBoolean("structures", true);
        w.autoload = s.getBoolean("autoload", true);
        w.serverWorld = s.getBoolean("server-world", false);
        w.hidden = s.getBoolean("hidden", false);
        try { w.spawn = Spawn.parse(s.getString("spawn", "")); } catch (RuntimeException bad) { w.problems.add("spawn: " + s.getString("spawn")); }
        w.gameMode = choice(w, "game-mode", s.getString("game-mode", ""), GAME_MODES, "");
        w.difficulty = choice(w, "difficulty", s.getString("difficulty", ""), DIFFICULTIES, "");
        w.pvp = s.getBoolean("pvp", true);
        w.monsters = s.getBoolean("monsters", true);
        w.animals = s.getBoolean("animals", true);
        w.hunger = s.getBoolean("hunger", true);
        w.fly = s.getBoolean("fly", false);
        w.keepSpawnLoaded = s.getBoolean("keep-spawn-loaded", false);
        w.timeLock = timeLock(w, s.getString("time-lock", "off"));
        w.weatherLock = choice(w, "weather-lock", s.getString("weather-lock", "off"), WEATHER_LOCKS, "off");
        w.respawnWorld = s.getString("respawn-world", "");
        w.permission = s.getBoolean("entry.permission", false);
        double price = s.getDouble("entry.price", 0);
        if (!Double.isFinite(price) || price < 0) { w.problems.add("entry.price: " + price); price = 0; }
        w.price = price;
        w.priceMode = choice(w, "entry.price-mode", s.getString("entry.price-mode", "once"), PRICE_MODES, "once");
        w.playerLimit = Math.max(0, s.getInt("entry.player-limit", 0));
        String group = s.getString("group", "default");
        if (validGroup(group)) w.group = group;
        else w.problems.add("group: " + group);
        w.nether = s.getString("links.nether", "");
        w.end = s.getString("links.end", "");
        w.rememberPosition = s.getBoolean("remember-position", true);
        ResetSchedule schedule = ResetSchedule.parse(s.getString("reset.schedule", "off"));
        if (schedule == null) w.problems.add("reset.schedule: " + s.getString("reset.schedule"));
        else w.reset = schedule.text();
        String resetSeedText = s.getString("reset.seed", "same");
        w.resetSeed = "random".equalsIgnoreCase(resetSeedText) ? "random" : "same";
        int borderSize = s.getInt("border.size", 0);
        if (borderSize != 0 && (borderSize < 16 || borderSize > 59_999_968)) w.problems.add("border.size: " + borderSize); else w.border = borderSize;
        String center = s.getString("border.center", "spawn");
        if (BorderEvents.center(center, null) == null) w.problems.add("border.center: " + center); else w.borderCenter = center.strip().equalsIgnoreCase("spawn") ? "spawn" : center.strip();
        ResetSchedule borderSchedule = ResetSchedule.parse(s.getString("border.event", "off"));
        if (borderSchedule == null) w.problems.add("border.event: " + s.getString("border.event")); else w.borderEvent = borderSchedule.text();
        if (s.isInt("build-height.min-y") && s.isInt("build-height.height")) { w.minY = s.getInt("build-height.min-y"); w.height = s.getInt("build-height.height"); }
        else if (s.contains("build-height")) w.problems.add("build-height: needs min-y and height");
        return w;
    }

    private static String choice(WorldSettings w, String key, String value, List<String> allowed, String fallback) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (allowed.contains(v)) return v;
        w.problems.add(key + ": " + value);
        return fallback;
    }

    private static String timeLock(WorldSettings w, String value) {
        String v = value == null ? "off" : value.strip().toLowerCase(Locale.ROOT);
        if (TIME_LOCKS.contains(v) || lockTicks(v) >= 0) return v;
        w.problems.add("time-lock: " + value);
        return "off";
    }

    /** Ticks for a time lock ({@code day}, {@code night}, ... or a number 0-23999); -1 = no lock / invalid. */
    static long lockTicks(String lock) {
        if (lock == null) return -1;
        return switch (lock) {
            case "day" -> 1000;
            case "noon" -> 6000;
            case "sunset" -> 12000;
            case "night" -> 13000;
            case "midnight" -> 18000;
            case "off", "" -> -1;
            default -> {
                try {
                    long t = Long.parseLong(lock);
                    yield t >= 0 && t < 24000 ? t : -1;
                } catch (NumberFormatException bad) { yield -1; }
            }
        };
    }

    /** Next value in a list of options (for menu buttons that cycle). */
    static String next(List<String> options, String current) {
        int index = options.indexOf(current);
        return options.get((index + 1) % options.size());
    }
}
