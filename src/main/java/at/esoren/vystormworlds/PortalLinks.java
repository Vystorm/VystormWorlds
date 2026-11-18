package at.esoren.vystormworlds;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Nether and end portals inside a world family lead to the family's own nether and end (links in worlds.yml, or the
 * {@code <name>_nether} / {@code <name>_the_end} naming convention). Worlds without a family keep vanilla behaviour.
 * Also tells VystormPortals (if installed) which worlds belong together, so its portal previews match.
 */
final class PortalLinks {
    private final WorldsPlugin plugin;

    PortalLinks(WorldsPlugin plugin) { this.plugin = plugin; }

    /** The linked world for a portal of the given dimension, or null if unlinked. */
    World target(World source, World.Environment portal) {
        WorldSettings self = plugin.settings(source);
        if (self == null) return null;
        WorldSettings family = plugin.registry().family(self.name);
        if (family == null) return null;
        String name;
        if (source.getEnvironment() == World.Environment.NORMAL) {
            name = portal == World.Environment.NETHER ? link(family.nether, family.name + "_nether") : link(family.end, family.name + "_the_end");
        } else if (source.getEnvironment() == portal) {
            name = family == self ? null : family.name;      // portal of the same kind leads home
        } else if (source.getEnvironment() == World.Environment.THE_END && portal == World.Environment.NETHER) {
            name = null;
        } else {
            name = family == self ? null : portal == World.Environment.NETHER ? link(family.nether, family.name + "_nether") : link(family.end, family.name + "_the_end");
        }
        return name == null ? null : WorldManager.loaded(name);
    }

    private static World.Environment portalKind(EntityPortalEvent event) {
        Location origin = event.getFrom();
        // Vanilla may report the entity's feet next to the actual portal block.
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Material type = origin.getBlock().getRelative(x, y, z).getType();
            if (type == Material.END_PORTAL) return World.Environment.THE_END;
            if (type == Material.NETHER_PORTAL) return World.Environment.NETHER;
        }
        World target = event.getTo() == null ? null : event.getTo().getWorld();
        if (target == null || target.equals(origin.getWorld())) return null;
        if (target.getEnvironment() == World.Environment.THE_END || origin.getWorld().getEnvironment() == World.Environment.THE_END)
            return World.Environment.THE_END;
        if (target.getEnvironment() == World.Environment.NETHER || origin.getWorld().getEnvironment() == World.Environment.NETHER)
            return World.Environment.NETHER;
        return null;
    }

    private String link(String explicit, String convention) {
        if (!explicit.isBlank()) return explicit;
        return plugin.registry().contains(convention) ? convention : null;
    }

    /** True if the world belongs to a family with links (so unlinked portals may be blocked). */
    private boolean managed(World world) { return plugin.settings(world) != null; }

    /**
     * Portal travel goes through Core's stages, so Core decides when these run among all Vystorm plugins. Both
     * handlers change the destination (or refuse an unlinked portal, a rule of this plugin): ADJUST, the same Bukkit
     * priority (NORMAL) as before. Neither sees a cancelled event.
     */
    void handleSharedEvents(EventStages stages) {
        stages.handle(plugin, PlayerPortalEvent.class, EventStage.ADJUST, this::playerPortal);
        stages.handle(plugin, EntityPortalEvent.class, EventStage.ADJUST, this::entityPortal);
    }

    /** Stage ADJUST, registered in {@link #handleSharedEvents}. */
    public void playerPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        // portals.enabled false (1.17.0): every portal works as in vanilla
        if (from == null || !Features.on(plugin.getConfig(), Features.PORTALS) || plugin.rules().coreWorld(from) || WorldManager.serverWorld(from)) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            World target = target(from, World.Environment.NETHER);
            if (target == null) { blockIfWanted(event, from); return; }
            event.setTo(scaled(event.getFrom(), target));
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            World target = target(from, World.Environment.THE_END);
            if (target == null) { blockIfWanted(event, from); return; }
            event.setTo(endArrival(event.getPlayer(), target));
        }
    }

    /** Stage ADJUST, registered in {@link #handleSharedEvents}. */
    public void entityPortal(EntityPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (from == null || !Features.on(plugin.getConfig(), Features.PORTALS) || plugin.rules().coreWorld(from) || WorldManager.serverWorld(from) || event.getTo() == null) return;
        World.Environment portal = portalKind(event);
        if (portal == null) return;
        World target = target(from, portal);
        if (target == null) {
            if (managed(from) && "block".equalsIgnoreCase(plugin.getConfig().getString("portals.unlinked", "vanilla"))) event.setCancelled(true);
            return;
        }
        event.setTo(portal == World.Environment.NETHER ? scaled(event.getFrom(), target) : endArrival(null, target));
    }

    private void blockIfWanted(PlayerPortalEvent event, World from) {
        if (!managed(from) || !"block".equalsIgnoreCase(plugin.getConfig().getString("portals.unlinked", "vanilla"))) return;
        event.setCancelled(true);
        plugin.lang().actionBar(event.getPlayer(), "portal-unlinked");
    }

    static Location scaled(Location from, World target) {
        double factor = scale(from.getWorld()) / scale(target);
        double x = from.getX() * factor, z = from.getZ() * factor;
        var border = target.getWorldBorder();
        double half = border.getSize() / 2 - 16;
        x = Math.clamp(x, border.getCenter().getX() - half, border.getCenter().getX() + half);
        z = Math.clamp(z, border.getCenter().getZ() - half, border.getCenter().getZ() + half);
        double y = Math.clamp(from.getY(), target.getMinHeight() + 1, (target.getEnvironment() == World.Environment.NETHER ? 120 : target.getMaxHeight() - 2));
        return new Location(target, x, y, z, from.getYaw(), from.getPitch());
    }

    static double scale(World world) { return world.getEnvironment() == World.Environment.NETHER ? 8.0 : 1.0; }

    /** Into an end: the obsidian platform at 100 49 0 (built like vanilla). Out of an end: respawn point or spawn. */
    private Location endArrival(Player player, World target) {
        if (target.getEnvironment() == World.Environment.THE_END) {
            try {
                for (int x = 98; x <= 102; x++) for (int z = -2; z <= 2; z++) {
                    target.getBlockAt(x, 48, z).setType(Material.OBSIDIAN);
                    for (int y = 49; y <= 51; y++) target.getBlockAt(x, y, z).setType(Material.AIR);
                }
            } catch (RuntimeException failure) { plugin.getLogger().log(Level.FINE, "End platform", failure); }
            return new Location(target, 100.5, 49, 0.5, 90f, 0f);
        }
        if (player != null) {
            Location respawn = player.getRespawnLocation();
            if (respawn != null && target.equals(respawn.getWorld())) return respawn;
        }
        return Teleports.spawn(target, plugin.settings(target));
    }

    /** Writes the family links into VystormPortals' in-memory config (its previews read them live). Not saved. */
    void sync() {
        if (!plugin.getConfig().getBoolean("portals.sync-vystorm-portals", true)) return;
        Plugin portals = plugin.getServer().getPluginManager().getPlugin("VystormPortals");
        if (portals == null || !portals.isEnabled()) return;
        try {
            var config = portals.getConfig();
            for (WorldSettings w : plugin.registry().all()) {
                World world = WorldManager.loaded(w.name);
                if (world == null || world.getEnvironment() != World.Environment.NORMAL) continue;
                World nether = target(world, World.Environment.NETHER), end = target(world, World.Environment.THE_END);
                if (nether != null) {
                    config.set("world-links." + world.getUID() + ".nether", nether.getName());
                    config.set("world-links." + nether.getUID() + ".overworld", world.getName());
                }
                if (end != null) {
                    config.set("world-links." + world.getUID() + ".end", end.getName());
                    config.set("world-links." + end.getUID() + ".overworld", world.getName());
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.FINE, "VystormPortals link sync skipped", failure);
        }
    }
}
