package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RulesTest {
    private static Access.Viewer viewer(boolean bypass, boolean permitted, boolean unlocked, boolean economy, boolean afford) {
        return new Access.Viewer(bypass, permitted, unlocked, economy, afford);
    }

    @Test
    void accessRules() {
        WorldSettings w = new WorldSettings("arena");
        Access.Viewer normal = viewer(false, false, false, true, true);
        assertEquals(Access.Result.NOT_LOADED, Access.check(w, false, normal, 0));
        assertEquals(Access.Result.OK, Access.check(w, true, normal, 0));
        w.permission = true;
        assertEquals(Access.Result.NO_PERMISSION, Access.check(w, true, normal, 0));
        assertEquals(Access.Result.OK, Access.check(w, true, viewer(false, true, false, true, true), 0));
        assertEquals(Access.Result.OK, Access.check(w, true, viewer(true, false, false, true, false), 99), "bypass ignores everything");
        w.permission = false;
        w.playerLimit = 2;
        assertEquals(Access.Result.FULL, Access.check(w, true, normal, 2));
        assertEquals(Access.Result.OK, Access.check(w, true, normal, 1));
        w.price = 50;
        assertEquals(Access.Result.PAY, Access.check(w, true, normal, 0));
        assertEquals(Access.Result.NO_MONEY, Access.check(w, true, viewer(false, false, false, true, false), 0));
        assertEquals(Access.Result.OK, Access.check(w, true, viewer(false, false, true, true, false), 0), "paid once already");
        w.priceMode = "every-visit";
        assertEquals(Access.Result.NO_MONEY, Access.check(w, true, viewer(false, false, true, true, false), 0));
        assertEquals(Access.Result.OK, Access.check(w, true, viewer(false, false, false, false, false), 0), "no economy: free");
        assertEquals("vystormworlds.enter.arena", Access.permission("Arena"));
    }

    @Test
    void safeSpotSearch() {
        // ground at y=64 everywhere except a lava pit around x=0..1; column x=3 is the first safe one
        SafeSpot.Blocks blocks = (x, y, z) -> {
            if (y == 64) return x < 3 ? SafeSpot.Kind.DANGER : SafeSpot.Kind.SOLID;
            if (y < 64) return SafeSpot.Kind.SOLID;
            return SafeSpot.Kind.FREE;
        };
        assertFalse(SafeSpot.safe(blocks, 0, 65, 0));
        SafeSpot.Spot spot = SafeSpot.find(blocks, 0, 65, 0, 4, 8, -64, 320);
        assertNotNull(spot);
        assertEquals(3, Math.abs(spot.x()) == 3 ? 3 : Math.max(Math.abs(spot.x()), Math.abs(spot.z())));
        assertEquals(65, spot.y());
        assertNull(SafeSpot.find(blocks, 0, 65, 0, 2, 8, -64, 320), "nothing safe within 2 blocks");
        // target inside a wall: moves up to the surface
        SafeSpot.Blocks hill = (x, y, z) -> y < 70 ? SafeSpot.Kind.SOLID : SafeSpot.Kind.FREE;
        assertEquals(new SafeSpot.Spot(5, 70, 5), SafeSpot.find(hill, 5, 64, 5, 0, 8, -64, 320));
        // headroom: one free block is not enough
        SafeSpot.Blocks low = (x, y, z) -> y == 64 ? SafeSpot.Kind.SOLID : y == 66 ? SafeSpot.Kind.SOLID : y == 65 ? SafeSpot.Kind.FREE : SafeSpot.Kind.SOLID;
        assertNull(SafeSpot.find(low, 0, 65, 0, 0, 3, -64, 320));
    }

    @Test
    void propertiesValidateAndRoundTrip() {
        WorldSettings w = new WorldSettings("hub");
        Set<String> worlds = Set.of("hub", "farm", "farm_nether");
        assertNull(Properties.set(w, "pvp", "false", worlds::contains));
        assertFalse(w.pvp);
        assertEquals("bad-value", Properties.set(w, "pvp", "maybe", worlds::contains));
        assertNull(Properties.set(w, "game-mode", "Creative", worlds::contains));
        assertEquals("creative", w.gameMode);
        assertNull(Properties.set(w, "game-mode", "none", worlds::contains));
        assertEquals("", w.gameMode);
        assertNull(Properties.set(w, "time-lock", "noon", worlds::contains));
        assertNull(Properties.set(w, "time-lock", "6000", worlds::contains));
        assertEquals("bad-value", Properties.set(w, "time-lock", "30000", worlds::contains));
        assertEquals("unknown-world", Properties.set(w, "nether", "nowhere", worlds::contains));
        assertEquals("unknown-world", Properties.set(w, "nether", "hub", worlds::contains), "not linked to itself");
        assertNull(Properties.set(w, "nether", "farm_nether", worlds::contains));
        assertNull(Properties.set(w, "price", "12,5", worlds::contains));
        assertEquals(12.5, w.price);
        assertEquals("bad-value", Properties.set(w, "price", "-1", worlds::contains));
        assertEquals("bad-value", Properties.set(w, "player-limit", "lots", worlds::contains));
        assertEquals("bad-group", Properties.set(w, "group", "Bad Group", worlds::contains));
        assertNull(Properties.set(w, "group", "Creative", worlds::contains));
        assertEquals("creative", w.group);
        assertEquals("unknown-property", Properties.set(w, "seed", "1", worlds::contains));
        assertNull(Properties.set(w, "respawn-world", "vanilla", worlds::contains));
        for (String key : Properties.KEYS) assertNotNull(Properties.get(w, key), key);
    }

    @Test
    void createRequestParsing() {
        CreateRequest.Parsed parsed = CreateRequest.parse("farm", List.of("amplified", "seed:hello", "generator:Iris:overworld", "structures:false", "with-nether", "+end", "group:Survival"), "default");
        assertNull(parsed.error());
        CreateRequest r = parsed.request();
        assertEquals(WorldKind.AMPLIFIED, r.kind());
        assertEquals((long) "hello".hashCode(), r.seed());
        assertEquals("Iris:overworld", r.generator().toString());
        assertFalse(r.structures());
        assertTrue(r.withNether() && r.withEnd());
        assertEquals("survival", r.group());
        assertEquals("farm_nether", r.linked(WorldKind.NETHER, 5L).name);
        assertEquals("farm_the_end", r.linked(WorldKind.END, 5L).name);
        assertEquals(123L, CreateRequest.parse("x", List.of("seed:123"), "d").request().seed());
        assertEquals("bad-option", CreateRequest.parse("x", List.of("sideways"), "d").error());
        assertEquals("bad-option", CreateRequest.parse("x", List.of("generator:bad name"), "d").error());
        assertEquals("links-overworld", CreateRequest.parse("x", List.of("end", "with-nether"), "d").error());
        assertEquals("height-both", CreateRequest.parse("x", List.of("min-y:-128"), "d").error());
        assertNull(CreateRequest.parse("x", List.of("min-y:-128", "height:640"), "d").error());
        assertEquals("height-multiple", CreateRequest.parse("x", List.of("min-y:-120", "height:640"), "d").error());
        assertEquals("height-terrain", CreateRequest.parse("x", List.of("min-y:0", "height:640"), "d").error());
        assertEquals("height-generator", CreateRequest.parse("x", List.of("min-y:-128", "height:640", "generator:preset:a:b"), "d").error());
        assertNull(CreateRequest.parse("x", List.of("min-y:0", "height:256", "generator:Iris"), "d").error(), "plugin generators decide their own terrain");
        assertEquals("height-seed", CreateRequest.parse("x", List.of("min-y:-128", "height:640", "seed:1"), "d").error());
        assertNull(CreateRequest.seed(" "));
    }

    @Test
    void worldKindAliases() {
        assertEquals(WorldKind.LARGE_BIOMES, WorldKind.parse("large_biomes"));
        assertEquals(WorldKind.END, WorldKind.parse("the_end"));
        assertEquals("large-biomes", WorldKind.LARGE_BIOMES.id());
        assertNull(WorldKind.parse("moon"));
    }
}
