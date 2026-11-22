package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vystorm Worlds: create, import, load and manage worlds, world groups with separate inventories, linked nether and
 * end, safe teleports and a world selector - on top of Vystorm Core (lang, menus, dialogs, config editor, tasks).
 */
public final class WorldsPlugin extends JavaPlugin implements Listener {
    private Lang lang;
    private TaskGroup tasks;
    /** The Core hub; set first thing in onEnable. */
    private Vystorm.vystorm_core.API core;
    private WorldRegistry registry;
    private WorldManager manager;
    private Teleports teleports;
    private PlayerRules rules;
    private PortalLinks links;
    private GroupSwitcher switcher;
    private Menus menus;
    private TemplateVisits templates;
    private ChunkGenerator voidGenerator;
    private boolean started;

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast("0.66.0")) {
            getLogger().severe("Vystorm Worlds requires Vystorm Core 0.66.0 or newer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();
        registerUpdateCheck(this, core);
        saveDefaultConfig();
        readConfig(); // a broken config.yml is named with line and column and never written
        lang = core.lang().register(this); try { if (Features.on(getConfig(), Features.TUTORIAL)) core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
        tasks = core.tasks().of(this, "worlds");
        registry = new WorldRegistry(getDataFolder().toPath().resolve("worlds.yml"), core.data().files());
        loadRegistry();
        explored = new ExploredWorlds(getDataFolder().toPath().resolve("explored.yml"), core.data().files());
        if (explored.problem() != null) getLogger().severe(explored.problem());
        manager = new WorldManager(this, WorldPaths.detect(getServer()), new Generators(getServer().getPluginManager(), this));
        if (!manager.paths().customHeights()) getLogger().info("Custom build heights unavailable: this server has no supported custom-dimension loader.");
        if (!WorldCompat.INSTANCE.canFlush()) getLogger().info("Live world cloning unavailable: this server has no flush-save API; own worlds can still be unloaded and cloned.");
        teleports = new Teleports(this);
        switcher = new GroupSwitcher(new StateStore(getDataFolder().toPath().resolve("groups"), core.data().files()));
        rules = new PlayerRules(this);
        links = new PortalLinks(this);
        menus = new Menus(this);
        try { templates = new TemplateVisits(this); }
        catch (Exception failure) { getLogger().severe("Visit templates unavailable; preserving templates.yml: " + failure); }
        VystormWorldsApi.attach(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(rules, this);
        rules.handleSharedEvents(core.priority().stages());
        links.handleSharedEvents(core.priority().stages());
        pm.registerEvents(menus, this);
        core.command().register(this, lang, WorldsCommand.commandSpec(new WorldsCommand(this)));
        registerCore();
        // after every plugin is enabled, so generator plugins and presets are ready
        tasks.later(1L, this::startup);
        tasks.timer(200L, 200L, this::enforceLocks);
        resets = new WorldResets(this);
        tasks.timer(400L, 400L, resets::tick);
        borders = new BorderEvents(this);
        tasks.timer(400L, 400L, borders::tick);
        for (WorldSettings w : registry().all()) { org.bukkit.World loaded = WorldManager.loaded(w.name); if (loaded != null) borders.apply(loaded, w); }
        if (templates != null) tasks.timer(20L, 20L, templates::tick);
        tasks.timer(1200L, 1200L, this::saveExplored);
    }

    // ------------------------------------------------------------------ ranking worlds:explored (1.19.0)

    private ExploredWorlds explored;

    /** Counts the player's current world for worlds:explored: worlds of worlds.yml that are not hidden, no visit worlds. */
    void explored(org.bukkit.entity.Player player) {
        if (explored == null || player == null || !Features.on(getConfig(), Features.RANKINGS)) return;
        WorldSettings w = settings(player.getWorld());
        if (w == null || w.hidden || rules == null || rules.coreWorld(player.getWorld()) || rules.inVisit(player)) return;
        explored.record(player.getUniqueId(), player.getName(), w.name);
    }

    /** At most once a minute: writes explored.yml off the server thread when a count changed. */
    private void saveExplored() {
        ExploredWorlds store = explored;
        ExploredWorlds.Snapshot changes = store == null ? null : store.takeChanges();
        if (changes == null) return;
        tasks.async(() -> {
            try { store.write(changes); }
            catch (IOException failure) {
                getLogger().warning("Could not save explored.yml: " + failure.getMessage());
                tasks.later(1L, store::failed);
            }
        });
    }

    /**
     * {@code worlds:explored} for Vystorm Boards and placeholders, only on a Core with the capability
     * {@code placeholder-rankings} (0.67.0); on an older Core nothing happens. At start and after every reload, so
     * {@code rankings.enabled} applies without a restart.
     */
    private void applyRankings() {
        if (core == null) return;
        boolean has;
        try { has = CoreApi.has("placeholder-rankings"); } catch (RuntimeException | LinkageError old) { has = false; }
        WorldsPlaceholders.rankings(core.placeholders(), this, has, Features.on(getConfig(), Features.RANKINGS), explored, lang, getLogger());
    }

    /** The Core hub for the classes of this plugin (they get the plugin in their constructor). */
    Vystorm.vystorm_core.API core() { return core; }

    @Override
    public void onDisable() {
        if (templates != null) templates.close();
        if (borders != null) borders.close();
        VystormWorldsApi.detach();
        if (tasks != null) tasks.close();
        if (explored != null) {
            try { explored.write(explored.takeChanges()); }
            catch (IOException failure) { getLogger().warning("Could not save explored.yml: " + failure.getMessage()); }
        }
        for (World world : getServer().getWorlds()) world.removePluginChunkTickets(this);
    }

    private void registerCore() {
        var api = core;
        try {
            api.settings().config().register(this, "config.yml", this::reload);
            describeSwitches(api.settings().config(), this, lang);
        } catch (RuntimeException failure) { getLogger().log(Level.FINE, "No config editor", failure); }
        try {
            WorldsPlaceholders.register(api.placeholders(), this, lang, placeholderSource());
        } catch (RuntimeException failure) { getLogger().warning("Placeholders not registered: " + failure); }
        applyRankings();
        try {
            if (!menus.registerPanels()) {
                api.gui().registerMenu(this, "worlds", lang.ref("vmenu-worlds"), Material.FILLED_MAP,
                        p -> p.hasPermission("vystormworlds.use") && Features.on(getConfig(), Features.SELECTOR), p -> menus.selector(p, 0),
                        Vystorm.vystorm_core._uiMethods.menu.MenuCategory.WORLD.group(), lang.ref("vmenu-worlds-desc"));
                api.gui().registerMenu(this, "worlds-admin", lang.ref("vmenu-admin"), Material.COMPASS,
                        p -> p.hasPermission("vystormworlds.admin") && Features.on(getConfig(), Features.ADMIN_MENU), p -> menus.admin(p, 0),
                        Vystorm.vystorm_core._uiMethods.menu.MenuCategory.ADMIN.group(), lang.ref("vmenu-admin-desc"));
            }
        } catch (RuntimeException failure) { getLogger().log(Level.FINE, "No Core main menu entry", failure); }
        try {
            AdminEntries.register(this);
        } catch (RuntimeException failure) { getLogger().log(Level.FINE, "No admin module", failure); }
        try {
            WorldSection.register(this);
        } catch (RuntimeException failure) { getLogger().log(Level.FINE, "No settings section", failure); }
    }

    /**
     * One-line descriptions in the admin's language for every switch in {@code /vadmin features}, and the older
     * booleans with other names declared as switches (their keys stay as they are).
     */
    /** SpigotMC resource of this plugin; Core's update check tells admins about a newer version, as for the other plugins. */
    static final int SPIGOT_RESOURCE = 140132;

    static void registerUpdateCheck(org.bukkit.plugin.Plugin plugin, Vystorm.vystorm_core.API core) {
        core.admin().updates().spigot(plugin, SPIGOT_RESOURCE);
    }

    static void describeSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Lang lang) {
        for (String path : Features.SWITCHES) config.label(plugin, path, lang.ref(Features.labelKey(path)), lang.ref(Features.descriptionKey(path)));
        for (String path : Features.TOP_LEVEL) config.label(plugin, path, lang.ref(Features.labelKey(path)), lang.ref(Features.descriptionKey(path)));
        for (String path : Features.DECLARED) config.feature(plugin, path, lang.ref(Features.labelKey(path)), lang.ref(Features.descriptionKey(path)));
        config.activation(plugin, Features.TUTORIAL, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
    }

    /** What the {@code worlds} placeholders read, looked up at call time. */
    WorldsPlaceholders.Source placeholderSource() {
        return new WorldsPlaceholders.Source() {
            public WorldSettings settings(World world) { return WorldsPlugin.this.settings(world); }
            public String group(World world) { return rules == null ? defaultGroup() : rules.groupOf(world); }
            public boolean groupsOn() { return getConfig().getBoolean("groups.enabled", true); }
            public boolean resetsOn() { return Features.on(getConfig(), Features.RESETS); }
            public boolean templatesOn() { return Features.on(getConfig(), Features.TEMPLATES); }
            public boolean entryRulesOn() { return Features.on(getConfig(), Features.ENTRY_RULES); }
            public java.time.ZonedDateTime nextReset(String world) { return resets == null ? null : resets.nextReset(world); }
            public java.util.Collection<WorldSettings> all() { return registry == null ? java.util.List.of() : registry.all(); }
            public World loaded(String name) { return WorldManager.loaded(name); }
            public boolean inVisit(org.bukkit.entity.Player player) { return rules != null && rules.inVisit(player); }
        };
    }

    /** First tick: adopt loaded worlds, auto-load the rest, apply properties, check online players. */
    private void startup() {
        started = true;
        if (registry.error() != null) {
            getLogger().severe("worlds.yml could not be read (" + registry.error() + "). No world is loaded or changed until it is fixed and /worlds reload is run.");
            return;
        }
        adoptLoaded();
        if (getConfig().getBoolean("autoload", true)) {
            for (WorldSettings w : registry.all()) {
                if (!w.autoload || WorldManager.loaded(w.name) != null) continue;
                var outcome = manager.load(w);
                if (outcome.world() != null) getLogger().info("Loaded world " + w.name);
            }
        }
        for (WorldSettings w : registry.all()) {
            World world = WorldManager.loaded(w.name);
            if (world == null) continue;
            if (w.customHeight()) manager.clearLoadError(w.name);
            if (w.seed == null) w.seed = world.getSeed();
            if (w.customHeight() && w.kind == WorldKind.VOID && w.spawn == null && w.generator.none()) WorldManager.platform(world, w);
            manager.apply(world, w);
        }
        for (String problem : registry.problems()) getLogger().warning("worlds.yml: " + problem);
        saveRegistry();
        links.sync();
        rules.recheckAll();
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) explored(online);
    }

    /** Adds loaded worlds missing in worlds.yml: server worlds visible, worlds of other plugins hidden and not auto-loaded. */
    private void adoptLoaded() {
        if (!getConfig().getBoolean("adopt-loaded-worlds", true)) return;
        for (World world : getServer().getWorlds()) {
            if (settings(world) != null || rules.coreWorld(world) || !WorldSettings.validName(world.getName())) continue;
            WorldSettings w = new WorldSettings(world.getName());
            w.kind = WorldKind.of(world.getEnvironment());
            w.seed = world.getSeed();
            w.serverWorld = WorldManager.serverWorld(world);
            w.autoload = false;
            w.hidden = !w.serverWorld;
            w.group = defaultGroup();
            w.structures = world.canGenerateStructures();
            String[] bukkit = Generators.bukkitYml(Path.of("bukkit.yml"), world.getName());
            try { w.generator = GeneratorSpec.parse(bukkit[0]); } catch (IllegalArgumentException ignored) { }
            w.biomeProvider = bukkit[1];
            if (world.getKey().getNamespace().equals(DimensionPack.NAMESPACE)) { w.minY = world.getMinHeight(); w.height = world.getMaxHeight() - world.getMinHeight(); }
            registry.put(w);
            getLogger().info("Added loaded world " + world.getName() + " to worlds.yml" + (w.serverWorld ? "" : " (hidden, loaded by another plugin)"));
        }
    }

    /** The YAML error of config.yml found by the last read ("line L, column C: ..."), null when it parsed. */
    private String configProblem;

    String configProblem() { return configProblem; }

    /**
     * Reads config.yml at start and on reload. A file with a YAML error is left byte-identical and the error logged
     * once; false is returned and getConfig() keeps what it had (the last good settings, at start the jar defaults).
     */
    boolean readConfig() {
        configProblem = YamlCheck.problem(new java.io.File(getDataFolder(), "config.yml"));
        if (configProblem != null) {
            getLogger().severe("config.yml has a YAML error at " + configProblem + ". The file was left unchanged and the settings read before stay in use; fix it and run /worlds reload.");
            return false;
        }
        reloadConfig();
        return true;
    }

    void reload() {
        var listed = WorldsCommand.listing(getConfig());
        boolean read = readConfig();
        // tab completion asks the switches live; a changed switch only needs the players' command lists sent again
        if (read && core != null && !listed.equals(WorldsCommand.listing(getConfig()))) core.command().refresh();
        if (lang != null) lang.reload();
        applyRankings();
        if (!loadRegistry()) return;
        if (!started) return;
        for (WorldSettings w : registry.all()) {
            World world = WorldManager.loaded(w.name);
            if (world != null) manager.apply(world, w);
        }
        links.sync();
        rules.recheckAll();
    }

    private boolean loadRegistry() {
        if (registry.load()) return true;
        getLogger().severe("worlds.yml could not be read: " + registry.error() + ". It is left untouched; fix it and run /worlds reload.");
        return false;
    }

    /** Saves worlds.yml; logs instead of throwing. */
    boolean saveRegistry() {
        try {
            if (registry.save()) return true;
            getLogger().warning("worlds.yml is not saved because it could not be read at load (" + registry.error() + ")");
        } catch (IOException failure) {
            getLogger().log(Level.SEVERE, "Could not save worlds.yml", failure);
        }
        return false;
    }

    private void enforceLocks() {
        if (!Features.on(getConfig(), Features.WORLD_RULES)) return;
        for (WorldSettings w : registry.all()) {
            if ("off".equals(w.timeLock) && "off".equals(w.weatherLock)) continue;
            World world = WorldManager.loaded(w.name);
            if (world != null) WorldManager.applyLocks(world, w, false);
        }
    }

    @EventHandler
    public void worldLoaded(WorldLoadEvent event) {
        WorldSettings w = settings(event.getWorld());
        if (w != null && started) manager.apply(event.getWorld(), w);
        if (started) tasks.later(1L, links::sync);
    }

    @EventHandler
    public void pluginDisabled(PluginDisableEvent event) { VystormWorldsApi.release(event.getPlugin()); }

    /** Settings of a loaded world (by name, or by key for worlds with a custom build height). */
    WorldSettings settings(World world) {
        if (world == null) return null;
        WorldSettings w = registry.get(world.getName());
        if (w == null && world.getKey().getNamespace().equals(DimensionPack.NAMESPACE)) w = registry.get(world.getKey().getKey());
        return w;
    }

    /** Names already used by loaded or known worlds (lower case). */
    Set<String> usedNames() {
        Set<String> names = new HashSet<>();
        for (World world : getServer().getWorlds()) { names.add(world.getName().toLowerCase(Locale.ROOT)); names.add(world.getKey().getKey()); }
        for (WorldSettings w : registry.all()) names.add(w.name.toLowerCase(Locale.ROOT));
        return names;
    }

    ChunkGenerator voidGenerator() {
        // own generator, not Core's: without a fixed spawn the server searches the empty world for a spawn point
        // chunk by chunk while creating it and the watchdog stops the server
        if (voidGenerator == null) voidGenerator = new VoidGenerator();
        return voidGenerator;
    }

    /** Void generator with a fixed spawn above the platform {@link WorldManager#platform} builds. */
    static final class VoidGenerator extends ChunkGenerator {
        @Override public org.bukkit.Location getFixedSpawnLocation(World world, java.util.Random random) {
            return new org.bukkit.Location(world, 0.5, WorldManager.platformY(world), 0.5);
        }

        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    String defaultGroup() {
        String group = getConfig().getString("default-group", "default");
        return WorldSettings.validGroup(group) ? group : "default";
    }

    Lang lang() { return lang; }
    TemplateVisits templates() { return templates; }

    TaskGroup tasks() { return tasks; }

    WorldRegistry registry() { return registry; }
    private WorldResets resets;
    WorldResets resets() { return resets; }
    private BorderEvents borders;
    BorderEvents borders() { return borders; }

    WorldManager manager() { return manager; }

    Teleports teleports() { return teleports; }

    PlayerRules rules() { return rules; }

    PortalLinks links() { return links; }

    GroupSwitcher switcher() { return switcher; }

    Menus menus() { return menus; }
}
