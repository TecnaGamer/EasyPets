package org.tecna.followersloadchunks.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ConfigCommand {

    // Complete setting information for all config options including dynamic pet speed
    private static final Map<String, SettingInfo> SETTING_INFO = new HashMap<>();

    static {
        // Core settings
        SETTING_INFO.put("chunkloading", new SettingInfo(
                "chunkloading", "boolean", "true",
                "Enable/disable the entire chunk loading system",
                "Set to false to use only /petlocator without chunk loading",
                "Server owners who want pets to locate but not load chunks"
        ));

        SETTING_INFO.put("teleportdistance", new SettingInfo(
                "teleportdistance", "1.0-100.0", "12.0",
                "Distance in blocks before pets try to teleport to owner",
                "Vanilla default is 12 blocks. Lower = pets stay closer",
                "Adjust based on your server's playstyle preferences"
        ));

        SETTING_INFO.put("maxchunkdistance", new SettingInfo(
                "maxchunkdistance", "1-10", "2",
                "Radius of chunks to keep loaded around each pet",
                "Higher values increase server load but provide more stability",
                "Only increase if pets are having chunk loading issues"
        ));

        SETTING_INFO.put("maxnavigationrange", new SettingInfo(
                "maxnavigationrange", "32-1000", "320",
                "Maximum navigation range in blocks for pet pathfinding",
                "Higher values let pets search further before teleporting",
                "Increase if pets teleport too often, decrease for performance"
        ));

        // Dynamic Pet Speed settings
        SETTING_INFO.put("dynamicpetspeed", new SettingInfo(
                "dynamicpetspeed", "boolean", "true",
                "Enable/disable dynamic pet speed adjustment system",
                "Pets will speed up when you're moving fast and they're far away",
                "Disable if you prefer vanilla pet following behavior"
        ));

        SETTING_INFO.put("petspeedmindistance", new SettingInfo(
                "petspeedmindistance", "1.0-50.0", "6.0",
                "Distance in blocks where pets start speeding up",
                "Pets closer than this distance use normal speed",
                "Lower values = pets speed up more often, higher = only when far"
        ));

        SETTING_INFO.put("petspeedmaxdistance", new SettingInfo(
                "petspeedmaxdistance", "-1 or 1.0-100.0", "-1",
                "Maximum distance for speed boosts (-1 = use teleport distance)",
                "-1 automatically uses your teleport distance, or set a custom value",
                "Should be larger than min distance, -1 recommended for consistency"
        ));

        SETTING_INFO.put("minpetspeedmultiplier", new SettingInfo(
                "minpetspeedmultiplier", "0.1-2.0", "0.8",
                "Speed multiplier when player is stationary",
                "0.8 = 20% slower when you're not moving, 1.0 = normal speed",
                "Lower values make stationary pets move slower and feel calmer"
        ));

        SETTING_INFO.put("maxpetspeedmultiplier", new SettingInfo(
                "maxpetspeedmultiplier", "1.0-10.0", "2.5",
                "Maximum speed multiplier when catching up",
                "2.5 = up to 150% faster when far behind and you're sprinting",
                "Higher values help pets keep up but may look unnatural"
        ));

        SETTING_INFO.put("playerspeedthreshold", new SettingInfo(
                "playerspeedthreshold", "0.01-1.0", "0.1",
                "Minimum player movement to trigger pet speed changes",
                "Very low value - prevents speed changes from tiny movements",
                "Technical setting, rarely needs adjustment"
        ));

        // Save options
        SETTING_INFO.put("saveonlocator", new SettingInfo(
                "saveonlocator", "boolean", "false",
                "Trigger world save when /petlocator is used",
                "Ensures most accurate pet locations but makes command slower",
                "Enable if pet locations are frequently inaccurate"
        ));

        SETTING_INFO.put("saveonrecovery", new SettingInfo(
                "saveonrecovery", "boolean", "false",
                "Trigger world save before /petrecovery runs",
                "Improves recovery success rate but makes command slower",
                "Enable if pet recovery often fails to find pets"
        ));

        // Debug option
        SETTING_INFO.put("debuglogging", new SettingInfo(
                "debuglogging", "boolean", "false",
                "Enable detailed console logging for troubleshooting",
                "Only enable when investigating issues - creates log spam",
                "Disable after troubleshooting to reduce server logs"
        ));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("followersconfig")
                    .requires(source -> source.hasPermissionLevel(4)) // Require admin level
                    .executes(ConfigCommand::executeShowAll) // Default: show all settings
                    .then(literal("reload")
                            .executes(ConfigCommand::executeReload))
                    .then(argument("setting", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                // Auto-complete ALL setting names
                                SETTING_INFO.keySet().forEach(builder::suggest);
                                return builder.buildFuture();
                            })
                            .executes(ConfigCommand::executeShowSetting) // Show specific setting
                            .then(literal("reset")
                                    .executes(ConfigCommand::executeResetSetting)) // Reset specific setting
                            .then(argument("value", StringArgumentType.greedyString())
                                    .suggests((context, builder) -> {
                                        // Context-aware value suggestions
                                        try {
                                            String settingName = StringArgumentType.getString(context, "setting").toLowerCase();
                                            SettingInfo info = SETTING_INFO.get(settingName);
                                            if (info != null) {
                                                if (info.validRange.equals("boolean")) {
                                                    builder.suggest("true");
                                                    builder.suggest("false");
                                                } else if (settingName.equals("teleportdistance")) {
                                                    builder.suggest("12.0"); // Default (vanilla)
                                                    builder.suggest("8.0");  // Closer
                                                    builder.suggest("16.0"); // Further
                                                    builder.suggest("24.0"); // Much further
                                                } else if (settingName.equals("maxchunkdistance")) {
                                                    builder.suggest("1");
                                                    builder.suggest("2"); // Default
                                                    builder.suggest("3");
                                                    builder.suggest("4");
                                                } else if (settingName.equals("maxnavigationrange")) {
                                                    builder.suggest("160");  // Lower
                                                    builder.suggest("320");  // Default
                                                    builder.suggest("480");  // Higher
                                                    builder.suggest("640");  // Very high
                                                } else if (settingName.equals("petspeedmindistance")) {
                                                    builder.suggest("4.0");  // Closer
                                                    builder.suggest("6.0");  // Default
                                                    builder.suggest("8.0");  // Further
                                                } else if (settingName.equals("petspeedmaxdistance")) {
                                                    builder.suggest("-1");   // Use teleport distance
                                                    builder.suggest("12.0"); // Custom
                                                    builder.suggest("16.0"); // Custom further
                                                } else if (settingName.equals("minpetspeedmultiplier")) {
                                                    builder.suggest("0.6");  // Slower
                                                    builder.suggest("0.8");  // Default
                                                    builder.suggest("1.0");  // Normal
                                                } else if (settingName.equals("maxpetspeedmultiplier")) {
                                                    builder.suggest("1.5");  // Conservative
                                                    builder.suggest("2.5");  // Default
                                                    builder.suggest("3.0");  // Fast
                                                }
                                            }
                                        } catch (Exception e) {
                                            // If we can't get the setting name, don't suggest anything
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ConfigCommand::executeSetSetting)))); // Set setting value
        });
    }

    private static int executeShowAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Config config = Config.getInstance();

        source.sendMessage(Text.of("§e=== FollowersLoadChunks Configuration ==="));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§7Use §f/followersconfig <setting>§7 to see details about a specific setting"));
        source.sendMessage(Text.of("§7Use §f/followersconfig <setting> <value>§7 to change a setting"));
        source.sendMessage(Text.of("§7Use §f/followersconfig <setting> reset§7 to reset a setting"));
        source.sendMessage(Text.of(""));

        // Core Features
        source.sendMessage(Text.of("§6Core Features:"));
        source.sendMessage(Text.of("§f  chunkloading: §" + (config.isChunkLoadingEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  teleportdistance: §b" + config.getPetTeleportDistance() + " blocks"));
        source.sendMessage(Text.of("§f  maxchunkdistance: §b" + config.getMaxChunkLoadingDistance() + " chunks"));
        source.sendMessage(Text.of("§f  maxnavigationrange: §b" + config.getMaxNavigationRange() + " blocks"));
        source.sendMessage(Text.of(""));

        // Dynamic Pet Speed
        source.sendMessage(Text.of("§6Dynamic Pet Speed:"));
        source.sendMessage(Text.of("§f  dynamicpetspeed: §" + (config.isDynamicPetSpeedEnabled() ? "aEnabled" : "cDisabled")));
        if (config.isDynamicPetSpeedEnabled()) {
            source.sendMessage(Text.of("§f  petspeedmindistance: §b" + config.getPetSpeedMinDistance() + " blocks"));
            source.sendMessage(Text.of("§f  petspeedmaxdistance: §b" + config.getPetSpeedMaxDistance() + " blocks" +
                    (config.petSpeedMaxDistance <= 0 ? " §7(using teleport distance)" : "")));
            source.sendMessage(Text.of("§f  minpetspeedmultiplier: §b" + config.getMinPetSpeedMultiplier() + "x"));
            source.sendMessage(Text.of("§f  maxpetspeedmultiplier: §b" + config.getMaxPetSpeedMultiplier() + "x"));
            source.sendMessage(Text.of("§f  playerspeedthreshold: §b" + config.getPlayerSpeedThreshold()));
        }
        source.sendMessage(Text.of(""));

        // Save Options
        source.sendMessage(Text.of("§6Save Options:"));
        source.sendMessage(Text.of("§f  saveonlocator: §" + (config.shouldTriggerSaveOnPetLocator() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  saveonrecovery: §" + (config.shouldTriggerSaveOnRecovery() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of(""));

        // Debug
        source.sendMessage(Text.of("§6Debug:"));
        source.sendMessage(Text.of("§f  debuglogging: §" + (config.isDebugLoggingEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of(""));

        // System info
        source.sendMessage(Text.of("§6System Info:"));
        source.sendMessage(Text.of("§7  Chunk System: §fPlayer-based (like ender pearls)"));
        source.sendMessage(Text.of("§7  Ticket Expiry: §f5 seconds auto-renewal"));
        source.sendMessage(Text.of("§7  Performance: §fOptimized for reliability"));

        return 1;
    }

    private static int executeShowSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting").toLowerCase();

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(Text.of("§cUnknown setting: " + settingName));
            source.sendMessage(Text.of("§7Available settings: " + String.join(", ", SETTING_INFO.keySet())));
            return 0;
        }

        Config config = Config.getInstance();
        String currentValue = getCurrentValue(config, settingName);

        source.sendMessage(Text.of("§e=== " + info.name + " ==="));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§6Current Value: §f" + currentValue));
        source.sendMessage(Text.of("§6Default Value: §7" + info.defaultValue));
        source.sendMessage(Text.of("§6Valid Range: §7" + info.validRange));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§6Description:"));
        source.sendMessage(Text.of("§f  " + info.description));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§6Details:"));
        source.sendMessage(Text.of("§f  " + info.details));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§6When to Use:"));
        source.sendMessage(Text.of("§f  " + info.whenToUse));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§7Commands:"));
        source.sendMessage(Text.of("§7  /followersconfig " + settingName + " <value> §7- Change setting"));
        source.sendMessage(Text.of("§7  /followersconfig " + settingName + " reset §7- Reset to default"));

        return 1;
    }

    private static int executeSetSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting").toLowerCase();
        String value = StringArgumentType.getString(context, "value");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(Text.of("§cUnknown setting: " + settingName));
            return 0;
        }

        Config config = Config.getInstance();

        try {
            boolean success = setSetting(config, settingName, value);
            if (success) {
                config.saveConfig();
                source.sendMessage(Text.of("§a[FollowersLoadChunks] " + info.name + " set to: " + value));

                // Show any relevant warnings or notes
                showSettingWarnings(source, settingName, value);
            } else {
                source.sendError(Text.of("§cInvalid value '" + value + "' for " + settingName));
                source.sendMessage(Text.of("§7Valid range: " + info.validRange));
            }
        } catch (Exception e) {
            source.sendError(Text.of("§cError setting " + settingName + ": " + e.getMessage()));
            source.sendMessage(Text.of("§7Valid range: " + info.validRange));
        }

        return 1;
    }

    private static int executeResetSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting").toLowerCase();

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(Text.of("§cUnknown setting: " + settingName));
            return 0;
        }

        Config config = Config.getInstance();

        boolean success = setSetting(config, settingName, info.defaultValue);
        if (success) {
            config.saveConfig();
            source.sendMessage(Text.of("§a[FollowersLoadChunks] " + info.name + " reset to default: " + info.defaultValue));
        } else {
            source.sendError(Text.of("§cFailed to reset " + settingName));
        }

        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        Config.getInstance().reloadConfig();
        source.sendMessage(Text.of("§a[FollowersLoadChunks] Configuration reloaded successfully"));

        return 1;
    }

    private static String getCurrentValue(Config config, String settingName) {
        return switch (settingName) {
            case "chunkloading" -> String.valueOf(config.isChunkLoadingEnabled());
            case "teleportdistance" -> String.valueOf(config.getPetTeleportDistance());
            case "maxchunkdistance" -> String.valueOf(config.getMaxChunkLoadingDistance());
            case "maxnavigationrange" -> String.valueOf(config.getMaxNavigationRange());
            case "dynamicpetspeed" -> String.valueOf(config.isDynamicPetSpeedEnabled());
            case "petspeedmindistance" -> String.valueOf(config.getPetSpeedMinDistance());
            case "petspeedmaxdistance" -> String.valueOf(config.petSpeedMaxDistance);
            case "minpetspeedmultiplier" -> String.valueOf(config.getMinPetSpeedMultiplier());
            case "maxpetspeedmultiplier" -> String.valueOf(config.getMaxPetSpeedMultiplier());
            case "playerspeedthreshold" -> String.valueOf(config.getPlayerSpeedThreshold());
            case "saveonlocator" -> String.valueOf(config.shouldTriggerSaveOnPetLocator());
            case "saveonrecovery" -> String.valueOf(config.shouldTriggerSaveOnRecovery());
            case "debuglogging" -> String.valueOf(config.isDebugLoggingEnabled());
            default -> "unknown";
        };
    }

    private static boolean setSetting(Config config, String settingName, String value) {
        try {
            switch (settingName) {
                case "chunkloading" -> {
                    config.enableChunkLoading = Boolean.parseBoolean(value);
                    return true;
                }
                case "teleportdistance" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 100.0) {
                        config.petTeleportDistance = d;
                        return true;
                    }
                }
                case "maxchunkdistance" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 1 && i <= 10) {
                        config.maxChunkLoadingDistance = i;
                        return true;
                    }
                }
                case "maxnavigationrange" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 32 && i <= 1000) {
                        config.maxNavigationRange = i;
                        return true;
                    }
                }
                case "dynamicpetspeed" -> {
                    config.enableDynamicPetSpeed = Boolean.parseBoolean(value);
                    return true;
                }
                case "petspeedmindistance" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 50.0) {
                        config.petSpeedMinDistance = d;
                        return true;
                    }
                }
                case "minpetspeedmultiplier" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 0.1 && d <= 2.0) {
                        config.minPetSpeedMultiplier = d;
                        return true;
                    }
                }
                case "maxpetspeedmultiplier" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 10.0) {
                        config.maxPetSpeedMultiplier = d;
                        return true;
                    }
                }
                case "playerspeedthreshold" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 0.01 && d <= 1.0) {
                        config.playerSpeedThreshold = d;
                        return true;
                    }
                }
                case "saveonlocator" -> {
                    config.triggerSaveOnPetLocator = Boolean.parseBoolean(value);
                    return true;
                }
                case "saveonrecovery" -> {
                    config.triggerSaveOnRecovery = Boolean.parseBoolean(value);
                    return true;
                }
                case "debuglogging" -> {
                    config.enableDebugLogging = Boolean.parseBoolean(value);
                    return true;
                }
            }
        } catch (NumberFormatException e) {
            // Invalid number format
        }
        return false;
    }

    private static void showSettingWarnings(ServerCommandSource source, String settingName, String value) {
        switch (settingName) {
            case "chunkloading" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Existing chunk tickets will auto-expire in 5 seconds"));
                }
            }
            case "dynamicpetspeed" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Pet speeds will return to vanilla behavior"));
                }
            }
            case "debuglogging" -> {
                if (Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Warning: Debug logging will create additional console output"));
                }
            }
            case "maxchunkdistance" -> {
                int distance = Integer.parseInt(value);
                if (distance > 4) {
                    source.sendMessage(Text.of("§7Warning: High chunk distances may impact server performance"));
                }
            }
            case "maxnavigationrange" -> {
                int range = Integer.parseInt(value);
                if (range > 500) {
                    source.sendMessage(Text.of("§7Warning: Very high navigation ranges may cause server lag"));
                } else if (range < 100) {
                    source.sendMessage(Text.of("§7Note: Very low navigation ranges may cause pets to teleport frequently"));
                }
            }
            case "teleportdistance" -> {
                double distance = Double.parseDouble(value);
                if (distance < 8.0) {
                    source.sendMessage(Text.of("§7Note: Very low teleport distances may cause pets to teleport frequently"));
                } else if (distance > 32.0) {
                    source.sendMessage(Text.of("§7Note: Very high teleport distances may cause pets to get lost more easily"));
                }
            }
            case "maxpetspeedmultiplier" -> {
                double multiplier = Double.parseDouble(value);
                if (multiplier > 4.0) {
                    source.sendMessage(Text.of("§7Warning: Very high speed multipliers may look unnatural or cause pathfinding issues"));
                }
            }
            case "petspeedmindistance" -> {
                double minDist = Double.parseDouble(value);
                if (minDist < 3.0) {
                    source.sendMessage(Text.of("§7Note: Very low min distances may cause frequent speed changes"));
                }
            }
        }
    }

    // Helper class for storing setting information
    private static class SettingInfo {
        final String name;
        final String validRange;
        final String defaultValue;
        final String description;
        final String details;
        final String whenToUse;

        SettingInfo(String name, String validRange, String defaultValue, String description, String details, String whenToUse) {
            this.name = name;
            this.validRange = validRange;
            this.defaultValue = defaultValue;
            this.description = description;
            this.details = details;
            this.whenToUse = whenToUse;
        }
    }
}