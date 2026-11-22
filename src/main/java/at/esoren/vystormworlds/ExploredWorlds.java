package at.esoren.vystormworlds;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * How many different worlds each player has entered (1.19.0, for the ranking {@code worlds:explored}), kept in
 * {@code explored.yml}: per player UUID the last known name and the names of the worlds, never a position. Counting
 * starts with 1.19.0; older versions do not read the file. A change only marks the store as changed; the plugin writes
 * it at most once a minute off the server thread and once more when it stops. An unreadable file is set aside under
 * another name and never overwritten. Server thread only, except {@link #write(Snapshot)}.
 */
final class ExploredWorlds {
    static final int FORMAT = 1;

    /** One player: last known name and the worlds entered (lower case). */
    static final class Explorer {
        String name;
        final Set<String> worlds = new TreeSet<>();

        Explorer(String name) { this.name = name; }

        int count() { return worlds.size(); }
    }

    /** What {@link #write} puts on disk, taken on the server thread. */
    record Snapshot(long version, byte[] bytes) {}

    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<UUID, Explorer> players = new LinkedHashMap<>();
    private String problem;
    private long version, written;
    private boolean dirty;

    ExploredWorlds(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        load();
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException broken) {
            Path aside = files.moveAside(file);
            problem = "explored.yml could not be read and was kept as " + (aside == null ? file.getFileName() : aside.getFileName()) + ": "
                    + (broken.getMessage() == null ? broken.getClass().getSimpleName() : broken.getMessage().lines().findFirst().orElse(""))
                    + "; worlds are not counted until it is fixed and the server restarts";
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection row = section.getConfigurationSection(key);
            if (row == null) continue;
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException notAPlayer) { continue; }
            Explorer explorer = new Explorer(row.getString("name", "?"));
            for (String world : row.getStringList("worlds")) if (WorldSettings.validName(world)) explorer.worlds.add(world.toLowerCase(Locale.ROOT));
            players.put(id, explorer);
        }
    }

    /** The reason the file could not be read at start, null when it was fine or missing. */
    String problem() { return problem; }

    /** Counts {@code world} for the player; true when it is a world he had not entered before. */
    boolean record(UUID player, String name, String world) {
        if (player == null || world == null || problem != null) return false;
        Explorer explorer = players.computeIfAbsent(player, id -> new Explorer(name));
        boolean renamed = name != null && !name.equals(explorer.name);
        if (renamed) explorer.name = name;
        boolean added = explorer.worlds.add(world.toLowerCase(Locale.ROOT));
        if (added || renamed) { dirty = true; version++; }
        return added;
    }

    /** Players with their counts, most worlds first (ties keep the order in which players were first counted). */
    List<Map.Entry<UUID, Explorer>> top(int n) {
        List<Map.Entry<UUID, Explorer>> rows = new ArrayList<>(players.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<UUID, Explorer> e) -> e.getValue().count()).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    int count(UUID player) {
        Explorer explorer = players.get(player);
        return explorer == null ? 0 : explorer.count();
    }

    /** The changes to write, or null when nothing changed since the last call (server thread). */
    Snapshot takeChanges() {
        if (!dirty || problem != null) return null;
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Vystorm Worlds: different worlds each player has entered, for the ranking worlds:explored.",
                "Written by the plugin; stop the server before editing."));
        yaml.set("format", FORMAT);
        yaml.createSection("players");
        for (Map.Entry<UUID, Explorer> e : players.entrySet()) {
            String base = "players." + e.getKey();
            yaml.set(base + ".name", e.getValue().name);
            yaml.set(base + ".worlds", List.copyOf(e.getValue().worlds));
        }
        return new Snapshot(version, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a snapshot atomically; an older snapshot than one already written is skipped (any thread). */
    synchronized void write(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.version() < written) return;
        Files.createDirectories(file.getParent());
        files.write(file, snapshot.bytes());
        written = snapshot.version();
    }

    /** After a failed write: the changes are written again next time (server thread). */
    void failed() { dirty = true; }
}
