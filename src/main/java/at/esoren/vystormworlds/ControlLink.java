package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.PanelOpenResult;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The soft link to Vystorm Control's game rule page (1.21.0). Vystorm Worlds has no game rule editor of its own: the
 * world page shows a "Game rules" tile only while the plugin {@value #PLUGIN} is enabled, and the tile opens Control's
 * panel {@code vystormcontrol:gamerules} (Core panel id = plugin name in lower case + ":" + panel name) with the world's
 * name as the panel argument. Control's own access rule decides who sees it; without Control nothing is shown.
 */
final class ControlLink {
    static final String PLUGIN = "VystormControl";
    static final String PANEL = "gamerules";
    /** The agreed id, for logs and the report. */
    static final String ID = "vystormcontrol:" + PANEL;

    private ControlLink() {}

    static Plugin control(WorldsPlugin plugin) {
        Plugin control = plugin.getServer().getPluginManager().getPlugin(PLUGIN);
        return control != null && control.isEnabled() ? control : null;
    }

    static boolean available(WorldsPlugin plugin) {
        try { return control(plugin) != null; } catch (RuntimeException none) { return false; }
    }

    /** Opens Control's game rules of {@code world}; false when Control is missing or its panel did not open. */
    static boolean open(WorldsPlugin plugin, Player player, String world) {
        Plugin control = control(plugin);
        if (control == null) return false;
        try {
            return plugin.core().gui().panels().of(control).open(player, PANEL, world) != PanelOpenResult.NONE;
        } catch (RuntimeException failure) {
            plugin.getLogger().fine("Vystorm Control's " + ID + " did not open: " + failure);
            return false;
        }
    }
}
