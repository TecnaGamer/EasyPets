package org.tecna.easypets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final String CONFIG_FILE_NAME = "easypets.json";
    private static Config INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Core functionality - only what we actually use
    public boolean enableChunkLoading = true;
    public double teleportDistance = 12.0; // Distance in blocks before pet tries to teleport
    public int maxChunkDistance = 2; // Chunk loading radius
    public int navigationScanningRange = 320; // Maximum navigation range in blocks
    public boolean enableDebugLogging = false;

    // Dynamic Pet Running Settings
    public boolean enableDynamicRunning = true;
    public double runningTargetDistance = 6.0; // Distance where pets start running faster
    public double runningMaxDistance = -1.0; // -1 means use teleportDistance
    public double maxRunningMultiplier = 1.5; // Maximum running speed boost
    public double playerMovementThreshold = 0.1; // Minimum player movement to trigger speed changes

    // Save options
    public boolean saveOnLocate = false;
    public boolean saveOnRecovery = false;

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
                    System.out.println("[EasyPets] Loaded configuration from " + CONFIG_FILE_NAME);
                    return config;
                }
            } catch (IOException | JsonSyntaxException e) {
                System.err.println("[EasyPets] Failed to load config: " + e.getMessage());
                System.out.println("[EasyPets] Creating new config file with default values");
            }
        }

        Config defaultConfig = new Config();
        defaultConfig.saveConfig();
        System.out.println("[EasyPets] Created new config file: " + CONFIG_FILE_NAME);
        return defaultConfig;
    }

    private void validateAndFixValues() {
        // Core validation
        if (teleportDistance < 1.0) teleportDistance = 1.0;
        if (teleportDistance > 100.0) teleportDistance = 100.0;

        if (maxChunkDistance < 1) maxChunkDistance = 1;
        if (maxChunkDistance > 10) maxChunkDistance = 10;

        if (navigationScanningRange < 32) navigationScanningRange = 32;
        if (navigationScanningRange > 1000) navigationScanningRange = 1000;

        // Dynamic Pet Running validation
        if (runningTargetDistance < 1.0) runningTargetDistance = 1.0;
        if (runningTargetDistance > 50.0) runningTargetDistance = 50.0;

        // If runningMaxDistance is -1, use teleportDistance, otherwise validate
        if (runningMaxDistance <= 0) {
            runningMaxDistance = teleportDistance;
        } else {
            if (runningMaxDistance > 100.0) runningMaxDistance = 100.0;
            if (runningMaxDistance <= runningTargetDistance) {
                runningMaxDistance = runningTargetDistance + 2.0;
            }
        }


        if (maxRunningMultiplier < 1.0) maxRunningMultiplier = 1.0;
        if (maxRunningMultiplier > 10.0) maxRunningMultiplier = 10.0;

        if (playerMovementThreshold < 0.01) playerMovementThreshold = 0.01;
        if (playerMovementThreshold > 1.0) playerMovementThreshold = 1.0;
    }

    public void saveConfig() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

        try {
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            if (enableDebugLogging) {
                System.out.println("[EasyPets] Saved configuration to " + CONFIG_FILE_NAME);
            }
        } catch (IOException e) {
            System.err.println("[EasyPets] Failed to save config: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        INSTANCE = loadConfig();
    }

    // Essential getters only
    public boolean isChunkLoadingEnabled() { return enableChunkLoading; }
    public double getTeleportDistance() { return teleportDistance; }
    public boolean shouldSaveOnLocate() { return saveOnLocate; }
    public boolean shouldSaveOnRecovery() { return saveOnRecovery; }
    public boolean isDebugLoggingEnabled() { return enableDebugLogging; }
    public int getMaxChunkDistance() { return maxChunkDistance; }
    public int getNavigationScanningRange() { return navigationScanningRange; }

    // Dynamic Pet Running getters
    public boolean isDynamicRunningEnabled() { return enableDynamicRunning; }
    public double getRunningTargetDistance() { return runningTargetDistance; }
    public double getRunningMaxDistance() {
        // Always return the actual teleport distance if set to -1
        return runningMaxDistance <= 0 ? teleportDistance : runningMaxDistance;
    }
    public double getMaxRunningMultiplier() { return maxRunningMultiplier; }
    public double getPlayerMovementThreshold() { return playerMovementThreshold; }

    // Keep this for the teleport distance calculation
    public double getTeleportDistanceSquared() {
        double blocksDistance = teleportDistance * 16.0;
        return blocksDistance * blocksDistance;
    }

    public void printCurrentConfig() {
        System.out.println("[EasyPets] Current Configuration:");
        System.out.println("  Chunk Loading Enabled: " + enableChunkLoading);
        System.out.println("  Pet Teleport Distance: " + teleportDistance + " blocks");
        System.out.println("  Save on /petlocate: " + saveOnLocate);
        System.out.println("  Save on Recovery: " + saveOnRecovery);
        System.out.println("  Debug Logging: " + enableDebugLogging);
        System.out.println("  Max Chunk Distance: " + maxChunkDistance);
        System.out.println("  Navigation Scanning Range: " + navigationScanningRange + " blocks");
        System.out.println("  ");
        System.out.println("  Dynamic Pet Running: " + enableDynamicRunning);
        if (enableDynamicRunning) {
            System.out.println("    Running Target Distance: " + runningTargetDistance + " blocks");
            System.out.println("    Max Running Multiplier: " + maxRunningMultiplier + "x");
            System.out.println("    Player Movement Threshold: " + playerMovementThreshold + " blocks/tick");
        }
        System.out.println("  System: Player-based (like ender pearls)");
    }
}