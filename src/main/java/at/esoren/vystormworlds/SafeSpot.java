package at.esoren.vystormworlds;

import org.bukkit.Material;

/**
 * Finds a spot where a player can stand: solid, harmless ground, and two free, harmless blocks above. Searches the
 * target column first (closest height first), then columns in growing squares around it.
 */
final class SafeSpot {
    enum Kind { FREE, SOLID, DANGER, LIQUID }

    @FunctionalInterface
    interface Blocks { Kind at(int x, int y, int z); }

    record Spot(int x, int y, int z) {}

    private SafeSpot() {}

    static boolean safe(Blocks blocks, int x, int y, int z) {
        return blocks.at(x, y - 1, z) == Kind.SOLID && blocks.at(x, y, z) == Kind.FREE && blocks.at(x, y + 1, z) == Kind.FREE;
    }

    /**
     * @param radius   horizontal search radius in blocks
     * @param vertical how far up and down to look in each column
     * @param minY     lowest y a player may stand on (exclusive of the ground block)
     * @param maxY     highest y the head may reach
     */
    static Spot find(Blocks blocks, int x, int y, int z, int radius, int vertical, int minY, int maxY) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Spot spot = column(blocks, x + dx, y, z + dz, vertical, minY, maxY);
                    if (spot != null) return spot;
                }
            }
        }
        return null;
    }

    private static Spot column(Blocks blocks, int x, int y, int z, int vertical, int minY, int maxY) {
        for (int d = 0; d <= vertical; d++) {
            int up = y + d, down = y - d;
            if (up > minY && up + 1 <= maxY && safe(blocks, x, up, z)) return new Spot(x, up, z);
            if (d > 0 && down > minY && down + 1 <= maxY && safe(blocks, x, down, z)) return new Spot(x, down, z);
        }
        return null;
    }

    /** Classifies a block for {@link Blocks}. */
    static Kind classify(Material type) {
        if (type == null) return Kind.DANGER;
        switch (type) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW,
                 POINTED_DRIPSTONE, NETHER_PORTAL, END_PORTAL, END_GATEWAY, COBWEB -> { return Kind.DANGER; }
            case WATER, BUBBLE_COLUMN -> { return Kind.LIQUID; }
            default -> { }
        }
        if (type.isAir()) return Kind.FREE;
        if (type.isSolid()) return Kind.SOLID;
        return type.isOccluding() ? Kind.SOLID : Kind.FREE;
    }
}
