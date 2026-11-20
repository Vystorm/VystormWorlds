package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * All properties of one world as a Vystorm Core settings dialog (same look as {@code /vadmin config}). One section
 * edits the world the admin chose with {@code /worlds edit <world>} or the world menu (else the world they stand in).
 * Loaded only after the Core capability check.
 */
final class WorldSection {
    static final String ID = "world";
    private static final Map<UUID, String> SELECTED = new HashMap<>();
    private static final List<String> TOGGLES = List.of("pvp", "monsters", "animals", "hunger", "fly", "keep-spawn-loaded", "autoload", "hidden", "permission", "remember-position");
    private static final Map<String, List<String>> CHOICES = Map.of(
            "game-mode", List.of("none", "survival", "creative", "adventure", "spectator"),
            "difficulty", List.of("none", "peaceful", "easy", "normal", "hard"),
            "time-lock", WorldSettings.TIME_LOCKS, "weather-lock", WorldSettings.WEATHER_LOCKS, "price-mode", WorldSettings.PRICE_MODES);

    private WorldSection() {}

    private static WorldSettings selected(WorldsPlugin plugin, Player player) {
        String name = SELECTED.get(player.getUniqueId());
        WorldSettings w = name == null ? null : plugin.registry().get(name);
        return w != null ? w : plugin.settings(player.getWorld());
    }

    static void register(WorldsPlugin plugin) {
        var lang = plugin.lang();
        List<Setting> settings = new ArrayList<>();
        for (String key : Properties.KEYS) {
            String name = "w-" + (key.equals("keep-spawn-loaded") ? "keep-spawn" : key.equals("remember-position") ? "remember" : key);
            String label = lang.ref(name), description = lang.ref(name + "-desc");
            if (TOGGLES.contains(key)) {
                settings.add(Setting.toggle(key, label, description, p -> {
                    WorldSettings w = selected(plugin, p);
                    return w != null && Boolean.parseBoolean(Properties.get(w, key));
                }, (p, value) -> apply(plugin, p, key, Boolean.toString(value))));
            } else if (CHOICES.containsKey(key)) {
                LinkedHashMap<String, String> options = new LinkedHashMap<>();
                for (String option : CHOICES.get(key)) options.put(option, option);
                settings.add(Setting.choice(key, label, description, options, p -> {
                    WorldSettings w = selected(plugin, p);
                    String value = w == null ? CHOICES.get(key).getFirst() : Properties.get(w, key);
                    return options.containsKey(value) ? value : CHOICES.get(key).getFirst();
                }, (p, value) -> apply(plugin, p, key, value)));
            } else {
                settings.add(Setting.text(key, label, description, key.equals("description") ? 120 : 64, p -> {
                    WorldSettings w = selected(plugin, p);
                    String value = w == null ? "" : Properties.get(w, key);
                    return value.equals("none") ? "" : value;
                }, (p, value) -> apply(plugin, p, key, value)));
            }
        }
        plugin.core().settings().register(plugin, SettingsSection.server(ID, lang.ref("section-title"), lang.ref("section-desc"),
                "vystormworlds.admin", settings, p -> {
                    WorldSettings w = selected(plugin, p);
                    if (w != null) {
                        plugin.menus().actions().changed(w, true);
                        plugin.lang().send(p, "section-saved", "world", w.name);
                    }
                }));
    }

    private static String apply(WorldsPlugin plugin, Player player, String key, String value) {
        WorldSettings w = selected(plugin, player);
        if (w == null) return plugin.lang().ref("section-no-world");
        if (Features.blocks(plugin.getConfig(), w, key, value)) return plugin.lang().ref(Features.OFF);
        String error = Properties.set(w, key, value, name -> plugin.registry().contains(name) || WorldManager.loaded(name) != null);
        return error == null ? null : plugin.lang().ref(error);
    }

    static void open(WorldsPlugin plugin, Player player, String world) {
        SELECTED.put(player.getUniqueId(), world);
        plugin.lang().send(player, "section-editing", "world", world);
        try {
            if (!plugin.core().settings().openSection(player, plugin.getName().toLowerCase(java.util.Locale.ROOT) + ":" + ID))
                plugin.menus().world(player, world);
        } catch (RuntimeException failure) { plugin.menus().world(player, world); }
    }
}
