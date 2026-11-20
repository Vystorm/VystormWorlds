package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core._uiMethods.settings.SettingPolicy;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Records what a plugin's switch declaration hands to Core, with Core 0.66.0 semantics: {@code label(...)} and a
 * {@code feature(owner, path, label, desc)} with any non-null text set BOTH texts of the path, so a null label in a
 * later call drops an earlier label (the last call decides).
 */
final class RecordingConfigEditor implements ConfigEditorInterface {
    static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");
    private static final Pattern REF = Pattern.compile("\\{lang:[^:{}]+:([^{}]+)}");

    final Set<String> features = new LinkedHashSet<>();
    final Set<String> notFeatures = new HashSet<>();
    final Map<String, String> labels = new HashMap<>();
    final Map<String, String> descriptions = new HashMap<>();

    /** A Lang mock whose {@code ref} answers exactly like Core's {@code Lang.ref}. */
    static Lang lang(String namespace) {
        Lang lang = mock(Lang.class);
        when(lang.ref(anyString())).thenAnswer(call -> "{lang:" + namespace + ":" + call.getArgument(0) + "}");
        when(lang.namespace()).thenReturn(namespace);
        return lang;
    }

    @Override public void feature(Plugin owner, String path) { features.add(path); }

    @Override public void feature(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        features.add(path);
        if (labelOrLangRef != null || descriptionRef != null) label(owner, path, labelOrLangRef, descriptionRef);
    }

    @Override public void label(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        labels.put(path, labelOrLangRef);
        descriptions.put(path, descriptionRef);
    }

    @Override public void notFeature(Plugin owner, String path) { notFeatures.add(path); }
    @Override public void activation(Plugin owner, String path, SettingPolicy.Activation activation) {}
    @Override public void range(Plugin owner, String path, double min, double max, double step) {}
    @Override public void choices(Plugin owner, String path, LinkedHashMap<String, String> choices) {}
    @Override public void hidden(Plugin owner, String path) {}
    @Override public boolean register(Plugin owner) { return true; }
    @Override public boolean register(Plugin owner, String file, Runnable reload) { return true; }
    @Override public boolean register(Plugin owner, String file, Runnable reload, boolean mergeDefaults) { return true; }
    @Override public int registerAll(Plugin owner, Runnable reload) { return 0; }
    @Override public int registerAll(Plugin owner, Runnable reload, boolean mergeConfig) { return 0; }
    @Override public void reloadHook(Plugin owner, Runnable reload) {}

    /** Every switch path Core lists for this config: declared ones plus its {@code enabled} keys and top-level booleans. */
    Set<String> listed(File configYml) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configYml);
        Set<String> listed = new TreeSet<>(features);
        for (String path : config.getKeys(true)) {
            if (!config.isBoolean(path)) continue;
            boolean byName = path.equals("enabled") || path.endsWith(".enabled") || path.indexOf('.') < 0 && !NOT_FEATURES.contains(path);
            if (byName) listed.add(path);
        }
        listed.removeAll(notFeatures);
        return listed;
    }

    /** Asserts that every listed switch got a label and a description ref whose key is non-blank in all seven languages. */
    void assertEverySwitchNamed(File configYml, File langDir) {
        Map<String, YamlConfiguration> languages = new LinkedHashMap<>();
        for (String code : LANGUAGES) languages.put(code, YamlConfiguration.loadConfiguration(new File(langDir, code + ".yml")));
        List<String> wrong = new ArrayList<>();
        Set<String> listed = listed(configYml);
        assertFalse(listed.isEmpty(), "no switches listed");
        for (String path : listed) {
            check(wrong, path, "label", labels.get(path), languages);
            check(wrong, path, "description", descriptions.get(path), languages);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    private static void check(List<String> wrong, String path, String what, String ref, Map<String, YamlConfiguration> languages) {
        Matcher m = ref == null ? null : REF.matcher(ref);
        if (m == null || !m.matches()) { wrong.add(path + ": " + what + " Core received is not a lang ref: " + ref); return; }
        for (var language : languages.entrySet())
            if (language.getValue().getString(m.group(1), "").isBlank()) wrong.add(path + ": " + what + " key " + m.group(1) + " blank in " + language.getKey());
    }
}
