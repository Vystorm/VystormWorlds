package at.esoren.vystormworlds;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Saved group states as {@code <dir>/<uuid>/<group>.yml}. Writes are atomic (temp file, fsync, rename through Core's
 * AtomicFiles), so a crash leaves either the old or the new file. Unreadable files are moved aside as
 * {@code .broken-<millis>} and never deleted; files from a newer plugin version are left alone and refused.
 */
final class StateStore {
    static final int FORMAT = 1;

    /** Result of a load: the state (null = never saved), whether a broken file was set aside, or a refusal. */
    record Loaded(PlayerState state, boolean setAside, String refused) {}

    private final Path dir;

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;

    /** @param files Core's file part, {@code api.data().files()} */
    StateStore(Path dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this.dir = dir; this.files = files; }

    Path file(UUID player, String group) { return dir.resolve(player.toString()).resolve(group + ".yml"); }

    synchronized void save(UUID player, PlayerState state) throws IOException {
        files.write(file(player, state.group()), serialize(state).getBytes(StandardCharsets.UTF_8));
    }

    synchronized Loaded load(UUID player, String group) {
        Path path = file(player, group);
        if (!Files.isRegularFile(path)) return new Loaded(null, false, null);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException broken) {
            return unreadable(path);
        }
        int format = yaml.getInt("format", 0);
        if (format > FORMAT) return new Loaded(null, false, "format " + format + " is newer than " + FORMAT);
        try {
            return new Loaded(deserialize(yaml, group), false, null);
        } catch (RuntimeException broken) {
            return unreadable(path);
        }
    }

    /** Groups with a saved state for this player. */
    List<String> groups(UUID player) {
        Path folder = dir.resolve(player.toString());
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yml")).map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        } catch (IOException failure) { return List.of(); }
    }

    /** A broken file is set aside; if that fails the swap is refused, so the fresh state is never saved over it. */
    private static Loaded unreadable(Path path) {
        if (setAside(path)) return new Loaded(null, true, null);
        return new Loaded(null, false, path.getFileName() + " is unreadable and could not be set aside (fix or remove it)");
    }

    private static boolean setAside(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis()));
            return true;
        } catch (IOException failure) { return false; }
    }

    static String serialize(PlayerState s) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("group", s.group());
        yaml.set("saved-at", s.savedAt());
        List<String> parts = new ArrayList<>();
        for (PlayerState.Part part : s.parts()) parts.add(part.id());
        yaml.set("parts", parts);
        if (s.has(PlayerState.Part.INVENTORY)) { yaml.set("inventory", s.inventory()); yaml.set("held-slot", s.heldSlot()); }
        if (s.has(PlayerState.Part.ENDER_CHEST)) yaml.set("ender-chest", s.enderChest());
        if (s.has(PlayerState.Part.EXPERIENCE)) {
            yaml.set("experience.level", s.level());
            yaml.set("experience.progress", (double) s.progress());
            yaml.set("experience.total", s.totalExperience());
        }
        if (s.has(PlayerState.Part.HEALTH)) yaml.set("health", s.health());
        if (s.has(PlayerState.Part.HUNGER)) {
            yaml.set("food.level", s.food());
            yaml.set("food.saturation", (double) s.saturation());
            yaml.set("food.exhaustion", (double) s.exhaustion());
        }
        if (s.has(PlayerState.Part.EFFECTS)) {
            List<Map<String, Object>> effects = new ArrayList<>();
            for (PlayerState.Effect e : s.effects()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", e.type()); map.put("duration", e.duration()); map.put("amplifier", e.amplifier());
                map.put("ambient", e.ambient()); map.put("particles", e.particles()); map.put("icon", e.icon());
                effects.add(map);
            }
            yaml.set("effects", effects);
        }
        return yaml.saveToString();
    }

    static PlayerState deserialize(YamlConfiguration yaml, String group) {
        if (!yaml.contains("format") || !group.equals(yaml.getString("group"))) throw new IllegalArgumentException("not a state file of " + group);
        Set<PlayerState.Part> parts = EnumSet.noneOf(PlayerState.Part.class);
        for (String raw : yaml.getStringList("parts")) {
            PlayerState.Part part = PlayerState.Part.parse(raw);
            if (part != null) parts.add(part);
        }
        if (parts.contains(PlayerState.Part.INVENTORY) && !yaml.isString("inventory")) throw new IllegalArgumentException("inventory missing");
        if (parts.contains(PlayerState.Part.ENDER_CHEST) && !yaml.isString("ender-chest")) throw new IllegalArgumentException("ender chest missing");
        List<PlayerState.Effect> effects = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("effects")) {
            effects.add(new PlayerState.Effect(String.valueOf(m.get("type")), ((Number) m.get("duration")).intValue(), ((Number) m.get("amplifier")).intValue(),
                    Boolean.TRUE.equals(m.get("ambient")), Boolean.TRUE.equals(m.get("particles")), Boolean.TRUE.equals(m.get("icon"))));
        }
        return new PlayerState(group, yaml.getLong("saved-at"), parts, yaml.getString("inventory", ""), yaml.getInt("held-slot"),
                yaml.getString("ender-chest", ""), yaml.getInt("experience.level"), (float) yaml.getDouble("experience.progress"),
                yaml.getInt("experience.total"), yaml.getDouble("health", 20), yaml.getInt("food.level", 20),
                (float) yaml.getDouble("food.saturation", 5), (float) yaml.getDouble("food.exhaustion", 0), effects);
    }
}
