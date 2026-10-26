package net.h2020.maps;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Empty chunks with a fixed biome (keeps grass/foliage/sky colours of the map theme outside the template). */
public final class VoidGenerator extends ChunkGenerator {
    private final Biome biome;
    private final Location spawn;

    public VoidGenerator(Biome biome, double sx, double sy, double sz) {
        this.biome = biome == null ? Biome.PLAINS : biome;
        this.spawn = new Location(null, sx, sy, sz);
    }

    public VoidGenerator(Biome biome) { this(biome, 0.5, 100, 0.5); }

    @Override
    public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid grid) {
        for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) grid.setBiome(bx, bz, biome);
        return createChunkData(world);
    }

    @Override public boolean canSpawn(World world, int x, int z) { return true; }

    @Override public List<BlockPopulator> getDefaultPopulators(World world) { return Collections.emptyList(); }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, spawn.getX(), spawn.getY(), spawn.getZ());
    }
}
