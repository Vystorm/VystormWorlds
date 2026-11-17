package at.esoren.vystormworlds;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * worlds.yml, the source of truth for every world Vystorm Worlds manages. Written atomically after every change. An
 * unreadable file is never overwritten: the registry then stays read-only until an admin fixes the file and reloads.
 */
final class WorldRegistry {
    static final int FORMAT = 1;

    private final Path file;
    private final Map<String, WorldSettings> worlds = new LinkedHashMap<>();
    private String error;
    private final List<String> problems = new ArrayList<>();

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;

    /** @param files Core's file part, {@code api.data().files()} */
    WorldRegistry(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this.file = file; this.files = files; }

    /** Loads the file; returns false (and keeps the registry empty and read-only) if it cannot be read. */
    boolean load() {
        worlds.clear();
        problems.clear();
        error = null;
        if (!Files.exists(file)) return true;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException broken) {
            error = broken.getMessage() == null ? broken.getClass().getSimpleName() : broken.getMessage().lines().findFirst().orElse("");
            return false;
        }
        int format = yaml.getInt("format", FORMAT);
        if (format > FORMAT) {
            error = "worlds.yml format " + format + " is newer than this plugin (" + FORMAT + ")";
            return false;
        }
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section != null) for (String name : section.getKeys(false)) {
            if (!WorldSettings.validName(name)) { problems.add(name + ": invalid world name"); continue; }
            WorldSettings settings = WorldSettings.read(name, section.getConfigurationSection(name));
            for (String problem : settings.problems) problems.add(name + " " + problem);
            worlds.put(key(name), settings);
        }
        return true;
    }

    /** Writes worlds.yml; refuses (returns false) while the file on disk could not be read. */
    boolean save() throws IOException {
        if (error != null) return false;
        files.write(file, render().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    String render() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Vystorm Worlds: one entry per world. Edit in game with /worlds edit <world> or the world menu.",
                "Hand edits: stop the server or run /worlds reload afterwards."));
        yaml.set("format", FORMAT);
        ConfigurationSection section = yaml.createSection("worlds");
        for (WorldSettings w : worlds.values()) w.write(section.createSection(w.name));
        return yaml.saveToString();
    }

    static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    WorldSettings get(String name) { return name == null ? null : worlds.get(key(name)); }

    boolean contains(String name) { return get(name) != null; }

    void put(WorldSettings settings) { worlds.put(key(settings.name), settings); }

    WorldSettings remove(String name) { return worlds.remove(key(name)); }

    Collection<WorldSettings> all() { return Collections.unmodifiableCollection(worlds.values()); }

    /** Read error of the last load, or null. */
    String error() { return error; }

    List<String> problems() { return List.copyOf(problems); }

    /** All group names in use, "default" first. */
    List<String> groups(String defaultGroup) {
        List<String> groups = new ArrayList<>();
        groups.add(defaultGroup);
        for (WorldSettings w : worlds.values()) if (!groups.contains(w.group)) groups.add(w.group);
        return groups;
    }

    /** The overworld whose nether or end link points at {@code name} (or the world itself when nothing links to it). */
    WorldSettings family(String name) {
        WorldSettings self = get(name);
        for (WorldSettings w : worlds.values())
            if (w.nether.equalsIgnoreCase(name) || w.end.equalsIgnoreCase(name)) return w;
        return self;
    }
}
