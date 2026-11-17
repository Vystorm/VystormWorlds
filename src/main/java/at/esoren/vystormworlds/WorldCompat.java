package at.esoren.vystormworlds;

import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/** Optional world operations, isolated from the baseline API. */
interface WorldCompat {
    WorldCompat INSTANCE = select(hasFlushSave(), WorldCompat::modern);

    boolean canFlush();
    void flush(World world);
    boolean forceSpawn(WorldCreator creator, int x, int y, int z);

    static boolean hasFlushSave() {
        try { World.class.getMethod("save", boolean.class); return true; }
        catch (NoSuchMethodException absent) { return false; }
    }

    static WorldCompat select(boolean flushAvailable, Supplier<WorldCompat> modern) {
        return flushAvailable ? modern.get() : new Legacy();
    }

    private static WorldCompat modern() {
        try {
            return (WorldCompat) Class.forName("at.esoren.vystormworlds.ModernWorldCompat").getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot load world adapter", failure); }
    }

    final class Legacy implements WorldCompat {
        public boolean canFlush() { return true; }
        public void flush(World world) {
            world.save();
            // CraftBukkit exposes the same durable vanilla save through its built-in command.
            if (!org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "minecraft:save-all flush"))
                throw new IllegalStateException("World flush failed; live clone was not started");
        }
        public boolean forceSpawn(WorldCreator creator, int x, int y, int z) { return false; }
    }
}
