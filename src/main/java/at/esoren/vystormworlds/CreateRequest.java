package at.esoren.vystormworlds;

import java.util.List;
import java.util.Locale;

/**
 * Options for a new world, from {@code /worlds create <name> [type] [seed:..] [generator:Plugin[:id]] [biomes:Plugin[:id]]
 * [structures:false] [with-nether] [with-end] [group:..] [min-y:.. height:..]} or from the create dialog.
 */
record CreateRequest(String name, WorldKind kind, Long seed, GeneratorSpec generator, String biomeProvider, boolean structures,
                     boolean withNether, boolean withEnd, String group, Integer minY, Integer height) {

    /** Parse result: the request or a lang key with the offending token. */
    record Parsed(CreateRequest request, String error, String token) {}

    static Parsed parse(String name, List<String> tokens, String defaultGroup) {
        WorldKind kind = WorldKind.NORMAL;
        Long seed = null;
        GeneratorSpec generator = GeneratorSpec.NONE;
        String biomes = "", group = defaultGroup;
        boolean structures = true, nether = false, end = false;
        Integer minY = null, height = null;
        for (String token : tokens) {
            int colon = token.indexOf(':');
            String key = (colon < 0 ? token : token.substring(0, colon)).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : token.substring(colon + 1);
            try {
                switch (key) {
                    case "seed", "s" -> seed = seed(value);
                    case "generator", "gen", "g" -> generator = GeneratorSpec.parse(value);
                    case "biomes", "biome-provider" -> { GeneratorSpec.parse(value); biomes = value.strip(); }
                    case "structures" -> {
                        Boolean b = Properties.bool(value.toLowerCase(Locale.ROOT));
                        if (b == null) return new Parsed(null, "bad-option", token);
                        structures = b;
                    }
                    case "with-nether", "+nether" -> nether = true;
                    case "with-end", "+end" -> end = true;
                    case "group" -> {
                        if (!WorldSettings.validGroup(value.toLowerCase(Locale.ROOT))) return new Parsed(null, "bad-group", token);
                        group = value.toLowerCase(Locale.ROOT);
                    }
                    case "min-y", "miny" -> minY = Integer.parseInt(value.strip());
                    case "height" -> height = Integer.parseInt(value.strip());
                    default -> {
                        WorldKind parsed = colon < 0 ? WorldKind.parse(token) : null;
                        if (parsed == null) return new Parsed(null, "bad-option", token);
                        kind = parsed;
                    }
                }
            } catch (IllegalArgumentException bad) {
                return new Parsed(null, "bad-option", token);
            }
        }
        if (minY != null ^ height != null) return new Parsed(null, "height-both", "");
        if (minY != null) {
            String problem = DimensionPack.validate(kind, minY, height, generator.none() && kind != WorldKind.VOID);
            if (problem != null) return new Parsed(null, problem, "min-y:" + minY + " height:" + height);
            if (generator.type() == GeneratorSpec.Type.PRESET || !biomes.isEmpty()) return new Parsed(null, "height-generator", "");
            if (seed != null) return new Parsed(null, "height-seed", "");
        }
        if ((nether || end) && kind.environment != org.bukkit.World.Environment.NORMAL) return new Parsed(null, "links-overworld", "");
        return new Parsed(new CreateRequest(name, kind, seed, generator, biomes, structures, nether, end, group, minY, height), null, null);
    }

    /** Numbers are used as they are, any other text like vanilla does (its hash code); empty = random. */
    static Long seed(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) return null;
        try { return Long.parseLong(text); } catch (NumberFormatException words) { return (long) text.hashCode(); }
    }

    /** The settings for the new world (the linked nether/end get their own via {@link #linked}). */
    WorldSettings settings() {
        WorldSettings w = new WorldSettings(name);
        w.kind = kind;
        w.seed = seed;
        w.generator = generator;
        w.biomeProvider = biomeProvider;
        w.structures = structures;
        w.group = group;
        w.minY = minY;
        w.height = height;
        w.icon = switch (kind) {
            case NETHER -> "netherrack";
            case END -> "end_stone";
            case FLAT -> "grass_block";
            case VOID -> "glass";
            case AMPLIFIED -> "stone";
            default -> "";
        };
        return w;
    }

    /** Settings of the linked nether or end of this world: same seed and group, hidden from the selector. */
    WorldSettings linked(WorldKind dimension, Long resolvedSeed) {
        WorldSettings w = new WorldSettings(name + (dimension == WorldKind.NETHER ? "_nether" : "_the_end"));
        w.kind = dimension;
        w.seed = resolvedSeed;
        w.structures = structures;
        w.group = group;
        w.hidden = true;
        w.icon = dimension == WorldKind.NETHER ? "netherrack" : "end_stone";
        return w;
    }
}
