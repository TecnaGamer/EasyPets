package org.tecna.followersloadchunks.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FollowersLoadChunksConfig {
    private static final String CONFIG_FILE_NAME = "followersloadchunks.json";
    private static FollowersLoadChunksConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Configuration options with default values
    public boolean enableChunkLoading = true;
    public double petTeleportDistance = 12.0; // Distance in blocks before pet tries to teleport (vanilla is 12)
    public boolean triggerSaveOnPetLocator = false;
    public boolean triggerSaveOnRecovery = false;
    public boolean enableDebugLogging = false;
    public int maxChunkLoadingDistance = 2; // Chunk loading radius

    // Advanced options
    public int chunkUnloadDelayTicks = 1200; // Delay before unloading chunks (60 seconds)
    public boolean enablePeriodicValidation = true;
    public int validationIntervalTicks = 6000; // How often to validate pet chunk loading (5 minutes)

    // Performance options
    public boolean enableFastMovementDetection = true;
    public double fastMovementThreshold = 5.0; // Chunks moved before considering it "fast movement"
    public int fastMovementOverlapTicks = 100; // Overlap time for fast-moving pets

    // Advanced Pathfinding Options
    public boolean enableAdvancedPathfinding = true; // Master switch for all advanced pathfinding
    public double navigationRangeMultiplier = 2.5; // Multiplier for navigation range based on teleport distance
    public int maxNavigationRange = 320; // Maximum navigation range in blocks
    public int minNavigationRange = 32; // Minimum navigation range in blocks

    // Pathfinding Intelligence Settings
    public boolean enableBiomeAwarePathfinding = true; // Different behavior in different biomes
    public boolean enableOwnerActivityDetection = true; // Adjust behavior based on owner activity
    public boolean enableTerrainAnalysis = true; // Look ahead for better path decisions
    public int pathfindingTimeoutTicks = 1200; // How long to try pathfinding before encouraging teleport (60 seconds)

    // Safety Settings - These ensure pets never follow owners into extreme danger
    public boolean enablePathfindingSafety = true; // Master safety switch
    public boolean alwaysAvoidLava = true; // Never path through lava even if owner is in it
    public boolean alwaysAvoidFire = true; // Never path through fire even if owner is in it
    public boolean prioritizeAirBreathing = true; // Encourage getting out of water when drowning

    private FollowersLoadChunksConfig() {}

    public static FollowersLoadChunksConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = loadConfig();
        }
        return INSTANCE;
    }

    private static FollowersLoadChunksConfig loadConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                FollowersLoadChunksConfig config = GSON.fromJson(json, FollowersLoadChunksConfig.class);
                if (config != null) {
                    // Validate config values
                    config.validateAndFixValues();
                    // Save back to ensure any new defaults are written
                    config.saveConfig();
                    System.out.println("[FollowersLoadChunks] Loaded configuration from " + CONFIG_FILE_NAME);
                    return config;
                }
            } catch (IOException | JsonSyntaxException e) {
                System.err.println("[FollowersLoadChunks] Failed to load config: " + e.getMessage());
                System.out.println("[FollowersLoadChunks] Creating new config file with default values");
            }
        }

        // Create new config with defaults
        FollowersLoadChunksConfig defaultConfig = new FollowersLoadChunksConfig();
        defaultConfig.saveConfig();
        System.out.println("[FollowersLoadChunks] Created new config file: " + CONFIG_FILE_NAME);
        return defaultConfig;
    }

    private void validateAndFixValues() {
        // Ensure values are within reasonable bounds
        if (petTeleportDistance < 1.0) petTeleportDistance = 1.0;
        if (petTeleportDistance > 100.0) petTeleportDistance = 100.0;

        if (maxChunkLoadingDistance < 1) maxChunkLoadingDistance = 1;
        if (maxChunkLoadingDistance > 10) maxChunkLoadingDistance = 10;

        if (chunkUnloadDelayTicks < 100) chunkUnloadDelayTicks = 100; // Minimum 5 seconds
        if (chunkUnloadDelayTicks > 12000) chunkUnloadDelayTicks = 12000; // Maximum 10 minutes

        if (validationIntervalTicks < 1200) validationIntervalTicks = 1200; // Minimum 1 minute
        if (validationIntervalTicks > 72000) validationIntervalTicks = 72000; // Maximum 1 hour

        if (fastMovementThreshold < 1.0) fastMovementThreshold = 1.0;
        if (fastMovementThreshold > 50.0) fastMovementThreshold = 50.0;

        if (fastMovementOverlapTicks < 20) fastMovementOverlapTicks = 20; // Minimum 1 second
        if (fastMovementOverlapTicks > 600) fastMovementOverlapTicks = 600; // Maximum 30 seconds

        // Validate new pathfinding settings
        if (navigationRangeMultiplier < 1.0) navigationRangeMultiplier = 1.0;
        if (navigationRangeMultiplier > 10.0) navigationRangeMultiplier = 10.0;

        if (maxNavigationRange < 32) maxNavigationRange = 32;
        if (maxNavigationRange > 1000) maxNavigationRange = 1000; // Prevent excessive values

        if (minNavigationRange < 16) minNavigationRange = 16;
        if (minNavigationRange > maxNavigationRange) minNavigationRange = maxNavigationRange / 2;

        if (pathfindingTimeoutTicks < 200) pathfindingTimeoutTicks = 200; // Minimum 10 seconds
        if (pathfindingTimeoutTicks > 6000) pathfindingTimeoutTicks = 6000; // Maximum 5 minutes
    }

    public void saveConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

        try {
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            if (enableDebugLogging) {
                System.out.println("[FollowersLoadChunks] Saved configuration to " + CONFIG_FILE_NAME);
            }
        } catch (IOException e) {
            System.err.println("[FollowersLoadChunks] Failed to save config: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        INSTANCE = loadConfig();
    }

    // Getter methods for easy access
    public boolean isChunkLoadingEnabled() {
        return enableChunkLoading;
    }

    public double getPetTeleportDistance() {
        return petTeleportDistance;
    }

    public boolean shouldTriggerSaveOnPetLocator() {
        return triggerSaveOnPetLocator;
    }

    public boolean shouldTriggerSaveOnRecovery() {
        return triggerSaveOnRecovery;
    }

    public boolean isDebugLoggingEnabled() {
        return enableDebugLogging;
    }

    public int getMaxChunkLoadingDistance() {
        return maxChunkLoadingDistance;
    }

    public int getChunkUnloadDelayTicks() {
        return chunkUnloadDelayTicks;
    }

    public boolean isPeriodicValidationEnabled() {
        return enablePeriodicValidation;
    }

    public int getValidationIntervalTicks() {
        return validationIntervalTicks;
    }

    public boolean isFastMovementDetectionEnabled() {
        return enableFastMovementDetection;
    }

    public double getFastMovementThreshold() {
        return fastMovementThreshold;
    }

    public int getFastMovementOverlapTicks() {
        return fastMovementOverlapTicks;
    }

    // New getters for advanced pathfinding
    public boolean isAdvancedPathfindingEnabled() {
        return enableAdvancedPathfinding;
    }

    public double getNavigationRangeMultiplier() {
        return navigationRangeMultiplier;
    }

    public int getMaxNavigationRange() {
        return maxNavigationRange;
    }

    public int getMinNavigationRange() {
        return minNavigationRange;
    }

    public boolean isBiomeAwarePathfindingEnabled() {
        return enableAdvancedPathfinding && enableBiomeAwarePathfinding;
    }

    public boolean isOwnerActivityDetectionEnabled() {
        return enableAdvancedPathfinding && enableOwnerActivityDetection;
    }

    public boolean isTerrainAnalysisEnabled() {
        return enableAdvancedPathfinding && enableTerrainAnalysis;
    }

    public int getPathfindingTimeoutTicks() {
        return pathfindingTimeoutTicks;
    }

    public boolean isPathfindingSafetyEnabled() {
        return enablePathfindingSafety;
    }

    public boolean shouldAlwaysAvoidLava() {
        return enablePathfindingSafety && alwaysAvoidLava;
    }

    public boolean shouldAlwaysAvoidFire() {
        return enablePathfindingSafety && alwaysAvoidFire;
    }

    public boolean shouldPrioritizeAirBreathing() {
        return enablePathfindingSafety && prioritizeAirBreathing;
    }

    // Method to get squared distance for performance
    public double getPetTeleportDistanceSquared() {
        // Convert chunks to blocks (1 chunk = 16 blocks) and square it
        double blocksDistance = petTeleportDistance * 16.0;
        return blocksDistance * blocksDistance;
    }

    public void printCurrentConfig() {
        System.out.println("[FollowersLoadChunks] Current Configuration:");
        System.out.println("  Chunk Loading Enabled: " + enableChunkLoading);
        System.out.println("  Pet Teleport Distance: " + petTeleportDistance + " chunks");
        System.out.println("  Save on /petlocator: " + triggerSaveOnPetLocator);
        System.out.println("  Save on Recovery: " + triggerSaveOnRecovery);
        System.out.println("  Debug Logging: " + enableDebugLogging);
        System.out.println("  Max Chunk Loading Distance: " + maxChunkLoadingDistance);
        System.out.println("  Chunk Unload Delay: " + chunkUnloadDelayTicks + " ticks");
        System.out.println("  Periodic Validation: " + enablePeriodicValidation);
        System.out.println("  Validation Interval: " + validationIntervalTicks + " ticks");
        System.out.println("  Fast Movement Detection: " + enableFastMovementDetection);
        System.out.println("  Fast Movement Threshold: " + fastMovementThreshold + " chunks");
        System.out.println("  Fast Movement Overlap: " + fastMovementOverlapTicks + " ticks");

        // Advanced pathfinding settings
        System.out.println("  Advanced Pathfinding: " + enableAdvancedPathfinding);
        if (enableAdvancedPathfinding) {
            System.out.println("    Navigation Range Multiplier: " + navigationRangeMultiplier);
            System.out.println("    Max Navigation Range: " + maxNavigationRange + " blocks");
            System.out.println("    Min Navigation Range: " + minNavigationRange + " blocks");
            System.out.println("    Biome Aware Pathfinding: " + enableBiomeAwarePathfinding);
            System.out.println("    Owner Activity Detection: " + enableOwnerActivityDetection);
            System.out.println("    Terrain Analysis: " + enableTerrainAnalysis);
            System.out.println("    Pathfinding Timeout: " + pathfindingTimeoutTicks + " ticks");
        }

        System.out.println("  Pathfinding Safety: " + enablePathfindingSafety);
        if (enablePathfindingSafety) {
            System.out.println("    Always Avoid Lava: " + alwaysAvoidLava);
            System.out.println("    Always Avoid Fire: " + alwaysAvoidFire);
            System.out.println("    Prioritize Air Breathing: " + prioritizeAirBreathing);
        }
    }
}