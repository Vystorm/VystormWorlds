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
    /** Language key of the reply when a switched-off feature is used. */
    static final String OFF = "feature-off";

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
