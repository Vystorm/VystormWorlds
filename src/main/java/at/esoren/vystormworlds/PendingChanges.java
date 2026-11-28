package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Changes to one world's settings collected in the world page before one "Apply" (1.21.0). Pure: every change is checked
 * on a copy of the settings with {@link Properties#set}, the same check {@code /worlds set} runs, so the menu never
 * accepts more than the command, and each change has its exact command for "copy as command".
 */
final class PendingChanges {
    /** One pending change: the property, its value now and the value after Apply (both as {@link Properties#get} shows them). */
    record Change(String key, String before, String after) {
        String command(String world) { return PendingChanges.command(world, key, after); }
    }

    /** A refused change: the language key ({@code bad-value}, {@code unknown-world}, {@link Features#OFF} ...) and the property. */
    record Problem(String error, String key, String value) {}

    private PendingChanges() {}

    /** A detached copy of the settings (through the worlds.yml form, so nothing is shared with the live entry). */
    static WorldSettings copy(WorldSettings w) {
        YamlConfiguration yaml = new YamlConfiguration();
        w.write(yaml);
        WorldSettings copy = WorldSettings.read(w.name, yaml);
        copy.serverWorld = w.serverWorld;
        return copy;
    }

    /**
     * Checks one more change against the world with the changes already pending; null when it can be added, else the
     * problem. A property whose feature is switched off refuses a new value as {@code /worlds set} does.
     */
    static Problem check(WorldSettings w, Map<String, String> pending, String key, String value, ConfigurationSection config,
                         Predicate<String> worldExists) {
        if (!Properties.KEYS.contains(key)) return new Problem("unknown-property", key, value);
        if (Features.blocks(config, w, key, value)) return new Problem(Features.OFF, key, value);
        WorldSettings copy = copy(w);
        for (var entry : pending.entrySet()) Properties.set(copy, entry.getKey(), entry.getValue(), worldExists);
        String error = Properties.set(copy, key, value, worldExists);
        return error == null ? null : new Problem(error, key, value);
    }

    /**
     * Adds or replaces a pending change; a value that equals the world's current value removes the entry again, so the
     * count on "Apply" only shows real changes.
     */
    static Map<String, String> put(WorldSettings w, Map<String, String> pending, String key, String value, Predicate<String> worldExists) {
        Map<String, String> next = new LinkedHashMap<>(pending);
        next.remove(key);
        WorldSettings copy = copy(w);
        if (Properties.set(copy, key, value, worldExists) == null && Properties.get(copy, key).equals(Properties.get(w, key))) return next;
        next.put(key, value == null ? "" : value.strip());
        return next;
    }

    /** The real changes, in the order they were made, with the values as the world page shows them. */
    static List<Change> changes(WorldSettings w, Map<String, String> pending, Predicate<String> worldExists) {
        List<Change> out = new ArrayList<>();
        WorldSettings copy = copy(w);
        for (var entry : pending.entrySet()) {
            if (Properties.set(copy, entry.getKey(), entry.getValue(), worldExists) != null) continue;
            String before = Properties.get(w, entry.getKey()), after = Properties.get(copy, entry.getKey());
            if (!before.equals(after)) out.add(new Change(entry.getKey(), before, after));
        }
        return out;
    }

    /** The command for one property: {@code /worlds set farm pvp false}; an empty value clears it ({@code none}). */
    static String command(String world, String key, String value) {
        String v = value == null || value.isBlank() ? "none" : value.strip();
        if (key.equals("display-name") || key.equals("description")) v = value == null ? "" : value.strip();
        return ("/worlds set " + world + " " + key + " " + v).stripTrailing();
    }

    static List<String> commands(String world, List<Change> changes) {
        return changes.stream().map(c -> c.command(world)).toList();
    }

    /**
     * One text for the confirm and the pending line, each change written by {@code line} (the page passes its language
     * text "{key}: {before} to {after}"; screens have no arrow glyph), joined with "; ".
     */
    static String summary(List<Change> changes, java.util.function.Function<Change, String> line) {
        StringBuilder text = new StringBuilder();
        for (Change c : changes) {
            if (!text.isEmpty()) text.append("; ");
            text.append(line.apply(c));
        }
        return text.toString();
    }

    static String show(String value) { return value == null || value.isEmpty() ? "-" : value; }
}
