package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

/**
 * Public API of Vystorm Worlds. Add {@code softdepend: [VystormWorlds]} to your plugin.yml and call it from
 * {@code onEnable} (server thread).
 *
 * <pre>{@code
 * if (getServer().getPluginManager().isPluginEnabled("VystormWorlds"))
 *     VystormWorldsApi.registerGenerator(this, "myplugin:islands", "Floating islands",
 *             worldName -> new IslandGenerator(), worldName -> new IslandBiomes());
 * }</pre>
 *
 * Registered presets appear in the generator picker of the create-world dialog, in tab completion and can be typed as
 * {@code preset:myplugin:islands}. Worlds created with a preset store that name in worlds.yml; if the preset is missing
 * when the world should load (plugin removed), the world is not loaded instead of being generated as vanilla terrain.
 * Presets of a plugin are removed automatically when it is disabled.
 */
public final class VystormWorldsApi {
    /** A registered generator preset. {@code biomes} may return null. */
    public record GeneratorPreset(Plugin owner, String id, String label, Function<String, ChunkGenerator> generator,
                                  Function<String, BiomeProvider> biomes) {}

    private static final Map<String, GeneratorPreset> PRESETS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile WorldsPlugin plugin;

    private VystormWorldsApi() {}

    static void attach(WorldsPlugin instance) { plugin = instance; }

    static void detach() { plugin = null; }

    /**
     * Registers (or replaces, for the same owner) a named generator preset.
     *
     * @param id        lower case {@code namespace:name}, e.g. {@code worldforge:fantasy}
     * @param label     name shown in the picker (plain text)
     * @param generator creates the generator for a world name; called for every load of such a world
     * @param biomes    optional biome provider per world name, or null
     * @throws IllegalArgumentException for a malformed id or an id owned by another plugin
     */
    public static void registerGenerator(Plugin owner, String id, String label, Function<String, ChunkGenerator> generator,
                                         Function<String, BiomeProvider> biomes) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(generator, "generator");
        String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (!GeneratorSpec.PRESET_ID.matcher(key).matches()) throw new IllegalArgumentException("preset id must look like plugin:name: " + id);
        synchronized (PRESETS) {
            GeneratorPreset existing = PRESETS.get(key);
            if (existing != null && existing.owner() != owner) throw new IllegalArgumentException("preset " + key + " belongs to " + existing.owner().getName());
            PRESETS.put(key, new GeneratorPreset(owner, key, label == null || label.isBlank() ? key : label, generator, biomes));
        }
    }

    public static boolean unregisterGenerator(Plugin owner, String id) {
        synchronized (PRESETS) {
            GeneratorPreset existing = PRESETS.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
            if (existing == null || existing.owner() != owner) return false;
            PRESETS.remove(existing.id());
            return true;
        }
    }

    public static List<GeneratorPreset> generators() {
        synchronized (PRESETS) { return List.copyOf(PRESETS.values()); }
    }

    public static Optional<GeneratorPreset> generator(String id) {
        return Optional.ofNullable(PRESETS.get(id == null ? "" : id.toLowerCase(Locale.ROOT)));
    }

    /** Removes every preset of a plugin (called when it is disabled). */
    static void release(Plugin owner) {
        synchronized (PRESETS) { PRESETS.values().removeIf(p -> p.owner() == owner); }
    }

    /** Whether Vystorm Worlds manages this world (it has an entry in worlds.yml). */
    public static boolean managed(World world) {
        WorldsPlugin p = plugin;
        return p != null && world != null && p.settings(world) != null;
    }

    /** The inventory group of a world, or empty if it is not managed. */
    public static Optional<String> group(World world) {
        WorldsPlugin p = plugin;
        if (p == null || world == null) return Optional.empty();
        WorldSettings settings = p.settings(world);
        return settings == null ? Optional.empty() : Optional.of(settings.group);
    }

    /** Names of all managed worlds, loaded or not. */
    public static List<String> worlds() {
        WorldsPlugin p = plugin;
        if (p == null) return List.of();
        List<String> names = new ArrayList<>();
        for (WorldSettings w : p.registry().all()) names.add(w.name);
        return names;
    }
}
