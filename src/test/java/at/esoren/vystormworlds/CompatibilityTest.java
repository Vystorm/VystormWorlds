package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.compat.ServerCompat;
import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
    @Test
    void absentFlushApiNeverLoadsModernAdapter() {
        WorldCompat compat = WorldCompat.select(false, () -> { throw new AssertionError("Modern adapter loaded"); });
        assertTrue(compat.canFlush());
        assertFalse(compat.forceSpawn(null, 0, 80, 0));
        org.bukkit.World world = mock(org.bukkit.World.class);
        org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getConsoleSender).thenReturn(console);
            bukkit.when(() -> org.bukkit.Bukkit.dispatchCommand(console, "minecraft:save-all flush")).thenReturn(true);
            compat.flush(world);
            verify(world).save();
            bukkit.verify(() -> org.bukkit.Bukkit.dispatchCommand(console, "minecraft:save-all flush"));
        }
    }

    @Test
    void presentFlushApiSelectsModernAdapter() {
        WorldCompat modern = mock(WorldCompat.class);
        assertSame(modern, WorldCompat.select(true, () -> modern));
    }

    @Test
    void runtimeDetectionMatchesAvailableApi() {
        boolean legacy = Boolean.getBoolean("paper1206");
        assertEquals(!legacy, WorldCompat.hasFlushSave());
        assertTrue(WorldCompat.INSTANCE.canFlush());
        assertEquals(!legacy, ServerCompat.hasDialogs());
        // Core 0.59.0 wraps the platform handler in ScreenDialogs (dialogs without inputs open as screens).
        String handler = Vystorm.vystorm_core.singeltons.DialogSingelton.getInstance().getClass().getSimpleName();
        assertTrue(handler.equals("ScreenDialogs") || handler.equals(legacy ? "LegacyDialogs" : "DefaultDialogHandler"), handler);
    }

    @Test
    void portableInputsLoadWithoutNativeDialogs() {
        var input = DialogInput.text("name", net.kyori.adventure.text.Component.text("World")).maxLength(32).build();
        assertEquals("name", input.key());
        assertEquals(32, input.maxLength());
    }

    @Test
    void inventoryViewWorksAcrossClassToInterfaceChange() {
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        when(InventoryCompat.top(view)).thenReturn(inventory);
        assertSame(inventory, InventoryCompat.top(view));
    }

    @Test
    void portableEmptyInventoryRoundTripsAndRejectsTruncation() {
        String encoded = BukkitLivePlayer.encode(Vystorm.vystorm_core.compat.ItemCompatInterface.DEFAULT, new org.bukkit.inventory.ItemStack[0]);
        assertEquals(0, BukkitLivePlayer.decode(Vystorm.vystorm_core.compat.ItemCompatInterface.DEFAULT, encoded).length);
        byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
        String truncated = java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(RuntimeException.class, () -> BukkitLivePlayer.decode(Vystorm.vystorm_core.compat.ItemCompatInterface.DEFAULT, truncated));
    }

    @Test
    @SuppressWarnings("removal")
    void baselineGamerulesAndAttributeResolve() {
        if (!Boolean.getBoolean("paper1206")) return; // modern registries require a running server
        assertEquals("doDaylightCycle", WorldManager.advanceTime().getName());
        assertEquals("doWeatherCycle", WorldManager.advanceWeather().getName());
        assertNotNull(ServerCompat.attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"));
    }
}
