package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.worlds.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateVisitsTest {
    @TempDir Path dir;
    @Test void recipesPersistClosedStateAndRejectUnsafeNamesAndLimits() throws Exception {
        Path file=dir.resolve("templates.yml");TemplateStore store=new TemplateStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        WorldTemplate template=new WorldTemplate("island","island",30,"kit",true);store.put(template);
        store=new TemplateStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);assertEquals(template,store.get("island"));
        store.put(template.enabled(false));assertFalse(new TemplateStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get("island").enabled());
        assertEquals(template.worldName(),template.enabled(false).worldName());
        assertThrows(IllegalArgumentException.class,()->new WorldTemplate("../world","flat",30,"locked",true));
        assertThrows(IllegalArgumentException.class,()->new WorldTemplate("x","flat",0,"locked",true));
        assertThrows(IllegalArgumentException.class,()->new WorldTemplate("x","flat",3601,"locked",true));
        assertEquals(31_000,template.expires(1_000));
        Files.writeString(file,"schema: 99\n");assertThrows(Exception.class,()->new TemplateStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        assertEquals("schema: 99\n",Files.readString(file));
    }
    @Test void failedWriteDoesNotPublishAndBoundsWorldCount() throws Exception {
        TemplateStore store=new TemplateStore(dir.resolve("templates.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        for(int i=0;i<8;i++)store.put(new WorldTemplate("w"+i,"flat",10,"locked",true));
        assertThrows(IllegalArgumentException.class,()->store.put(new WorldTemplate("ninth","flat",10,"locked",true)));
        Files.createDirectory(dir.resolve("blocked"));
        TemplateStore blocked=new TemplateStore(dir.resolve("blocked/file"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);Files.writeString(dir.resolve("blocked/file"),"");
        Files.delete(dir.resolve("blocked/file"));Files.delete(dir.resolve("blocked"));Files.writeString(dir.resolve("blocked"),"block");
        assertThrows(Exception.class,()->blocked.put(new WorldTemplate("x","flat",10,"locked",true)));assertTrue(blocked.all().isEmpty());
    }
    @Test void generatedArrivalHasSolidFloorAndIslandHasShallowWater() {
        TemplateGenerator flat=new TemplateGenerator("flat"),island=new TemplateGenerator("island");
        assertEquals(Material.GRASS_BLOCK,flat.block(50,63,50));assertEquals(Material.GRASS_BLOCK,island.block(0,63,0));
        assertEquals(Material.AIR,island.block(30,63,30));assertEquals(Material.WATER,island.block(30,62,30));
        assertEquals(Material.BEDROCK,island.block(30,59,30));assertEquals(Material.AIR,flat.block(0,64,0));
    }
    @Test void ownedVisitsUseDurableCoreInventoryAndRetryCancelledTimeoutWithoutRestoringInside() throws Exception {
        WorldTemplate template=new WorldTemplate("island","island",10,"kit",true);
        new TemplateStore(dir.resolve("templates.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).put(template);
        WorldsPlugin plugin=mock(WorldsPlugin.class);when(plugin.core()).thenReturn(TestCore.api());Lang lang=mock(Lang.class);Server server=mock(Server.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());when(plugin.lang()).thenReturn(lang);when(plugin.getServer()).thenReturn(server);
        Player player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("vystormworlds.use")).thenReturn(true);when(player.isOnline()).thenReturn(true);when(server.getPlayer(id)).thenReturn(player);
        VisitService visits=mock(VisitService.class);ManagedWorld managed=mock(ManagedWorld.class);World world=mock(World.class);
        when(managed.owner()).thenReturn(plugin);when(managed.world()).thenReturn(world);when(visits.enter(eq(player),eq(template.worldId()),any())).thenReturn(VisitResult.OK);
        AtomicLong clock=new AtomicLong(1_000);TemplateVisits service=new TemplateVisits(plugin,clock::get);
        try(var api=mockStatic(ManagedWorlds.class)){
            api.when(ManagedWorlds::visits).thenReturn(visits);api.when(()->ManagedWorlds.get(template.worldId())).thenReturn(Optional.of(managed));
            service.visit(player,"island");verify(visits).enter(eq(player),eq(template.worldId()),any());
            Visit visit=mock(Visit.class);when(visit.player()).thenReturn(id);when(visit.worldId()).thenReturn(template.worldId());
            when(visit.phase()).thenReturn(Visit.Phase.ACTIVE);when(visit.startedAt()).thenReturn(1_000L);when(visits.visits(template.worldId())).thenReturn(List.of(visit));
            clock.set(10_999);service.tick();verify(visits,never()).exit(any(),any());
            clock.set(11_000);service.tick();service.tick();verify(visits,times(2)).exit(player,ExitReason.PLUGIN);
            verify(lang,times(1)).send(player,"template-timeout");verify(player,never()).getInventory();
            when(visits.hasSnapshot(id)).thenReturn(true);service.visit(player,"island");verify(visits,times(1)).enter(any(),any(),any());
            Visit foreign=mock(Visit.class);when(foreign.worldId()).thenReturn("other:world");when(visits.visit(id)).thenReturn(Optional.of(foreign));
            service.leave(player);verify(visits,never()).exit(player,ExitReason.COMMAND);
        }
        WorldSpec spec=service.spec(template);assertFalse(spec.policy().lockInventory());assertTrue(spec.policy().isolateExperience());
        assertEquals(GameMode.ADVENTURE,spec.policy().gameMode());assertTrue(spec.protections().size()>5);
        assertTrue(service.spec(new WorldTemplate("locked","flat",10,"locked",true)).policy().lockInventory());
    }
    @Test void closeSurvivesRestartAndPermissionDenialCannotChangeTemplate() throws Exception {
        WorldTemplate template=new WorldTemplate("island","island",10,"locked",true);new TemplateStore(dir.resolve("templates.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).put(template);
        WorldsPlugin plugin=mock(WorldsPlugin.class);when(plugin.core()).thenReturn(TestCore.api());when(plugin.getDataFolder()).thenReturn(dir.toFile());when(plugin.lang()).thenReturn(mock(Lang.class));
        Player player=mock(Player.class);TemplateVisits service=new TemplateVisits(plugin);
        service.command(player,new String[]{"template","close","island"});assertTrue(new TemplateStore(dir.resolve("templates.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get("island").enabled());
        when(player.hasPermission("vystormworlds.admin")).thenReturn(true);service.command(player,new String[]{"template","close","island"});
        assertFalse(new TemplateStore(dir.resolve("templates.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get("island").enabled());
        when(player.hasPermission("vystormworlds.use")).thenReturn(true);
        try(var api=mockStatic(ManagedWorlds.class)){new TemplateVisits(plugin).visit(player,"island");api.verifyNoInteractions();}
    }
}
