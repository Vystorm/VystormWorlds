package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorldPanelMenusTest {
    private final WorldsPlugin plugin = mock(WorldsPlugin.class);
    private final Menus menus = mock(Menus.class);
    private final Actions actions = mock(Actions.class);
    private final WorldFavorites favorites = mock(WorldFavorites.class);
    private final WorldRegistry registry = mock(WorldRegistry.class);
    private final Panels panels = mock(Panels.class);
    private final Player player = mock(Player.class);
    private WorldPanelMenus bridge;

    @BeforeEach void setup() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.lang()).thenReturn(mock(Lang.class));
        when(plugin.lang().ref(anyString())).thenAnswer(call -> call.getArgument(0));
        when(plugin.registry()).thenReturn(registry);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WorldPanelMenusTest"));
        when(menus.actions()).thenReturn(actions);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(plugin.core()).thenReturn(TestCore.api());
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            bridge = new WorldPanelMenus(plugin, menus, favorites);
        }
    }

    private PanelSpec spec(String name) {
        ArgumentCaptor<PanelSpec> capture = ArgumentCaptor.forClass(PanelSpec.class);
        verify(panels).register(eq(name), capture.capture());
        return capture.getValue();
    }

    @Test void givenRegisteredPanels_whenFallbackRuns_thenOriginalChestsReceiveTheirContext() {
        bridge.register();
        when(panels.open(any(), anyString(), anyString())).thenReturn(PanelOpenResult.FALLBACK);
        assertTrue(bridge.openSelector(player, 2, true));
        spec("worlds").fallback().accept(player);
        verify(menus).selectorChest(player, 2, true);
        bridge.openAdmin(player, 3);
        spec("worlds-admin").fallback().accept(player);
        verify(menus).adminChest(player, 3);
        bridge.openWorld(player, "farm");
        spec("world-settings").fallback().accept(player);
        verify(menus).worldChest(player, "farm");
    }

    @Test void givenUnavailableOrBrokenOpening_whenOpened_thenCallerCanFallBack() {
        when(panels.open(player, "worlds", "")).thenReturn(PanelOpenResult.NONE);
        assertFalse(bridge.openSelector(player, 0, false));
        when(panels.open(player, "worlds", "")).thenThrow(new IllegalArgumentException("panel failure"));
        assertFalse(bridge.openSelector(player, 0, false));
        doThrow(new IllegalStateException("unavailable")).when(panels).open(player, "worlds", "");
        assertFalse(bridge.openSelector(player, 0, false));
    }

    @Test void givenPartialRegistrationFailure_whenRegistering_thenRegistrationsAreRemoved() {
        doThrow(new IllegalStateException("panel failure")).when(panels).register(eq("world-settings"), any());
        assertThrows(IllegalStateException.class, bridge::register);
        for (String name : List.of("worlds", "worlds-admin", "world-settings")) verify(panels).unregister(name);
    }

    @Test void givenRevokedPermission_whenActionsRun_thenNoMutationOrCommandOccurs() {
        when(player.hasPermission(anyString())).thenReturn(false);
        assertFalse(bridge.set(player, "farm", "pvp", "false").success());
        assertFalse(bridge.favorite(player, "farm").success());
        assertFalse(bridge.state(player, true, "page", "1").success());
        bridge.go(player, "delete", "farm");
        bridge.go(player, "travel", "farm");
        verifyNoInteractions(actions, favorites, registry);
        verify(player, never()).performCommand(anyString());
    }

    @Test void givenBorderOffAndBorderEventsOn_whenABorderEventIsStarted_thenTheAnswerNamesTheSwitchItNeeds() {
        when(registry.get("farm")).thenReturn(new WorldSettings("farm"));
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set(Features.BORDER, false);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.lang().plain(player, Features.labelKey(Features.BORDER))).thenReturn("World border");
        bridge.go(player, "border-start", "farm");
        verify(plugin.lang()).send(player, Features.NEEDS, "switch", "border.enabled", "name", "World border");
        verify(plugin, never()).borders();

        config.set(Features.BORDER, true);
        config.set(Features.BORDER_EVENTS, false);
        bridge.go(player, "border-start", "farm");
        verify(plugin.lang()).send(player, Features.OFF);
        verify(plugin, never()).borders();
    }

    @Test void givenEveryLanguage_whenRead_thenTheNeedsMessageNamesTheSwitch() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/lang/" + code + ".yml"))
                    .getString(Features.NEEDS, "").matches(".*\\{name}.*\\{switch}.*"), code);
    }

    @Test void givenReloadedOrDeletedWorld_whenSettingChanges_thenOnlyCurrentRegistryEntryIsUsed() {
        WorldSettings replacement = new WorldSettings("farm");
        when(registry.get("farm")).thenReturn(replacement);
        when(actions.setProperty(player, replacement, "pvp", "false")).thenReturn(true);
        assertTrue(bridge.set(player, "farm", "pvp", "false").success());
        verify(actions).setProperty(player, replacement, "pvp", "false");
        clearInvocations(actions);
        when(registry.get("farm")).thenReturn(null);
        assertFalse(bridge.set(player, "farm", "pvp", "true").success());
        bridge.go(player, "delete", "farm");
        verifyNoInteractions(actions);
        verify(player, never()).performCommand(anyString());
    }

    @Test void givenWorldBecomesHidden_whenTravelOrFavoriteRuns_thenVisibilityIsCheckedAgain() {
        when(registry.get("farm")).thenReturn(new WorldSettings("farm"));
        when(menus.selectable(player)).thenReturn(List.of());
        bridge.go(player, "travel", "farm");
        assertFalse(bridge.favorite(player, "farm").success());
        verifyNoInteractions(actions, favorites);
    }

    @Test void givenBrokenRegistry_whenSettingChanges_thenNothingIsApplied() {
        when(registry.error()).thenReturn("invalid yaml");
        assertFalse(bridge.set(player, "farm", "pvp", "false").success());
        verifyNoInteractions(actions);
    }

    @Test void givenRegisteredPanel_whenPermissionOrPluginStateChanges_thenAccessIsDenied() {
        bridge.register();
        PanelSpec spec = spec("worlds-admin");
        assertTrue(spec.access().test(player));
        when(player.hasPermission("vystormworlds.admin")).thenReturn(false);
        assertFalse(spec.access().test(player));
        when(player.hasPermission("vystormworlds.admin")).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(false);
        assertFalse(spec.access().test(player));
    }

    @Test void givenDestructiveAction_whenDispatched_thenExistingTypedConfirmationCommandIsUsed() {
        when(registry.get("farm")).thenReturn(new WorldSettings("farm"));
        bridge.go(player, "delete", "farm");
        bridge.go(player, "regen", "farm");
        verify(player).performCommand("worlds delete farm");
        verify(player).performCommand("worlds regen farm");
        verifyNoInteractions(actions);
    }
}
