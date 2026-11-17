package at.esoren.vystormworlds;

import io.papermc.paper.math.Position;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/** Compiled separately against the current Paper API; loaded only after method detection. */
public final class ModernWorldCompat implements WorldCompat {
    public boolean canFlush() { return true; }
    public void flush(World world) { world.save(true); }

    public boolean forceSpawn(WorldCreator creator, int x, int y, int z) {
        try {
            WorldCreator.class.getMethod("forcedSpawnPosition", Position.class, float.class, float.class)
                    .invoke(creator, Position.block(x, y, z), 0f, 0f);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError older) { return false; }
    }
}
