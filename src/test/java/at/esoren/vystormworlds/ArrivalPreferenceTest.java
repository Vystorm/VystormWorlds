package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArrivalPreferenceTest {
    private final WorldsPlugin plugin = mock(WorldsPlugin.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final WorldSettings settings = new WorldSettings("farm");
    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private Teleports teleports;
    private Location last, spawn;

    @BeforeEach
    void setup() {
        when(plugin.getName()).thenReturn("VystormWorlds");
        if (!Boolean.getBoolean("paper1206")) when(plugin.namespace()).thenReturn("vystormworlds");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.registry()).thenReturn(mock(WorldRegistry.class));
        when(plugin.lang()).thenReturn(mock(Lang.class));
        when(player.hasPermission("vystormworlds.use")).thenReturn(true);
        when(world.getName()).thenReturn("farm");
        when(world.getSpawnLocation()).thenAnswer(i -> new Location(world, 0, 64, 0));
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        doAnswer(i -> { data.put(i.getArgument(0), i.getArgument(2)); return null; }).when(pdc).set(any(), any(), any());
        doAnswer(i -> { data.remove(i.getArgument(0)); return null; }).when(pdc).remove(any());
        when(pdc.has(any(), eq(PersistentDataType.BYTE))).thenAnswer(i -> data.get(i.getArgument(0)) instanceof Byte);
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(i -> data.get(i.getArgument(0)));
        teleports = new Teleports(plugin);
        when(plugin.teleports()).thenReturn(teleports);
        last = new Location(world, 100, 70, 200, 90, 0);
        spawn = new Location(world, 0.5, 64, 0.5);
        teleports.remember(player, last);
    }

    @Test
    void preferenceSurvivesNewServiceAndKeepsSavedPositions() {
        assertEquals(last, teleports.arrival(player, world, settings, true));
        teleports.preferSpawn(player, true);
        Teleports reloaded = new Teleports(plugin);
        assertEquals(spawn, reloaded.arrival(player, world, settings, true));
        assertEquals(last, reloaded.last(player, world));
        reloaded.preferSpawn(player, false);
        assertEquals(last, reloaded.arrival(player, world, settings, true));
        assertFalse(reloaded.prefersSpawn(player));
    }

    @Test
    void serverWorldAndExplicitSpawnRulesStillWin() {
        teleports.preferSpawn(player, false);
        assertEquals(spawn, teleports.arrival(player, world, settings, false));
        config.set("teleport.return-to-last-position", false);
        assertEquals(spawn, teleports.arrival(player, world, settings, true));
        config.set("teleport.return-to-last-position", true);
        settings.rememberPosition = false;
        assertEquals(spawn, teleports.arrival(player, world, settings, true));
    }

    @Test
    void missingPositionFallsBackToConfiguredSpawn() {
        data.clear();
        settings.spawn = new WorldSettings.Spawn(5, 80, 6, 45, 10);
        assertEquals(new Location(world, 5, 80, 6, 45, 10), teleports.arrival(player, world, settings, true));
    }

    @Test
    void commandChangesOnlyOwnPreferenceAndRejectsInvalidArguments() {
        WorldsCommand command = new WorldsCommand(plugin);
        command.run(player, "worlds", new String[]{"arrival", "SPAWN"});
        assertTrue(teleports.prefersSpawn(player));
        for (String[] args : new String[][]{{"arrival"}, {"arrival", "other"}, {"arrival", "last", "extra"}})
            command.run(player, "worlds", args);
        assertTrue(teleports.prefersSpawn(player));
        command.run(player, "worlds", new String[]{"arrival", "last"});
        assertFalse(teleports.prefersSpawn(player));
        assertEquals(last, teleports.last(player, world));
    }

    @Test
    void deniedPlayerCannotChangePreference() {
        when(player.hasPermission("vystormworlds.use")).thenReturn(false);
        new WorldsCommand(plugin).run(player, "worlds", new String[]{"arrival", "spawn"});
        assertFalse(teleports.prefersSpawn(player));
    }
}
