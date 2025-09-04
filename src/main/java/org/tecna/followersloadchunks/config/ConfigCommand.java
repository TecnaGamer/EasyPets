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

    // Setting information for help display
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
                "teleportdistance", "0.1-10.0", "0.75",
                "Distance in chunks before pets try to teleport to owner",
                "Vanilla default is 0.75 chunks (12 blocks). Lower = pets stay closer",
                "Adjust based on your server's playstyle preferences"
        ));

        SETTING_INFO.put("maxchunkdistance", new SettingInfo(
                "maxchunkdistance", "1-10", "2",
                "Radius of chunks to keep loaded around each pet",
                "Higher values increase server load but provide more stability",
                "Only increase if pets are having chunk loading issues"
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

        // Performance settings
        SETTING_INFO.put("debuglogging", new SettingInfo(
                "debuglogging", "boolean", "false",
                "Enable detailed console logging for troubleshooting",
                "Only enable when investigating issues - creates log spam",
                "Disable after troubleshooting to reduce server logs"
        ));

        SETTING_INFO.put("chunkdelay", new SettingInfo(
                "chunkdelay", "100-12000 ticks", "1200",
                "Delay before unloading chunks (20 ticks = 1 second)",
                "Higher values reduce chunk flickering but use more memory",
                "Increase if you see chunks loading/unloading rapidly"
        ));

        SETTING_INFO.put("validation", new SettingInfo(
                "validation", "boolean", "true",
                "Enable periodic validation of pet chunk loading",
                "Helps detect and fix issues automatically",
                "Disable only if causing performance issues"
        ));

        SETTING_INFO.put("validationinterval", new SettingInfo(
                "validationinterval", "1200-72000 ticks", "6000",
                "How often to run validation (20 ticks = 1 second)",
                "Lower values = more frequent checks, higher server load",
                "Decrease if pets frequently lose chunk loading"
        ));

        // Fast movement settings
        SETTING_INFO.put("fastmovement", new SettingInfo(
                "fastmovement", "boolean", "true",
                "Enable enhanced chunk loading for fast-moving pets",
                "Helps with teleporting/elytra flying pets",
                "Disable if causing chunk loading performance issues"
        ));

        SETTING_INFO.put("fastmovementthreshold", new SettingInfo(
                "fastmovementthreshold", "1.0-50.0", "5.0",
                "Distance in chunks that triggers fast movement detection",
                "Lower values make the system more sensitive to movement",
                "Adjust based on how fast your pets typically move"
        ));

        SETTING_INFO.put("fastmovementoverlap", new SettingInfo(
                "fastmovementoverlap", "20-600 ticks", "100",
                "Overlap time for fast movement chunk loading",
                "Prevents chunk gaps during rapid movement",
                "Increase if fast-moving pets experience chunk gaps"
        ));

        // Advanced Pathfinding Settings
        SETTING_INFO.put("advancedpathfinding", new SettingInfo(
                "advancedpathfinding", "boolean", "true",
                "Enable advanced intelligent pathfinding for pets",
                "Makes pets smarter about dangerous terrain and navigation",
                "Disable if you prefer vanilla pathfinding behavior"
        ));

        SETTING_INFO.put("navigationmultiplier", new SettingInfo(
                "navigationmultiplier", "1.0-10.0", "2.5",
                "Multiplier for pet navigation range based on teleport distance",
                "Higher values let pets search further before teleporting",
                "Increase if pets teleport too often, decrease for performance"
        ));

        SETTING_INFO.put("maxnavigationrange", new SettingInfo(
                "maxnavigationrange", "32-1000", "320",
                "Maximum navigation range in blocks",
                "Prevents excessive pathfinding that could cause lag",
                "Only increase if pets need to navigate very large structures"
        ));

        SETTING_INFO.put("minnavigationrange", new SettingInfo(
                "minnavigationrange", "16-500", "32",
                "Minimum navigation range in blocks",
                "Ensures pets can do basic pathfinding even with low teleport distance",
                "Rarely needs adjustment"
        ));

        SETTING_INFO.put("biomeaware", new SettingInfo(
                "biomeaware", "boolean", "true",
                "Enable biome-aware pathfinding (e.g., swimming in ocean biomes)",
                "Pets adapt their pathfinding to current biome conditions",
                "Disable if pets behave unexpectedly in certain biomes"
        ));

        SETTING_INFO.put("owneractivity", new SettingInfo(
                "owneractivity", "boolean", "true",
                "Detect owner activity to adjust pet pathfinding patience",
                "Pets are more patient when owner is building/AFK",
                "Disable if pets don't follow properly during activities"
        ));

        SETTING_INFO.put("terrainanalysis", new SettingInfo(
                "terrainanalysis", "boolean", "true",
                "Analyze terrain between pet and owner for better pathfinding",
                "Helps pets make smarter decisions about routes",
                "Disable if causing performance issues on large servers"
        ));

        SETTING_INFO.put("pathfindingtimeout", new SettingInfo(
                "pathfindingtimeout", "200-6000 ticks", "1200",
                "How long pets try pathfinding before encouraging teleport",
                "Lower values make pets teleport sooner when stuck",
                "Increase if pets give up on pathfinding too quickly"
        ));

        // Safety Settings
        SETTING_INFO.put("pathfindingsafety", new SettingInfo(
                "pathfindingsafety", "boolean", "true",
                "Enable pathfinding safety features",
                "Prevents pets from following owners into extreme danger",
                "Only disable if you want pets to follow into any danger"
        ));

        SETTING_INFO.put("alwaysavoidlava", new SettingInfo(
                "alwaysavoidlava", "boolean", "true",
                "Pets never pathfind through lava even if owner is in it",
                "Critical safety feature to prevent pet deaths",
                "Only disable if you specifically want pets to follow into lava"
        ));

        SETTING_INFO.put("alwaysavoidfire", new SettingInfo(
                "alwaysavoidfire", "boolean", "true",
                "Pets avoid fire damage even if owner is in fire",
                "Prevents pets from taking unnecessary fire damage",
                "Disable if you want pets to follow through fire"
        ));

        SETTING_INFO.put("prioritizebreathing", new SettingInfo(
                "prioritizebreathing", "boolean", "true",
                "Pets prioritize getting air when drowning",
                "Helps prevent pets from drowning while following",
                "Disable if pets avoid water too aggressively"
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
                                // Auto-complete setting names
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
                                                    builder.suggest("0.75"); // Default (vanilla)
                                                    builder.suggest("0.5");  // Closer
                                                    builder.suggest("1.0");  // Further
                                                    builder.suggest("2.0");  // Much further
                                                } else if (settingName.equals("maxchunkdistance")) {
                                                    builder.suggest("1");
                                                    builder.suggest("2"); // Default
                                                    builder.suggest("3");
                                                    builder.suggest("4");
                                                } else if (settingName.equals("navigationmultiplier")) {
                                                    builder.suggest("1.5");  // Conservative
                                                    builder.suggest("2.0");  // Balanced
                                                    builder.suggest("2.5");  // Default
                                                    builder.suggest("3.0");  // Aggressive
                                                } else if (settingName.equals("maxnavigationrange")) {
                                                    builder.suggest("160");  // Lower
                                                    builder.suggest("320");  // Default
                                                    builder.suggest("480");  // Higher
                                                    builder.suggest("640");  // Very high
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
        source.sendMessage(Text.of("§f  teleportdistance: §b" + config.getPetTeleportDistance() + " chunks"));
        source.sendMessage(Text.of("§f  maxchunkdistance: §b" + config.getMaxChunkLoadingDistance() + " chunks"));
        source.sendMessage(Text.of(""));

        // Save Options
        source.sendMessage(Text.of("§6Save Options:"));
        source.sendMessage(Text.of("§f  saveonlocator: §" + (config.shouldTriggerSaveOnPetLocator() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  saveonrecovery: §" + (config.shouldTriggerSaveOnRecovery() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of(""));

        // Performance
        source.sendMessage(Text.of("§6Performance:"));
        source.sendMessage(Text.of("§f  debuglogging: §" + (config.isDebugLoggingEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  chunkdelay: §b" + config.getChunkUnloadDelayTicks() + " ticks"));
        source.sendMessage(Text.of("§f  validation: §" + (config.isPeriodicValidationEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  validationinterval: §b" + config.getValidationIntervalTicks() + " ticks"));
        source.sendMessage(Text.of(""));

        // Fast Movement
        source.sendMessage(Text.of("§6Fast Movement:"));
        source.sendMessage(Text.of("§f  fastmovement: §" + (config.isFastMovementDetectionEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  fastmovementthreshold: §b" + config.getFastMovementThreshold() + " chunks"));
        source.sendMessage(Text.of("§f  fastmovementoverlap: §b" + config.getFastMovementOverlapTicks() + " ticks"));
        source.sendMessage(Text.of(""));

        // Advanced Pathfinding
        source.sendMessage(Text.of("§6Advanced Pathfinding:"));
        source.sendMessage(Text.of("§f  advancedpathfinding: §" + (config.isAdvancedPathfindingEnabled() ? "aEnabled" : "cDisabled")));
        if (config.isAdvancedPathfindingEnabled()) {
            source.sendMessage(Text.of("§f    navigationmultiplier: §b" + config.getNavigationRangeMultiplier()));
            source.sendMessage(Text.of("§f    maxnavigationrange: §b" + config.getMaxNavigationRange() + " blocks"));
            source.sendMessage(Text.of("§f    minnavigationrange: §b" + config.getMinNavigationRange() + " blocks"));
            source.sendMessage(Text.of("§f    biomeaware: §" + (config.isBiomeAwarePathfindingEnabled() ? "aEnabled" : "cDisabled")));
            source.sendMessage(Text.of("§f    owneractivity: §" + (config.isOwnerActivityDetectionEnabled() ? "aEnabled" : "cDisabled")));
            source.sendMessage(Text.of("§f    terrainanalysis: §" + (config.isTerrainAnalysisEnabled() ? "aEnabled" : "cDisabled")));
            source.sendMessage(Text.of("§f    pathfindingtimeout: §b" + config.getPathfindingTimeoutTicks() + " ticks"));
        }
        source.sendMessage(Text.of(""));

        // Safety Settings
        source.sendMessage(Text.of("§6Safety Settings:"));
        source.sendMessage(Text.of("§f  pathfindingsafety: §" + (config.isPathfindingSafetyEnabled() ? "aEnabled" : "cDisabled")));
        if (config.isPathfindingSafetyEnabled()) {
            source.sendMessage(Text.of("§f    alwaysavoidlava: §" + (config.shouldAlwaysAvoidLava() ? "aEnabled" : "cDisabled")));
            source.sendMessage(Text.of("§f    alwaysavoidfire: §" + (config.shouldAlwaysAvoidFire() ? "aEnabled" : "cDisabled")));
            source.sendMessage(Text.of("§f    prioritizebreathing: §" + (config.shouldPrioritizeAirBreathing() ? "aEnabled" : "cDisabled")));
        }

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
            case "saveonlocator" -> String.valueOf(config.shouldTriggerSaveOnPetLocator());
            case "saveonrecovery" -> String.valueOf(config.shouldTriggerSaveOnRecovery());
            case "debuglogging" -> String.valueOf(config.isDebugLoggingEnabled());
            case "chunkdelay" -> String.valueOf(config.getChunkUnloadDelayTicks());
            case "validation" -> String.valueOf(config.isPeriodicValidationEnabled());
            case "validationinterval" -> String.valueOf(config.getValidationIntervalTicks());
            case "fastmovement" -> String.valueOf(config.isFastMovementDetectionEnabled());
            case "fastmovementthreshold" -> String.valueOf(config.getFastMovementThreshold());
            case "fastmovementoverlap" -> String.valueOf(config.getFastMovementOverlapTicks());
            // Advanced pathfinding settings
            case "advancedpathfinding" -> String.valueOf(config.isAdvancedPathfindingEnabled());
            case "navigationmultiplier" -> String.valueOf(config.getNavigationRangeMultiplier());
            case "maxnavigationrange" -> String.valueOf(config.getMaxNavigationRange());
            case "minnavigationrange" -> String.valueOf(config.getMinNavigationRange());
            case "biomeaware" -> String.valueOf(config.isBiomeAwarePathfindingEnabled());
            case "owneractivity" -> String.valueOf(config.isOwnerActivityDetectionEnabled());
            case "terrainanalysis" -> String.valueOf(config.isTerrainAnalysisEnabled());
            case "pathfindingtimeout" -> String.valueOf(config.getPathfindingTimeoutTicks());
            // Safety settings
            case "pathfindingsafety" -> String.valueOf(config.isPathfindingSafetyEnabled());
            case "alwaysavoidlava" -> String.valueOf(config.shouldAlwaysAvoidLava());
            case "alwaysavoidfire" -> String.valueOf(config.shouldAlwaysAvoidFire());
            case "prioritizebreathing" -> String.valueOf(config.shouldPrioritizeAirBreathing());
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
                    if (d >= 0.1 && d <= 128) {
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
                case "chunkdelay" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 100 && i <= 12000) {
                        config.chunkUnloadDelayTicks = i;
                        return true;
                    }
                }
                case "validation" -> {
                    config.enablePeriodicValidation = Boolean.parseBoolean(value);
                    return true;
                }
                case "validationinterval" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 1200 && i <= 72000) {
                        config.validationIntervalTicks = i;
                        return true;
                    }
                }
                case "fastmovement" -> {
                    config.enableFastMovementDetection = Boolean.parseBoolean(value);
                    return true;
                }
                case "fastmovementthreshold" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 50.0) {
                        config.fastMovementThreshold = d;
                        return true;
                    }
                }
                case "fastmovementoverlap" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 20 && i <= 600) {
                        config.fastMovementOverlapTicks = i;
                        return true;
                    }
                }
                // Advanced pathfinding settings
                case "advancedpathfinding" -> {
                    config.enableAdvancedPathfinding = Boolean.parseBoolean(value);
                    return true;
                }
                case "navigationmultiplier" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 10.0) {
                        config.navigationRangeMultiplier = d;
                        return true;
                    }
                }
                case "maxnavigationrange" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 32 && i <= 3200) {
                        config.maxNavigationRange = i;
                        return true;
                    }
                }
                case "minnavigationrange" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 16 && i <= 500) {
                        config.minNavigationRange = i;
                        return true;
                    }
                }
                case "biomeaware" -> {
                    config.enableBiomeAwarePathfinding = Boolean.parseBoolean(value);
                    return true;
                }
                case "owneractivity" -> {
                    config.enableOwnerActivityDetection = Boolean.parseBoolean(value);
                    return true;
                }
                case "terrainanalysis" -> {
                    config.enableTerrainAnalysis = Boolean.parseBoolean(value);
                    return true;
                }
                case "pathfindingtimeout" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 200 && i <= 6000) {
                        config.pathfindingTimeoutTicks = i;
                        return true;
                    }
                }
                // Safety settings
                case "pathfindingsafety" -> {
                    config.enablePathfindingSafety = Boolean.parseBoolean(value);
                    return true;
                }
                case "alwaysavoidlava" -> {
                    config.alwaysAvoidLava = Boolean.parseBoolean(value);
                    return true;
                }
                case "alwaysavoidfire" -> {
                    config.alwaysAvoidFire = Boolean.parseBoolean(value);
                    return true;
                }
                case "prioritizebreathing" -> {
                    config.prioritizeAirBreathing = Boolean.parseBoolean(value);
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
                    source.sendMessage(Text.of("§7Note: Existing chunk tickets will remain until pets move or server restart"));
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
            case "chunkdelay" -> {
                int delay = Integer.parseInt(value);
                if (delay < 600) {
                    source.sendMessage(Text.of("§7Note: Low delays may cause chunk flickering"));
                } else if (delay > 6000) {
                    source.sendMessage(Text.of("§7Note: High delays will use more server memory"));
                }
            }
            case "advancedpathfinding" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Pets will use basic pathfinding and may get stuck more often"));
                }
            }
            case "maxnavigationrange" -> {
                int range = Integer.parseInt(value);
                if (range > 500) {
                    source.sendMessage(Text.of("§7Warning: Very high navigation ranges may cause server lag"));
                }
            }
            case "pathfindingsafety" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§6Warning: Disabling safety may cause pets to follow owners into lava/fire!"));
                }
            }
            case "alwaysavoidlava" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§c§lWARNING: Pets may now follow owners into lava and die!"));
                }
            }
            case "alwaysavoidfire" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§c§lWARNING: Pets may now follow owners into fire and take damage!"));
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