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
    }
}