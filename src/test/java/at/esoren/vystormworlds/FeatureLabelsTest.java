package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@code /vadmin features} lists every boolean {@code enabled} key and every top-level boolean of config.yml, plus the
 * declared ones. Each of them must have a readable name and a description in all seven languages, so the screen never
 * falls back to a key path such as "Teleport › Return to last position".
 */
class FeatureLabelsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");

    private static boolean listedByCore(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path);
    }

    private static List<String> declared() {
        List<String> all = new ArrayList<>(Features.SWITCHES);
        all.addAll(Features.TOP_LEVEL);
        all.addAll(Features.DECLARED);
        return all;
    }

    @Test void givenEveryBooleanCoreListsAsAFeature_whenChecked_thenItIsADeclaredSwitch() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (String path : config.getKeys(true))
            if (config.isBoolean(path) && listedByCore(path)) assertTrue(declared().contains(path), "feature without a name: " + path);
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchHasANameAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String path : declared()) {
                assertFalse(lang.getString(Features.labelKey(path), "").isBlank(), code + " " + Features.labelKey(path));
                assertFalse(lang.getString(Features.descriptionKey(path), "").isBlank(), code + " " + Features.descriptionKey(path));
            }
        }
    }

    @Test void givenTheRealDeclaration_whenCoreRecordsIt_thenEverySwitchCoreListsGetsANamedLabelAndDescription() {
        RecordingConfigEditor core = new RecordingConfigEditor();
        WorldsPlugin.describeSwitches(core, mock(Plugin.class), RecordingConfigEditor.lang("vystormworlds"));
        core.assertEverySwitchNamed(new File("src/main/resources/config.yml"), new File("src/main/resources/lang"));
    }

    @Test void givenThePlugin_whenItStarts_thenCoreChecksItsSpigotPageForUpdatesLikeTheOtherPlugins() {
        Vystorm.vystorm_core.API core = mock(Vystorm.vystorm_core.API.class, RETURNS_DEEP_STUBS);
        Plugin plugin = mock(Plugin.class);
        WorldsPlugin.registerUpdateCheck(plugin, core);
        verify(core.admin().updates()).spigot(plugin, 140132);
    }
}
