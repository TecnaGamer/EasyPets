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
    public double teleportDistance = 48; // Distance in blocks before pet tries to teleport (changed from 6.0 to match vanilla)
    public int maxChunkDistance = 2; // Chunk loading radius
    public int navigationScanningRange = 64; // Maximum navigation range in blocks
    public boolean enableDebugLogging = false;

    // Auto-recovery feature
    public boolean autoRecoverOnFirstJoin = true; // Automatically run pet recovery when joining world for first time

    // Dynamic Pet Running Settings
    public boolean enableDynamicRunning = true;
    public double runningTargetDistance = 6.0; // Distance where pets start running faster
    public double maxRunningMultiplier = 1.6; // Maximum running speed boost
    public double playerMovementThreshold = 0.1; // Minimum player movement to trigger speed changes

    // Natural Regeneration Settings
    public boolean enableNaturalRegen = true;
    public int regenDelayTicks = 300; // 15 seconds (300 ticks) after damage before regen starts
    public float regenAmountPerSecond = 0.05f; // 0.05 health points per second
    public float regenMaxHealthPercent = 1.0f; // Regenerate to 100% health

    // Save options
    public boolean saveOnLocate = true;
    public boolean saveOnRecovery = true;
    
    // Language setting
    public String language = "en_us"; // Default to English

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
        // Core validation - now actually in blocks
        if (teleportDistance < 1.0) teleportDistance = 1.0;
        if (teleportDistance > 200.0) teleportDistance = 200.0; // Increased max since we're using blocks now

        if (maxChunkDistance < 1) maxChunkDistance = 1;
        if (maxChunkDistance > 10) maxChunkDistance = 10;

        if (navigationScanningRange < 32) navigationScanningRange = 32;
        if (navigationScanningRange > 1000) navigationScanningRange = 1000;

        // Dynamic Pet Running validation
        if (runningTargetDistance < 1.0) runningTargetDistance = 1.0;
        if (runningTargetDistance > 50.0) runningTargetDistance = 50.0;

        if (maxRunningMultiplier < 1.0) maxRunningMultiplier = 1.0;
        if (maxRunningMultiplier > 10.0) maxRunningMultiplier = 10.0;

        if (playerMovementThreshold < 0.01) playerMovementThreshold = 0.01;
        if (playerMovementThreshold > 1.0) playerMovementThreshold = 1.0;

        // Natural Regeneration validation
        if (regenDelayTicks < 20) regenDelayTicks = 20; // Minimum 1 second
        if (regenDelayTicks > 6000) regenDelayTicks = 6000; // Maximum 5 minutes

        if (regenAmountPerSecond < 0.01f) regenAmountPerSecond = 0.01f;
        if (regenAmountPerSecond > 5.0f) regenAmountPerSecond = 5.0f;

        if (regenMaxHealthPercent < 0.1f) regenMaxHealthPercent = 0.1f;
        if (regenMaxHealthPercent > 1.0f) regenMaxHealthPercent = 1.0f;
        
        // Language validation - ensure lowercase with underscore
        if (language == null || language.isEmpty()) {
            language = "en_us";
        } else {
            language = language.toLowerCase().replace("-", "_");
        }
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

    // Method to get default values for ConfigCommand
    public static String getDefaultValueFor(String settingName) {
        Config defaultConfig = new Config(); // This works since we're inside the Config class
        return switch (settingName) {
            case "enableChunkLoading" -> String.valueOf(defaultConfig.enableChunkLoading);
            case "teleportDistance" -> String.valueOf(defaultConfig.teleportDistance);
            case "maxChunkDistance" -> String.valueOf(defaultConfig.maxChunkDistance);
            case "navigationScanningRange" -> String.valueOf(defaultConfig.navigationScanningRange);
            case "autoRecoverOnFirstJoin" -> String.valueOf(defaultConfig.autoRecoverOnFirstJoin);
            case "enableDynamicRunning" -> String.valueOf(defaultConfig.enableDynamicRunning);
            case "runningTargetDistance" -> String.valueOf(defaultConfig.runningTargetDistance);
            case "maxRunningMultiplier" -> String.valueOf(defaultConfig.maxRunningMultiplier);
            case "playerMovementThreshold" -> String.valueOf(defaultConfig.playerMovementThreshold);
            case "enableNaturalRegen" -> String.valueOf(defaultConfig.enableNaturalRegen);
            case "regenDelayTicks" -> String.valueOf(defaultConfig.regenDelayTicks);
            case "regenAmountPerSecond" -> String.valueOf(defaultConfig.regenAmountPerSecond);
            case "regenMaxHealthPercent" -> String.valueOf(defaultConfig.regenMaxHealthPercent);
            case "saveOnLocate" -> String.valueOf(defaultConfig.saveOnLocate);
            case "saveOnRecovery" -> String.valueOf(defaultConfig.saveOnRecovery);
            case "enableDebugLogging" -> String.valueOf(defaultConfig.enableDebugLogging);
            case "language" -> defaultConfig.language;
            default -> "unknown";
        };
    }

    // Method to reset all settings to defaults
    public void resetToDefaults() {
        Config defaultConfig = new Config(); // This works since we're inside the Config class

        // Copy all default values to current config
        this.enableChunkLoading = defaultConfig.enableChunkLoading;
        this.teleportDistance = defaultConfig.teleportDistance;
        this.maxChunkDistance = defaultConfig.maxChunkDistance;
        this.navigationScanningRange = defaultConfig.navigationScanningRange;
        this.autoRecoverOnFirstJoin = defaultConfig.autoRecoverOnFirstJoin;
        this.enableDynamicRunning = defaultConfig.enableDynamicRunning;
        this.runningTargetDistance = defaultConfig.runningTargetDistance;
        this.maxRunningMultiplier = defaultConfig.maxRunningMultiplier;
        this.playerMovementThreshold = defaultConfig.playerMovementThreshold;
        this.enableNaturalRegen = defaultConfig.enableNaturalRegen;
        this.regenDelayTicks = defaultConfig.regenDelayTicks;
        this.regenAmountPerSecond = defaultConfig.regenAmountPerSecond;
        this.regenMaxHealthPercent = defaultConfig.regenMaxHealthPercent;
        this.saveOnLocate = defaultConfig.saveOnLocate;
        this.saveOnRecovery = defaultConfig.saveOnRecovery;
        this.enableDebugLogging = defaultConfig.enableDebugLogging;
        this.language = defaultConfig.language;
    }

    // Essential getters only
    public boolean isChunkLoadingEnabled() { return enableChunkLoading; }
    public double getTeleportDistance() { return teleportDistance; }
    public boolean shouldSaveOnLocate() { return saveOnLocate; }
    public boolean shouldSaveOnRecovery() { return saveOnRecovery; }
    public boolean shouldAutoRecoverOnFirstJoin() { return autoRecoverOnFirstJoin; }
    public boolean isDebugLoggingEnabled() { return enableDebugLogging; }
    public int getMaxChunkDistance() { return maxChunkDistance; }
    public int getNavigationScanningRange() { return navigationScanningRange; }

    // Dynamic Pet Running getters
    public boolean isDynamicRunningEnabled() { return enableDynamicRunning; }
    public double getRunningTargetDistance() { return runningTargetDistance; }
    public double getMaxRunningMultiplier() { return maxRunningMultiplier; }
    public double getPlayerMovementThreshold() { return playerMovementThreshold; }

    // Natural Regeneration getters
    public boolean isNaturalRegenEnabled() { return enableNaturalRegen; }
    public int getRegenDelayTicks() { return regenDelayTicks; }
    public float getRegenAmountPerSecond() { return regenAmountPerSecond; }
    public float getRegenMaxHealthPercent() { return regenMaxHealthPercent; }
    
    // Language getter
    public String getLanguage() { return language; }

    // FIXED: Now actually returns squared distance in blocks, not chunks
    public double getTeleportDistanceSquared() {
        return teleportDistance * teleportDistance; // Removed the * 16.0 conversion
    }

    public void printCurrentConfig() {
        System.out.println("[EasyPets] Current Configuration:");
        System.out.println("  Chunk Loading Enabled: " + enableChunkLoading);
        System.out.println("  Pet Teleport Distance: " + teleportDistance + " blocks"); // Now actually blocks!
        System.out.println("  Auto-Recover on First Join: " + autoRecoverOnFirstJoin);
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
        System.out.println("  ");
        System.out.println("  Natural Regeneration: " + enableNaturalRegen);
        if (enableNaturalRegen) {
            System.out.println("    Regen Delay: " + (regenDelayTicks / 20.0) + " seconds");
            System.out.println("    Regen Rate: " + regenAmountPerSecond + " health/second");
            System.out.println("    Max Health %: " + (regenMaxHealthPercent * 100) + "%");
        }
        System.out.println("  System: Player-based (like ender pearls)");
    }
}