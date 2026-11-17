package at.esoren.vystormworlds;

import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Safe teleports: the chunks around the target are loaded asynchronously, then a safe spot is searched near the target
 * and the player is moved there. Remembers the last position per world in the player's persistent data.
 */
final class Teleports {
    private final WorldsPlugin plugin;

    Teleports(WorldsPlugin plugin) { this.plugin = plugin; }

    void preferSpawn(Player player, boolean spawn) {
        NamespacedKey key = new NamespacedKey(plugin, "arrival_spawn");
        if (spawn) player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        else player.getPersistentDataContainer().remove(key);
    }

    boolean prefersSpawn(Player player) {
        return player.getPersistentDataContainer().has(new NamespacedKey(plugin, "arrival_spawn"), PersistentDataType.BYTE);
    }

    private NamespacedKey lastKey(String world) {
        return new NamespacedKey(plugin, "last/" + world.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "_"));
    }

    void remember(Player player, Location location) {
        if (location == null || location.getWorld() == null) return;
        player.getPersistentDataContainer().set(lastKey(location.getWorld().getName()), PersistentDataType.STRING,
                String.format(Locale.ROOT, "%.2f;%.2f;%.2f;%.1f;%.1f", location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
    }

    Location last(Player player, World world) {
        String raw = player.getPersistentDataContainer().get(lastKey(world.getName()), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            String[] p = raw.split(";");
            return new Location(world, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]));
        } catch (RuntimeException broken) { return null; }
    }

    /** Spawn of a world: the stored spawn, else the world's own. */
    static Location spawn(World world, WorldSettings settings) {
        if (settings != null && settings.spawn != null)
            return new Location(world, settings.spawn.x(), settings.spawn.y(), settings.spawn.z(), settings.spawn.yaw(), settings.spawn.pitch());
        return world.getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** Where a player arrives in a world: last position (if wanted and remembered) or the spawn. */
    Location arrival(Player player, World world, WorldSettings settings, boolean preferLast) {
        if (preferLast && !prefersSpawn(player) && plugin.getConfig().getBoolean("teleport.return-to-last-position", true) && (settings == null || settings.rememberPosition)) {
            Location last = last(player, world);
            if (last != null) return last;
        }
        return spawn(world, settings);
    }

    /** Teleports safely; {@code done} gets true when the player was moved. */
    void send(Player player, Location target, Consumer<Boolean> done) {
        World world = target.getWorld();
        int radius = Math.clamp(plugin.getConfig().getInt("teleport.safe-radius", 8), 0, 32);
        plugin.manager().loadAround(world, target.getBlockX(), target.getBlockZ(), radius, () -> {
            if (!player.isOnline() || !world.equals(WorldManager.loaded(world.getName()))) { done.accept(false); return; }
            Location spot = safe(target, radius);
            if (spot == null) { plugin.lang().send(player, "no-safe-spot", "world", world.getName()); done.accept(false); return; }
            plugin.core().compat().platform().teleportAsync(player, spot, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) ->
                    plugin.tasks().now(() -> done.accept(Boolean.TRUE.equals(ok))));
        });
    }

    /** A safe location near the target (keeping its exact position and view when it is already safe), or null. */
    static Location safe(Location target, int radius) {
        World world = target.getWorld();
        int maxY = world.getEnvironment() == World.Environment.NETHER && target.getBlockY() < 127 ? 127 : world.getMaxHeight() - 1;
        SafeSpot.Blocks blocks = (x, y, z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return y < world.getMinHeight() ? SafeSpot.Kind.DANGER : SafeSpot.Kind.FREE;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return SafeSpot.Kind.DANGER;
            return SafeSpot.classify(world.getBlockAt(x, y, z).getType());
        };
        int bx = target.getBlockX(), by = target.getBlockY(), bz = target.getBlockZ();
        if (SafeSpot.safe(blocks, bx, by, bz)) return target;
        SafeSpot.Spot spot = SafeSpot.find(blocks, bx, Math.min(by, maxY - 1), bz, radius, 24, world.getMinHeight(), maxY);
        if (spot == null) {
            int top = world.getHighestBlockYAt(bx, bz) + 1;
            if (top < maxY && SafeSpot.safe(blocks, bx, top, bz)) spot = new SafeSpot.Spot(bx, top, bz);
        }
        if (spot == null) return null;
        Location location = new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5, target.getYaw(), target.getPitch());
        return location;
    }
}
