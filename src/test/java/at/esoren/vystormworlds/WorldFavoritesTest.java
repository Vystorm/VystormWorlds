package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldFavoritesTest {
    private final WorldsPlugin plugin = mock(WorldsPlugin.class);
    private final Player player = mock(Player.class);
    private final Map<NamespacedKey, String> data = new HashMap<>();
    private WorldFavorites favorites;
    private Menus menus;

    @BeforeEach
    void setup() {
        when(plugin.getName()).thenReturn("VystormWorlds");
        if (!Boolean.getBoolean("paper1206")) when(plugin.namespace()).thenReturn("vystormworlds");
        when(plugin.lang()).thenReturn(mock(Lang.class));
        when(plugin.registry()).thenReturn(mock(WorldRegistry.class));
        when(player.hasPermission("vystormworlds.use")).thenReturn(true);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(i -> data.get(i.getArgument(0)));
        doAnswer(i -> { data.put(i.getArgument(0), i.getArgument(2)); return null; }).when(pdc).set(any(), any(), any());
        doAnswer(i -> { data.remove(i.getArgument(0)); return null; }).when(pdc).remove(any());
        favorites = new WorldFavorites(plugin);
        menus = spy(new Menus(plugin));
        doNothing().when(menus).selector(any(), anyInt(), anyBoolean());
        when(plugin.registry().get("farm")).thenReturn(new WorldSettings("farm"));
    }

    @Test
    void bookmarksSurviveServiceReloadAndLastRemovalClearsData() {
        assertEquals(Set.of(), favorites.read(player));
        assertTrue(favorites.toggle(player, "farm"));
        assertEquals(Set.of("farm"), new WorldFavorites(plugin).read(player));
        assertFalse(favorites.toggle(player, "FARM"));
        assertTrue(data.isEmpty());
    }

    @Test
    void commandShortcutsToggleVisibleWorldsAndOpenFilteredSelectorWithoutTravel() {
        when(plugin.menus()).thenReturn(menus);
        doReturn(List.of(new WorldSettings("farm"))).when(menus).selectable(player);
        WorldsCommand command = new WorldsCommand(plugin);
        command.run(player, "worlds", new String[]{"favorite", "FARM"});
        assertEquals(Set.of("farm"), favorites.read(player));
        command.run(player, "worlds", new String[]{"favorite", "hidden"});
        assertEquals(Set.of("farm"), favorites.read(player));
        assertEquals(List.of("farm"), command.complete(player, new String[]{"favorite", ""}));
        command.run(player, "worlds", new String[]{"favorites"});
        verify(menus).selector(player, 0, true);
        command.run(player, "worlds", new String[]{"favorite", "farm"});
        assertTrue(favorites.read(player).isEmpty());
        when(player.hasPermission("vystormworlds.use")).thenReturn(false);
        command.run(player, "worlds", new String[]{"favorite", "farm"});
        assertTrue(favorites.read(player).isEmpty());
        verify(plugin, never()).teleports();
    }

    @Test
    void removingOneBookmarkKeepsTheOthersAndUnrelatedPlayerData() {
        NamespacedKey unrelated = new NamespacedKey(plugin, "arrival_spawn");
        data.put(unrelated, "untouched");
        favorites.toggle(player, "farm");
        favorites.toggle(player, "creative");
        favorites.toggle(player, "farm");
        assertEquals(Set.of("creative"), favorites.read(player));
        assertEquals("untouched", data.get(unrelated));
    }

    @Test
    void malformedEntriesAreIgnoredAndInvalidNamesCannotBeWritten() {
        data.put(new NamespacedKey(plugin, "world_favorites"), "farm\n\n../bad\nFARM");
        assertEquals(Set.of("farm"), favorites.read(player));
        assertThrows(IllegalArgumentException.class, () -> favorites.toggle(player, "bad\nname"));
    }

    @Test
    void favoritesSortBeforeServerWorldsWithStableOrdering() {
        WorldSettings server = new WorldSettings("world");
        server.serverWorld = true;
        WorldSettings farm = new WorldSettings("farm"), creative = new WorldSettings("creative");
        List<WorldSettings> visible = List.of(farm, server, creative);
        assertEquals(List.of(server, creative, farm), WorldFavorites.arrange(visible, Set.of(), false));
        assertEquals(List.of(farm, server, creative), WorldFavorites.arrange(visible, Set.of("farm"), false));
        assertEquals(List.of(farm, server, creative), visible);
    }

    @Test
    void filterNeverRestoresHiddenUnloadedOrRemovedWorlds() {
        WorldSettings farm = new WorldSettings("farm");
        assertEquals(List.of(farm), WorldFavorites.arrange(List.of(farm), Set.of("farm", "hidden", "unloaded", "deleted"), true));
        assertTrue(WorldFavorites.arrange(List.of(farm), Set.of("hidden"), true).isEmpty());
    }

    @Test
    void shiftRightBookmarksForPlayersAndAdminsWithoutTeleporting() {
        menus.selectorClick(player, "farm", ClickType.SHIFT_RIGHT, 2, false);
        assertEquals(Set.of("farm"), favorites.read(player));
        verify(menus).selector(player, 2, false);
        when(player.hasPermission("vystormworlds.admin")).thenReturn(true);
        menus.selectorClick(player, "farm", ClickType.SHIFT_RIGHT, 0, true);
        assertTrue(favorites.read(player).isEmpty());
        verify(menus).selector(player, 0, true);
        verify(player, never()).closeInventory();
        verify(plugin, never()).teleports();
    }

    @Test
    void staleMenuCannotBookmarkRemovedWorldOrActAfterPermissionLoss() {
        menus.selectorClick(player, "removed", ClickType.SHIFT_RIGHT, 0, false);
        when(player.hasPermission("vystormworlds.use")).thenReturn(false);
        menus.selectorClick(player, "farm", ClickType.SHIFT_RIGHT, 0, false);
        assertTrue(data.isEmpty());
        verify(menus, never()).selector(any(), anyInt(), anyBoolean());
    }

    @Test
    void adminShiftLeftStillOpensSettingsAndOtherClicksDoNothing() {
        when(player.hasPermission("vystormworlds.admin")).thenReturn(true);
        doNothing().when(menus).world(player, "farm");
        menus.selectorClick(player, "farm", ClickType.SHIFT_LEFT, 0, false);
        verify(menus).world(player, "farm");
        menus.selectorClick(player, "farm", ClickType.MIDDLE, 0, false);
        menus.selectorClick(player, "farm", ClickType.NUMBER_KEY, 0, false);
        assertTrue(data.isEmpty());
        verify(player, never()).closeInventory();
    }
}
