package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml with a YAML error stays byte-identical; a reload keeps the last good settings and names the position. */
class BrokenYamlTest {
    private static final String BROKEN = "# my worlds\nselector:\n  enabled: false\nthis line has no colon\nteleport:\n  enabled: true\n";
    @TempDir Path dir;

    private WorldsPlugin plugin(AtomicReference<FileConfiguration> config) throws Exception {
        WorldsPlugin plugin = mock(WorldsPlugin.class, CALLS_REAL_METHODS);
        File configFile = dir.resolve("config.yml").toFile();
        doReturn(dir.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getLogger("BrokenYamlTest")).when(plugin).getLogger();
        doAnswer(call -> config.get()).when(plugin).getConfig();
        doAnswer(call -> { config.set(bukkitLoad(configFile)); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { config.get().save(configFile); return null; }).when(plugin).saveConfig();
        WorldRegistry registry = mock(WorldRegistry.class);
        when(registry.load()).thenReturn(true);
        var field = WorldsPlugin.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(plugin, registry);
        return plugin;
    }

    /** Bukkit's YamlConfiguration.loadConfiguration without a server: a parse error only gives an empty config. */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (Exception logged) { /* Bukkit logs it and returns what it has */ }
        return yaml;
    }

    @Test void givenABrokenConfig_whenTheStartReadsIt_thenTheFileIsByteIdentical() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        WorldsPlugin plugin = plugin(new AtomicReference<>(new YamlConfiguration()));
        assertFalse(plugin.readConfig());
        verify(plugin, never()).saveConfig();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
    }

    @Test void givenABrokenConfig_whenReloaded_thenTheLastGoodSettingsStayAndTheProblemNamesLineAndColumn() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        YamlConfiguration good = new YamlConfiguration();
        good.set(Features.FAVORITES, false);
        WorldsPlugin plugin = plugin(new AtomicReference<>(good));
        plugin.reload();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
        verify(plugin, never()).reloadConfig();
        assertFalse(plugin.getConfig().getBoolean(Features.FAVORITES, true), "last good settings");
        assertEquals("line 5, column 1: could not find expected ':'", plugin.configProblem());
    }
}
