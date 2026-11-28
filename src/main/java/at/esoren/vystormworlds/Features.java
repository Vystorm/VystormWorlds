package at.esoren.vystormworlds;

import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Feature switches in config.yml ({@code <feature>.enabled}, shown by Vystorm Core as feature switches). Read from the
 * live config on every use, so a reload applies them. A missing key counts as on; switching a feature off never
 * deletes its saved data.
 */
final class Features {
    static final String RESETS = "reset.enabled";
    static final String BORDER_EVENTS = "border-events.enabled";
    static final String TEMPLATES = "templates.enabled";
    static final String BUILD_HEIGHT = "build-height.enabled";
    // 1.17.0
    /** Creating, importing, loading, unloading, cloning, regenerating and deleting worlds. */
    static final String MANAGEMENT = "management.enabled";
    /** World selector and travel commands for players. */
    static final String SELECTOR = "selector.enabled";
    static final String FAVORITES = "favorites.enabled";
    static final String SAFE_SPOT = "teleport.safe-spot.enabled";
    static final String PORTALS = "portals.enabled";
    static final String BORDER = "border.enabled";
    /** Game mode, difficulty, PvP, mobs, hunger, flying, time and weather locks, spawn chunks. */
    static final String WORLD_RULES = "world-rules.enabled";
    /** Entry permission, price and player limit. */
    static final String ENTRY_RULES = "entry-rules.enabled";
    static final String ADMIN_MENU = "admin-menu.enabled";
    static final String TUTORIAL = "tutorial.enabled";
    /** 1.19.0: the ranking worlds:explored (needs Core 0.67.0) and counting the worlds players enter for it. */
    static final String RANKINGS = "rankings.enabled";
    /** 1.21.0: one line per admin action in plugins/VystormWorlds/log/. */
    static final String AUDIT = "audit.enabled";
    /** Language key of the reply when a switched-off feature is used. */
    static final String OFF = "feature-off";
    /** Language key of the reply when a feature needs another switch that is off ({switch} = its key). */
    static final String NEEDS = "feature-needs";

    /** Every {@code .enabled} switch, in the order of config.yml. */
    static final java.util.List<String> SWITCHES = java.util.List.of(MANAGEMENT, "groups.enabled", SELECTOR, FAVORITES,
            SAFE_SPOT, PORTALS, WORLD_RULES, ENTRY_RULES, BORDER, BORDER_EVENTS, RESETS, TEMPLATES, BUILD_HEIGHT, ADMIN_MENU, AUDIT, RANKINGS, TUTORIAL);
    /** Top-level booleans Core lists as switches by itself. */
    static final java.util.List<String> TOP_LEVEL = java.util.List.of("autoload", "adopt-loaded-worlds", "arrival-title");
    /** Booleans with another name, declared as switches (their keys stay as they are). */
    static final java.util.List<String> DECLARED = java.util.List.of("groups.notify", "teleport.return-to-last-position",
            "portals.sync-vystorm-portals", "respawn.same-world", "delete.backup", "selector.show-locked");

    /** {@code groups.enabled} -> {@code features.groups}: the language key of a switch description. */
    static String descriptionKey(String path) {
        String subject = path.endsWith(".enabled") ? path.substring(0, path.length() - ".enabled".length()) : path;
        return "features." + subject.replace('.', '-');
    }

    /** {@code groups.enabled} -> {@code feature-names.groups}: the language key of a switch's readable name. */
    static String labelKey(String path) {
        return "feature-names." + descriptionKey(path).substring("features.".length());
    }

    private Features() { }

    static boolean on(ConfigurationSection config, String key) { return config == null || config.getBoolean(key, true); }

    /** The switch a world property belongs to, or null for properties without one. */
    static String switchOf(String property) {
        return switch (property == null ? "" : property.toLowerCase(Locale.ROOT)) {
            case "reset", "reset-seed" -> RESETS;
            case "border-event" -> BORDER_EVENTS;
            default -> null;
        };
    }

    /**
     * True when {@code property} of {@code w} may not be changed to {@code value} because its feature is switched off.
     * Writing the value the world already has stays allowed, so dialogs that save every field keep working.
     */
    static boolean blocks(ConfigurationSection config, WorldSettings w, String property, String value) {
        String key = switchOf(property);
        if (key == null || on(config, key)) return false;
        String current = Properties.get(w, property.toLowerCase(Locale.ROOT));
        return value == null || !value.strip().equalsIgnoreCase(current);
    }
}
