package at.esoren.vystormworlds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Turns a {@link GeneratorSpec} into a generator: presets registered through {@link VystormWorldsApi}, or any
 * installed generator plugin ({@code Plugin[:id]}). A spec that cannot be resolved is an error, never vanilla terrain.
 */
final class Generators {
    /** The resolved generator and biome provider, or an error text (then both are null). */
    record Resolved(ChunkGenerator generator, BiomeProvider biomes, String error) {
        boolean failed() { return error != null; }
    }

    /** A generator offered in the picker: {@code value} is what gets stored, {@code label} what is shown. */
    record Choice(String value, String label, boolean preset) {}

    private final PluginManager plugins;
    private final Plugin self;

    Generators(PluginManager plugins, Plugin self) { this.plugins = plugins; this.self = self; }

    Resolved resolve(GeneratorSpec spec, String biomeProvider, String world) {
        ChunkGenerator generator = null;
        BiomeProvider biomes = null;
        switch (spec.type()) {
            case NONE -> { }
            case PRESET -> {
                var preset = VystormWorldsApi.generator(spec.id()).orElse(null);
                if (preset == null || !preset.owner().isEnabled()) return new Resolved(null, null, "generator preset " + spec.id() + " is not registered (plugin missing?)");
                try {
                    generator = preset.generator().apply(world);
                    biomes = preset.biomes() == null ? null : preset.biomes().apply(world);
                } catch (RuntimeException | LinkageError failure) {
                    return new Resolved(null, null, "generator preset " + spec.id() + " failed: " + failure);
                }
                if (generator == null) return new Resolved(null, null, "generator preset " + spec.id() + " returned no generator");
            }
            case PLUGIN -> {
                Plugin plugin = plugin(spec.plugin());
                if (plugin == null) return new Resolved(null, null, "generator plugin " + spec.plugin() + " is not installed or not enabled");
                try {
                    generator = plugin.getDefaultWorldGenerator(world, spec.idOrNull());
                } catch (RuntimeException | LinkageError failure) {
                    return new Resolved(null, null, "generator plugin " + plugin.getName() + " failed: " + failure);
                }
                if (generator == null) return new Resolved(null, null, plugin.getName() + " has no world generator" + (spec.id().isEmpty() ? "" : " for id " + spec.id()));
                try { biomes = plugin.getDefaultBiomeProvider(world, spec.idOrNull()); } catch (RuntimeException | LinkageError ignored) { biomes = null; }
            }
        }
        if (biomeProvider != null && !biomeProvider.isBlank()) {
            GeneratorSpec biomeSpec;
            try { biomeSpec = GeneratorSpec.parse(biomeProvider); } catch (IllegalArgumentException bad) { return new Resolved(null, null, "biome provider: " + bad.getMessage()); }
            if (biomeSpec.type() == GeneratorSpec.Type.PLUGIN) {
                Plugin plugin = plugin(biomeSpec.plugin());
                if (plugin == null) return new Resolved(null, null, "biome provider plugin " + biomeSpec.plugin() + " is not installed or not enabled");
                try { biomes = plugin.getDefaultBiomeProvider(world, biomeSpec.idOrNull()); }
                catch (RuntimeException | LinkageError failure) { return new Resolved(null, null, "biome provider " + biomeSpec + " failed: " + failure); }
                if (biomes == null) return new Resolved(null, null, plugin.getName() + " has no biome provider");
            }
        }
        return new Resolved(generator, biomes, null);
    }

    /** Enabled plugin by name, ignoring case. */
    Plugin plugin(String name) {
        Plugin exact = plugins.getPlugin(name);
        if (exact != null) return exact.isEnabled() ? exact : null;
        for (Plugin plugin : plugins.getPlugins()) if (plugin.getName().equalsIgnoreCase(name)) return plugin.isEnabled() ? plugin : null;
        return null;
    }

    /** True if the plugin overrides {@code getDefaultWorldGenerator} (so it can generate worlds). No generator is created. */
    static boolean providesGenerator(Plugin plugin) {
        try {
            return plugin instanceof JavaPlugin && plugin.getClass().getMethod("getDefaultWorldGenerator", String.class, String.class).getDeclaringClass() != JavaPlugin.class;
        } catch (NoSuchMethodException | RuntimeException | LinkageError unknown) { return false; }
    }

    /** Presets first, then enabled plugins that provide a generator. */
    List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        for (var preset : VystormWorldsApi.generators()) if (preset.owner().isEnabled()) list.add(new Choice(GeneratorSpec.PRESET_PREFIX + preset.id(), preset.label(), true));
        for (Plugin plugin : plugins.getPlugins())
            if (plugin != self && plugin.isEnabled() && providesGenerator(plugin)) list.add(new Choice(plugin.getName(), plugin.getName(), false));
        return list;
    }

    /** {@code [generator, biome-provider]} configured for a world in bukkit.yml ({@code worlds.<name>.generator}), empty strings if none. */
    static String[] bukkitYml(Path file, String world) {
        if (!Files.isRegularFile(file)) return new String[] {"", ""};
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (IOException | InvalidConfigurationException unreadable) { return new String[] {"", ""}; }
        var worlds = yaml.getConfigurationSection("worlds");
        if (worlds == null) return new String[] {"", ""};
        for (String key : worlds.getKeys(false)) {
            if (!key.equalsIgnoreCase(world)) continue;
            return new String[] {worlds.getString(key + ".generator", ""), worlds.getString(key + ".biome-provider", "")};
        }
        return new String[] {"", ""};
    }

    /**
     * Sets (or with an empty generator removes) {@code worlds.<world>.generator} in bukkit.yml. A copy of the old file
     * is kept as {@code bukkit.yml.vystormworlds-backup}. Paper reads it at the next start for worlds the server creates.
     */
    static void writeBukkitYml(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files, Path file, String world, String generator) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(file)) {
            try { yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
            catch (InvalidConfigurationException broken) { throw new IOException("bukkit.yml is not valid YAML: " + broken.getMessage()); }
            Files.copy(file, file.resolveSibling(file.getFileName() + ".vystormworlds-backup"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (generator == null || generator.isBlank()) {
            yaml.set("worlds." + world + ".generator", null);
            var section = yaml.getConfigurationSection("worlds." + world);
            if (section != null && section.getKeys(false).isEmpty()) yaml.set("worlds." + world, null);
        } else yaml.set("worlds." + world + ".generator", generator);
        files.write(file, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    /** Whether the plugin is enabled before the server creates its worlds ({@code load: STARTUP}). */
    static boolean loadsAtStartup(Plugin plugin) {
        try { return plugin.getDescription().getLoad() == org.bukkit.plugin.PluginLoadOrder.STARTUP; }
        catch (RuntimeException | LinkageError unknown) { return false; }
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
