package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 1.21.0: the pure parts of the world menus: new-world form, pending changes, danger zone, permission table. */
class MenuModelTest {
    private static final Predicate<String> WORLDS = name -> Set.of("world", "farm", "farm_nether", "lobby").contains(name);

    // ------------------------------------------------------------------ new world form

    @Test void givenAFilledForm_whenTurnedIntoTheCommand_thenTheWordsAreThoseOfWorldsCreate() {
        NewWorldForm form = NewWorldForm.defaults("default").with(Map.of("name", "Farm2", "type", "flat", "seed", "42",
                "structures", false, "nether", true, "group", "survival"));
        assertEquals(List.of("flat", "seed:42", "structures:false", "with-nether", "group:survival"), form.tokens());
        assertEquals("/worlds create farm2 flat seed:42 structures:false with-nether group:survival", form.command());
        NewWorldForm.Check check = form.check("default", true, name -> null);
        assertTrue(check.ok(), String.valueOf(check));
        assertEquals("farm2", check.request().name());
        assertEquals(WorldKind.FLAT, check.request().kind());
        assertEquals(42L, check.request().seed());
        assertTrue(check.request().withNether());
    }

    @Test void givenScreenNumbers_whenReadIntoTheForm_thenWholeNumbersStayIntegers() {
        NewWorldForm form = NewWorldForm.defaults("default").with(Map.of("name", "tall", "min-y", -128.0, "height", 512.0));
        assertEquals("-128", form.minY());
        assertEquals("512", form.height());
        assertTrue(form.tokens().containsAll(List.of("min-y:-128", "height:512")));
    }

    @Test void givenBadForms_whenChecked_thenTheFieldAndTheCommandsMessageAreNamed() {
        NewWorldForm base = NewWorldForm.defaults("default");
        assertEquals("bad-name", base.check("default", true, n -> null).error());
        assertEquals("name", base.with(Map.of("name", "bad name")).check("default", true, n -> null).field());
        assertEquals("bad-name", base.with(Map.of("name", "x".repeat(33))).check("default", true, n -> null).error());
        NewWorldForm.Check exists = base.with(Map.of("name", "farm")).check("default", true, n -> n.equals("farm") ? "exists" : null);
        assertEquals("exists", exists.error());
        assertEquals("name", exists.field());
        NewWorldForm.Check linkTaken = base.with(Map.of("name", "new", "nether", true)).check("default", true, n -> n.equals("new_nether") ? "exists" : null);
        assertEquals("exists", linkTaken.error());
        assertEquals("type", base.with(Map.of("name", "a", "type", "moon")).check("default", true, n -> null).field());
        assertEquals("links-overworld", base.with(Map.of("name", "a", "type", "nether", "end", true)).check("default", true, n -> null).error());
        NewWorldForm.Check height = base.with(Map.of("name", "a", "min-y", "-128", "height", "512")).check("default", false, n -> null);
        assertEquals(Features.OFF, height.error(), "build-height.enabled off: refused like the command");
        assertEquals(NewWorldForm.HEIGHT_FIELD, height.field());
        assertEquals("height-both", base.with(Map.of("name", "a", "min-y", "-128")).check("default", true, n -> null).error());
        assertEquals("bad-group", base.with(Map.of("name", "a", "group", "Bad Group")).check("default", true, n -> null).error());
        assertEquals("seed", base.with(Map.of("name", "a", "seed", "two words")).check("default", true, n -> null).field());
    }

    // ------------------------------------------------------------------ pending changes

    @Test void givenChangesOnTheWorldPage_whenCollected_thenNoOpsDropOutAndEachHasItsCommand() {
        WorldSettings w = new WorldSettings("farm");
        Map<String, String> pending = new LinkedHashMap<>();
        pending = PendingChanges.put(w, pending, "pvp", "false", WORLDS);
        pending = PendingChanges.put(w, pending, "time-lock", "noon", WORLDS);
        pending = PendingChanges.put(w, pending, "nether", "farm_nether", WORLDS);
        pending = PendingChanges.put(w, pending, "monsters", "true", WORLDS); // already true: no change
        assertEquals(List.of("pvp", "time-lock", "nether"), List.copyOf(pending.keySet()));
        pending = PendingChanges.put(w, pending, "pvp", "true", WORLDS); // back to the current value
        assertEquals(List.of("time-lock", "nether"), List.copyOf(pending.keySet()));
        List<PendingChanges.Change> changes = PendingChanges.changes(w, pending, WORLDS);
        assertEquals(List.of("/worlds set farm time-lock noon", "/worlds set farm nether farm_nether"), PendingChanges.commands("farm", changes));
        assertEquals("time-lock: off to noon; nether: none to farm_nether",
                PendingChanges.summary(changes, c -> c.key() + ": " + c.before() + " to " + c.after()));
        assertTrue(w.pvp && "off".equals(w.timeLock) && w.nether.isEmpty(), "the live settings are untouched until Apply");
    }

    @Test void givenInvalidOrSwitchedOffValues_whenChecked_thenTheyAreRefusedLikeWorldsSet() {
        WorldSettings w = new WorldSettings("farm");
        YamlConfiguration config = new YamlConfiguration();
        assertEquals("bad-value", PendingChanges.check(w, Map.of(), "player-limit", "-3", config, WORLDS).error());
        assertEquals("unknown-world", PendingChanges.check(w, Map.of(), "nether", "nowhere", config, WORLDS).error());
        assertEquals("unknown-property", PendingChanges.check(w, Map.of(), "seed", "5", config, WORLDS).error());
        assertEquals("bad-reset", PendingChanges.check(w, Map.of(), "reset", "sometimes", config, WORLDS).error());
        assertNull(PendingChanges.check(w, Map.of(), "reset", "daily 04:00", config, WORLDS));
        config.set(Features.RESETS, false);
        assertEquals(Features.OFF, PendingChanges.check(w, Map.of(), "reset", "daily 04:00", config, WORLDS).error());
        assertNull(PendingChanges.check(w, Map.of(), "reset", "off", config, WORLDS), "writing the current value stays allowed");
        WorldSettings server = new WorldSettings("world");
        server.serverWorld = true;
        assertEquals("server-world", PendingChanges.check(server, Map.of(), "reset", "daily 04:00", new YamlConfiguration(), WORLDS).error());
    }

    @Test void givenEmptyTextValues_whenTurnedIntoCommands_thenTheyClearTheProperty() {
        assertEquals("/worlds set farm border off", PendingChanges.command("farm", "border", "off"));
        assertEquals("/worlds set farm respawn-world none", PendingChanges.command("farm", "respawn-world", ""));
        assertEquals("/worlds set farm display-name", PendingChanges.command("farm", "display-name", ""));
        assertEquals("/worlds set farm description A quiet farm", PendingChanges.command("farm", "description", "A quiet farm"));
    }

    // ------------------------------------------------------------------ danger zone

    @Test void givenTypedNames_whenConfirmingDeleteOrRegen_thenOnlyTheExactWorldNameCounts() {
        assertNull(DangerZone.typed("farm", "farm"));
        assertNull(DangerZone.typed("  FARM ", "farm"), "case and outer spaces are ignored, as the command does");
        for (String wrong : new String[] {null, "", "far", "farm2", "yes", "confirm"}) assertEquals("confirm-mismatch", DangerZone.typed(wrong, "farm"), wrong);
        assertEquals("confirm-mismatch", DangerZone.typed("", ""));
    }

    @Test void givenServerWorldsBusyOrUnloadedWorlds_whenADangerousActionIsAsked_thenItIsRefused() {
        assertEquals("server-world", DangerZone.refused(DangerZone.Kind.DELETE, true, true, false));
        assertEquals("server-world", DangerZone.refused(DangerZone.Kind.REGEN, false, true, false));
        assertEquals("server-world", DangerZone.refused(DangerZone.Kind.UNLOAD, true, true, false));
        assertEquals("not-loaded", DangerZone.refused(DangerZone.Kind.UNLOAD, false, false, false));
        assertEquals("busy", DangerZone.refused(DangerZone.Kind.DELETE, true, false, true));
        assertNull(DangerZone.refused(DangerZone.Kind.DELETE, true, false, false));
        assertNull(DangerZone.refused(DangerZone.Kind.CLONE, true, true, false), "server worlds can be copied (live)");
    }

    @Test void givenSeedChoices_whenRegenerating_thenTheCommandsSeedWordIsUsed() {
        assertEquals("same", DangerZone.seed("same", "123"));
        assertEquals("random", DangerZone.seed("random", ""));
        assertEquals("123", DangerZone.seed("own", " 123 "));
        assertEquals("hello", DangerZone.seed("own", "hello"));
        assertNull(DangerZone.seed("own", ""));
        assertNull(DangerZone.seed("own", "two words"));
        assertNull(DangerZone.seed("own", "random"));
        assertEquals("/worlds regen farm 123 farm", DangerZone.command(DangerZone.Kind.REGEN, "farm", "123", null, false));
        assertEquals("/worlds delete farm farm", DangerZone.command(DangerZone.Kind.DELETE, "farm", null, null, false));
        assertEquals("/worlds clone farm copy confirm", DangerZone.command(DangerZone.Kind.CLONE, "farm", null, "Copy", true));
    }

    @Test void givenPlayersInTheSource_whenCopying_thenAConfirmIsNeededExceptForUnloadedWorlds() {
        assertTrue(DangerZone.cloneNeedsConfirm(WorldManager.CloneMode.RELOAD, 2));
        assertTrue(DangerZone.cloneNeedsConfirm(WorldManager.CloneMode.LIVE, 1));
        assertFalse(DangerZone.cloneNeedsConfirm(WorldManager.CloneMode.RELOAD, 0));
        assertFalse(DangerZone.cloneNeedsConfirm(WorldManager.CloneMode.OFFLINE, 3));
    }

    // ------------------------------------------------------------------ permissions

    @Test void givenTheMenuEntries_whenAskedForTheirNode_thenItIsTheNodeOfTheCommandTheyRun() {
        Map<String, String> table = MenuAccess.table();
        for (String player : List.of("selector", "travel", "spawn", "arrival", "favorite", "visit", "leave"))
            assertTrue(table.get(player).startsWith("vystormworlds.use "), player + " " + table.get(player));
        for (String admin : List.of("overview", "list", "groups", "reload", "new-world", "import", "templates", "world-page", "settings-dialog", "info",
                "apply", "link", "set-spawn", "send", "load", "unload", "clone", "regen", "delete", "border-start", "game-rules"))
            assertTrue(table.get(admin).startsWith("vystormworlds.admin "), admin + " " + table.get(admin));
        assertEquals("vystormworlds.use", MenuAccess.node("go"), "going yourself is /worlds tp WORLD");
        assertEquals("vystormworlds.admin", MenuAccess.node("send"), "sending others is /worlds tp WORLD PLAYER");
        assertEquals(List.of(Features.MANAGEMENT), MenuAccess.switches("delete"));
        assertEquals(List.of(Features.SELECTOR), MenuAccess.switches("go"));
        assertEquals(List.of(), MenuAccess.switches("send"), "tp WORLD PLAYER has no switch, as the command");
        assertEquals(List.of(Features.ADMIN_MENU), MenuAccess.switches("world-page"));
        for (MenuAccess.Entry e : MenuAccess.ENTRIES) assertEquals(WorldsCommand.nodeOf(e.sub()).equals(WorldsCommand.ADMIN_NODE) || e.id().equals("send"),
                MenuAccess.node(e.id()).equals(WorldsCommand.ADMIN_NODE), e.id());
    }

    @Test void givenTheFamilyPermissionRule_whenNodesAreSetOrNot_thenTheNodeDecidesAlsoForOperators() {
        YamlConfiguration config = new YamlConfiguration();
        // Bukkit answers hasPermission: no node set -> the plugin.yml default (admin: op, use: true); a set node decides
        Predicate<String> opWithoutNodes = node -> true;
        Predicate<String> playerWithoutNodes = node -> node.equals("vystormworlds.use");
        Predicate<String> opWithAdminNegated = node -> !node.equals("vystormworlds.admin");
        Predicate<String> playerGrantedAdmin = node -> true;
        assertNull(MenuAccess.refusal("delete", opWithoutNodes, config));
        assertEquals("denied", MenuAccess.refusal("delete", playerWithoutNodes, config));
        assertNull(MenuAccess.refusal("go", playerWithoutNodes, config), "what players could do stays allowed");
        assertEquals("denied", MenuAccess.refusal("delete", opWithAdminNegated, config), "a negated node wins over op");
        assertNull(MenuAccess.refusal("go", opWithAdminNegated, config));
        assertNull(MenuAccess.refusal("send", playerGrantedAdmin, config));
        config.set(Features.MANAGEMENT, false);
        assertEquals(Features.OFF, MenuAccess.refusal("delete", opWithoutNodes, config));
        assertNull(MenuAccess.refusal("apply", opWithoutNodes, config), "settings stay usable with management off");
        assertThrows(IllegalArgumentException.class, () -> MenuAccess.node("nope"));
    }

    @Test void givenCommandsThatChangeWorlds_whenRun_thenTheyAreAuditedButReadingIsNot() {
        assertTrue(WorldsCommand.audited("set", new String[] {"set", "farm", "pvp", "false"}));
        assertTrue(WorldsCommand.audited("tp", new String[] {"tp", "farm", "Alex"}));
        assertFalse(WorldsCommand.audited("tp", new String[] {"tp", "farm"}));
        assertTrue(WorldsCommand.audited("template", new String[] {"template", "open", "meadow"}));
        assertFalse(WorldsCommand.audited("template", new String[] {"template", "list"}));
        assertFalse(WorldsCommand.audited("delete", new String[] {"delete", "farm"}), "delete is written when it really runs");
        assertFalse(WorldsCommand.audited("info", new String[] {"info", "farm"}));
        assertFalse(WorldsCommand.audited("create", new String[] {"create"}));
        assertTrue(WorldsCommand.audited("reload", new String[] {"reload"}));
        assertEquals("farm", AuditLog.worldOf("set", new String[] {"set", "farm", "pvp"}));
        assertEquals("", AuditLog.worldOf("template", new String[] {"template", "open", "x"}));
    }

    // ------------------------------------------------------------------ badges

    @Test void givenManagedSettings_whenSummarised_thenOnlyNonDefaultsAreListed() {
        WorldSettings w = new WorldSettings("farm");
        assertEquals("", WorldBadges.summary(w, key -> key));
        w.timeLock = "noon";
        w.weatherLock = "clear";
        w.border = 2000;
        w.pvp = false;
        w.gameMode = "creative";
        assertEquals("panel-option-noon, panel-option-clear, mode-creative, badge-border 2000, badge-no-pvp", WorldBadges.summary(w, key -> key));
        w.timeLock = "6000";
        assertTrue(WorldBadges.summary(w, key -> key).startsWith("w-time-lock 6000"));
    }
}
