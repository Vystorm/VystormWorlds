package at.esoren.vystormworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

/**
 * Loads, creates, imports, unloads, clones, regenerates and deletes worlds, and applies the world properties. Server
 * thread; file copies and deletions run asynchronously while the world is unloaded and marked busy.
 */
final class WorldManager {
    private final WorldsPlugin plugin;
    private final WorldPaths paths;
    private final Generators generators;
    private final Map<String, String> loadErrors = new HashMap<>();
    private final Set<String> busy = new HashSet<>();
    private final Set<java.util.UUID> spawnPending = new HashSet<>();

    WorldManager(WorldsPlugin plugin, WorldPaths paths, Generators generators) {
        this.plugin = plugin;
        this.paths = paths;
        this.generators = generators;
    }

    WorldPaths paths() { return paths; }

    Generators generators() { return generators; }

    /** Why a world could not be loaded (generator missing, ...), or null. */
    String loadError(String name) { return loadErrors.get(WorldRegistry.key(name)); }

    void clearLoadError(String name) { loadErrors.remove(WorldRegistry.key(name)); }

    boolean busy(String name) { return busy.contains(WorldRegistry.key(name)); }

    // static on purpose: used from every class and from tests, so the Core call stays the static one
    static World loaded(String name) {
        if (name == null || name.isBlank()) return null;
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        try {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.matches("[a-z0-9_./-]+")) {
                World custom = Vystorm.vystorm_core.compat.WorldCompat.getWorld(new NamespacedKey(DimensionPack.NAMESPACE, key));
                if (custom != null) return custom;
                if (name.equals(key)) return Vystorm.vystorm_core.compat.WorldCompat.getWorld(NamespacedKey.minecraft(name));
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return null;
    }

    /** Server worlds (main level and its nether and end) are never unloaded, deleted or regenerated. */
    static boolean serverWorld(World world) {
        if (world == null) return false;
        World main = Bukkit.getWorlds().getFirst();
        if (world.equals(main)) return true;
        String key = world.getKey().toString();
        if (key.equals("minecraft:overworld") || key.equals("minecraft:the_nether") || key.equals("minecraft:the_end")) return true;
        return world.getName().equals(main.getName() + "_nether") || world.getName().equals(main.getName() + "_the_end");
    }

    Path folder(String name) {
        World world = loaded(name);
        if (world != null) return world.getWorldFolder().toPath();
        WorldSettings w = plugin.registry().get(name);
        return w != null && w.customHeight() ? paths.folder(DimensionPack.NAMESPACE, name) : paths.folder(name);
    }

    /** Folders that must never be removed: every other loaded world and the main world. */
    private List<Path> protectedFolders(String except) {
        List<Path> list = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) if (!world.getName().equalsIgnoreCase(except)) list.add(world.getWorldFolder().toPath());
        return list;
    }

    // ------------------------------------------------------------------ load

    record Outcome(World world, String error) {
        static Outcome fail(String error) { return new Outcome(null, error); }
    }

    /** Loads the world (or returns it if loaded) and applies its properties. Never falls back to vanilla terrain. */
    Outcome load(WorldSettings w) { return load(w, false); }

    /**
     * As {@link #load(WorldSettings)}; {@code creating}: a brand-new world, whose spawn is searched in the background
     * ({@link #spawnPending}) instead of by the server on the main thread.
     */
    Outcome load(WorldSettings w, boolean creating) {
        World existing = loaded(w.name);
        if (existing != null) { apply(existing, w); loadErrors.remove(WorldRegistry.key(w.name)); return new Outcome(existing, null); }
        if (busy(w.name)) return Outcome.fail("busy");
        if (w.customHeight()) {
            // created by the server from the data pack while it starts; never create it here with the vanilla height
            String reason = "custom build height: created by the server on the next restart";
            loadErrors.put(WorldRegistry.key(w.name), reason);
            return Outcome.fail(reason);
        }
        ChunkGenerator generator = null;
        org.bukkit.generator.BiomeProvider biomes = null;
        if (!w.generator.none() || !w.biomeProvider.isBlank()) {
            Generators.Resolved resolved = generators.resolve(w.generator, w.biomeProvider, w.name);
            if (resolved.failed()) {
                loadErrors.put(WorldRegistry.key(w.name), resolved.error());
                plugin.getLogger().warning("World " + w.name + " not loaded: " + resolved.error() + ". It stays unloaded so it is not generated with the wrong terrain.");
                return Outcome.fail(resolved.error());
            }
            generator = resolved.generator();
            biomes = resolved.biomes();
        } else if (w.kind == WorldKind.VOID) {
            generator = plugin.voidGenerator();
        }
        if (w.customHeight() && !paths.customHeights()) return Outcome.fail("custom build heights need Minecraft 26.x");
        WorldCreator creator = paths.modern()
                ? plugin.core().compat().world().worldCreator(new NamespacedKey(w.customHeight() ? DimensionPack.NAMESPACE : NamespacedKey.MINECRAFT, w.name.toLowerCase(Locale.ROOT)))
                : new WorldCreator(w.name);
        creator.environment(w.kind.environment).generateStructures(w.structures);
        if (w.seed != null) creator.seed(w.seed);
        switch (w.kind) {
            case FLAT -> creator.type(WorldType.FLAT);
            case AMPLIFIED -> creator.type(WorldType.AMPLIFIED);
            case LARGE_BIOMES -> creator.type(WorldType.LARGE_BIOMES);
            default -> { }
        }
        if (generator != null) creator.generator(generator);
        if (biomes != null) creator.biomeProvider(biomes);
        boolean provisionalSpawn = creating && generator == null && biomes == null && w.kind.environment == World.Environment.NORMAL
                && w.spawn == null && forceSpawn(creator, 0, 64, 0);
        World world;
        try {
            world = creator.createWorld();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "World " + w.name + " could not be loaded", failure);
            loadErrors.put(WorldRegistry.key(w.name), String.valueOf(failure.getMessage()));
            return Outcome.fail(String.valueOf(failure.getMessage()));
        }
        if (world == null) { loadErrors.put(WorldRegistry.key(w.name), "server refused"); return Outcome.fail("server refused"); }
        if (provisionalSpawn) spawnPending.add(world.getUID());
        loadErrors.remove(WorldRegistry.key(w.name));
        if (w.seed == null) w.seed = world.getSeed();
        apply(world, w);
        return new Outcome(world, null);
    }

    // ------------------------------------------------------------------ properties

    @SuppressWarnings({"deprecation", "removal", "unchecked"})
    private static GameRule<Boolean> booleanRule(String modern, String legacy) {
        GameRule<?> rule = GameRule.getByName(modern);
        if (rule == null) rule = GameRule.getByName(legacy);
        if (rule == null || rule.getType() != Boolean.class) throw new IllegalStateException("Missing gamerule " + legacy);
        return (GameRule<Boolean>) rule;
    }

    static GameRule<Boolean> advanceTime() {
        return booleanRule("advance_time", "doDaylightCycle");
    }

    static GameRule<Boolean> advanceWeather() {
        return booleanRule("advance_weather", "doWeatherCycle");
    }

    /** Applies the stored properties to a loaded world. */
    void apply(World world, WorldSettings w) {
        try {
            if (!w.difficulty.isEmpty()) world.setDifficulty(Difficulty.valueOf(w.difficulty.toUpperCase(Locale.ROOT)));
            world.setPVP(w.pvp);
            world.setSpawnFlags(w.monsters, w.animals);
            if (w.spawn != null) world.setSpawnLocation(new Location(world, w.spawn.x(), w.spawn.y(), w.spawn.z(), w.spawn.yaw(), w.spawn.pitch()));
            applyLocks(world, w, false);
            if (plugin.borders() != null) plugin.borders().apply(world, w);
            world.removePluginChunkTickets(plugin);
            if (w.keepSpawnLoaded) {
                Location spawn = world.getSpawnLocation();
                int cx = spawn.getBlockX() >> 4, cz = spawn.getBlockZ() >> 4;
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.addPluginChunkTicket(cx + x, cz + z, plugin);
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not apply all properties to " + world.getName(), failure);
        }
    }

    /** Time and weather locks; {@code unlockChanged}: switch the cycles back on when a lock was turned off. */
    static void applyLocks(World world, WorldSettings w, boolean unlockChanged) {
        long ticks = WorldSettings.lockTicks(w.timeLock);
        if (ticks >= 0) {
            world.setGameRule(advanceTime(), false);
            if (Math.floorMod(world.getTime() - ticks, 24000L) > 40) world.setTime(ticks);
        } else if (unlockChanged) world.setGameRule(advanceTime(), true);
        switch (w.weatherLock) {
            case "clear" -> { world.setGameRule(advanceWeather(), false); if (world.hasStorm()) world.setStorm(false); world.setThundering(false); }
            case "rain" -> { world.setGameRule(advanceWeather(), false); if (!world.hasStorm()) world.setStorm(true); world.setThundering(false); }
            case "thunder" -> { world.setGameRule(advanceWeather(), false); if (!world.hasStorm()) world.setStorm(true); if (!world.isThundering()) world.setThundering(true); }
            default -> { if (unlockChanged) world.setGameRule(advanceWeather(), true); }
        }
    }

    // ------------------------------------------------------------------ create / import

    /** Checks a new world name; returns a lang key for the problem or null. */
    String checkNewName(String name) {
        if (!WorldSettings.validName(name) || paths.modern() && !name.equals(name.toLowerCase(Locale.ROOT))) return "bad-name";
        if (plugin.registry().contains(name) || loaded(name) != null) return "exists";
        if (Files.exists(paths.folder(name))) return "folder-exists";
        return null;
    }

    Outcome create(WorldSettings w) {
        if (w.customHeight()) {
            if (!paths.customHeights()) return Outcome.fail("custom build heights need Minecraft 26.x");
            if (DimensionPack.exists(paths.datapackLevel(), w.name) || Files.exists(paths.folder(DimensionPack.NAMESPACE, w.name))) return Outcome.fail("a data pack dimension or folder for " + w.name + " already exists");
            String bukkitName = Bukkit.getWorlds().getFirst().getName() + "_" + DimensionPack.NAMESPACE + "_" + w.name.toLowerCase(Locale.ROOT);
            if (w.generator.type() == GeneratorSpec.Type.PLUGIN) {
                org.bukkit.plugin.Plugin owner = generators.plugin(w.generator.plugin());
                if (owner == null) return Outcome.fail("generator plugin " + w.generator.plugin() + " is not installed");
                if (!Generators.loadsAtStartup(owner)) return Outcome.fail(owner.getName() + " must load at server start (load: STARTUP in its plugin.yml) to generate a world with a custom build height; otherwise the server would create it with vanilla terrain");
                try { Generators.writeBukkitYml(plugin.core().data().files(), java.nio.file.Path.of("bukkit.yml"), bukkitName, w.generator.toString()); }
                catch (IOException failure) { return Outcome.fail("bukkit.yml: " + failure.getMessage()); }
            }
            try { DimensionPack.write(plugin.core().data().files(), paths.datapackLevel(), w.name, w.kind, w.minY, w.height, plugin.getServer().getClass().getClassLoader()); }
            catch (IOException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Could not write the data pack for " + w.name, failure);
                return Outcome.fail(String.valueOf(failure.getMessage()));
            }
            plugin.registry().put(w);
            plugin.saveRegistry();
            loadErrors.put(WorldRegistry.key(w.name), "custom build height: created by the server on the next restart");
            return new Outcome(null, null);
        }
        Outcome outcome = load(w, true);
        if (outcome.world() == null) return outcome;
        World world = outcome.world();
        if (w.kind == WorldKind.VOID && w.generator.none()) platform(world, w);
        plugin.registry().put(w);
        plugin.saveRegistry();
        return outcome;
    }

    // ------------------------------------------------------------------ spawn of a new world

    /**
     * Gives a new world a provisional spawn (Paper 26.x {@code WorldCreator#forcedSpawnPosition}), so the server does
     * not search one itself: that search generates chunk after chunk on the server thread and froze the server for
     * a minute and more. False on servers without the method (they keep the vanilla search).
     */
    static boolean forceSpawn(WorldCreator creator, int x, int y, int z) {
        return WorldCompat.INSTANCE.forceSpawn(creator, x, y, z);
    }

    /** Whether the world still waits for {@link #findSpawn}. */
    boolean spawnPending(World world) { return world != null && spawnPending.contains(world.getUID()); }

    /** Chunks checked for a spawn, nearest first: rings around 0,0 every {@code step} chunks up to {@code radius} chunks. */
    static List<int[]> spawnChunks(int radius, int step) {
        List<int[]> list = new ArrayList<>();
        for (int r = 0; r <= radius; r += step)
            for (int x = -r; x <= r; x += step)
                for (int z = -r; z <= r; z += step)
                    if (Math.max(Math.abs(x), Math.abs(z)) == r) list.add(new int[] {x, z});
        return list;
    }

    /**
     * Searches land for the spawn of a new world, one chunk at a time with asynchronous chunk loading, so the server
     * never waits for terrain generation. {@code progress} gets the number of checked areas now and then,
     * {@code done} the spawn (or the provisional spawn when only water was found, null when the world is gone).
     */
    void findSpawn(World world, java.util.function.IntConsumer progress, Consumer<Location> done) {
        findSpawn(world.getUID(), spawnChunks(32, 4), 0, progress, done);
    }

    private void findSpawn(java.util.UUID id, List<int[]> chunks, int index, java.util.function.IntConsumer progress, Consumer<Location> done) {
        World world = Bukkit.getWorld(id);
        if (world == null) { spawnPending.remove(id); done.accept(null); return; }
        if (index >= chunks.size()) {
            spawnPending.remove(id);
            Location fallback = world.getSpawnLocation();
            plugin.core().compat().platform().getChunkAtAsync(world, fallback.getBlockX() >> 4, fallback.getBlockZ() >> 4).whenComplete((chunk, failure) -> plugin.tasks().now(() -> {
                World still = Bukkit.getWorld(id);
                if (still == null) { done.accept(null); return; }
                Location top = still.getHighestBlockAt(fallback.getBlockX(), fallback.getBlockZ()).getLocation().add(0.5, 1, 0.5);
                still.setSpawnLocation(top);
                spawnSet(still);
                plugin.getLogger().info("No dry spawn found near 0,0 in " + still.getName() + "; spawn set to " + top.getBlockX() + " " + top.getBlockY() + " " + top.getBlockZ());
                done.accept(top);
            }));
            return;
        }
        if (index > 0 && index % 32 == 0) progress.accept(index);
        int[] chunk = chunks.get(index);
        if (!dryBiome(world, (chunk[0] << 4) + 8, (chunk[1] << 4) + 8)) { findSpawn(id, chunks, index + 1, progress, done); return; } // no chunk generated for open sea
        plugin.core().compat().platform().getChunkAtAsync(world, chunk[0], chunk[1]).whenComplete((loaded, failure) -> onMain(() -> {
            World still = Bukkit.getWorld(id);
            Location spot = still == null || failure != null || !still.isChunkLoaded(chunk[0], chunk[1]) ? null : land(still, chunk[0], chunk[1]);
            if (spot == null) { plugin.tasks().later(2L, () -> findSpawn(id, chunks, index + 1, progress, done)); return; } // spread the generation load
            spawnPending.remove(id);
            still.setSpawnLocation(spot);
            spawnSet(still);
            plugin.getLogger().info("Spawn of " + still.getName() + " set to " + spot.getBlockX() + " " + spot.getBlockY() + " " + spot.getBlockZ() + " (" + (index + 1) + " areas checked)");
            done.accept(spot);
        }));
    }

    /** Right away on the server thread (Paper completes chunk futures there, while the chunk is surely loaded), else next tick. */
    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run(); else plugin.tasks().now(task);
    }

    /** The biome the generator will place there (computed without generating the chunk) is no ocean or river. */
    boolean dryBiome(World world, int x, int z) {
        try {
            return !wetBiome(((org.bukkit.Keyed) plugin.core().compat().world().computedBiome(world, x, world.getSeaLevel(), z)).getKey().getKey());
        } catch (RuntimeException | LinkageError unknown) {
            return true;
        }
    }

    static boolean wetBiome(String key) { return key.contains("ocean") || key.contains("river"); }

    /** Keep-spawn-loaded tickets follow the new spawn. */
    private void spawnSet(World world) {
        WorldSettings w = plugin.settings(world);
        if (w != null) apply(world, w);
    }

    /** A dry, safe place to stand in a loaded chunk (columns nearest the chunk centre first), or null. */
    static Location land(World world, int chunkX, int chunkZ) {
        SafeSpot.Blocks blocks = (x, y, z) -> y < world.getMinHeight() ? SafeSpot.Kind.DANGER
                : y >= world.getMaxHeight() ? SafeSpot.Kind.FREE : SafeSpot.classify(world.getBlockAt(x, y, z).getType());
        for (int[] offset : new int[][] {{8, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12}, {8, 0}, {0, 8}, {8, 15}, {15, 8}}) {
            int x = (chunkX << 4) + offset[0], z = (chunkZ << 4) + offset[1];
            int y = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y + 1 < world.getMaxHeight() && SafeSpot.safe(blocks, x, y, z)) return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** A 5x5 stone platform as spawn of a void world. */
    static int platformY(World world) { return Math.max(world.getMinHeight() + 1, Math.min(64, world.getMaxHeight() - 4)); }

    static void platform(World world, WorldSettings w) {
        int y = platformY(world);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) world.getBlockAt(x, y - 1, z).setType(Material.STONE, false);
        w.spawn = new WorldSettings.Spawn(0.5, y, 0.5, 0f, 0f);
        world.setSpawnLocation(new Location(world, 0.5, y, 0.5));
    }

    /**
     * Imports a folder and loads it. On 26.x Paper itself moves an old-layout folder from the server folder into the
     * level when the world is loaded; it refuses (and nothing is lost) if a target file already exists.
     */
    void importWorld(WorldPaths.Candidate candidate, WorldSettings w, Consumer<Outcome> done) {
        if (candidate.legacy() && paths.modern() && !candidate.folder().getFileName().toString().equals(w.name)) {
            done.accept(Outcome.fail("rename the folder " + candidate.folder().getFileName() + " to " + w.name + " (lower case) first"));
            return;
        }
        Outcome outcome = load(w);
        if (outcome.world() != null) { plugin.registry().put(w); plugin.saveRegistry(); }
        done.accept(outcome);
    }

    // ------------------------------------------------------------------ unload / delete / regenerate / clone

    /** Where players go when their world closes: the main world's spawn. */
    static Location fallback() { return Bukkit.getWorlds().getFirst().getSpawnLocation(); }

    private void evacuate(World world) {
        Location target = fallback();
        for (Player player : List.copyOf(world.getPlayers())) {
            player.teleport(target);
            plugin.lang().send(player, "evacuated", "world", world.getName());
        }
    }

    /** Unloads a world (saving it); returns a lang key for the problem, or null on success. */
    String unload(String name) {
        World world = loaded(name);
        if (world == null) return "not-loaded";
        if (serverWorld(world)) return "server-world";
        evacuate(world);
        if (!world.getPlayers().isEmpty()) return "unload-failed";
        world.removePluginChunkTickets(plugin);
        return Bukkit.unloadWorld(world, true) ? null : "unload-failed";
    }

    private Path backupTarget(String name, String what) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(new Date());
        return plugin.getDataFolder().toPath().resolve("backups").resolve(name + "-" + what + "-" + stamp + "-" + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    /** Deletes a world: unload, then move its folder to backups (or erase it), then forget it. */
    void delete(String name, Consumer<String> done) {
        WorldSettings w = plugin.registry().get(name);
        World world = loaded(name);
        if (world != null && serverWorld(world) || w != null && w.serverWorld) { done.accept("server-world"); return; }
        if (busy(name)) { done.accept("busy"); return; }
        Path folder = folder(name);
        if (world != null) {
            String problem = unload(name);
            if (problem != null) { done.accept(problem); return; }
        }
        try { paths.checkDestructible(folder, protectedFolders(name)); }
        catch (IOException refused) {
            if (Files.exists(folder)) { plugin.getLogger().warning(refused.getMessage()); done.accept("refused"); return; }
        }
        boolean backup = plugin.getConfig().getBoolean("delete.backup", true);
        busy.add(WorldRegistry.key(name));
        Path target = backupTarget(name, "deleted");
        if (w != null && w.customHeight() && paths.customHeights()) {
            try { DimensionPack.remove(paths.datapackLevel(), name); }
            catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not remove the data pack files of " + name, failure); }
            if (w.generator.type() == GeneratorSpec.Type.PLUGIN) {
                String bukkitName = Bukkit.getWorlds().getFirst().getName() + "_" + DimensionPack.NAMESPACE + "_" + name.toLowerCase(Locale.ROOT);
                try { Generators.writeBukkitYml(plugin.core().data().files(), java.nio.file.Path.of("bukkit.yml"), bukkitName, ""); }
                catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not remove " + bukkitName + " from bukkit.yml", failure); }
            }
        }
        plugin.tasks().async(() -> {
            String error = null;
            try {
                if (Files.exists(folder)) {
                    if (backup) WorldPaths.moveTree(folder, target);
                    else WorldPaths.deleteTree(folder);
                }
            } catch (IOException failure) {
                error = failure.toString();
                plugin.getLogger().log(Level.WARNING, "Deleting world folder " + folder + " failed", failure);
            }
            String result = error;
            plugin.tasks().now(() -> {
                busy.remove(WorldRegistry.key(name));
                if (result != null) { done.accept("delete-failed"); return; }
                plugin.registry().remove(name);
                for (WorldSettings other : plugin.registry().all()) {
                    if (other.nether.equalsIgnoreCase(name)) other.nether = "";
                    if (other.end.equalsIgnoreCase(name)) other.end = "";
                    if (other.respawnWorld.equalsIgnoreCase(name)) other.respawnWorld = "";
                }
                loadErrors.remove(WorldRegistry.key(name));
                plugin.saveRegistry();
                plugin.links().sync();
                done.accept(null);
            });
        });
    }

    /**
     * Regenerates a world: unload, move its chunk data to backups, load again with {@code seed} (null = keep the seed,
     * "random" handled by the caller). The world entry and its properties stay.
     */
    void regenerate(String name, Long seed, Consumer<String> done) {
        WorldSettings w = plugin.registry().get(name);
        if (w == null) { done.accept("unknown-world"); return; }
        World world = loaded(name);
        if (world != null && serverWorld(world) || w.serverWorld) { done.accept("server-world"); return; }
        if (busy(name)) { done.accept("busy"); return; }
        if (!w.generator.none() && generators.resolve(w.generator, w.biomeProvider, w.name).failed()) { done.accept("generator-missing"); return; }
        Path folder = folder(name);
        if (world != null) {
            String problem = unload(name);
            if (problem != null) { done.accept(problem); return; }
        }
        try { paths.checkDestructible(folder, protectedFolders(name)); }
        catch (IOException refused) { if (Files.exists(folder)) { plugin.getLogger().warning(refused.getMessage()); done.accept("refused"); return; } }
        busy.add(WorldRegistry.key(name));
        Path target = backupTarget(name, "regenerated");
        boolean backup = plugin.getConfig().getBoolean("delete.backup", true);
        plugin.tasks().async(() -> {
            String error = null;
            try {
                for (String sub : WorldPaths.CHUNK_DATA) {
                    Path data = folder.resolve(sub);
                    if (!Files.exists(data)) continue;
                    if (backup) WorldPaths.moveTree(data, target.resolve(sub));
                    else WorldPaths.deleteTree(data);
                }
            } catch (IOException failure) {
                error = failure.toString();
                plugin.getLogger().log(Level.WARNING, "Regenerating " + name + " failed", failure);
            }
            String result = error;
            plugin.tasks().now(() -> {
                busy.remove(WorldRegistry.key(name));
                if (result != null) { load(w); done.accept("delete-failed"); return; }
                if (seed != null) w.seed = seed;
                w.spawn = null;
                Outcome outcome = w.kind == WorldKind.VOID && w.generator.none() ? recreateVoid(w) : load(w);
                plugin.saveRegistry();
                done.accept(outcome.world() == null ? (w.customHeight() ? "regenerated-restart" : "load-failed") : null);
            });
        });
    }

    private Outcome recreateVoid(WorldSettings w) {
        plugin.registry().remove(w.name);
        return create(w);
    }

    /** How a world is copied: not loaded (plain copy), unloaded for the copy and loaded again, or copied while loaded. */
    enum CloneMode { OFFLINE, RELOAD, LIVE }

    /**
     * A loaded world is unloaded for the copy (all chunks saved and the region files closed, so the copy can never be
     * torn) and loaded again afterwards. Worlds that cannot be unloaded and loaded by us (server worlds, custom build
     * heights, worlds of other plugins) are copied live after a flushed save with auto-save off.
     */
    static CloneMode cloneMode(boolean loaded, boolean serverWorld, WorldSettings from) {
        if (!loaded) return CloneMode.OFFLINE;
        if (serverWorld || from == null || from.serverWorld || from.customHeight() || from.hidden && !from.autoload) return CloneMode.LIVE;
        return CloneMode.RELOAD;
    }

    /** Players in the source world: the admin must confirm (they are moved out, or their latest changes may be missing). */
    static String clonePlayersProblem(CloneMode mode, int players, boolean confirmed) {
        if (mode == CloneMode.OFFLINE || players == 0 || confirmed) return null;
        return mode == CloneMode.RELOAD ? "clone-confirm-evacuate" : "clone-confirm-live";
    }

    /**
     * Copies a world's folder to a new world name and loads it with the same properties. See {@link #cloneMode} for
     * how a loaded source is kept consistent; {@code confirmed}: go on although players are in the source world.
     */
    void cloneWorld(String source, String targetName, boolean confirmed, Consumer<String> done) {
        WorldSettings from = plugin.registry().get(source);
        World world = loaded(source);
        if (from == null && world == null) { done.accept("unknown-world"); return; }
        String problem = checkNewName(targetName);
        if (problem != null) { done.accept(problem); return; }
        if (busy(source)) { done.accept("busy"); return; }
        Path sourceFolder = folder(source), targetFolder = paths.folder(targetName);
        if (!WorldPaths.strictlyInside(paths.root(), targetFolder)) { done.accept("refused"); return; }
        CloneMode mode = cloneMode(world != null, world != null && serverWorld(world), from);
        String players = clonePlayersProblem(mode, world == null ? 0 : world.getPlayers().size(), confirmed);
        if (players != null) { done.accept(players); return; }
        if (mode == CloneMode.LIVE && !WorldCompat.INSTANCE.canFlush()) { done.accept("clone-live-unsupported"); return; }
        boolean autoSave = world != null && world.isAutoSave();
        if (mode == CloneMode.RELOAD) {
            String unloaded = unload(source); // saves every chunk and closes the region files
            if (unloaded != null) { done.accept(unloaded); return; }
        } else if (mode == CloneMode.LIVE) {
            WorldCompat.INSTANCE.flush(world); // flush: written to disk before the copy starts, not only queued
            world.setAutoSave(false);
        }
        World live = mode == CloneMode.LIVE ? world : null;
        busy.add(WorldRegistry.key(source));
        busy.add(WorldRegistry.key(targetName));
        plugin.tasks().async(() -> {
            String error = null;
            try { WorldPaths.copyTree(sourceFolder, targetFolder); }
            catch (IOException failure) {
                error = failure.toString();
                plugin.getLogger().log(Level.WARNING, "Cloning " + source + " failed", failure);
                try { WorldPaths.deleteTree(targetFolder); } catch (IOException ignored) { }
            }
            String result = error;
            plugin.tasks().now(() -> {
                busy.remove(WorldRegistry.key(source));
                busy.remove(WorldRegistry.key(targetName));
                if (live != null && Bukkit.getWorld(live.getUID()) != null) live.setAutoSave(autoSave);
                if (mode == CloneMode.RELOAD && load(from).world() == null)
                    plugin.getLogger().warning("World " + source + " could not be loaded again after copying it; load it with /worlds load " + source);
                if (result != null) { done.accept("clone-failed"); return; }
                WorldSettings copy = copyOf(from, world, targetName);
                Outcome outcome = load(copy);
                if (outcome.world() == null) { done.accept("load-failed"); return; }
                plugin.registry().put(copy);
                plugin.saveRegistry();
                done.accept(null);
            });
        });
    }

    /**
     * Settings of a clone: the source's properties under the new name. A copy of a server world or of an adopted world of
     * another plugin (both registered with autoload off, the latter hidden) is an ordinary world of this plugin, so it
     * loads on start and shows in the selector; without that it was gone after the next restart.
     */
    static WorldSettings copyOf(WorldSettings from, World world, String name) {
        WorldSettings copy = new WorldSettings(name);
        boolean adopted = from != null && (from.serverWorld || from.hidden && !from.autoload);
        if (from != null) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            from.write(yaml);
            copy = WorldSettings.read(name, yaml);
        } else {
            copy.kind = WorldKind.of(world.getEnvironment());
            copy.seed = world.getSeed();
        }
        copy.serverWorld = false;
        copy.nether = "";
        copy.end = "";
        copy.displayName = "";
        if (adopted) {
            copy.autoload = true;
            copy.hidden = false;
        }
        return copy;
    }

    /** Loads chunks around a location asynchronously; {@code then} runs on the server thread. */
    void loadAround(World world, int x, int z, int radius, Runnable then) {
        int r = Math.max(0, (radius + 15) >> 4);
        List<java.util.concurrent.CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = (x >> 4) - r; cx <= (x >> 4) + r; cx++)
            for (int cz = (z >> 4) - r; cz <= (z >> 4) + r; cz++) futures.add(plugin.core().compat().platform().getChunkAtAsync(world, cx, cz));
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((ok, failure) -> plugin.tasks().now(then));
    }

    Collection<String> loadErrorNames() { return loadErrors.keySet(); }
}
