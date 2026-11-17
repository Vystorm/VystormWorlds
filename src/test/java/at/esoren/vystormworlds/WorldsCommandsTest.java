package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /worlds through Core's command tree with the real language files: routing, names, completion and help. */
class WorldsCommandsTest {
    private final List<String> chat = new ArrayList<>();
    private final WorldsCommand command = mock(WorldsCommand.class);

    private static String read(String name) throws Exception {
        try (InputStream in = WorldsCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CommandRegistration registration() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormWorlds");
        Lang lang = Lang.create("VystormWorlds", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(plugin, lang, WorldsCommand.commandSpec(command));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    private Player admin(Locale locale) { return player(locale, "vystormworlds.use", "vystormworlds.admin"); }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        return console;
    }

    private void assertRuns(CommandRegistration r, CommandSender sender, String label, String typed, String... expected) {
        clearInvocations(command);
        r.execute(sender, label, typed.isEmpty() ? new String[0] : typed.split(" "));
        verify(command).run(eq(sender), eq(label), eq(expected));
    }

    private static String[] words(String s) { return s.isEmpty() ? new String[0] : s.split(" "); }

    @Test
    void favoriteShortcutsRouteInAllLanguagesAndRejectConsole() throws Exception {
        CommandRegistration r = registration();
        assertRuns(r, player(Locale.US, "vystormworlds.use"), "worlds", "favorite farm", "favorite", "farm");
        assertRuns(r, player(Locale.GERMANY, "vystormworlds.use"), "welten", "favoriten", "favorites");
        assertRuns(r, player(Locale.SIMPLIFIED_CHINESE, "vystormworlds.use"), "worlds", "收藏 farm", "favorite", "farm");
        clearInvocations(command);
        r.execute(console(), "worlds", new String[]{"favorites"});
        r.execute(player(Locale.US), "worlds", new String[]{"favorite", "farm"});
        verify(command, never()).run(any(), any(), any());
    }

    @Test
    void arrivalPreferenceUsesLocalizedKeywordsAndPlayerPermission() throws Exception {
        CommandRegistration r = registration();
        assertRuns(r, player(Locale.US, "vystormworlds.use"), "worlds", "arrival last", "arrival", "last");
        assertRuns(r, player(Locale.GERMANY, "vystormworlds.use"), "welten", "ankunft zuletzt", "arrival", "last");
        assertRuns(r, player(Locale.SIMPLIFIED_CHINESE, "vystormworlds.use"), "worlds", "抵达 出生点", "arrival", "spawn");
        when(command.suggest(any(), any())).thenCallRealMethod();
        when(command.complete(any(), any())).thenReturn(List.of("spawn", "last"));
        assertEquals(List.of("spawn", "zuletzt"), r.suggest(player(Locale.GERMANY, "vystormworlds.use"), "welten", new String[]{"ankunft", ""}));
        clearInvocations(command);
        r.execute(console(), "worlds", new String[]{"arrival", "spawn"});
        r.execute(player(Locale.US), "worlds", new String[]{"arrival", "spawn"});
        verify(command, never()).run(any(), any(), any());
    }

    @Test
    void givenEveryOldEnglishPath_whenRun_thenTheOldDispatchGetsTheSameArguments() throws Exception {
        CommandRegistration r = registration();
        Player admin = admin(Locale.US);
        for (String typed : List.of("", "menu", "tp lobby", "tp lobby Bob", "spawn", "admin", "list", "info", "info lobby", "groups",
                "create", "create mine normal seed:42 generator:Terra:default with-nether with-end group:survival min-y:-128 height:512",
                "import", "import old flat seed:1", "load lobby", "unload lobby", "delete lobby", "regen lobby", "regen lobby random",
                "regen lobby 12345", "clone lobby copy", "clone lobby copy confirm", "set lobby pvp false", "set lobby description A nice place",
                "edit", "edit lobby", "settings", "settings lobby", "setspawn", "link lobby nether hell", "link lobby end none", "generators",
                "reload", "lobby", "MENU", "Tp lobby"))
            assertRuns(r, admin, "worlds", typed, words(typed.startsWith("MENU") ? "menu" : typed.startsWith("Tp") ? "tp lobby" : typed));
        assertRuns(r, admin, "vw", "list", "list");
    }

    @Test
    void givenLocalizedNamesAndKeywords_whenRun_thenEnglishIdsReachTheDispatch() throws Exception {
        CommandRegistration r = registration();
        assertRuns(r, admin(Locale.GERMANY), "welten", "liste", "list");
        assertRuns(r, admin(Locale.GERMANY), "welten", "klonen lobby kopie bestätigen", "clone", "lobby", "kopie", "confirm");
        assertRuns(r, admin(Locale.GERMANY), "welten", "neugenerieren lobby zufall", "regen", "lobby", "random");
        assertRuns(r, admin(Locale.GERMANY), "welten", "verknüpfen lobby ende keine", "link", "lobby", "end", "none");
        assertRuns(r, admin(Locale.SIMPLIFIED_CHINESE), "世界", "链接 lobby 下界 无", "link", "lobby", "nether", "none");
        assertRuns(r, admin(Locale.SIMPLIFIED_CHINESE), "世界", "传送 lobby", "tp", "lobby");
        // every language works for everyone, and a world name in a keyword position is kept
        assertRuns(r, admin(Locale.US), "worlds", "reise lobby", "tp", "lobby");
        assertRuns(r, admin(Locale.US), "worlds", "link lobby nether hell", "link", "lobby", "nether", "hell");
    }

    @Test
    void givenWorldNameAsFirstToken_whenRun_thenRootTravels() throws Exception {
        CommandRegistration r = registration();
        assertRuns(r, player(Locale.GERMANY, "vystormworlds.use"), "welten", "farmwelt", "farmwelt");
        assertRuns(r, player(Locale.US, "vystormworlds.use"), "worlds", "farmwelt extra words", "farmwelt", "extra", "words");
    }

    @Test
    void givenConsole_whenRun_thenOldConsoleFormsWorkAndPlayerOnlyIsRefused() throws Exception {
        CommandRegistration r = registration();
        ConsoleCommandSender console = console();
        assertRuns(r, console, "worlds", "", new String[0]);
        assertRuns(r, console, "worlds", "delete lobby lobby", "delete", "lobby", "lobby");
        assertRuns(r, console, "worlds", "regen lobby same lobby", "regen", "lobby", "same", "lobby");
        assertRuns(r, console, "worlds", "tp lobby Bob", "tp", "lobby", "Bob");
        clearInvocations(command);
        chat.clear();
        r.execute(console, "worlds", new String[]{"menu"});
        verify(command, never()).run(any(), any(), any());
        assertEquals("Only players can do that.", chat.get(0));
    }

    @Test
    void givenNoAdminPermission_whenAdminSub_thenRefusedInPlayersLanguage() throws Exception {
        CommandRegistration r = registration();
        r.execute(player(Locale.GERMANY, "vystormworlds.use"), "welten", new String[]{"löschen", "lobby"});
        verify(command, never()).run(any(), any(), any());
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration();
        when(command.rootWorlds()).thenReturn(List.of("lobby"));
        String[] none = {""};
        assertEquals(List.of("menu", "tp", "spawn", "arrival", "favorite", "favorites", "visit", "leave", "help", "lobby"), r.suggest(player(Locale.US, "vystormworlds.use"), "worlds", none));
        assertEquals(List.of("menü", "reise", "spawn", "ankunft", "favorit", "favoriten", "besuchen", "verlassen", "hilfe", "lobby"), r.suggest(player(Locale.GERMANY, "vystormworlds.use"), "welten", none));
        assertEquals(List.of("菜单", "传送", "出生点", "抵达", "收藏", "收藏夹", "访问", "离开", "帮助", "lobby"), r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormworlds.use"), "世界", none));
        List<String> admin = r.suggest(admin(Locale.GERMANY), "welten", none);
        assertTrue(admin.containsAll(List.of("verwaltung", "liste", "erstellen", "löschen", "klonen", "verknüpfen", "neuladen")), admin.toString());
        assertTrue(r.suggest(admin(Locale.US), "worlds", new String[]{"cl"}).contains("clone"));
    }

    @Test
    void givenKeywordPosition_whenCompleting_thenLocalizedKeywordAndOldOptions() throws Exception {
        CommandRegistration r = registration();
        when(command.suggest(any(), any())).thenCallRealMethod();
        when(command.complete(any(), any())).thenAnswer(i -> {
            String[] args = i.getArgument(1);
            if (args[0].equals("clone") && args.length == 4) return List.of("confirm");
            if (args[0].equals("link") && args.length == 3) return List.of("nether", "end");
            if (args[0].equals("create")) return List.of("normal", "seed:", "with-nether");
            return List.of();
        });
        assertEquals(List.of("bestätigen"), r.suggest(admin(Locale.GERMANY), "welten", new String[]{"klonen", "a", "b", ""}));
        assertEquals(List.of("confirm"), r.suggest(admin(Locale.GERMANY), "welten", new String[]{"klonen", "a", "b", "conf"}));
        assertEquals(List.of("下界", "末地"), r.suggest(admin(Locale.SIMPLIFIED_CHINESE), "世界", new String[]{"链接", "a", ""}));
        // the old option tokens are offered at every position, not only the first word after the name
        assertEquals(List.of("with-nether"), r.suggest(admin(Locale.US), "worlds", new String[]{"create", "mine", "normal", "seed:1", "with"}));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration().execute(player(Locale.GERMANY, "vystormworlds.use"), "welten", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/welten reise WELT [SPIELER] – In eine Welt reisen"), all);
        assertTrue(all.contains("/welten menü – Weltauswahl öffnen"), all);
        assertFalse(all.contains("/welten verwaltung"), all);
        verify(command, never()).run(any(), any(), any());
    }

    @Test
    void givenRootAliases_whenRead_thenFixedGermanAndChinese() throws Exception {
        CommandRegistration r = registration();
        assertEquals(List.of("vw", "mvw"), r.fixedAliases());
        assertEquals("welten", r.lang().raw("de", "commands.worlds.aliases"));
        assertEquals("世界", r.lang().raw("zh_cn", "commands.worlds.aliases"));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration().conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndADescriptionForEveryNode() {
        Map<String, YamlConfiguration> files = Map.of("en", load("en.yml"), "de", load("de.yml"), "zh_cn", load("zh_cn.yml"));
        Set<String> english = keys(files.get("en"));
        for (var file : files.entrySet()) {
            assertEquals(english, keys(file.getValue()), file.getKey());
            List<String> paths = new ArrayList<>();
            collect(WorldsCommand.commandSpec(null), paths);
            for (String path : paths) {
                assertFalse(file.getValue().getString(path + ".description", "").isBlank(), file.getKey() + " " + path);
                if (!path.equals("commands.worlds")) assertFalse(file.getValue().getString(path + ".name", "").isBlank(), file.getKey() + " " + path);
            }
        }
    }

    private static YamlConfiguration load(String name) { return YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + name)); }

    private static Set<String> keys(YamlConfiguration yaml) {
        Set<String> out = new TreeSet<>();
        for (String key : yaml.getConfigurationSection("commands").getKeys(true)) out.add(key);
        return out;
    }

    private static void collect(CommandSpec node, List<String> out) {
        out.add(node.langPath());
        for (CommandSpec child : node.children().values()) collect(child, out);
    }
}
