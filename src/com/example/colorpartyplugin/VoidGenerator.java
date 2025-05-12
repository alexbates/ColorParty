// This file will need to be updated when methods are no longer available
// It generates void world for the minigame

package com.example.colorpartyplugin;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import java.util.Random;

public class VoidGenerator extends ChunkGenerator {
    @Override
    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        // Use the helper method from ChunkGenerator to create an empty chunk.
        return this.createChunkData(world);
    }

    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome, ChunkData chunkData) {
        // Simply return the provided ChunkData for the new API.
        return chunkData;
    }
}