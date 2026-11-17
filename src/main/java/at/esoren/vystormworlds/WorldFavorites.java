package at.esoren.vystormworlds;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Personal selector bookmarks, stored with the player just like remembered arrival positions. */
final class WorldFavorites {
    private final NamespacedKey key;

    WorldFavorites(WorldsPlugin plugin) { key = new NamespacedKey(plugin, "world_favorites"); }

    Set<String> read(Player player) {
        Set<String> names = new TreeSet<>();
        String stored = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (stored != null) for (String name : stored.split("\n"))
            if (WorldSettings.validName(name)) names.add(name.toLowerCase(Locale.ROOT));
        return names;
    }

    boolean toggle(Player player, String world) {
        if (!WorldSettings.validName(world)) throw new IllegalArgumentException("Invalid world name");
        Set<String> names = read(player);
        String name = world.toLowerCase(Locale.ROOT);
        boolean added = names.add(name);
        if (!added) names.remove(name);
        if (names.isEmpty()) player.getPersistentDataContainer().remove(key);
        else player.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.join("\n", names));
        return added;
    }

    /** Only reorder/filter worlds already admitted by the selector's visibility rules. */
    static List<WorldSettings> arrange(List<WorldSettings> visible, Set<String> favorites, boolean onlyFavorites) {
        return visible.stream().filter(w -> !onlyFavorites || contains(favorites, w.name))
                .sorted(Comparator.comparing((WorldSettings w) -> !contains(favorites, w.name))
                        .thenComparing(w -> !w.serverWorld).thenComparing(w -> w.name)).toList();
    }

    static boolean contains(Set<String> favorites, String name) { return favorites.contains(name.toLowerCase(Locale.ROOT)); }
}
