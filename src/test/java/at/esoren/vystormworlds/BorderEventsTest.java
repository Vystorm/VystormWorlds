package at.esoren.vystormworlds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BorderEventsTest {
    @Test
    void givenCentres_whenParsed_thenSpawnOrCoordinates() {
        assertArrayEquals(new double[] {0.5, 0.5}, BorderEvents.center("spawn", null));
        assertArrayEquals(new double[] {100, -250}, BorderEvents.center("100 -250", null));
        assertArrayEquals(new double[] {3, 4}, BorderEvents.center("3,4", null));
        assertNull(BorderEvents.center("north", null));
        assertNull(BorderEvents.center("1 2 3", null));
    }

    @Test
    void givenSize_whenShrinking_thenPercentWithFloor() {
        assertEquals(500, BorderEvents.target(2000, 25));
        assertEquals(16, BorderEvents.target(40, 1), "never below 16 blocks");
    }

    @Test
    void givenBorderProperties_whenSet_thenValidatedAndShown() {
        WorldSettings w = new WorldSettings("arena");
        assertNull(Properties.set(w, "border", "2000", name -> true));
        assertEquals("2000", Properties.get(w, "border"));
        assertEquals("bad-value", Properties.set(w, "border", "5", name -> true));
        assertNull(Properties.set(w, "border-center", "10 20", name -> true));
        assertEquals("bad-value", Properties.set(w, "border-center", "x", name -> true));
        assertNull(Properties.set(w, "border-event", "daily 20:00", name -> true));
        assertEquals("bad-reset", Properties.set(w, "border-event", "sometimes", name -> true));
        assertNull(Properties.set(w, "border", "off", name -> true));
        assertEquals("off", Properties.get(w, "border"));
    }
}
