package org.tecna.followersloadchunks.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final String CONFIG_FILE_NAME = "followersloadchunks.json";
    private static Config INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Core functionality - only what we actually use
    public boolean enableChunkLoading = true;
    public double petTeleportDistance = 12.0; // Distance in blocks before pet tries to teleport
    public int maxChunkLoadingDistance = 2; // Chunk loading radius
    public int maxNavigationRange = 320; // Maximum navigation range in blocks (used in FollowOwnerGoalMixin)
    public boolean enableDebugLogging = false;

    // Save options (used in PetRecoveryCommand)
    public boolean triggerSaveOnPetLocator = false;
    public boolean triggerSaveOnRecovery = false;

    private Config() {}

    public static Config getInstance() {
        if (INSTANCE == null) {
            INSTANCE = loadConfig();
        }
        return INSTANCE;
    }

    private static Config loadConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                Config config = GSON.fromJson(json, Config.class);
                if (config != null) {
                    config.validateAndFixValues();
                    config.saveConfig();
                    System.out.println("[FollowersLoadChunks] Loaded configuration from " + CONFIG_FILE_NAME);
                    return config;
                }
            } catch (IOException | JsonSyntaxException e) {
                System.err.println("[FollowersLoadChunks] Failed to load config: " + e.getMessage());
                System.out.println("[FollowersLoadChunks] Creating new config file with default values");
            }
        }

        Config defaultConfig = new Config();
        defaultConfig.saveConfig();
        System.out.println("[FollowersLoadChunks] Created new config file: " + CONFIG_FILE_NAME);
        return defaultConfig;
    }

    private void validateAndFixValues() {
        // Simple validation for the few options we have left
        if (petTeleportDistance < 1.0) petTeleportDistance = 1.0;
        if (petTeleportDistance > 100.0) petTeleportDistance = 100.0;

        if (maxChunkLoadingDistance < 1) maxChunkLoadingDistance = 1;
        if (maxChunkLoadingDistance > 10) maxChunkLoadingDistance = 10;

        if (maxNavigationRange < 32) maxNavigationRange = 32;
        if (maxNavigationRange > 1000) maxNavigationRange = 1000;
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

    // Essential getters only
    public boolean isChunkLoadingEnabled() { return enableChunkLoading; }
    public double getPetTeleportDistance() { return petTeleportDistance; }
    public boolean shouldTriggerSaveOnPetLocator() { return triggerSaveOnPetLocator; }
    public boolean shouldTriggerSaveOnRecovery() { return triggerSaveOnRecovery; }
    public boolean isDebugLoggingEnabled() { return enableDebugLogging; }
    public int getMaxChunkLoadingDistance() { return maxChunkLoadingDistance; }
    public int getMaxNavigationRange() { return maxNavigationRange; }

    // Keep this for the teleport distance calculation
    public double getPetTeleportDistanceSquared() {
        double blocksDistance = petTeleportDistance * 16.0;
        return blocksDistance * blocksDistance;
    }

    public void printCurrentConfig() {
        System.out.println("[FollowersLoadChunks] Current Configuration:");
        System.out.println("  Chunk Loading Enabled: " + enableChunkLoading);
        System.out.println("  Pet Teleport Distance: " + petTeleportDistance + " blocks");
        System.out.println("  Save on /petlocator: " + triggerSaveOnPetLocator);
        System.out.println("  Save on Recovery: " + triggerSaveOnRecovery);
        System.out.println("  Debug Logging: " + enableDebugLogging);
        System.out.println("  Max Chunk Loading Distance: " + maxChunkLoadingDistance);
        System.out.println("  Max Navigation Range: " + maxNavigationRange + " blocks");
        System.out.println("  System: Player-based (like ender pearls)");
    }
}