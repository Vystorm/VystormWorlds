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
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("create", "import", "templates", "groups", "reload", "list", "selector", "config", "edit")));
        assertTrue(table.toString().contains("\"settings\""), "1.21.0: the managed settings column");
        assertTrue(doc.toString().contains("not loaded"));
    }

    private WorldsPanel.Overview personal(String arrival, String trip) {
        return new WorldsPanel.Overview(List.of(row("farm", true, false)), false, false, false, "farm", 0, 1, "",
                new Ui.Sort("name", false), "20 TPS", arrival, trip);
    }

    private static final List<WorldsPanel.Trip> TRIPS = List.of(new WorldsPanel.Trip("meadow", "flat", 120, "kit"),
            new WorldsPanel.Trip("isle", "island", 60, "locked"));

    @Test void givenArrivalAndOpenVisitWorlds_whenSelectorRendered_thenArrivalChoiceAndVisitButtonArePresent() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), personal("spawn", "visit"), actions));
        JsonObject arrival = PanelTesting.find(doc, "arrival");
        assertEquals("select", arrival.get("type").getAsString());
        assertEquals("spawn", arrival.get("value").getAsString());
        assertTrue(doc.toString().contains("My last spot") && doc.toString().contains("World spawn"), doc.toString());
        assertNotNull(PanelTesting.find(doc, "visits"));
        assertNull(PanelTesting.find(doc, "leave"));
    }

    @Test void givenVisitingPlayer_whenSelectorRendered_thenLeaveReplacesVisit() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), personal("last", "leave"), actions));
        assertNotNull(PanelTesting.find(doc, "leave"));
        assertNull(PanelTesting.find(doc, "visits"));
        assertEquals("last", PanelTesting.find(doc, "arrival").get("value").getAsString());
    }

    @Test void givenArrivalSwitchOffAndNoVisitWorlds_whenSelectorRendered_thenNeitherPartIsShown() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.overview(context("en"), personal("", ""), actions));
        for (String id : List.of("arrival", "visits", "leave")) assertNull(PanelTesting.find(doc, id), id);
        var admin = PanelTesting.render(WorldsPanel.overview(context("en"), new WorldsPanel.Overview(List.of(row("farm", true, false)),
                true, true, false, "farm", 0, 1, "", new Ui.Sort("name", false), "20 TPS", "spawn", "visit"), actions));
        for (String id : List.of("arrival", "visits", "leave")) assertNull(PanelTesting.find(admin, id), id);
    }

    @Test void givenOpenTemplates_whenVisitMenuRendered_thenEachHasAVisitButton() throws Exception {
        var doc = PanelTesting.render(WorldsPanel.visits(context("en"), TRIPS, false, actions));
        assertNotNull(PanelTesting.find(doc, "visit-table/meadow/visit"));
        assertNotNull(PanelTesting.find(doc, "visit-table/isle/visit"));
        assertTrue(doc.toString().contains("flat landscape") && doc.toString().contains("120s"), doc.toString());
        assertNull(PanelTesting.find(doc, "leave"));
        var empty = PanelTesting.render(WorldsPanel.visits(context("en"), List.of(), false, actions));
        assertTrue(empty.toString().contains("No visit worlds are open"));
        var visiting = PanelTesting.render(WorldsPanel.visits(context("en"), TRIPS, true, actions));
        assertNotNull(PanelTesting.find(visiting, "leave"));
        assertNull(PanelTesting.find(visiting, "visit-table/meadow/visit"));
    }

    @Test void givenEachLanguage_whenNewMenuPartsRender_thenNoKeyIsShownAndLabelsFit() throws Exception {
        Lang lang = lang();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            var selector = PanelTesting.render(WorldsPanel.overview(context(code), personal("spawn", "visit"), actions));
            var leaving = PanelTesting.render(WorldsPanel.overview(context(code), personal("last", "leave"), actions));
            var visits = PanelTesting.render(WorldsPanel.visits(context(code), TRIPS, false, actions));
            for (var doc : List.of(selector, leaving, visits))
                for (String key : List.of("arrival-label", "arrival-option-last", "arrival-option-spawn", "arrival-desc", "visit-button",
                        "leave-button", "visit-desc", "leave-desc", "visit-title", "visit-col-name", "visit-col-terrain", "visit-col-time",
                        "visit-seconds", "template-terrain-flat", "template-terrain-island", "template-inventory-kit"))
                    assertFalse(doc.toString().contains(key), code + " " + key + doc);
            for (String key : List.of("arrival-label", "arrival-option-last", "arrival-option-spawn", "visit-button", "leave-button")) {
                String label = lang.plain(code, key);
                assertTrue(label.length() <= 14 && !label.equals(key), code + " " + key + " " + label);
            }
            assertEquals(lang.plain(code, "visit-button"), PanelTesting.plain(PanelTesting.find(selector, "visits").get("label")), code);
            assertEquals(lang.plain(code, "leave-button"), PanelTesting.plain(PanelTesting.find(leaving, "leave").get("label")), code);
        }
    }

    @Test void givenAllSevenLanguages_whenPanelsRender_thenLabelsAndOptionsAreTranslated() throws Exception {
        Lang lang = lang();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            PanelContext ctx = context(code);
            PanelTesting.render(WorldsPanel.overview(ctx, overview(false, false, false, List.of(row("farm", true, false)), null), actions));
        }
    }
}
