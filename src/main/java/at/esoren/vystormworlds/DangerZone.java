package at.esoren.vystormworlds;

import java.util.Locale;

/**
 * The rules of the world page's danger zone (1.21.0): unload, regenerate, delete and copy. Pure, so tests pin them.
 * Regenerate and delete need the world name typed again, exactly as {@code /worlds regen|delete} asks for it today
 * (dialog in game, name as last word from the console); unload and a copy that moves players out need a confirm.
 * Nothing here is ever one click.
 */
final class DangerZone {
    enum Kind { UNLOAD, REGEN, DELETE, CLONE }

    private DangerZone() {}

    /** Null when {@code typed} is the world's name (case and outer spaces ignored, as the command does), else the refusal key. */
    static String typed(String typed, String world) {
        return typed != null && !world.isEmpty() && typed.strip().equalsIgnoreCase(world) ? null : "confirm-mismatch";
    }

    /**
     * Why an operation cannot run on the world at all, or null.
     *
     * @param loaded      the world is loaded now
     * @param serverWorld the world is the main world, its nether or end (or marked as server world)
     */
    static String refused(Kind kind, boolean loaded, boolean serverWorld, boolean busy) {
        if (busy) return "busy";
        return switch (kind) {
            case UNLOAD -> !loaded ? "not-loaded" : serverWorld ? "server-world" : null;
            case REGEN, DELETE -> serverWorld ? "server-world" : null;
            case CLONE -> null;
        };
    }

    /**
     * The seed word of {@code /worlds regen WORLD SEED}: {@code same}, {@code random} or the typed seed (one word,
     * numbers or text like the command). Null when "own seed" was chosen without a usable seed.
     */
    static String seed(String choice, String typed) {
        String c = choice == null ? "same" : choice.strip().toLowerCase(Locale.ROOT);
        String t = typed == null ? "" : typed.strip();
        return switch (c) {
            case "same", "" -> "same";
            case "random" -> "random";
            default -> t.isEmpty() || t.contains(" ") || t.equalsIgnoreCase("same") || t.equalsIgnoreCase("random") ? null : t;
        };
    }

    /** Whether a copy needs the extra "players are moved out / copied live" confirm, as {@code /worlds clone ... confirm}. */
    static boolean cloneNeedsConfirm(WorldManager.CloneMode mode, int players) {
        return WorldManager.clonePlayersProblem(mode, players, false) != null;
    }

    /** The command a console would type for the same operation (the menu shows it under "copy as command"). */
    static String command(Kind kind, String world, String seed, String target, boolean confirmed) {
        return switch (kind) {
            case UNLOAD -> "/worlds unload " + world;
            case DELETE -> "/worlds delete " + world + " " + world;
            case REGEN -> "/worlds regen " + world + " " + (seed == null ? "same" : seed) + " " + world;
            case CLONE -> "/worlds clone " + world + " " + (target == null || target.isBlank() ? "NEW" : target.strip().toLowerCase(Locale.ROOT))
                    + (confirmed ? " confirm" : "");
        };
    }
}
