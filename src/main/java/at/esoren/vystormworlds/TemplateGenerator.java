package at.esoren.vystormworlds;

import java.util.Random;
import org.bukkit.*;
import org.bukkit.generator.*;

/** Small, deterministic visit landscapes with a safe fixed spawn and no world scans. */
final class TemplateGenerator extends ChunkGenerator {
    private final boolean island;
    TemplateGenerator(String terrain){island=terrain.equals("island");}
    Material block(int x,int y,int z){
        if(y==59)return Material.BEDROCK;
        if(y==60)return Material.STONE;
        boolean land=!island||(long)x*x+(long)z*z<=18*18;
        if(y==61||y==62)return land?Material.DIRT:Material.WATER;
        if(y==63&&land)return Material.GRASS_BLOCK;
        return Material.AIR;
    }
    @Override public void generateSurface(WorldInfo world,Random random,int cx,int cz,ChunkData data){
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=59;y<=63;y++)data.setBlock(x,y,z,block(cx*16+x,y,cz*16+z));
    }
    @Override public Location getFixedSpawnLocation(World world,Random random){return new Location(world,.5,64,.5);}
    @Override public boolean shouldGenerateNoise(){return false;}
    @Override public boolean shouldGenerateSurface(){return false;}
    @Override public boolean shouldGenerateCaves(){return false;}
    @Override public boolean shouldGenerateDecorations(){return false;}
    @Override public boolean shouldGenerateMobs(){return false;}
    @Override public boolean shouldGenerateStructures(){return false;}
}
