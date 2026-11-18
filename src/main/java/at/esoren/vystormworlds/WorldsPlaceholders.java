package at.esoren.vystormworlds;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core.lang.Lang;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@code worlds} placeholders (1.17.0, Vystorm Core 0.65.0): {@code %vystorm_worlds_world%},
 * {@code {worlds:world_players:lobby}} and so on. Plain values; a value whose feature is switched off is empty.
 * They read loaded worlds and players, so they answer on the server thread only.
 */
final class WorldsPlaceholders {
    static final String NAMESPACE = "worlds";
    static final List<String> KEYS = List.of("world", "world_name", "world_group", "world_kind", "world_players",
            "world_limit", "world_next_reset", "worlds_count", "worlds_loaded", "in_visit");
    private static final DateTimeFormatter RESET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** What the values read, looked up at call time. */
    interface Source {
        WorldSettings settings(World world);
        String group(World world);
        boolean groupsOn();
        boolean resetsOn();
        boolean templatesOn();
        boolean entryRulesOn();
        ZonedDateTime nextReset(String world);
        Collection<WorldSettings> all();
        World loaded(String name);
        boolean inVisit(Player player);
        /** Display name without colours. */
        default String title(WorldSettings settings) { return Menus.plain(settings); }
    }

    private WorldsPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, Source s) {
        PlaceholderNamespace ns = placeholders.register(owner, NAMESPACE);
        add(ns, lang, "world", PlaceholderValue.player("world", p -> {
            WorldSettings w = s.settings(p.getWorld());
            return w == null ? p.getWorld().getName() : s.title(w);
        }));
        add(ns, lang, "world_name", PlaceholderValue.player("world_name", p -> p.getWorld().getName()));
        add(ns, lang, "world_group", PlaceholderValue.player("world_group", p -> !s.groupsOn() ? "" : s.group(p.getWorld())));
        add(ns, lang, "world_kind", PlaceholderValue.player("world_kind", p -> {
            WorldSettings w = s.settings(p.getWorld());
            return w == null ? WorldKind.of(p.getWorld().getEnvironment()).id() : w.kind.id();
        }));
        // without an argument the viewer's world, with one the named world: %vystorm_worlds_world_players_lobby%
        add(ns, lang, "world_players", PlaceholderValue.of("world_players", context -> {
            String name = context.rest(0);
            if (name == null || name.isBlank()) {
                Player p = context.player();
                return p == null ? null : p.getWorld().getPlayers().size();
            }
            World world = s.loaded(name);
            return world == null ? "" : world.getPlayers().size();
        }).arguments("[world]"));
        add(ns, lang, "world_limit", PlaceholderValue.player("world_limit", p -> {
            if (!s.entryRulesOn()) return "";
            WorldSettings w = s.settings(p.getWorld());
            return w == null ? 0 : w.playerLimit;
        }));
        add(ns, lang, "world_next_reset", PlaceholderValue.player("world_next_reset", p -> {
            if (!s.resetsOn()) return "";
            WorldSettings w = s.settings(p.getWorld());
            ZonedDateTime next = w == null ? null : s.nextReset(w.name);
            return next == null ? "" : RESET.format(next);
        }));
        add(ns, lang, "worlds_count", PlaceholderValue.global("worlds_count", () -> s.all().size()));
        add(ns, lang, "worlds_loaded", PlaceholderValue.global("worlds_loaded", () -> s.all().stream().filter(w -> s.loaded(w.name) != null).count()));
        add(ns, lang, "in_visit", PlaceholderValue.player("in_visit", p -> !s.templatesOn() ? "" : s.inVisit(p)));
        return ns;
    }

    private static void add(PlaceholderNamespace ns, Lang lang, String descriptionKey, PlaceholderValue value) {
        ns.add(lang == null ? value : value.description(lang.ref("placeholder." + descriptionKey)));
    }
}
