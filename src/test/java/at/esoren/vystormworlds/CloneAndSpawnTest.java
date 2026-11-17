package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Cloning a loaded world never copies files the server is writing; spawn search order of new worlds. */
class CloneAndSpawnTest {
    @Test
    void ownLoadedWorldsAreUnloadedForTheCopy() {
        WorldSettings own = new WorldSettings("farm");
        assertEquals(WorldManager.CloneMode.OFFLINE, WorldManager.cloneMode(false, false, own));
        assertEquals(WorldManager.CloneMode.RELOAD, WorldManager.cloneMode(true, false, own), "a loaded world is closed before its files are copied");
    }

    @Test
    void worldsThatCannotBeReloadedAreCopiedLive() {
        WorldSettings server = new WorldSettings("world");
        server.serverWorld = true;
        assertEquals(WorldManager.CloneMode.LIVE, WorldManager.cloneMode(true, true, server));
        assertEquals(WorldManager.CloneMode.LIVE, WorldManager.cloneMode(true, false, null), "a world we know nothing about");
        WorldSettings foreign = new WorldSettings("minigame");
        foreign.hidden = true;
        foreign.autoload = false;
        assertEquals(WorldManager.CloneMode.LIVE, WorldManager.cloneMode(true, false, foreign), "adopted world of another plugin");
        WorldSettings tall = new WorldSettings("tall");
        tall.minY = -128;
        tall.height = 640;
        assertEquals(WorldManager.CloneMode.LIVE, WorldManager.cloneMode(true, false, tall), "custom height worlds only load at server start");
    }

    @Test
    void playersInTheSourceNeedAConfirmation() {
        assertNull(WorldManager.clonePlayersProblem(WorldManager.CloneMode.RELOAD, 0, false));
        assertNull(WorldManager.clonePlayersProblem(WorldManager.CloneMode.OFFLINE, 3, false));
        assertEquals("clone-confirm-evacuate", WorldManager.clonePlayersProblem(WorldManager.CloneMode.RELOAD, 2, false));
        assertEquals("clone-confirm-live", WorldManager.clonePlayersProblem(WorldManager.CloneMode.LIVE, 1, false));
        assertNull(WorldManager.clonePlayersProblem(WorldManager.CloneMode.LIVE, 1, true));
    }

    @Test
    void oceansAndRiversAreSkippedWithoutGeneratingThem() {
        assertEquals(true, WorldManager.wetBiome("deep_lukewarm_ocean"));
        assertEquals(true, WorldManager.wetBiome("frozen_river"));
        assertEquals(false, WorldManager.wetBiome("plains"));
        assertEquals(false, WorldManager.wetBiome("beach"));
    }

    @Test
    void spawnSearchStartsInTheMiddleAndCoversEveryRingOnce() {
        List<int[]> chunks = WorldManager.spawnChunks(32, 4);
        assertArrayEquals(new int[] {0, 0}, chunks.getFirst());
        assertEquals(17 * 17, chunks.size());
        Set<String> seen = new HashSet<>();
        int lastRing = 0;
        for (int[] c : chunks) {
            int ring = Math.max(Math.abs(c[0]), Math.abs(c[1]));
            assertEquals(0, ring % 4);
            assertEquals(true, ring >= lastRing, "nearest rings first");
            lastRing = ring;
            assertEquals(true, seen.add(c[0] + "," + c[1]), "no chunk twice");
        }
    }

    @Test
    void cloneNameProblemsNameTheNewWorld() {
        assertEquals("copy", WorldsCommand.cloneSubject("exists", "farm", "copy"), "the new name is taken, not the source");
        assertEquals("copy", WorldsCommand.cloneSubject("folder-exists", "farm", "copy"));
        assertEquals("farm", WorldsCommand.cloneSubject("busy", "farm", "copy"));
        assertEquals("farm", WorldsCommand.cloneSubject(null, "farm", "copy"));
    }

    @Test
    void clonesOfServerAndAdoptedWorldsLoadOnStart() {
        WorldSettings server = new WorldSettings("world");
        server.serverWorld = true;
        server.autoload = false; // as adopted: the server loads it
        WorldSettings copy = WorldManager.copyOf(server, null, "world_copy");
        assertEquals(true, copy.autoload, "a copy of the main world must come back after a restart");
        assertEquals(false, copy.serverWorld);

        WorldSettings foreign = new WorldSettings("minigame");
        foreign.hidden = true;
        foreign.autoload = false;
        WorldSettings foreignCopy = WorldManager.copyOf(foreign, null, "minigame_copy");
        assertEquals(true, foreignCopy.autoload);
        assertEquals(false, foreignCopy.hidden);

        WorldSettings own = new WorldSettings("farm");
        own.autoload = false; // the admin switched autoload off: the copy keeps that
        own.hidden = false;
        assertEquals(false, WorldManager.copyOf(own, null, "farm_copy").autoload);
        WorldSettings secret = new WorldSettings("secret");
        secret.hidden = true;
        assertEquals(true, WorldManager.copyOf(secret, null, "secret_copy").hidden, "a hidden own world stays hidden");
    }
}
