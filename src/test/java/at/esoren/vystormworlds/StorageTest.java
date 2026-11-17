package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
    @TempDir Path dir;

    // ------------------------------------------------------------------ worlds.yml

    @Test
    void registryRoundTripKeepsEveryProperty() throws Exception {
        WorldRegistry registry = new WorldRegistry(dir.resolve("worlds.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(registry.load());
        WorldSettings w = new WorldSettings("farm");
        w.displayName = "<green>Farm";
        w.kind = WorldKind.AMPLIFIED;
        w.seed = -42L;
        w.structures = false;
        w.spawn = new WorldSettings.Spawn(1.5, 70, -3.5, 90, 10);
        w.gameMode = "adventure";
        w.difficulty = "hard";
        w.pvp = false;
        w.timeLock = "6000";
        w.weatherLock = "clear";
        w.permission = true;
        w.price = 25;
        w.priceMode = "every-visit";
        w.playerLimit = 10;
        w.group = "survival";
        w.nether = "farm_nether";
        w.minY = -128;
        w.height = 512;
        registry.put(w);
        assertTrue(registry.save());
        WorldRegistry back = new WorldRegistry(dir.resolve("worlds.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(back.load());
        WorldSettings r = back.get("FARM");
        assertEquals("<green>Farm", r.displayName);
        assertEquals(WorldKind.AMPLIFIED, r.kind);
        assertEquals(-42L, r.seed);
        assertFalse(r.structures);
        assertEquals(new WorldSettings.Spawn(1.5, 70, -3.5, 90, 10), r.spawn);
        assertEquals("adventure", r.gameMode);
        assertEquals("6000", r.timeLock);
        assertEquals(6000, WorldSettings.lockTicks(r.timeLock));
        assertTrue(r.permission);
        assertEquals("every-visit", r.priceMode);
        assertEquals(10, r.playerLimit);
        assertEquals("survival", r.group);
        assertEquals(-128, r.minY);
        assertEquals(512, r.height);
        assertTrue(r.customHeight());
        assertTrue(back.problems().isEmpty(), back.problems().toString());
        assertEquals(w.name, back.family("farm_nether").name);
    }

    @Test
    void brokenWorldsYmlIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("worlds.yml");
        Files.writeString(file, "worlds:\n  farm: [unclosed\n", StandardCharsets.UTF_8);
        WorldRegistry registry = new WorldRegistry(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertFalse(registry.load());
        registry.put(new WorldSettings("other"));
        assertFalse(registry.save());
        assertEquals("worlds:\n  farm: [unclosed\n", Files.readString(file));
        Files.writeString(file, "format: 7\nworlds: {}\n", StandardCharsets.UTF_8);
        assertFalse(registry.load(), "newer format is refused");
        assertFalse(registry.save());
    }

    @Test
    void invalidValuesFallBackAndAreReported() throws Exception {
        Path file = dir.resolve("worlds.yml");
        Files.writeString(file, """
                format: 1
                worlds:
                  farm:
                    type: moon
                    seed: abc
                    game-mode: god
                    time-lock: 99999
                    group: 'Bad Group'
                    entry:
                      price: -5
                  'bad name!': {}
                """, StandardCharsets.UTF_8);
        WorldRegistry registry = new WorldRegistry(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(registry.load());
        WorldSettings w = registry.get("farm");
        assertEquals(WorldKind.NORMAL, w.kind);
        assertNull(w.seed);
        assertEquals("", w.gameMode);
        assertEquals("off", w.timeLock);
        assertEquals("default", w.group);
        assertEquals(0, w.price);
        assertNull(registry.get("bad name!"));
        assertTrue(registry.problems().size() >= 6, registry.problems().toString());
    }

    @Test
    void namesAndGroups() {
        assertTrue(WorldSettings.validName("farm_nether"));
        assertFalse(WorldSettings.validName("../evil"));
        assertFalse(WorldSettings.validName(""));
        assertThrows(IllegalArgumentException.class, () -> new WorldSettings("a/b"));
        assertTrue(WorldSettings.validGroup("creative-1"));
        assertFalse(WorldSettings.validGroup("Creative"));
        assertEquals("night", WorldSettings.next(WorldSettings.TIME_LOCKS, "sunset"));
        assertEquals("off", WorldSettings.next(WorldSettings.TIME_LOCKS, "midnight"));
    }

    // ------------------------------------------------------------------ folders

    @Test
    void destructiveOperationsOnlyTouchRealWorldFolders() throws Exception {
        Path level = Files.createDirectories(dir.resolve("server/world"));
        WorldPaths paths = new WorldPaths(level, dir.resolve("server"));
        assertTrue(paths.modern());
        Path farm = Files.createDirectories(paths.folder("farm"));
        assertEquals(level.resolve("dimensions/minecraft/farm").toAbsolutePath().normalize(), farm.toAbsolutePath().normalize());
        paths.checkDestructible(farm, List.of());
        assertThrows(IOException.class, () -> paths.checkDestructible(paths.root().resolve("minecraft"), List.of()), "namespace folder");
        assertThrows(IOException.class, () -> paths.checkDestructible(paths.root(), List.of()));
        assertThrows(IOException.class, () -> paths.checkDestructible(level, List.of()));
        assertThrows(IOException.class, () -> paths.checkDestructible(dir.resolve("elsewhere/x/y"), List.of()));
        assertThrows(IOException.class, () -> paths.checkDestructible(paths.folder("../../evil"), List.of()));
        assertThrows(IOException.class, () -> paths.checkDestructible(farm, List.of(farm)), "protected (loaded) world");
        Files.writeString(farm.resolve(WorldPaths.CORE_MARKER), "id: core:test\n");
        assertThrows(IOException.class, () -> paths.checkDestructible(farm, List.of()), "Vystorm Core plugin world");
        assertEquals(level.resolve("dimensions/vystormworlds/tall").toAbsolutePath().normalize(), paths.folder(DimensionPack.NAMESPACE, "Tall").toAbsolutePath().normalize());
    }

    @Test
    void legacyLayoutUsesTheContainer() throws Exception {
        WorldPaths paths = new WorldPaths(null, dir);
        assertFalse(paths.modern());
        Path w = Files.createDirectories(paths.folder("Hub"));
        assertEquals(dir.resolve("Hub").toAbsolutePath().normalize(), w.toAbsolutePath().normalize());
        paths.checkDestructible(w, List.of());
        assertThrows(IOException.class, () -> paths.checkDestructible(dir.resolve("Hub/region"), List.of()));
    }

    @Test
    void importCandidates() throws Exception {
        Path server = dir.resolve("server");
        Path level = Files.createDirectories(server.resolve("world"));
        WorldPaths paths = new WorldPaths(level, server);
        Files.createDirectories(paths.folder("unloaded").resolve("region"));
        Files.createDirectories(paths.folder("loaded").resolve("region"));
        Files.createDirectories(paths.folder("empty"));
        Path core = Files.createDirectories(paths.folder("coreworld").resolve("region"));
        Files.writeString(core.getParent().resolve(WorldPaths.CORE_MARKER), "id: x:y\n");
        Path old = Files.createDirectories(server.resolve("oldnether/DIM-1/region"));
        Files.writeString(old.getParent().getParent().resolve("level.dat"), "x");
        Files.createDirectories(server.resolve("plugins/Something/region"));
        List<WorldPaths.Candidate> candidates = paths.candidates(Set.of("loaded", "world"));
        assertEquals(List.of("unloaded", "oldnether"), candidates.stream().map(WorldPaths.Candidate::name).toList());
        assertTrue(candidates.get(1).legacy());
        assertEquals(WorldKind.NETHER, candidates.get(1).kind());
    }

    @Test
    void oldLayoutImportKeepsNetherAndEndTypes() throws Exception {
        WorldPaths paths = new WorldPaths(null, dir);
        Files.createDirectories(dir.resolve("nether/DIM-1/region"));
        Files.createDirectories(dir.resolve("end/DIM1/region"));
        List<WorldPaths.Candidate> candidates = paths.candidates(Set.of());
        assertEquals(List.of(WorldKind.END, WorldKind.NETHER), candidates.stream().map(WorldPaths.Candidate::kind).toList());
        assertTrue(candidates.stream().noneMatch(WorldPaths.Candidate::legacy), "already in the server's layout");
    }

    @Test
    void copiesLeaveOutLocksAndWorldIdentity() throws Exception {
        Path from = Files.createDirectories(dir.resolve("from"));
        Files.createDirectories(from.resolve("region"));
        Files.createDirectories(from.resolve("data/paper"));
        Files.writeString(from.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(from.resolve("session.lock"), "lock");
        Files.writeString(from.resolve("uid.dat"), "id");
        Files.writeString(from.resolve("data/paper/metadata.dat"), "id");
        Files.writeString(from.resolve("data/paper/level_overrides.dat"), "spawn");
        WorldPaths.copyTree(from, dir.resolve("copy"));
        assertTrue(Files.exists(dir.resolve("copy/region/r.0.0.mca")));
        assertTrue(Files.exists(dir.resolve("copy/data/paper/level_overrides.dat")));
        assertFalse(Files.exists(dir.resolve("copy/session.lock")));
        assertFalse(Files.exists(dir.resolve("copy/uid.dat")));
        assertFalse(Files.exists(dir.resolve("copy/data/paper/metadata.dat")));
        WorldPaths.moveTree(from, dir.resolve("backups/from-1"));
        assertFalse(Files.exists(from));
        assertTrue(Files.exists(dir.resolve("backups/from-1/data/paper/metadata.dat")), "backups keep everything");
        assertThrows(IOException.class, () -> WorldPaths.moveTree(dir.resolve("copy"), dir.resolve("backups/from-1")));
        WorldPaths.deleteTree(dir.resolve("copy"));
        assertFalse(Files.exists(dir.resolve("copy")));
    }

    // ------------------------------------------------------------------ build height data pack

    static final String TYPE = """
            {"ambient_light":0.0,"attributes":{"minecraft:visual/sky_color":"#78a7ff"},"coordinate_scale":1.0,"has_ceiling":false,
             "height":384,"logical_height":384,"min_y":-64,"infiniburn":"#minecraft:infiniburn_overworld"}""";
    static final String NETHER_TYPE = """
            {"coordinate_scale":8.0,"has_ceiling":true,"height":256,"logical_height":128,"min_y":0}""";
    static final String PRESET = """
            {"dimensions":{"minecraft:overworld":{"type":"minecraft:overworld","generator":{"type":"minecraft:noise",
             "biome_source":{"type":"minecraft:multi_noise","preset":"minecraft:overworld"},"settings":"minecraft:overworld"}},
             "minecraft:the_nether":{"type":"minecraft:the_nether","generator":{"type":"minecraft:noise","settings":"minecraft:nether"}}}}""";
    static final String FLAT = """
            {"dimensions":{"minecraft:overworld":{"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
             "biome":"minecraft:plains","features":false,"lakes":false,"layers":[{"block":"minecraft:bedrock","height":1}],
             "structure_overrides":["minecraft:villages"]}}}}}""";

    @Test
    void buildHeightValidation() {
        assertNull(DimensionPack.validate(WorldKind.NORMAL, -128, 640, true));
        assertNull(DimensionPack.validate(WorldKind.NORMAL, -2032, 4064, true));
        assertEquals("height-multiple", DimensionPack.validate(WorldKind.NORMAL, -100, 640, true));
        assertEquals("height-multiple", DimensionPack.validate(WorldKind.NORMAL, -128, 650, true));
        assertEquals("height-range", DimensionPack.validate(WorldKind.NORMAL, -2048, 640, true));
        assertEquals("height-range", DimensionPack.validate(WorldKind.NORMAL, 0, 4080, false));
        assertEquals("height-range", DimensionPack.validate(WorldKind.NORMAL, 1024, 1024, false), "top above 2032");
        assertEquals("height-range", DimensionPack.validate(WorldKind.NORMAL, 0, 0, false));
        assertEquals("height-terrain", DimensionPack.validate(WorldKind.NORMAL, -32, 640, true));
        assertEquals("height-terrain", DimensionPack.validate(WorldKind.NORMAL, -64, 368, true));
        assertNull(DimensionPack.validate(WorldKind.NORMAL, 0, 256, false), "own generator decides");
        assertNull(DimensionPack.validate(WorldKind.NETHER, 0, 512, true));
        assertEquals("height-terrain", DimensionPack.validate(WorldKind.NETHER, 64, 512, true));
    }

    @Test
    void generatedDimensionTypeKeepsVanillaFields() {
        JsonObject json = JsonParser.parseString(DimensionPack.dimensionType(TYPE, -128, 640)).getAsJsonObject();
        assertEquals(-128, json.get("min_y").getAsInt());
        assertEquals(640, json.get("height").getAsInt());
        assertEquals(640, json.get("logical_height").getAsInt());
        assertEquals("#78a7ff", json.getAsJsonObject("attributes").get("minecraft:visual/sky_color").getAsString());
        assertEquals("#minecraft:infiniburn_overworld", json.get("infiniburn").getAsString());
        JsonObject nether = JsonParser.parseString(DimensionPack.dimensionType(NETHER_TYPE, 0, 512)).getAsJsonObject();
        assertEquals(128, nether.get("logical_height").getAsInt(), "nether keeps its lower logical height");
    }

    @Test
    void generatedLevelStemPointsAtOurType() {
        JsonObject stem = JsonParser.parseString(DimensionPack.levelStem(PRESET, WorldKind.NORMAL, DimensionPack.key("Tall"))).getAsJsonObject();
        assertEquals("vystormworlds:tall", stem.get("type").getAsString());
        assertEquals("minecraft:overworld", stem.getAsJsonObject("generator").get("settings").getAsString());
        JsonObject nether = JsonParser.parseString(DimensionPack.levelStem(PRESET, WorldKind.NETHER, "vystormworlds:deep")).getAsJsonObject();
        assertEquals("minecraft:nether", nether.getAsJsonObject("generator").get("settings").getAsString());
        JsonObject empty = JsonParser.parseString(DimensionPack.levelStem(FLAT, WorldKind.VOID, "vystormworlds:sky")).getAsJsonObject();
        JsonObject settings = empty.getAsJsonObject("generator").getAsJsonObject("settings");
        assertEquals(0, settings.getAsJsonArray("layers").size());
        assertEquals(0, settings.getAsJsonArray("structure_overrides").size());
        JsonObject flat = JsonParser.parseString(DimensionPack.levelStem(FLAT, WorldKind.FLAT, "vystormworlds:f")).getAsJsonObject();
        assertEquals(1, flat.getAsJsonObject("generator").getAsJsonObject("settings").getAsJsonArray("layers").size());
        JsonObject meta = JsonParser.parseString(DimensionPack.packMeta(107)).getAsJsonObject().getAsJsonObject("pack");
        assertEquals(107, meta.get("pack_format").getAsInt());
        assertEquals(107, meta.get("min_format").getAsInt());
    }

    @Test
    void dataPackFilesAreWrittenAndRemoved() throws Exception {
        ClassLoader fake = new ClassLoader(null) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                String text = switch (name) {
                    case "data/minecraft/dimension_type/overworld.json" -> TYPE;
                    case "data/minecraft/worldgen/world_preset/normal.json" -> PRESET;
                    case "version.json" -> "{\"pack_version\":{\"data_major\":107}}";
                    default -> null;
                };
                return text == null ? null : new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            }
        };
        Path level = Files.createDirectories(dir.resolve("world"));
        DimensionPack.write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, level, "Tall", WorldKind.NORMAL, -128, 640, fake);
        assertTrue(DimensionPack.exists(level, "tall"));
        Path type = level.resolve("datapacks/vystormworlds/data/vystormworlds/dimension_type/tall.json");
        assertEquals(640, JsonParser.parseString(Files.readString(type)).getAsJsonObject().get("height").getAsInt());
        assertTrue(Files.exists(level.resolve("datapacks/vystormworlds/pack.mcmeta")));
        assertEquals(107, DimensionPack.dataFormat(fake));
        assertThrows(IOException.class, () -> DimensionPack.write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, level, "nether1", WorldKind.NETHER, 0, 256, fake), "missing vanilla file is an error");
        DimensionPack.remove(level, "tall");
        assertFalse(DimensionPack.exists(level, "tall"));
    }
}
