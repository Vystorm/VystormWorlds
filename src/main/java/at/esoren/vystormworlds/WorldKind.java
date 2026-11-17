package at.esoren.vystormworlds;

import java.util.Locale;
import org.bukkit.World;

/** World types offered when creating a world: dimension plus terrain preset. */
enum WorldKind {
    NORMAL(World.Environment.NORMAL), NETHER(World.Environment.NETHER), END(World.Environment.THE_END),
    FLAT(World.Environment.NORMAL), VOID(World.Environment.NORMAL), AMPLIFIED(World.Environment.NORMAL), LARGE_BIOMES(World.Environment.NORMAL);

    final World.Environment environment;

    WorldKind(World.Environment environment) { this.environment = environment; }

    /** Lower-case id as used in commands and worlds.yml ({@code large-biomes}). */
    String id() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Parses ids and common aliases; null for unknown input. */
    static WorldKind parse(String raw) {
        if (raw == null) return null;
        String key = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "normal", "overworld", "default" -> NORMAL;
            case "nether", "the-nether", "hell" -> NETHER;
            case "end", "the-end" -> END;
            case "flat", "superflat" -> FLAT;
            case "void", "empty" -> VOID;
            case "amplified" -> AMPLIFIED;
            case "large-biomes", "largebiomes" -> LARGE_BIOMES;
            default -> null;
        };
    }

    static WorldKind of(World.Environment environment) {
        return switch (environment) {
            case NETHER -> NETHER;
            case THE_END -> END;
            default -> NORMAL;
        };
    }
}
