package at.esoren.vystormworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Scheduled world resets: worlds with a {@code reset} schedule are regenerated at that time (same or a new random
 * seed, {@code reset-seed}), with a warning to everyone in the world ten minutes and one minute before. The old chunk
 * data goes to backups like a manual {@code /worlds regen}; only the newest {@code reset.keep-backups} of a world are
 * kept. Checked every 20 seconds on the server thread.
 */
final class WorldResets {
    private final WorldsPlugin plugin;
    private final Map<String, ZonedDateTime> next = new HashMap<>();
    private final Map<String, String> seen = new HashMap<>();
    private final Map<String, Integer> warned = new HashMap<>();

    WorldResets(WorldsPlugin plugin) { this.plugin = plugin; }

    void tick() {
        // switched off: forget the schedule state, so nothing that came due meanwhile runs when it is switched on again
        if (!Features.on(plugin.getConfig(), Features.RESETS)) { next.clear(); seen.clear(); warned.clear(); return; }
        ZonedDateTime now = ZonedDateTime.now();
        Set<String> present = new HashSet<>();
        for (WorldSettings w : plugin.registry().all()) {
            String key = WorldRegistry.key(w.name);
            present.add(key);
            ResetSchedule schedule = ResetSchedule.parse(w.reset);
            if (schedule == null || schedule.kind() == ResetSchedule.Kind.OFF || w.serverWorld) { next.remove(key); continue; }
            if (!schedule.text().equals(seen.get(key))) { seen.put(key, schedule.text()); next.put(key, schedule.next(now)); warned.remove(key); }
            ZonedDateTime due = next.get(key);
            if (due == null) continue;
            long seconds = java.time.Duration.between(now, due).getSeconds();
            int stage = warned.getOrDefault(key, 0);
            if (seconds <= 600 && seconds > 60 && stage < 1) { warn(w.name, 10); warned.put(key, 1); }
            else if (seconds <= 60 && seconds > 0 && stage < 2) { warn(w.name, 1); warned.put(key, 2); }
            if (seconds <= 0) {
                next.put(key, schedule.next(now.plusSeconds(1)));
                warned.remove(key);
                reset(w);
            }
        }
        next.keySet().retainAll(present);
        seen.keySet().retainAll(present);
    }

    /** When the next automatic reset of a world runs, or null. */
    ZonedDateTime nextReset(String name) {
        WorldSettings w = plugin.registry().get(name);
        ResetSchedule schedule = w == null ? null : ResetSchedule.parse(w.reset);
        return schedule == null ? null : schedule.next(ZonedDateTime.now());
    }

    private void warn(String name, int minutes) {
        World world = Bukkit.getWorld(name);
        if (world == null) return;
        for (Player player : world.getPlayers()) plugin.lang().send(player, "reset-warning", "world", name, "minutes", minutes);
    }

    private void reset(WorldSettings w) {
        Long seed = "random".equals(w.resetSeed) ? java.util.concurrent.ThreadLocalRandom.current().nextLong() : null;
        plugin.getLogger().info("Scheduled reset of " + w.name + " starting.");
        plugin.manager().regenerate(w.name, seed, problem -> {
            if (problem == null) {
                plugin.getLogger().info("Scheduled reset of " + w.name + " finished.");
                for (Player player : Bukkit.getOnlinePlayers()) if (player.hasPermission("vystormworlds.admin")) plugin.lang().send(player, "reset-done", "world", w.name);
                pruneBackups(plugin.getDataFolder().toPath().resolve("backups"), w.name, Math.clamp(plugin.getConfig().getInt("reset.keep-backups", 3), 0, 100));
            } else plugin.getLogger().warning("Scheduled reset of " + w.name + " failed: " + problem);
        });
    }

    /** Deletes all but the newest {@code keep} "regenerated" backups of a world (folder names sort by time). */
    static int pruneBackups(Path backups, String world, int keep) {
        if (!Files.isDirectory(backups)) return 0;
        String prefix = world + "-regenerated-";
        List<Path> mine = new ArrayList<>();
        try (Stream<Path> list = Files.list(backups)) {
            list.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith(prefix)).forEach(mine::add);
        } catch (IOException failure) { return 0; }
        mine.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        int removed = 0;
        for (Path old : mine.subList(Math.min(keep, mine.size()), mine.size())) {
            try { WorldPaths.deleteTree(old); removed++; } catch (IOException ignored) { /* try again next reset */ }
        }
        return removed;
    }
}
