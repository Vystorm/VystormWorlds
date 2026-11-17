package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangTest {
    private static YamlConfiguration load(String name) { return YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + name)); }

    @Test
    void englishAndGermanHaveTheSameKeys() {
        assertEquals(load("en.yml").getKeys(true), load("de.yml").getKeys(true));
    }

    @Test
    void chineseHasTheEnglishKeysAndPlaceholders() {
        YamlConfiguration en = load("en.yml");
        assertEquals(en.getKeys(true), load("zh_cn.yml").getKeys(true));
        Pattern placeholder = Pattern.compile("\\{[a-z0-9_-]+}");
        for (String file : new String[] {"de.yml", "zh_cn.yml"}) {
            YamlConfiguration other = load(file);
            for (String key : en.getKeys(true)) {
                if (en.isConfigurationSection(key)) continue;
                Set<String> a = new TreeSet<>(), b = new TreeSet<>();
                placeholder.matcher(en.getString(key, "")).results().forEach(m -> a.add(m.group()));
                placeholder.matcher(other.getString(key, "")).results().forEach(m -> b.add(m.group()));
                assertEquals(a, b, file + " " + key);
            }
        }
    }

    @Test
    void noRawArgumentTags() {
        Pattern tag = Pattern.compile("<(?!/?(gray|gold|yellow|green|red|white|dark_gray)>)[a-z_-]+>");
        for (String file : new String[] {"en.yml", "de.yml", "zh_cn.yml"}) {
            YamlConfiguration lang = load(file);
            for (String key : lang.getKeys(true)) {
                String text = lang.getString(key, "");
                assertFalse(tag.matcher(text).find(), file + " " + key + ": " + text);
            }
        }
    }

    /** Every literal key the code sends exists in en.yml (dynamic keys are checked by their known suffixes). */
    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        YamlConfiguration en = load("en.yml");
        Set<String> missing = new TreeSet<>();
        // Stop at a nested call: UI IDs and property values are not translation keys.
        // Match ctx.text too, so the panel's actual language keys remain covered.
        Pattern call = Pattern.compile("(?:send|component|plain|lines|ref|actionBar|(?<!Ui\\.)item|(?<!Ui\\.)toggle|explain|ctx\\.text)\\([^;\"()]*?\"([a-z][a-z0-9-]*)\"");
        Pattern returned = Pattern.compile("(?:return|accept\\(|fail\\(|Parsed\\(null,) ?\"([a-z]+(?:-[a-z]+)+)\"");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (Pattern p : List.of(call, returned)) {
                    Matcher m = p.matcher(source);
                    while (m.find()) {
                        String key = m.group(1);
                        if (key.endsWith("-") || Set.of("world", "name", "key", "token", "price", "value", "type", "count", "group", "players", "label",
                                "sub", "error", "target", "player", "seed", "extra", "min", "max", "node", "mode", "limit", "state", "title", "hidden",
                                "chunks", "entities", "tiles", "tps", "generator", "height", "properties", "separate", "worlds", "keys", "x", "y", "z").contains(key)) continue;
                        if (Set.of("none", "vanilla", "once", "every-visit", "custom build heights need", "load-failed-text").contains(key)) continue;
                        if (!en.contains(key)) missing.add(key + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        for (String suffix : List.of("loaded", "unloaded", "busy", "error")) if (!en.contains("list-entry-" + suffix)) missing.add("list-entry-" + suffix);
        for (WorldKind kind : WorldKind.values()) if (!en.contains("kind-" + kind.id())) missing.add("kind-" + kind.id());
        for (String mode : List.of("survival", "creative", "adventure", "spectator")) if (!en.contains("mode-" + mode)) missing.add("mode-" + mode);
        for (String mode : WorldSettings.PRICE_MODES) if (!en.contains("selector-price-" + mode)) missing.add("selector-price-" + mode);
        for (String key : List.of("display-name", "description", "price", "player-limit", "group", "respawn-world", "nether", "end"))
            if (!en.contains("prompt-" + key)) missing.add("prompt-" + key);
        for (String key : Properties.KEYS) {
            String label = "w-" + (key.equals("keep-spawn-loaded") ? "keep-spawn" : key.equals("remember-position") ? "remember" : key);
            if (!en.contains(label)) missing.add(label);
        }
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }

    @Test
    void configHasDefaultsWithCommentsAndNoBareSwitchKeys() throws IOException {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(config.getBoolean("autoload"));
        assertEquals("default", config.getString("default-group"));
        assertEquals(List.of("inventory", "ender-chest", "experience", "health", "hunger", "effects"), config.getStringList("groups.separate"));
        assertEquals(8, config.getInt("teleport.safe-radius"));
        assertEquals("vanilla", config.getString("portals.unlinked"));
        assertTrue(config.getBoolean("delete.backup"));
        for (String key : config.getKeys(true)) {
            String last = key.substring(key.lastIndexOf('.') + 1);
            assertFalse(Set.of("on", "off", "yes", "no").contains(last), key);
        }
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/config.yml"));
        List<String> uncommented = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#") || !line.contains(":")) continue;
            boolean section = line.strip().endsWith(":");
            if (section && !line.startsWith(" ")) continue;
            String previous = i > 0 ? lines.get(i - 1).strip() : "";
            if (!previous.startsWith("#") && !section) uncommented.add(line.strip());
        }
        assertTrue(uncommented.isEmpty(), "config keys without comment: " + uncommented);
    }

    @Test
    void pluginYmlDeclaresThePermissionsButNotTheCoreCommand() {
        YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        assertEquals(List.of("vystorm_core"), plugin.getStringList("depend"));
        assertFalse(plugin.contains("commands.worlds"), "/worlds is registered through Vystorm Core");
        assertFalse(WorldsCommand.commandSpec(null).aliases().contains("vworlds"), "/vworlds belongs to Vystorm Core");
        for (String node : List.of("use", "admin", "bypass", "bypass.gamemode", "enter.*"))
            assertTrue(plugin.contains("permissions.vystormworlds." + node.replace(".", "\u0000").replace("\u0000", ".")) || plugin.getConfigurationSection("permissions").getKeys(false).contains("vystormworlds." + node), node);
    }
}
