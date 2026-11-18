package at.esoren.vystormworlds;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Rules on the way into a world (permission, player limit, price), on arrival (inventory group, game mode, flight)
 * and inside it (hunger, mob spawning, respawn). Vystorm Core plugin worlds and running Core visits are left alone:
 * Core saves and restores the player there itself.
 */
final class PlayerRules implements Listener {
    private final WorldsPlugin plugin;
    private final NamespacedKey stampKey, flyKey, unlockedKey;
    private final Map<UUID, Location> origins = new HashMap<>();

    PlayerRules(WorldsPlugin plugin) {
        this.plugin = plugin;
        stampKey = new NamespacedKey(plugin, "group");
        flyKey = new NamespacedKey(plugin, "fly_granted");
        unlockedKey = new NamespacedKey(plugin, "unlocked");
    }

    // ------------------------------------------------------------------ Core worlds

    boolean coreWorld(World world) {
        try { return plugin.core().worldGenerator().worlds().managed(world); } catch (RuntimeException none) { return false; }
    }

    boolean inVisit(Player player) {
        try { return plugin.core().worldGenerator().worlds().visits().inVisit(player.getUniqueId()); } catch (RuntimeException none) { return false; }
    }

    String groupOf(World world) {
        WorldSettings settings = plugin.settings(world);
        return settings == null ? plugin.defaultGroup() : settings.group;
    }

    String stamp(Player player) { return player.getPersistentDataContainer().get(stampKey, PersistentDataType.STRING); }

    // ------------------------------------------------------------------ unlocks

    boolean unlocked(Player player, String world) {
        String list = player.getPersistentDataContainer().get(unlockedKey, PersistentDataType.STRING);
        if (list == null) return false;
        for (String entry : list.split(",")) if (entry.equalsIgnoreCase(world)) return true;
        return false;
    }

    void unlock(Player player, String world) {
        if (unlocked(player, world)) return;
        String list = player.getPersistentDataContainer().get(unlockedKey, PersistentDataType.STRING);
        player.getPersistentDataContainer().set(unlockedKey, PersistentDataType.STRING, list == null || list.isEmpty() ? world.toLowerCase(Locale.ROOT) : list + "," + world.toLowerCase(Locale.ROOT));
    }

    Access.Viewer viewer(Player player, WorldSettings world) {
        boolean economy = world.price > 0 && Money.available();
        // entry-rules.enabled false (1.17.0): nobody is held back by permission, limit or price
        boolean bypass = player.hasPermission("vystormworlds.bypass") || !Features.on(plugin.getConfig(), Features.ENTRY_RULES);
        return new Access.Viewer(bypass,
                player.hasPermission(Access.permission(world.name)) || player.hasPermission("vystormworlds.enter.*"),
                unlocked(player, world.name), economy, economy && Money.has(player, world.price));
    }

    /** Access check for a player entering a world (players already inside are not counted twice). */
    Access.Result access(Player player, WorldSettings settings, World world) {
        int players = 0;
        if (world != null) for (Player other : world.getPlayers())
            if (!other.equals(player) && !other.hasPermission("vystormworlds.bypass")) players++;
        return Access.check(settings, world != null, viewer(player, settings), players);
    }

    void explain(Player player, WorldSettings settings, Access.Result result) {
        switch (result) {
            case NOT_LOADED -> plugin.lang().send(player, "not-loaded", "world", settings.name);
            case NO_PERMISSION -> plugin.lang().send(player, "no-entry", "world", settings.name);
            case FULL -> plugin.lang().send(player, "world-full", "world", settings.name, "limit", settings.playerLimit);
            case NO_MONEY -> plugin.lang().send(player, "too-poor", "world", settings.name, "price", Money.format(settings.price));
            default -> { }
        }
    }

    // ------------------------------------------------------------------ teleports

    /**
     * Refusing the way into a world goes through Core's stages: PROTECT, so no other Vystorm plugin acts on a
     * teleport that is not allowed. It does not see a cancelled teleport. {@link #entered} stays a Bukkit listener at
     * MONITOR: it takes the price as late as possible and cancels if the money is gone, which a handler of Core's
     * OBSERVE stage may not do.
     */
    void handleSharedEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, PlayerTeleportEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, this::checkEntry);
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void checkEntry(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || to.getWorld().equals(event.getFrom().getWorld())) return;
        WorldSettings settings = plugin.settings(to.getWorld());
        if (settings == null || coreWorld(to.getWorld())) return;
        Access.Result result = access(event.getPlayer(), settings, to.getWorld());
        if (result == Access.Result.OK || result == Access.Result.PAY) return;
        event.setCancelled(true);
        explain(event.getPlayer(), settings, result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entered(PlayerTeleportEvent event) {
        Location to = event.getTo();
        Player player = event.getPlayer();
        if (to == null || to.getWorld() == null || to.getWorld().equals(event.getFrom().getWorld())) return;
        WorldSettings settings = plugin.settings(to.getWorld());
        if (settings != null && !coreWorld(to.getWorld()) && Access.charges(settings, viewer(player, settings))) {
            if (!Money.withdraw(player, settings.price)) {
                event.setCancelled(true);
                plugin.lang().send(player, "too-poor", "world", settings.name, "price", Money.format(settings.price));
                return;
            }
            if ("once".equals(settings.priceMode)) unlock(player, settings.name);
            plugin.lang().send(player, "paid", "world", Menus.title(settings), "price", Money.format(settings.price));
        }
        if (!coreWorld(event.getFrom().getWorld()) && !inVisit(player)) plugin.teleports().remember(player, event.getFrom());
        origins.put(player.getUniqueId(), event.getFrom().clone());
    }

    // ------------------------------------------------------------------ arrival

    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event) {
        arrive(event.getPlayer());
        announce(event.getPlayer());
    }

    /** Title with the world's display name and its description when a player arrives in another world (arrival-title). */
    void announce(Player player) {
        if (!plugin.getConfig().getBoolean("arrival-title", true) || coreWorld(player.getWorld()) || inVisit(player)) return;
        WorldSettings settings = plugin.settings(player.getWorld());
        if (settings == null) return;
        plugin.core().compat().platform().showTitle(player, net.kyori.adventure.title.Title.title(plugin.lang().component(player, "arrival-title", "world", Menus.title(settings)), subtitle(settings),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(400), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(800))));
    }

    /** The description as subtitle (MiniMessage like in the selector), empty when there is none. */
    static net.kyori.adventure.text.Component subtitle(WorldSettings w) {
        if (w.description == null || w.description.isBlank()) return net.kyori.adventure.text.Component.empty();
        try { return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<gray>" + w.description); }
        catch (RuntimeException bad) { return net.kyori.adventure.text.Component.text(w.description); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        WorldSettings settings = plugin.settings(player.getWorld());
        if (settings != null && settings.permission && Features.on(plugin.getConfig(), Features.ENTRY_RULES) && !coreWorld(player.getWorld()) && !player.hasPermission("vystormworlds.bypass")
                && !player.hasPermission(Access.permission(settings.name)) && !player.hasPermission("vystormworlds.enter.*")) {
            plugin.lang().send(player, "no-entry", "world", settings.name);
            player.teleport(WorldManager.fallback());
        }
        arrive(player);
    }

    /** After a respawn (possibly into another world or group). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(org.bukkit.event.player.PlayerRespawnEvent event) {
        // Bukkit's event runs before the player changes world; reconcile only after the respawn completes.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) arrive(event.getPlayer());
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) { origins.remove(event.getPlayer().getUniqueId()); }

    /** Group swap and arrival rules for the world the player is in now. */
    void arrive(Player player) {
        World world = player.getWorld();
        if (coreWorld(world) || inVisit(player)) return;
        reconcile(player);
        WorldSettings settings = plugin.settings(world);
        applyGameMode(player, settings);
        applyFlight(player, settings);
    }

    Set<PlayerState.Part> parts() {
        Set<PlayerState.Part> parts = EnumSet.noneOf(PlayerState.Part.class);
        for (String raw : plugin.getConfig().getStringList("groups.separate")) {
            PlayerState.Part part = PlayerState.Part.parse(raw);
            if (part != null) parts.add(part);
        }
        return parts;
    }

    /** Swaps the player's group state if it does not belong to the group of their world. */
    void reconcile(Player player) {
        if (!plugin.getConfig().getBoolean("groups.enabled", true) || player.isDead() || !player.isOnline()) return;
        // worlds.yml unreadable: every world would look like the default group, so leave inventories alone until it is fixed
        if (plugin.registry().error() != null) return;
        World world = player.getWorld();
        if (coreWorld(world) || inVisit(player)) return;
        Set<PlayerState.Part> parts = parts();
        String target = groupOf(world);
        GroupSwitcher.Result result = plugin.switcher().switchTo(new BukkitLivePlayer(player, stampKey, plugin.core().compat()), target, parts, System.currentTimeMillis());
        if (result.brokenSetAside()) {
            plugin.getLogger().severe("Group file " + target + " of " + player.getName() + " was unreadable and set aside as .broken-*; the player starts fresh in this group. Check plugins/VystormWorlds/groups/" + player.getUniqueId());
            plugin.lang().send(player, "group-file-broken", "group", target);
        }
        switch (result.outcome()) {
            case SWITCHED, SWITCHED_FIRST_VISIT -> {
                if (plugin.getConfig().getBoolean("groups.notify", true))
                    plugin.core().compat().platform().sendActionBar(player, plugin.lang().component(player, "group-switched", "group", target));
            }
            case FAILED_SAVE, FAILED_NEWER, FAILED_APPLY -> {
                plugin.getLogger().severe("Could not switch " + player.getName() + " from group " + result.from() + " to " + target + ": " + result.detail()
                        + ". Inventory unchanged.");
                plugin.lang().send(player, "group-failed");
                Location origin = origins.remove(player.getUniqueId());
                if (origin != null && origin.getWorld() != null && groupOf(origin.getWorld()).equals(result.from())) player.teleport(origin);
            }
            default -> { }
        }
    }

    /** world-rules.enabled (1.17.0): off = the plugin applies no game mode, flight, hunger, mob or world rule. */
    boolean worldRules() { return Features.on(plugin.getConfig(), Features.WORLD_RULES); }

    void applyGameMode(Player player, WorldSettings settings) {
        if (!worldRules() || settings == null || settings.gameMode.isEmpty() || player.hasPermission("vystormworlds.bypass.gamemode")) return;
        GameMode mode = GameMode.valueOf(settings.gameMode.toUpperCase(Locale.ROOT));
        if (player.getGameMode() != mode) player.setGameMode(mode);
    }

    void applyFlight(Player player, WorldSettings settings) {
        if (!worldRules()) return;
        boolean survivalLike = player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
        boolean granted = player.getPersistentDataContainer().has(flyKey, PersistentDataType.BYTE);
        boolean wanted = settings != null && settings.fly && survivalLike;
        if (wanted && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            player.getPersistentDataContainer().set(flyKey, PersistentDataType.BYTE, (byte) 1);
        } else if (!wanted && granted) {
            player.getPersistentDataContainer().remove(flyKey);
            if (survivalLike) {
                player.setFlying(false);
                player.setAllowFlight(false);
                player.setFallDistance(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameModeChanged(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        plugin.tasks().later(1L, () -> { if (player.isOnline()) applyFlight(player, plugin.settings(player.getWorld())); });
    }

    // ------------------------------------------------------------------ inside

    @EventHandler(priority = EventPriority.HIGH)
    public void respawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;
        Player player = event.getPlayer();
        World died = player.getWorld();
        if (coreWorld(died) || inVisit(player)) return;
        WorldSettings settings = plugin.settings(died);
        if (settings == null) return;
        String target = settings.respawnWorld;
        if (target.equalsIgnoreCase("vanilla")) return;
        if (target.isBlank()) {
            if (!plugin.getConfig().getBoolean("respawn.same-world", true)) return;
            WorldSettings family = plugin.registry().family(plugin.settings(died) == null ? died.getName() : plugin.settings(died).name);
            target = family == null ? died.getName() : family.name;
        }
        World world = WorldManager.loaded(target);
        if (world == null) return;
        Location spawn = Teleports.spawn(world, plugin.settings(world));
        Location safe = world.isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4) ? Teleports.safe(spawn, 4) : spawn;
        event.setRespawnLocation(safe == null ? spawn : safe);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFoodLevel() >= player.getFoodLevel()) return;
        WorldSettings settings = plugin.settings(player.getWorld());
        if (settings != null && !settings.hunger && worldRules()) event.setCancelled(true);
    }

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL = EnumSet.of(CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.PATROL, CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.JOCKEY, CreatureSpawnEvent.SpawnReason.MOUNT, CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (!NATURAL.contains(event.getSpawnReason()) || !worldRules()) return;
        WorldSettings settings = plugin.settings(event.getLocation().getWorld());
        if (settings == null) return;
        var entity = event.getEntity();
        if (!settings.monsters && entity instanceof Enemy) event.setCancelled(true);
        else if (!settings.animals && (entity instanceof Animals || entity instanceof WaterMob || entity instanceof Ambient)) event.setCancelled(true);
    }

    /** Re-checks every online player, e.g. after the group setup changed. */
    void recheckAll() {
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) arrive(player);
    }
}
