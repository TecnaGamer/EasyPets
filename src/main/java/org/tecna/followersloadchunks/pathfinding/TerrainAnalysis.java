package org.tecna.followersloadchunks.pathfinding;

/**
 * Enum for terrain analysis results
 */
public enum TerrainAnalysis {
    CLEAR,              // Direct path with no obstacles
    MOSTLY_WATER,       // Path goes mostly through water
    DANGEROUS_LIQUIDS,  // Path contains lava or other dangerous liquids
    DENSE_TERRAIN,      // Path blocked by many solid blocks
    OPEN_AIR,           // Path mostly through air (good for flying)
    MIXED,              // Mixed terrain types
    UNKNOWN             // Analysis failed or disabled
}