package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Ui;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class WorldsPanelTest {
    private final WorldsPanel.Actions actions = mock(WorldsPanel.Actions.class);

    static Lang lang() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("de", "en", "fr", "it", "pt_br", "ru", "zh_cn")) {
            try (var in = WorldsPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Lang.create("vystormworlds", files);
    }

    private PanelContext context(String code) throws Exception {
        Lang lang = lang();
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            return lang.plain(code, (String) args[0], java.util.Arrays.copyOfRange(args, 1, args.length));
        });
        when(ctx.text(anyString())).thenAnswer(call -> lang.plain(code, call.getArgument(0)));
        return ctx;
    }

    private WorldsPanel.Row row(String name, boolean loaded, boolean protectedWorld) {
        return new WorldsPanel.Row(name, "World " + name, Material.GRASS_BLOCK, "A peaceful world", "normal", "survival",
                loaded, protectedWorld, 4, 10, 32, 12, "", "", true, false, "Entry: 5", true);
    }

    private WorldsPanel.Overview overview(boolean admin, boolean canAdmin, boolean favorites, List<WorldsPanel.Row> rows, String selected) {
        return new WorldsPanel.Overview(rows, admin, canAdmin, favorites, selected, 0, 2, "", new Ui.Sort("players", true), "20 TPS");
    }

    private WorldsPanel.Editor editor(boolean loaded, boolean protectedWorld) {
        WorldSettings world = new WorldSettings("farm");
        world.border = 1000;
        Map<String, String> values = new HashMap<>();
        for (String key : Properties.KEYS) values.put(key, Properties.get(world, key));
        return new WorldsPanel.Editor(row("farm", loaded, protectedWorld), values);
    }

    @Test void givenSelector_whenRendered_thenSelectionDetailsFavoritesAndTravelArePresent() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), overview(false, true, false,
                List.of(row("lobby", true, true), row("farm", true, false)), "farm"), actions));
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("search", "world-filter", "worlds-list", "world-stats", "favorite", "capacity", "travel", "travel-spawn", "edit", "admin", "spawn")));
        assertEquals("farm", PanelTesting.find(doc, "worlds-list").get("selected").getAsString());
        assertTrue(doc.toString().contains("World farm"));
        assertTrue(PanelTesting.find(doc, "travel").has("confirm"));
        assertTrue(PanelTesting.find(doc, "travel-spawn").has("confirm"));
    }

    @Test void givenEmptyFavoritesWithoutAdmin_whenRendered_thenEmptyStateAndNoAdminControls() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), overview(false, false, true, List.of(), null), actions));
        assertTrue(doc.toString().contains("No visible favorites"));
        assertEquals("favorites", PanelTesting.find(doc, "world-filter").get("selected").getAsString());
        assertNull(PanelTesting.find(doc, "admin"));
        assertNull(PanelTesting.find(doc, "travel"));
        assertTrue(PanelTesting.find(doc, "previous").get("disabled").getAsBoolean());
    }

    @Test void givenStaleSelection_whenRendered_thenFirstVisibleWorldIsSelected() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), overview(false, false, false,
                List.of(row("farm", true, false)), "deleted"), actions));
        assertEquals("farm", PanelTesting.find(doc, "worlds-list").get("selected").getAsString());
    }

    @Test void givenAdminOverview_whenRendered_thenTablePagingSortAndManagementFooterExist() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), overview(true, true, false,
                List.of(row("farm", false, false)), "farm"), actions));
        JsonObject table = PanelTesting.find(doc, "worlds-table");
        assertEquals(2, table.getAsJsonObject("paging").get("pages").getAsInt());
        assertEquals("players", table.getAsJsonObject("sort").get("column").getAsString());
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("create", "import", "list", "selector", "config", "edit")));
        assertTrue(doc.toString().contains("not loaded"));
    }

    @Test void givenWorldEditor_whenRendered_thenEveryPropertyHasTheAppropriateControl() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.editor(context("en"), editor(true, false), actions));
        for (String key : Properties.KEYS) assertNotNull(PanelTesting.find(doc, "set-" + key), key);
        assertEquals("toggle", PanelTesting.find(doc, "set-pvp").get("type").getAsString());
        assertEquals("select", PanelTesting.find(doc, "set-game-mode").get("type").getAsString());
        assertEquals("number", PanelTesting.find(doc, "set-price").get("type").getAsString());
        assertEquals("input", PanelTesting.find(doc, "set-description").get("type").getAsString());
        for (String id : List.of("delete", "regen", "unload", "border-start")) assertTrue(PanelTesting.find(doc, id).has("confirm"), id);
        assertTrue(doc.toString().contains("World rules"));
    }

    @Test void givenProtectedOrUnloadedWorld_whenRendered_thenOnlyValidOperationsExist() throws Exception {
        var protectedDoc = PanelTesting.render(WorldsPanel.editor(context("en"), editor(true, true), actions));
        for (String id : List.of("delete", "regen", "unload", "load")) assertNull(PanelTesting.find(protectedDoc, id), id);
        var unloadedDoc = PanelTesting.render(WorldsPanel.editor(context("en"), editor(false, false), actions));
        assertNotNull(PanelTesting.find(unloadedDoc, "load"));
        for (String id : List.of("setspawn", "admin-travel", "unload", "border-start")) assertNull(PanelTesting.find(unloadedDoc, id), id);
    }

    @Test void givenLargePricesAndCustomTime_whenRendered_thenValuesRemainEditableWithinCoreLimits() throws Exception {
        for (String price : List.of("1234.56", "98765432.12", "1000000000000.00")) {
            WorldsPanel.Editor editor = editor(true, false);
            editor.values().put("price", price);
            editor.values().put("time-lock", "6000");
            var doc = PanelTesting.render(WorldsPanel.editor(context("en"), editor, actions));
            assertEquals(price, PanelTesting.find(doc, "exact-price").get("value").getAsString());
            assertEquals("6000", PanelTesting.find(doc, "set-time-lock").get("value").getAsString());
        }
    }

    @Test void givenAllSevenLanguages_whenPanelsRender_thenLabelsAndOptionsAreTranslated() throws Exception {
        Lang lang = lang();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            PanelContext ctx = context(code);
            var doc = PanelTesting.render(WorldsPanel.editor(ctx, editor(true, false), actions));
            assertTrue(doc.toString().contains(lang.plain(code, "panel-rules")), code);
            assertFalse(doc.toString().contains("panel-option-"), code);
            assertFalse(doc.toString().contains("w-keep-spawn"), code);
            PanelTesting.render(WorldsPanel.overview(ctx, overview(false, false, false, List.of(row("farm", true, false)), null), actions));
        }
    }
}
