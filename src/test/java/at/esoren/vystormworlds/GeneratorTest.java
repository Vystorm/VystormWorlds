package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorTest {
    @TempDir Path dir;

    /** A plugin stub: name, enabled flag and an optional generator. */
    static Plugin plugin(String name, boolean enabled, ChunkGenerator generator) {
        return (Plugin) Proxy.newProxyInstance(GeneratorTest.class.getClassLoader(), new Class<?>[] {Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            case "getDefaultWorldGenerator" -> generator;
            case "getDefaultBiomeProvider" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Plugin[" + name + "]";
            default -> null;
        });
    }

    static PluginManager manager(Plugin... plugins) {
        return (PluginManager) Proxy.newProxyInstance(GeneratorTest.class.getClassLoader(), new Class<?>[] {PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getPlugin" -> { for (Plugin p : plugins) if (p.getName().equals(args[0])) yield p; yield null; }
            case "getPlugins" -> plugins;
            default -> null;
        });
    }

    @AfterEach
    void clearPresets() {
        for (var preset : VystormWorldsApi.generators()) VystormWorldsApi.release(preset.owner());
    }

    @Test
    void parsesPluginAndIdLikeMultiverse() {
        assertTrue(GeneratorSpec.parse("").none());
        assertTrue(GeneratorSpec.parse(null).none());
        assertTrue(GeneratorSpec.parse("vanilla").none());
        GeneratorSpec plain = GeneratorSpec.parse("TerraformGenerator");
        assertEquals(GeneratorSpec.Type.PLUGIN, plain.type());
        assertEquals("TerraformGenerator", plain.plugin());
        assertNull(plain.idOrNull());
        GeneratorSpec withId = GeneratorSpec.parse(" Iris:overworld ");
        assertEquals("Iris", withId.plugin());
        assertEquals("overworld", withId.id());
        GeneratorSpec withParams = GeneratorSpec.parse("CleanroomGenerator:64|stone|dirt:grass");
        assertEquals("CleanroomGenerator", withParams.plugin());
        assertEquals("64|stone|dirt:grass", withParams.id());
        assertEquals("CleanroomGenerator:64|stone|dirt:grass", withParams.toString());
    }

    @Test
    void parsesPresetsAndRejectsGarbage() {
        GeneratorSpec preset = GeneratorSpec.parse("preset:WorldForge:Fantasy");
        assertEquals(GeneratorSpec.Type.PRESET, preset.type());
        assertEquals("worldforge:fantasy", preset.id());
        assertEquals("preset:worldforge:fantasy", preset.toString());
        assertEquals(preset, GeneratorSpec.parse(preset.toString()));
        assertThrows(IllegalArgumentException.class, () -> GeneratorSpec.parse("Bad Plugin:id"));
        assertThrows(IllegalArgumentException.class, () -> GeneratorSpec.parse("Plugin:"));
        assertThrows(IllegalArgumentException.class, () -> GeneratorSpec.parse("preset:nocolon"));
    }

    @Test
    void missingGeneratorPluginIsAnErrorNotVanilla() {
        Generators generators = new Generators(manager(), null);
        Generators.Resolved resolved = generators.resolve(GeneratorSpec.parse("Iris:overworld"), "", "w");
        assertTrue(resolved.failed());
        assertNull(resolved.generator());
        assertTrue(resolved.error().contains("Iris"));
    }

    @Test
    void disabledPluginOrPluginWithoutGeneratorIsRefused() {
        Generators generators = new Generators(manager(plugin("Iris", false, new ChunkGenerator() {}), plugin("Empty", true, null)), null);
        assertTrue(generators.resolve(GeneratorSpec.parse("Iris"), "", "w").failed());
        assertTrue(generators.resolve(GeneratorSpec.parse("Empty:x"), "", "w").failed());
    }

    @Test
    void installedPluginGeneratorIsUsedCaseInsensitive() {
        ChunkGenerator generator = new ChunkGenerator() {};
        Generators generators = new Generators(manager(plugin("TerraGen", true, generator)), null);
        Generators.Resolved resolved = generators.resolve(GeneratorSpec.parse("terragen:hills"), "", "w");
        assertFalse(resolved.failed());
        assertSame(generator, resolved.generator());
    }

    @Test
    void presetsResolveAndAreRemovedWithTheirPlugin() {
        Plugin owner = plugin("WorldForge", true, null);
        ChunkGenerator generator = new ChunkGenerator() {};
        VystormWorldsApi.registerGenerator(owner, "worldforge:fantasy", "Fantasy", name -> generator, null);
        Generators generators = new Generators(manager(owner), null);
        assertSame(generator, generators.resolve(GeneratorSpec.preset("worldforge:fantasy"), "", "w").generator());
        assertEquals(1, generators.choices().stream().filter(Generators.Choice::preset).count());
        assertThrows(IllegalArgumentException.class, () -> VystormWorldsApi.registerGenerator(plugin("Other", true, null), "worldforge:fantasy", "x", n -> generator, null));
        assertThrows(IllegalArgumentException.class, () -> VystormWorldsApi.registerGenerator(owner, "no-namespace", "x", n -> generator, null));
        VystormWorldsApi.release(owner);
        assertTrue(generators.resolve(GeneratorSpec.preset("worldforge:fantasy"), "", "w").failed());
    }

    @Test
    void generatorSurvivesWorldsYmlRoundTripAndBrokenTextIsKept() throws Exception {
        WorldRegistry registry = new WorldRegistry(dir.resolve("worlds.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(registry.load());
        WorldSettings w = new WorldSettings("islands");
        w.generator = GeneratorSpec.parse("Iris:overworld");
        w.biomeProvider = "Iris:overworld";
        registry.put(w);
        assertTrue(registry.save());
        WorldRegistry again = new WorldRegistry(dir.resolve("worlds.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(again.load());
        assertEquals("Iris:overworld", again.get("islands").generator.toString());
        assertEquals("Iris:overworld", again.get("islands").biomeProvider);

        Files.writeString(dir.resolve("worlds.yml"), "format: 1\nworlds:\n  odd:\n    generator: 'Bad Name:x'\n", StandardCharsets.UTF_8);
        assertTrue(again.load());
        WorldSettings odd = again.get("odd");
        assertFalse(odd.generator.none(), "an unreadable generator must never turn into vanilla");
        Generators generators = new Generators(manager(), null);
        assertTrue(generators.resolve(odd.generator, "", "odd").failed());
        assertFalse(again.problems().isEmpty());
    }

    @Test
    void readsAndWritesBukkitYml() throws Exception {
        Path bukkit = dir.resolve("bukkit.yml");
        Files.writeString(bukkit, "# comment\nsettings:\n  allow-end: true\nworlds:\n  Skylands:\n    generator: CleanroomGenerator:.\n    biome-provider: Bio\n", StandardCharsets.UTF_8);
        String[] found = Generators.bukkitYml(bukkit, "skylands");
        assertEquals("CleanroomGenerator:.", found[0]);
        assertEquals("Bio", found[1]);
        assertEquals("", Generators.bukkitYml(bukkit, "other")[0]);
        assertEquals("", Generators.bukkitYml(dir.resolve("missing.yml"), "x")[0]);

        Generators.writeBukkitYml(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, bukkit, "world_vystormworlds_tall", "Iris:overworld");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(bukkit.toFile());
        assertEquals("Iris:overworld", yaml.getString("worlds.world_vystormworlds_tall.generator"));
        assertTrue(yaml.getBoolean("settings.allow-end"));
        assertTrue(Files.exists(dir.resolve("bukkit.yml.vystormworlds-backup")));
        Generators.writeBukkitYml(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, bukkit, "world_vystormworlds_tall", "");
        assertNull(YamlConfiguration.loadConfiguration(bukkit.toFile()).get("worlds.world_vystormworlds_tall"));
        assertEquals(Map.of(), Map.of());
    }

    @Test
    void probingDoesNotCreateGenerators() {
        assertFalse(Generators.providesGenerator(plugin("Proxy", true, null)));
        assertNotNull(new Generators(manager(), null).choices());
    }
}
