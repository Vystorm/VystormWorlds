package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 1.21.0: the world page, the admin pages and the audit log as the screen and the files see them. */
class WorldPageTest {
    static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private final WorldPage.Handlers handlers = mock(WorldPage.Handlers.class);
    private final AdminPages.Handlers admin = mock(AdminPages.Handlers.class);
    private static Lang lang;

    static PanelContext context(String code) throws Exception {
        if (lang == null) lang = WorldsPanelTest.lang();
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            return lang.plain(code, (String) args[0], java.util.Arrays.copyOfRange(args, 1, args.length));
        });
        when(ctx.text(anyString())).thenAnswer(call -> lang.plain(code, call.getArgument(0)));
        when(ctx.session()).thenReturn(mock(PanelSession.class));
        return ctx;
    }

    private static Set<String> everything() {
        Set<String> all = new HashSet<>();
        for (MenuAccess.Entry e : MenuAccess.ENTRIES) all.add(e.id());
        return all;
    }

    static WorldPage.View view(String tab, boolean loaded, boolean server, int players, Map<String, String> pending, Set<String> allowed, boolean gameRules) {
        WorldSettings w = new WorldSettings("farm");
        w.border = 1000;
        Map<String, String> values = new HashMap<>();
        for (String key : Properties.KEYS) values.put(key, Properties.get(w, key));
        List<PendingChanges.Change> changes = PendingChanges.changes(w, pending, name -> true);
        List<String> inside = new ArrayList<>();
        for (int i = 0; i < players; i++) inside.add("Player" + i);
        return new WorldPage.View("farm", "Farm", Material.GRASS_BLOCK, loaded, server, false, "normal", "42", "-", "-", "default",
                players, 10, 5, "", inside, "0.5 64.0 0.5", values, pending, changes, List.of("farm", "lobby", "world"), allowed, true,
                true, gameRules, players > 0, true, "", tab);
    }

    @Test void givenTheWorldPage_whenRendered_thenEveryTabAndEverySettingIsThere() throws Exception {
        var doc = PanelTesting.render(WorldPage.render(context("en"), view("rules", true, false, 2, Map.of(), everything(), true), handlers));
        JsonObject tabs = PanelTesting.find(doc, "page-tabs");
        assertEquals("rules", tabs.get("selected").getAsString());
        for (String key : Properties.KEYS) assertNotNull(PanelTesting.find(doc, "set-" + key), key);
        assertEquals("toggle", PanelTesting.find(doc, "set-pvp").get("type").getAsString());
        assertEquals("select", PanelTesting.find(doc, "set-nether").get("type").getAsString());
        assertEquals("number", PanelTesting.find(doc, "set-border").get("type").getAsString());
        for (String id : List.of("overview-go", "overview-send", "game-rules", "dialog", "info", "access-send", "spawn-here", "spawn-pick",
                "center-here", "center-pick", "border-start", "clone-form", "templates", "unload", "regen-form", "delete-form", "back"))
            assertNotNull(PanelTesting.find(doc, id), id);
        assertTrue(doc.toString().contains("Player0, Player1"), "the players inside are named");
        assertNull(PanelTesting.find(doc, "apply"), "nothing pending: no Apply");
    }

    @Test void givenPendingChanges_whenRendered_thenApplyConfirmsAndListsThemAndCopyAndDiscardExist() throws Exception {
        Map<String, String> pending = new LinkedHashMap<>(Map.of("pvp", "false"));
        pending.put("time-lock", "noon");
        var doc = PanelTesting.render(WorldPage.render(context("en"), view("rules", true, false, 0, pending, everything(), false), handlers));
        JsonObject apply = PanelTesting.find(doc, "apply");
        assertNotNull(apply);
        assertTrue(apply.has("confirm"), "several changes are applied after one confirm");
        assertTrue(apply.toString().contains("PvP: true to false") && apply.toString().contains("Time lock: off to noon"), apply.toString());
        assertTrue(PanelTesting.plain(apply.get("label")).contains("2"));
        assertNotNull(PanelTesting.find(doc, "copy"));
        assertNotNull(PanelTesting.find(doc, "discard"));
        assertNotNull(PanelTesting.find(doc, "pending-line"));
        assertTrue(PanelTesting.plain(PanelTesting.find(doc, "set-pvp").get("label")).contains("(pending)"));
        assertFalse(PanelTesting.find(doc, "set-pvp").get("value").getAsBoolean(), "the page shows the pending value");
        assertNull(PanelTesting.find(doc, "game-rules"), "no game rules tile without Vystorm Control");
    }

    @Test void givenTheDangerZone_whenRendered_thenNothingDestructiveIsOneClick() throws Exception {
        var doc = PanelTesting.render(WorldPage.render(context("en"), view("danger", true, false, 3, Map.of(), everything(), false), handlers));
        assertTrue(PanelTesting.find(doc, "unload").has("confirm"));
        assertTrue(PanelTesting.find(doc, "unload").toString().contains("3 player(s)"), "the confirm says who is inside");
        for (String form : List.of("regen-form", "delete-form")) assertTrue(PanelTesting.find(doc, form).has("confirm"), form);
        assertNotNull(PanelTesting.find(doc, "regen-typed"));
        assertNotNull(PanelTesting.find(doc, "delete-typed"));
        assertTrue(PanelTesting.find(doc, "delete-form").toString().contains("3 player(s) inside are moved"));
        assertTrue(PanelTesting.find(doc, "clone-form").has("confirm"), "a copy with players inside asks first");
        assertTrue(PanelTesting.find(doc, "border-start").has("confirm"));
    }

    @Test void givenAServerOrUnloadedWorld_whenRendered_thenOnlyPossibleOperationsAreOffered() throws Exception {
        var server = PanelTesting.render(WorldPage.render(context("en"), view("danger", true, true, 0, Map.of(), everything(), false), handlers));
        for (String id : List.of("unload", "regen-form", "delete-form")) assertNull(PanelTesting.find(server, id), id);
        assertNotNull(PanelTesting.find(server, "server-world"));
        var unloaded = PanelTesting.render(WorldPage.render(context("en"), view("danger", false, false, 0, Map.of(), everything(), true), handlers));
        assertNotNull(PanelTesting.find(unloaded, "load"));
        assertNotNull(PanelTesting.find(unloaded, "overview-load"));
        for (String id : List.of("unload", "overview-go", "overview-send", "border-start", "game-rules")) assertNull(PanelTesting.find(unloaded, id), id);
    }

    @Test void givenAViewerWithoutTheAdminNode_whenRendered_thenNoActionIsOfferedAndSettingsAreLocked() throws Exception {
        var doc = PanelTesting.render(WorldPage.render(context("en"), view("overview", true, false, 1, Map.of(), Set.of("go"), true), handlers));
        for (String id : List.of("overview-send", "game-rules", "dialog", "info", "unload", "regen-form", "delete-form", "clone-form", "templates",
                "spawn-here", "spawn-pick", "center-here", "center-pick", "border-start", "access-send"))
            assertNull(PanelTesting.find(doc, id), id);
        assertTrue(PanelTesting.find(doc, "set-pvp").get("disabled").getAsBoolean());
        assertNotNull(PanelTesting.find(doc, "overview-go"));
    }

    @Test void givenEveryLanguage_whenTheNewPagesRender_thenNoKeyShowsAndEveryButtonLabelFits() throws Exception {
        Lang l = WorldsPanelTest.lang();
        List<String> labels = List.of("page-tab-overview", "page-tab-rules", "page-tab-travel", "page-tab-access", "page-tab-border", "page-tab-spawn",
                "page-tab-copies", "page-tab-danger", "page-back-list", "page-copy", "page-discard", "page-go", "page-send", "page-load", "page-game-rules",
                "page-dialog", "page-info", "page-icon-hand", "page-center-here", "page-center-pick", "page-border-start", "page-spawn-here",
                "page-spawn-pick", "page-clone", "page-templates", "page-unload", "page-regen", "page-delete", "page-import-one", "page-template-open",
                "page-template-close", "page-template-create", "admin-templates", "admin-groups", "admin-reload", "page-groups");
        for (String code : LANGUAGES) {
            for (String key : labels) {
                String label = l.plain(code, key);
                assertTrue(!label.equals(key) && label.length() <= 14, code + " " + key + " = " + label + " (" + label.length() + ")");
            }
            assertTrue(l.plain(code, "page-apply", "count", 99).length() <= 14, code + " page-apply");
            PanelContext ctx = context(code);
            Map<String, String> pending = new LinkedHashMap<>(Map.of("pvp", "false"));
            List<String> docs = new ArrayList<>();
            for (String tab : WorldPage.TABS)
                docs.add(PanelTesting.render(WorldPage.render(ctx, view(tab, true, false, 1, pending, everything(), true), handlers)).toString());
            NewWorldForm form = NewWorldForm.defaults("default").with(Map.of("name", "farm"));
            docs.add(PanelTesting.render(AdminPages.newWorld(ctx, form, form.check("default", true, n -> "exists"),
                    List.of(new AdminPages.Choice("Terra", "Plugin: Terra")), true, admin)).toString());
            docs.add(PanelTesting.render(AdminPages.imports(ctx, List.of(new AdminPages.Folder("old", "normal", true)), "detected", "default", admin)).toString());
            docs.add(PanelTesting.render(AdminPages.templates(ctx, List.of(new AdminPages.Template("meadow", "flat", 120, "kit", true)), true, admin)).toString());
            docs.add(PanelTesting.render(AdminPages.groups(ctx, Map.of("default", List.of("world", "farm")), true, "inventory", admin)).toString());
            for (String doc : docs) {
                for (String key : List.of("page-tab-rules", "page-go", "page-apply-text", "page-change", "page-create-confirm", "page-import-confirm",
                        "template-terrain-flat", "page-groups-on", "w-pvp-desc"))
                    assertFalse(doc.contains("\"" + key + "\"") || doc.contains(key + "\""), code + " shows key " + key);
            }
        }
    }

    @Test void givenTheNewWorldPage_whenRendered_thenTheExactCommandAndTheProblemAreShown() throws Exception {
        NewWorldForm form = NewWorldForm.defaults("default").with(Map.of("name", "farm", "type", "flat", "nether", true));
        var doc = PanelTesting.render(AdminPages.newWorld(context("en"), form, form.check("default", true, n -> n.equals("farm") ? "exists" : null),
                List.of(), false, admin));
        assertTrue(PanelTesting.find(doc, "command-line").toString().contains("/worlds create farm flat with-nether group:default"));
        assertTrue(PanelTesting.find(doc, "new-world").has("confirm"));
        assertTrue(PanelTesting.find(doc, "form-problem").toString().contains("already exists"));
        assertNull(PanelTesting.find(doc, "min-y"), "no build height fields while the switch or the server does not allow them");
        assertNotNull(PanelTesting.find(doc, "copy"));
    }

    @Test void givenTheImportAndTemplatePages_whenRendered_thenEveryRowHasItsButton() throws Exception {
        var imports = PanelTesting.render(AdminPages.imports(context("en"), List.of(new AdminPages.Folder("oldmap", "nether", false)), "detected", "default", admin));
        JsonObject button = PanelTesting.find(imports, "import-table/oldmap/import-oldmap");
        assertNotNull(button, imports.toString());
        assertTrue(button.has("confirm"));
        var templates = PanelTesting.render(AdminPages.templates(context("en"), List.of(new AdminPages.Template("meadow", "flat", 120, "kit", true),
                new AdminPages.Template("isle", "island", 60, "locked", false)), true, admin));
        assertNotNull(PanelTesting.find(templates, "template-table/meadow/toggle-meadow"));
        assertTrue(templates.toString().contains("Close") && templates.toString().contains("Open"));
        assertNotNull(PanelTesting.find(templates, "template-form"));
    }

    // ------------------------------------------------------------------ audit log

    @Test void givenAdminActions_whenRecorded_thenOneLinePerActionGoesToTheMonthlyFile(@TempDir Path dir) throws Exception {
        ZonedDateTime time = ZonedDateTime.of(2026, 11, 27, 18, 5, 9, 0, ZoneId.of("UTC"));
        AuditLog log = new AuditLog(dir, () -> time, Runnable::run);
        java.util.UUID id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        log.record(true, "Admin1", id, "farm", "/worlds set farm pvp false");
        log.record(true, null, null, "farm", "/worlds unload farm\nfake 2026-01-01 line");
        log.record(false, "Admin1", id, "lobby", "/worlds delete lobby");
        List<String> lines = Files.readAllLines(dir.resolve("2026-11.log"));
        assertEquals(List.of("2026-11-27 18:05:09 Admin1(00000000-0000-0000-0000-000000000001) farm /worlds set farm pvp false",
                "2026-11-27 18:05:09 CONSOLE farm /worlds unload farm fake 2026-01-01 line"), lines);
        assertEquals("CONSOLE", log.last("FARM").actor());
        assertNull(log.last("lobby"), "audit.enabled false: nothing written or kept");
    }
}
