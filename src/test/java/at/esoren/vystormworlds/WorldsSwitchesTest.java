package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.junit.jupiter.api.Test;

/** The 1.17.0 feature switches: off stops the feature, on behaves as before, an old config keeps working. */
class WorldsSwitchesTest {
    static final List<String> NEW = List.of(Features.MANAGEMENT, Features.SELECTOR, Features.FAVORITES, Features.SAFE_SPOT,
            Features.PORTALS, Features.WORLD_RULES, Features.ENTRY_RULES, Features.BORDER, Features.ADMIN_MENU, Features.TUTORIAL);

    private static YamlConfiguration shipped() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static YamlConfiguration with(String key, boolean value) {
        YamlConfiguration config = shipped();
        config.set(key, value);
        return config;
    }

    private static WorldsPlugin plugin(YamlConfiguration config, Lang lang) {
        WorldsPlugin plugin = mock(WorldsPlugin.class);
        when(plugin.getName()).thenReturn("VystormWorlds");
        if (!Boolean.getBoolean("paper1206")) when(plugin.namespace()).thenReturn("vystormworlds");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.lang()).thenReturn(lang);
        when(plugin.registry()).thenReturn(mock(WorldRegistry.class));
        return plugin;
    }

    // ---- shipped file, old files, descriptions -------------------------------------------------------------------

    @Test void givenShippedConfig_whenRead_thenEverySwitchIsABooleanAndOn() {
        YamlConfiguration config = shipped();
        for (String key : Features.SWITCHES) assertTrue(config.isBoolean(key) && config.getBoolean(key), key);
        for (String key : Features.TOP_LEVEL) assertTrue(config.isBoolean(key), key);
        for (String key : Features.DECLARED) assertTrue(config.isBoolean(key), key);
        assertTrue(Features.SWITCHES.containsAll(NEW));
    }

    @Test void givenEverySwitch_whenDescribed_thenAllSevenLanguagesHaveTheLine() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (List<String> group : List.of(Features.SWITCHES, Features.TOP_LEVEL, Features.DECLARED))
                for (String key : group) assertFalse(lang.getString(Features.descriptionKey(key), "").isBlank(), code + " " + key);
            for (String key : WorldsPlaceholders.KEYS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder " + key);
            assertFalse(lang.getString(Features.OFF, "").isBlank(), code + " off message");
        }
        assertEquals("features.teleport-safe-spot", Features.descriptionKey(Features.SAFE_SPOT));
        assertEquals("features.groups-notify", Features.descriptionKey("groups.notify"));
    }

    @Test void givenOldConfigWithoutNewKeys_whenRead_thenEverythingIsOn() {
        YamlConfiguration old = shipped();
        for (String key : NEW) old.set(key, null);
        for (String key : NEW) assertTrue(Features.on(old, key), key);
        assertTrue(Features.on(null, Features.MANAGEMENT), "no config yet counts as on");
    }

    // ---- commands ------------------------------------------------------------------------------------------------

    @Test void givenFeaturesOff_whenTheirCommandsRun_thenOffMessageAndOtherCommandsWork() {
        record Case(String key, String[] args) {}
        List<Case> cases = List.of(
                new Case(Features.MANAGEMENT, new String[]{"create", "farm"}), new Case(Features.MANAGEMENT, new String[]{"unload", "farm"}),
                new Case(Features.MANAGEMENT, new String[]{"delete", "farm"}), new Case(Features.MANAGEMENT, new String[]{"clone", "a", "b"}),
                new Case(Features.ADMIN_MENU, new String[]{"admin"}), new Case(Features.ADMIN_MENU, new String[]{"edit", "farm"}),
                new Case(Features.SELECTOR, new String[]{"menu"}), new Case(Features.SELECTOR, new String[]{"tp", "farm"}),
                new Case(Features.FAVORITES, new String[]{"favorites"}), new Case("teleport.return-to-last-position", new String[]{"arrival", "spawn"}));
        for (Case c : cases) {
            Lang lang = mock(Lang.class);
            Player admin = mock(Player.class);
            when(admin.hasPermission(anyString())).thenReturn(true);
            new WorldsCommand(plugin(with(c.key(), false), lang)).run(admin, "worlds", c.args());
            verify(lang).send(admin, Features.OFF);
        }
        Lang lang = mock(Lang.class);
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        new WorldsCommand(plugin(shipped(), lang)).run(console, "worlds", new String[]{"unload"});
        verify(lang).send(console, "usage-world", "label", "worlds", "sub", "unload");
        verify(lang, never()).send(console, Features.OFF);
    }

    @Test void givenAdminTeleportsAnotherPlayer_whenSelectorOff_thenItStillWorks() {
        assertEquals(List.of(), WorldsCommand.switchesOf("tp", new String[]{"tp", "farm", "Alex"}));
        assertEquals(List.of(Features.SELECTOR), WorldsCommand.switchesOf("tp", new String[]{"tp", "farm"}));
        assertEquals(List.of(), WorldsCommand.switchesOf("reload", new String[]{"reload"}), "reload always works");
    }

    @Test void givenFeaturesSwitched_whenTabCompleting_thenTheSameTreeFollowsTheSwitchAtOnce() {
        YamlConfiguration config = with(Features.MANAGEMENT, false);
        config.set(Features.TEMPLATES, false);
        var spec = WorldsCommand.commandSpec(new WorldsCommand(plugin(config, mock(Lang.class))));
        List<String> switched = List.of("create", "import", "load", "unload", "delete", "regen", "clone", "template", "visit");
        for (String name : switched) {
            assertFalse(spec.children().get(name).visibleTo(null), name);
            assertFalse(spec.children().get(name).hidden(), name + " is not hidden for good");
        }
        for (String shown : List.of("menu", "tp", "list", "reload", "set", "admin", "leave"))
            assertTrue(spec.children().get(shown).visibleTo(null), shown);
        config.set(Features.MANAGEMENT, true);
        config.set(Features.TEMPLATES, true);
        config.set(Features.SELECTOR, false);
        for (String name : switched) assertTrue(spec.children().get(name).visibleTo(null), name + " after switching on");
        for (String name : List.of("menu", "spawn", "favorite")) assertFalse(spec.children().get(name).visibleTo(null), name + " after switching the selector off");
    }

    @Test void givenReload_whenASwitchChanged_thenCommandListsAreRefreshedAndNothingIsRegisteredAgain() throws Exception {
        WorldsPlugin plugin = mock(WorldsPlugin.class, CALLS_REAL_METHODS);
        YamlConfiguration[] config = {shipped()};
        doAnswer(invocation -> config[0]).when(plugin).getConfig();
        doNothing().when(plugin).reloadConfig();
        var core = mock(Vystorm.vystorm_core.API.class);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        WorldRegistry registry = mock(WorldRegistry.class);
        when(registry.load()).thenReturn(true);
        for (var entry : java.util.Map.of("core", (Object) core, "registry", registry).entrySet()) {
            var field = WorldsPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(plugin, entry.getValue());
        }
        plugin.reload();
        verify(commands, never()).refresh();
        doAnswer(invocation -> { config[0] = with(Features.FAVORITES, false); return null; }).when(plugin).reloadConfig();
        plugin.reload();
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    // ---- rules ---------------------------------------------------------------------------------------------------

    private static FoodLevelChangeEvent hungerEvent(Player player) {
        FoodLevelChangeEvent event = mock(FoodLevelChangeEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getFoodLevel()).thenReturn(10);
        when(player.getFoodLevel()).thenReturn(15);
        return event;
    }

    @Test void givenWorldRulesOff_whenHungerOrWorldApplied_thenNothingIsEnforced() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        WorldSettings settings = new WorldSettings("farm");
        settings.hunger = false;
        for (boolean on : new boolean[]{false, true}) {
            WorldsPlugin plugin = plugin(with(Features.WORLD_RULES, on), mock(Lang.class));
            when(plugin.settings(world)).thenReturn(settings);
            FoodLevelChangeEvent event = hungerEvent(player);
            new PlayerRules(plugin).hunger(event);
            verify(event, on ? times(1) : never()).setCancelled(true);
            World target = mock(World.class);
            new WorldManager(plugin, mock(WorldPaths.class), mock(Generators.class)).apply(target, settings);
            verify(target, on ? times(1) : never()).setPVP(anyBoolean());
        }
    }

    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }

    @Test void givenEntryRulesOff_whenPlayerEnters_thenNothingHoldsHimBack() {
        WorldSettings locked = new WorldSettings("vip");
        locked.permission = true;
        locked.playerLimit = 1;
        Player player = mock(Player.class);
        when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        Access.Viewer off = new PlayerRules(plugin(with(Features.ENTRY_RULES, false), mock(Lang.class))).viewer(player, locked);
        assertEquals(Access.Result.OK, Access.check(locked, true, off, 5));
        Access.Viewer on = new PlayerRules(plugin(shipped(), mock(Lang.class))).viewer(player, locked);
        assertEquals(Access.Result.NO_PERMISSION, Access.check(locked, true, on, 0));
    }

    @Test void givenSafeSpotOff_whenLanding_thenExactlyOnTheTarget() {
        Location target = mock(Location.class);
        assertSame(target, Teleports.landing(target, 8, false));
        verifyNoInteractions(target);
    }

    @Test void givenPortalsOff_whenPortalUsed_thenVanillaBehaviour() {
        WorldsPlugin plugin = plugin(with(Features.PORTALS, false), mock(Lang.class));
        PlayerPortalEvent event = mock(PlayerPortalEvent.class);
        Location from = mock(Location.class);
        when(from.getWorld()).thenReturn(mock(World.class));
        when(event.getFrom()).thenReturn(from);
        new PortalLinks(plugin).playerPortal(event);
        verify(event, never()).setTo(any());
        verify(event, never()).setCancelled(true);
        verify(plugin, never()).rules();
    }

    @Test void givenBorderOff_whenApplied_thenTheBorderIsLeftAlone() {
        WorldSettings settings = new WorldSettings("arena");
        settings.border = 2000;
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        new BorderEvents(plugin(with(Features.BORDER, false), mock(Lang.class))).apply(world, settings);
        verify(world, never()).getWorldBorder();
    }

    @Test void givenTutorial_whenRead_thenRestartSwitch() {
        assertFalse(Features.on(with(Features.TUTORIAL, false), Features.TUTORIAL));
        assertTrue(shipped().getComments("tutorial.enabled").stream().anyMatch(c -> c != null && c.contains("restart")));
    }

    // ---- placeholders --------------------------------------------------------------------------------------------

    private static final class Fake implements WorldsPlaceholders.Source {
        boolean groups = true, resets = true, templates = true, entry = true;
        final WorldSettings farm = new WorldSettings("farm");
        final World world;
        Fake(World world) { this.world = world; farm.group = "survival"; farm.playerLimit = 20; }
        public WorldSettings settings(World w) { return w == world ? farm : null; }
        public String group(World w) { return "survival"; }
        public boolean groupsOn() { return groups; }
        public boolean resetsOn() { return resets; }
        public boolean templatesOn() { return templates; }
        public boolean entryRulesOn() { return entry; }
        public ZonedDateTime nextReset(String name) { return ZonedDateTime.parse("2026-11-19T04:00:00+01:00[Europe/Vienna]"); }
        public Collection<WorldSettings> all() { return List.of(farm, new WorldSettings("lobby")); }
        public World loaded(String name) { return name.equals("farm") ? world : null; }
        public boolean inVisit(Player player) { return false; }
        public String title(WorldSettings settings) { return "Farm World"; }
    }

    @Test void givenRegisteredValues_whenResolved_thenPlainTextAndEmptyWhenOff() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("farm");
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getPlayers()).thenReturn(List.of(player));
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Fake source = new Fake(world);
        WorldsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormWorlds"), null, source);
        assertEquals("Farm World farm survival 20", testing.resolve(player, "{worlds:world} {worlds:world_name} {worlds:world_group} {worlds:world_limit}"));
        assertEquals("1 1 2 1", testing.resolve(player, "{worlds:world_players} %vystorm_worlds_world_players_farm% {worlds:worlds_count} {worlds:worlds_loaded}"));
        assertEquals("2026-11-19 04:00 false", testing.resolve(player, "{worlds:world_next_reset} {worlds:in_visit}"));
        source.groups = false;
        source.resets = false;
        source.templates = false;
        source.entry = false;
        assertEquals("[][][][]", testing.resolve(player, "[{worlds:world_group}][{worlds:world_next_reset}][{worlds:in_visit}][{worlds:world_limit}]"));
        assertEquals("{worlds:world_name}", testing.resolveOffThread(player, "{worlds:world_name}"), "server thread only");
    }
}
