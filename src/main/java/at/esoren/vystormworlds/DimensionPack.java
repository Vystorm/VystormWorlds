package at.esoren.vystormworlds;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Worlds with a custom build height. Paper has no API to pick a dimension type for a world, so - like Iris - a data
 * pack defines one: {@code <level>/datapacks/vystormworlds/data/vystormworlds/dimension_type/<world>.json} plus a level
 * stem {@code dimension/<world>.json} using it. The server reads data packs only while starting, so such a world is
 * created by the server on the next start (world key {@code vystormworlds:<world>}) and adopted by the plugin then.
 *
 * <p>Both files are derived from the running server's own vanilla files (dimension type and world preset), so they
 * always match its data format: a malformed dimension file would stop the whole server from starting.</p>
 */
final class DimensionPack {
    static final String NAMESPACE = "vystormworlds";
    static final int LOWEST = -2032, MAX_HEIGHT = 4064, HIGHEST = 2032;

    private DimensionPack() {}

    /** Vanilla terrain range of a world type (lowest y, top y exclusive): custom heights must contain it. */
    static int[] terrain(WorldKind kind) {
        return switch (kind) {
            case NETHER, END -> new int[] {0, 128};
            case FLAT, VOID -> new int[] {-64, -60};
            default -> new int[] {-64, 320};
        };
    }

    /**
     * Checks a custom height; returns a lang key for the problem, or null if valid.
     *
     * @param vanillaTerrain true when the vanilla generator fills the world (its terrain range must fit)
     */
    static String validate(WorldKind kind, int minY, int height, boolean vanillaTerrain) {
        if (minY % 16 != 0 || height % 16 != 0) return "height-multiple";
        if (height < 16 || height > MAX_HEIGHT) return "height-range";
        if (minY < LOWEST || minY + height > HIGHEST) return "height-range";
        if (vanillaTerrain) {
            int[] terrain = terrain(kind);
            if (minY > terrain[0] || minY + height < terrain[1]) return "height-terrain";
        }
        return null;
    }

    static String key(String world) { return NAMESPACE + ":" + world.toLowerCase(Locale.ROOT); }

    /** The vanilla dimension type file this kind starts from. */
    static String vanillaType(WorldKind kind) {
        return switch (kind) {
            case NETHER -> "the_nether";
            case END -> "the_end";
            default -> "overworld";
        };
    }

    /** Dimension type JSON: the vanilla type with new {@code min_y}, {@code height} and {@code logical_height}. */
    static String dimensionType(String vanillaJson, int minY, int height) {
        JsonObject json = JsonParser.parseString(vanillaJson).getAsJsonObject();
        int logical = json.has("logical_height") ? json.get("logical_height").getAsInt() : height;
        int vanillaHeight = json.has("height") ? json.get("height").getAsInt() : height;
        json.addProperty("min_y", minY);
        json.addProperty("height", height);
        // nether-like types keep their lower logical height (portal and chorus logic), others use the full height
        json.addProperty("logical_height", logical < vanillaHeight ? Math.min(logical, height) : height);
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    /** Which vanilla world preset file provides the generator, and which dimension in it. */
    static String[] presetSource(WorldKind kind) {
        return switch (kind) {
            case NETHER -> new String[] {"normal", "minecraft:the_nether"};
            case END -> new String[] {"normal", "minecraft:the_end"};
            case FLAT, VOID -> new String[] {"flat", "minecraft:overworld"};
            case AMPLIFIED -> new String[] {"amplified", "minecraft:overworld"};
            case LARGE_BIOMES -> new String[] {"large_biomes", "minecraft:overworld"};
            default -> new String[] {"normal", "minecraft:overworld"};
        };
    }

    /** Level stem JSON: the generator of the vanilla preset, pointing at our dimension type. Void = flat without layers. */
    static String levelStem(String presetJson, WorldKind kind, String typeKey) {
        JsonObject preset = JsonParser.parseString(presetJson).getAsJsonObject();
        JsonObject dimension = preset.getAsJsonObject("dimensions").getAsJsonObject(presetSource(kind)[1]);
        JsonObject generator = dimension.getAsJsonObject("generator").deepCopy();
        if (kind == WorldKind.VOID) {
            JsonObject settings = generator.getAsJsonObject("settings");
            settings.add("layers", new JsonArray());
            settings.add("structure_overrides", new JsonArray());
            settings.addProperty("features", false);
            settings.addProperty("lakes", false);
            settings.addProperty("biome", "minecraft:the_void");
        }
        JsonObject stem = new JsonObject();
        stem.addProperty("type", typeKey);
        stem.add("generator", generator);
        return new GsonBuilder().setPrettyPrinting().create().toJson(stem);
    }

    static String packMeta(int dataFormat) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Vystorm Worlds: custom build heights");
        pack.addProperty("pack_format", dataFormat);
        pack.addProperty("min_format", dataFormat);
        pack.addProperty("max_format", 9999);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** Data pack folder in the main level. */
    static Path folder(Path level) { return level.resolve("datapacks").resolve(NAMESPACE); }

    /** Writes (or replaces) the files for one world; the server picks them up on the next start. */
    static void write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files, Path level, String world, WorldKind kind, int minY, int height, ClassLoader server) throws IOException {
        String type = dimensionType(resource(server, "data/minecraft/dimension_type/" + vanillaType(kind) + ".json"), minY, height);
        String stem = levelStem(resource(server, "data/minecraft/worldgen/world_preset/" + presetSource(kind)[0] + ".json"), kind, key(world));
        int format = dataFormat(server);
        Path pack = folder(level);
        Path data = pack.resolve("data").resolve(NAMESPACE);
        files.write(pack.resolve("pack.mcmeta"), packMeta(format).getBytes(StandardCharsets.UTF_8));
        files.write(data.resolve("dimension_type").resolve(world.toLowerCase(Locale.ROOT) + ".json"), type.getBytes(StandardCharsets.UTF_8));
        files.write(data.resolve("dimension").resolve(world.toLowerCase(Locale.ROOT) + ".json"), stem.getBytes(StandardCharsets.UTF_8));
    }

    /** Removes a world's files (the world is then no longer created on start). */
    static void remove(Path level, String world) throws IOException {
        Path data = folder(level).resolve("data").resolve(NAMESPACE);
        Files.deleteIfExists(data.resolve("dimension_type").resolve(world.toLowerCase(Locale.ROOT) + ".json"));
        Files.deleteIfExists(data.resolve("dimension").resolve(world.toLowerCase(Locale.ROOT) + ".json"));
    }

    static boolean exists(Path level, String world) {
        return Files.isRegularFile(folder(level).resolve("data").resolve(NAMESPACE).resolve("dimension").resolve(world.toLowerCase(Locale.ROOT) + ".json"));
    }

    static String resource(ClassLoader loader, String path) throws IOException {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) throw new IOException("vanilla file " + path + " not found in the server jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Data pack format of the running server (version.json in the server jar), 107 if unknown. */
    static int dataFormat(ClassLoader loader) {
        try {
            JsonObject version = JsonParser.parseString(resource(loader, "version.json")).getAsJsonObject();
            JsonElement pack = version.get("pack_version");
            if (pack != null && pack.isJsonObject() && pack.getAsJsonObject().has("data_major")) return pack.getAsJsonObject().get("data_major").getAsInt();
            if (pack != null && pack.isJsonObject() && pack.getAsJsonObject().has("data")) return pack.getAsJsonObject().get("data").getAsInt();
        } catch (IOException | RuntimeException unknown) { }
        return 107;
    }
}
