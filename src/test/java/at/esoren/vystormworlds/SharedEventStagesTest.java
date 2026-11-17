package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The teleport and portal handlers, called by Vystorm Core's real stage dispatcher next to a second plugin. */
class SharedEventStagesTest {
    private final WorldsPlugin plugin = mock(WorldsPlugin.class);
    private final Lang lang = mock(Lang.class);
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final Player player = mock(Player.class);
    private final World home = mock(World.class), arena = mock(World.class);
    private final WorldSettings settings = new WorldSettings("arena");
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<String> calls = new ArrayList<>();
    private PlayerRules rules;

    @BeforeEach
    void setUp() {
        when(plugin.getName()).thenReturn("VystormWorlds");
        when(plugin.isEnabled()).thenReturn(true);
        if (!Boolean.getBoolean("paper1206")) when(plugin.namespace()).thenReturn("vystormworlds");
        when(plugin.lang()).thenReturn(lang);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.registry()).thenReturn(mock(WorldRegistry.class));
        when(plugin.settings(arena)).thenReturn(settings);
        when(arena.getPlayers()).thenReturn(List.of());
        when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        rules = new PlayerRules(plugin);
        when(plugin.rules()).thenReturn(rules);
    }

    private EventStageTesting rulesAndAnotherPlugin() {
        EventStageTesting stages = EventStageTesting.detached();
        stages.handle(other, PlayerTeleportEvent.class, EventStage.ADJUST, event -> calls.add("adjust"));
        stages.handle(other, PlayerTeleportEvent.class, EventStage.ACT, event -> calls.add("act"));
        stages.handle(other, PlayerTeleportEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        rules.handleSharedEvents(stages.stages());
        return stages;
    }

    private PlayerTeleportEvent intoArena() {
        return new PlayerTeleportEvent(player, new Location(home, 0, 64, 0), new Location(arena, 0, 64, 0), TeleportCause.COMMAND);
    }

    @Test
    void givenWorldThatNeedsAPermission_whenAPlayerWithoutItTeleportsIn_thenItIsRefusedBeforeAnyLaterHandler() {
        settings.permission = true;

        assertTrue(rulesAndAnotherPlugin().fire(intoArena()).isCancelled());

        verify(lang).send(player, "no-entry", "world", "arena");
        assertEquals(List.of(), calls);
    }

    @Test
    void givenWorldThatNeedsAPermission_whenThePlayerHasIt_thenTheLaterHandlersRun() {
        settings.permission = true;
        when(player.hasPermission("vystormworlds.enter.arena")).thenReturn(true);

        assertFalse(rulesAndAnotherPlugin().fire(intoArena()).isCancelled());

        assertEquals(List.of("adjust", "act", "observe"), calls);
    }

    @Test
    void givenTeleportAnotherPluginAlreadyDenied_thenTheEntryCheckStaysSilent() {
        settings.permission = true;
        EventStageTesting stages = rulesAndAnotherPlugin();
        stages.handle(other, PlayerTeleportEvent.class, EventStage.PREPARE, event -> event.setCancelled(true));

        assertTrue(stages.fire(intoArena()).isCancelled());

        org.mockito.Mockito.verifyNoInteractions(lang);
    }

    @Test
    void thePriceIsStillTakenByABukkitListenerAtMonitor() throws Exception {
        EventHandler entered = PlayerRules.class.getMethod("entered", PlayerTeleportEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, entered.priority());
        assertTrue(entered.ignoreCancelled());
        assertEquals(null, PlayerRules.class.getMethod("checkEntry", PlayerTeleportEvent.class).getAnnotation(EventHandler.class));
    }

    @Test
    void givenUnlinkedPortalThatIsBlocked_whenAPlayerUsesIt_thenTheRuleRefusesItAndNothingObservesTheTravel() {
        config.set("portals.unlinked", "block");
        World main = mock(World.class);
        when(main.getName()).thenReturn("world");
        when(arena.getName()).thenReturn("arena");
        when(arena.getKey()).thenReturn(new NamespacedKey("vystormworlds", "arena"));
        PortalLinks links = new PortalLinks(plugin);
        EventStageTesting stages = EventStageTesting.detached();
        stages.handle(other, PlayerPortalEvent.class, EventStage.ACT, event -> calls.add("act"));
        stages.handle(other, PlayerPortalEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        links.handleSharedEvents(stages.stages());
        PlayerPortalEvent travel = new PlayerPortalEvent(player, new Location(arena, 0, 64, 0), new Location(home, 0, 64, 0), TeleportCause.NETHER_PORTAL);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main));
            stages.fire(travel);
        }

        assertTrue(travel.isCancelled());
        verify(lang).actionBar(player, "portal-unlinked");
        assertEquals(List.of(), calls);
    }

    @Test
    void givenPortalTravelAProtectionDenied_thenTheDestinationIsNotTouched() {
        PortalLinks links = new PortalLinks(plugin);
        EventStageTesting stages = EventStageTesting.detached();
        links.handleSharedEvents(stages.stages());
        stages.handle(other, PlayerPortalEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));
        Location to = new Location(home, 0, 64, 0);
        PlayerPortalEvent travel = new PlayerPortalEvent(player, new Location(arena, 0, 64, 0), to, TeleportCause.NETHER_PORTAL);

        stages.fire(travel);

        assertTrue(travel.isCancelled());
        assertEquals(to, travel.getTo());
        verify(plugin, never()).rules();
    }
}
