package at.esoren.vystormworlds;

import java.time.ZonedDateTime;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

/**
 * World border editor and shrinking border events. A world with a {@code border} size gets a vanilla world border
 * around its {@code border-center} (the spawn by default). With a {@code border-event} schedule (like resets:
 * {@code daily 20:00}, {@code weekly sat 18:00}, …) the border shrinks to {@code border-events.shrink-to-percent} of
 * its size over {@code border-events.minutes}, stays there for {@code border-events.hold-minutes} and then grows back.
 * Players in the world are warned five minutes and one minute before. Admins can also start an event from the world
 * menu. Checked every 20 seconds on the server thread; a restart ends a running event and restores the border.
 */
final class BorderEvents {
    /** A running event: the border grows back at {@code restoreAt}. */
    record Running(long restoreAt) {}

    private final WorldsPlugin plugin;
    private final Map<String, ZonedDateTime> next = new HashMap<>();
    private final Map<String, String> seen = new HashMap<>();
    private final Map<String, Integer> warned = new HashMap<>();
    private final Map<String, Running> running = new HashMap<>();

    BorderEvents(WorldsPlugin plugin) { this.plugin = plugin; }

    /** Border size during an event: {@code percent} of the size, at least 16 blocks. */
    static double target(int size, int percent) { return Math.max(16, size * Math.clamp(percent, 1, 99) / 100.0); }

    /** Centre text: "spawn" or "x z" (also "x,z"); null when invalid. */
    static double[] center(String text, Location spawn) {
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase("spawn"))
            return spawn == null ? new double[] {0.5, 0.5} : new double[] {spawn.getBlockX() + 0.5, spawn.getBlockZ() + 0.5};
        String[] parts = text.strip().split("[\\s,;]+");
        if (parts.length != 2) return null;
        try {
            double x = Double.parseDouble(parts[0]), z = Double.parseDouble(parts[1]);
            return Double.isFinite(x) && Double.isFinite(z) && Math.abs(x) <= 29_999_984 && Math.abs(z) <= 29_999_984 ? new double[] {x, z} : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    boolean active(String world) { return running.containsKey(WorldRegistry.key(world)); }

    /** Applies the configured border (not during an event); size 0 leaves the border alone. */
    void apply(World world, WorldSettings w) {
        // border.enabled false (1.17.0): the plugin leaves every world border as it is
        if (w.border <= 0 || active(world.getName()) || !Features.on(plugin.getConfig(), Features.BORDER)) return;
        double[] c = center(w.borderCenter, world.getSpawnLocation());
        if (c == null) return;
        WorldBorder border = world.getWorldBorder();
        border.setCenter(c[0], c[1]);
        if (Math.abs(border.getSize() - w.border) > 0.01) border.setSize(w.border);
    }

    void tick() {
        ZonedDateTime now = ZonedDateTime.now();
        long millis = System.currentTimeMillis();
        for (Map.Entry<String, Running> entry : List.copyOf(running.entrySet()))
            if (millis >= entry.getValue().restoreAt()) restore(entry.getKey());
        // switched off: a running event still grows back above, but nothing new is scheduled
        if (!Features.on(plugin.getConfig(), Features.BORDER_EVENTS) || !Features.on(plugin.getConfig(), Features.BORDER)) { next.clear(); seen.clear(); warned.clear(); return; }
        Set<String> present = new HashSet<>();
        for (WorldSettings w : plugin.registry().all()) {
            String key = WorldRegistry.key(w.name);
            present.add(key);
            ResetSchedule schedule = ResetSchedule.parse(w.borderEvent);
            if (schedule == null || schedule.kind() == ResetSchedule.Kind.OFF || w.border <= 0) { next.remove(key); continue; }
            if (!schedule.text().equals(seen.get(key))) { seen.put(key, schedule.text()); next.put(key, schedule.next(now)); warned.remove(key); }
            ZonedDateTime due = next.get(key);
            if (due == null) continue;
            long seconds = java.time.Duration.between(now, due).getSeconds();
            int stage = warned.getOrDefault(key, 0);
            if (seconds <= 300 && seconds > 60 && stage < 1) { warn(w, 5); warned.put(key, 1); }
            else if (seconds <= 60 && seconds > 0 && stage < 2) { warn(w, 1); warned.put(key, 2); }
            if (seconds <= 0) {
                next.put(key, schedule.next(now.plusSeconds(1)));
                warned.remove(key);
                start(w);
            }
        }
        next.keySet().retainAll(present);
        seen.keySet().retainAll(present);
    }

    private int percent() { return Math.clamp(plugin.getConfig().getInt("border-events.shrink-to-percent", 25), 1, 99); }
    private int minutes() { return Math.clamp(plugin.getConfig().getInt("border-events.minutes", 10), 1, 240); }
    private int hold() { return Math.clamp(plugin.getConfig().getInt("border-events.hold-minutes", 10), 0, 1440); }

    private void warn(WorldSettings w, int minutes) {
        World world = WorldManager.loaded(w.name);
        if (world == null) return;
        long to = Math.round(target(w.border, percent()));
        for (Player player : world.getPlayers()) plugin.lang().send(player, "border-warning", "world", w.name, "minutes", minutes, "size", to);
    }

    /** Starts the shrinking event now; false when the world is not loaded or has no border size. */
    boolean start(WorldSettings w) {
        World world = WorldManager.loaded(w.name);
        if (world == null || w.border <= 0) return false;
        String key = WorldRegistry.key(w.name);
        running.remove(key);
        apply(world, w);
        double to = target(w.border, percent());
        int minutes = minutes();
        world.getWorldBorder().setSize(to, minutes * 60L);
        running.put(key, new Running(System.currentTimeMillis() + (minutes + hold()) * 60_000L));
        for (Player player : world.getPlayers()) {
            plugin.lang().send(player, "border-shrinking", "world", w.name, "size", Math.round(to), "minutes", minutes);
            player.playSound(player.getLocation(), "minecraft:block.beacon.deactivate", 0.8f, 0.7f);
        }
        plugin.getLogger().info("Border event in " + w.name + ": shrinking to " + Math.round(to) + " blocks over " + minutes + " minutes.");
        return true;
    }

    private void restore(String key) {
        running.remove(key);
        WorldSettings w = plugin.registry().get(key);
        World world = w == null ? null : WorldManager.loaded(w.name);
        if (world == null || w.border <= 0) return;
        world.getWorldBorder().setSize(w.border, 60L);
        for (Player player : world.getPlayers()) plugin.lang().send(player, "border-restored", "world", w.name, "size", w.border);
    }

    /** Plugin stops: running events end, borders go back to their configured size at once. */
    void close() {
        for (String key : List.copyOf(running.keySet())) {
            running.remove(key);
            WorldSettings w = plugin.registry().get(key);
            World world = w == null ? null : Bukkit.getWorld(w.name);
            if (world != null && w.border > 0) world.getWorldBorder().setSize(w.border);
        }
    }
}
