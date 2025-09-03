package org.tecna.followersloadchunks.pathfinding;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * Helper class to store pathfinding context information
 */
public class PathfindingContext {
    public final BlockPos petPos;
    public final BlockPos ownerPos;
    public final double distanceToOwner;
    public final boolean isNearOwner;
    public final boolean isFarFromOwner;
    public final boolean significantHeightDifference;
    public final boolean extremeHeightDifference;
    public final Biome petBiome;
    public final Biome ownerBiome;
    public final TerrainAnalysis terrainAnalysis;

    public PathfindingContext(BlockPos petPos, BlockPos ownerPos, double distanceToOwner,
                              boolean isNearOwner, boolean isFarFromOwner,
                              boolean significantHeightDifference, boolean extremeHeightDifference,
                              Biome petBiome, Biome ownerBiome, TerrainAnalysis terrainAnalysis) {
        this.petPos = petPos;
        this.ownerPos = ownerPos;
        this.distanceToOwner = distanceToOwner;
        this.isNearOwner = isNearOwner;
        this.isFarFromOwner = isFarFromOwner;
        this.significantHeightDifference = significantHeightDifference;
        this.extremeHeightDifference = extremeHeightDifference;
        this.petBiome = petBiome;
        this.ownerBiome = ownerBiome;
        this.terrainAnalysis = terrainAnalysis;
    }
}