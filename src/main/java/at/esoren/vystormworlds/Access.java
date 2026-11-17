package at.esoren.vystormworlds;

/** Who may enter a world: permission, player limit and entry price. Pure rules, checked on every way in. */
final class Access {
    enum Result { OK, NOT_LOADED, NO_PERMISSION, FULL, PAY, NO_MONEY }

    /** What is known about the player at the moment of entry. */
    record Viewer(boolean bypass, boolean permitted, boolean unlocked, boolean economy, boolean canAfford) {}

    private Access() {}

    /** Permission node a world with {@code entry.permission: true} requires. */
    static String permission(String world) { return "vystormworlds.enter." + world.toLowerCase(java.util.Locale.ROOT); }

    /**
     * @param players players already in the world (without the one entering)
     * @return OK (free entry), PAY (allowed, price is charged), or the reason for refusal
     */
    static Result check(WorldSettings world, boolean loaded, Viewer viewer, int players) {
        if (!loaded) return Result.NOT_LOADED;
        if (viewer.bypass()) return Result.OK;
        if (world.permission && !viewer.permitted()) return Result.NO_PERMISSION;
        if (world.playerLimit > 0 && players >= world.playerLimit) return Result.FULL;
        if (!charges(world, viewer)) return Result.OK;
        return viewer.canAfford() ? Result.PAY : Result.NO_MONEY;
    }

    /** Whether entering costs money for this viewer (no economy installed = free). */
    static boolean charges(WorldSettings world, Viewer viewer) {
        if (world.price <= 0 || !viewer.economy() || viewer.bypass()) return false;
        return !("once".equals(world.priceMode) && viewer.unlocked());
    }
}
