package org.tecna.easypets.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ConfigCommand {

    // Complete setting information for all config options
    private static final Map<String, SettingInfo> SETTING_INFO = new HashMap<>();

    static {
        // Core settings
        SETTING_INFO.put("enableChunkLoading", new SettingInfo(
                "enableChunkLoading", "boolean",
                "Enable/disable the entire chunk loading system",
                "Set to false to use only /petlocate without chunk loading",
                "Server owners who want pets to locate but not load chunks"
        ));

        SETTING_INFO.put("teleportDistance", new SettingInfo(
                "teleportDistance", "1.0-100.0",
                "Distance in blocks before pets try to teleport to owner",
                "Vanilla default is 12 blocks. Lower = pets stay closer",
                "Adjust based on your server's playstyle preferences"
        ));

        SETTING_INFO.put("maxChunkDistance", new SettingInfo(
                "maxChunkDistance", "1-10",
                "Radius of chunks to keep loaded around each pet",
                "Higher values increase server load but provide more stability",
                "Only increase if pets are having chunk loading issues"
        ));

        SETTING_INFO.put("navigationScanningRange", new SettingInfo(
                "navigationScanningRange", "32-1000",
                "Maximum navigation range in blocks for pet pathfinding",
                "Higher values let pets search further before teleporting",
                "Increase if pets teleport too often, decrease for performance"
        ));

        // Auto-recovery option
        SETTING_INFO.put("autoRecoverOnFirstJoin", new SettingInfo(
                "autoRecoverOnFirstJoin", "boolean",
                "Automatically run pet recovery when joining world for first time",
                "Helps players find their pets when first installing the mod on existing worlds",
                "Enable for user-friendly experience, disable if you prefer manual control"
        ));

        // Dynamic Pet Running settings
        SETTING_INFO.put("enableDynamicRunning", new SettingInfo(
                "enableDynamicRunning", "boolean",
                "Enable/disable dynamic pet running adjustment system",
                "Pets will run faster when you're moving fast and they're far away",
                "Disable if you prefer vanilla pet following behavior"
        ));

        SETTING_INFO.put("runningTargetDistance", new SettingInfo(
                "runningTargetDistance", "1.0-50.0",
                "Distance in blocks where pets start running faster",
                "Pets closer than this distance use normal speed",
                "Lower values = pets run faster more often, higher = only when far"
        ));

        SETTING_INFO.put("maxRunningMultiplier", new SettingInfo(
                "maxRunningMultiplier", "1.0-10.0",
                "Maximum running speed multiplier when catching up",
                "Higher values help pets keep up but may look unnatural",
                "Adjust based on desired pet movement balance"
        ));

        SETTING_INFO.put("playerMovementThreshold", new SettingInfo(
                "playerMovementThreshold", "0.01-1.0",
                "Minimum player movement to trigger pet speed changes",
                "Very low value - prevents speed changes from tiny movements",
                "Technical setting, rarely needs adjustment"
        ));

        // Natural Regeneration settings
        SETTING_INFO.put("enableNaturalRegen", new SettingInfo(
                "enableNaturalRegen", "boolean",
                "Enable/disable natural health regeneration for pets",
                "Pets will slowly regenerate health when not taking damage",
                "Enable for easier pet management, disable for vanilla behavior"
        ));

        SETTING_INFO.put("regenDelayTicks", new SettingInfo(
                "regenDelayTicks", "20-6000",
                "Delay in ticks before regeneration starts after taking damage",
                "Higher values = longer wait after damage for balance",
                "Increase if regen feels too overpowered, decrease for faster healing"
        ));

        SETTING_INFO.put("regenAmountPerSecond", new SettingInfo(
                "regenAmountPerSecond", "0.01-5.0",
                "Amount of health regenerated per second",
                "Default is similar to horse regeneration speed",
                "Adjust based on desired healing speed and game balance"
        ));

        SETTING_INFO.put("regenMaxHealthPercent", new SettingInfo(
                "regenMaxHealthPercent", "0.1-1.0",
                "Maximum health percentage to regenerate to",
                "1.0 = 100% health, 0.8 = 80% health. Prevents full healing if desired",
                "Set to less than 1.0 if you want pets to need some manual healing"
        ));

        // Save options
        SETTING_INFO.put("saveOnLocate", new SettingInfo(
                "saveOnLocate", "boolean",
                "Trigger world save when /petlocate is used",
                "Ensures most accurate pet locations but makes command slower",
                "Enable if pet locations are frequently inaccurate"
        ));

        SETTING_INFO.put("saveOnRecovery", new SettingInfo(
                "saveOnRecovery", "boolean",
                "Trigger world save before /petrecovery runs",
                "Improves recovery success rate but makes command slower",
                "Enable if pet recovery often fails to find pets"
        ));

        // Debug option
        SETTING_INFO.put("enableDebugLogging", new SettingInfo(
                "enableDebugLogging", "boolean",
                "Enable detailed console logging for troubleshooting",
                "Only enable when investigating issues - creates log spam",
                "Disable after troubleshooting to reduce server logs"
        ));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("petconfig")
                    .requires(source -> hasPermission(source, "easypets.admin.config", 3)) // Require moderator level or permission
                    .executes(ConfigCommand::executeShowAll) // Default: show all settings
                    .then(literal("reload")
                            .executes(ConfigCommand::executeReload))
                    .then(literal("reset")
                            .then(literal("all")
                                    .executes(ConfigCommand::executeResetAll))
                            .executes(context -> {
                                // Show usage when just typing /petconfig reset
                                context.getSource().sendMessage(Text.of("§7Usage: §f/petconfig reset all §7or §f/petconfig <setting> reset"));
                                return 1;
                            }))
                    .then(argument("setting", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                // Auto-complete setting names that start with what the user typed
                                String partialInput = builder.getRemaining().toLowerCase();
                                SETTING_INFO.keySet().stream()
                                        .filter(setting -> setting.toLowerCase().startsWith(partialInput))
                                        .forEach(builder::suggest);
                                return builder.buildFuture();
                            })
                            .executes(ConfigCommand::executeShowSetting) // Show specific setting
                            .then(literal("reset")
                                    .executes(ConfigCommand::executeResetSetting)) // Reset specific setting
                            .then(argument("value", StringArgumentType.greedyString())
                                    .suggests((context, builder) -> {
                                        // Context-aware value suggestions
                                        try {
                                            String settingName = StringArgumentType.getString(context, "setting");
                                            SettingInfo info = SETTING_INFO.get(settingName);
                                            if (info != null) {
                                                if (info.validRange.equals("boolean")) {
                                                    builder.suggest("true");
                                                    builder.suggest("false");
                                                } else if (settingName.equals("teleportDistance")) {
                                                    builder.suggest("12.0"); // Default (vanilla)
                                                    builder.suggest("8.0");  // Closer
                                                    builder.suggest("16.0"); // Further
                                                    builder.suggest("24.0"); // Much further
                                                } else if (settingName.equals("maxChunkDistance")) {
                                                    builder.suggest("1");
                                                    builder.suggest("2"); // Default
                                                    builder.suggest("3");
                                                    builder.suggest("4");
                                                } else if (settingName.equals("navigationScanningRange")) {
                                                    builder.suggest("160");  // Lower
                                                    builder.suggest("320");  // Default
                                                    builder.suggest("480");  // Higher
                                                    builder.suggest("640");  // Very high
                                                } else if (settingName.equals("runningTargetDistance")) {
                                                    builder.suggest("4.0");  // Default
                                                    builder.suggest("6.0");  // Further
                                                    builder.suggest("8.0");  // Much further
                                                } else if (settingName.equals("maxRunningMultiplier")) {
                                                    builder.suggest("1.5");  // Default
                                                    builder.suggest("2.0");  // Faster
                                                    builder.suggest("2.5");  // Very fast
                                                } else if (settingName.equals("regenDelayTicks")) {
                                                    builder.suggest("300");   // 15 seconds (default)
                                                    builder.suggest("600");   // 30 seconds
                                                    builder.suggest("1200");  // 1 minute
                                                    builder.suggest("2400");  // 2 minutes
                                                } else if (settingName.equals("regenAmountPerSecond")) {
                                                    builder.suggest("0.05");   // Default
                                                    builder.suggest("0.1");    // Faster
                                                    builder.suggest("0.025");  // Slower
                                                    builder.suggest("0.2");    // Very fast
                                                } else if (settingName.equals("regenMaxHealthPercent")) {
                                                    builder.suggest("1.0");   // 100% (default)
                                                    builder.suggest("0.8");   // 80%
                                                    builder.suggest("0.75");  // 75%
                                                    builder.suggest("0.5");   // 50%
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

    /**
     * Check permissions using LuckPerms if available, otherwise fall back to vanilla permission levels
     */
    private static boolean hasPermission(ServerCommandSource source, String permission, int fallbackLevel) {
        // First check if this is a player command
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            // Console/command blocks always have permission for admin commands
            return true;
        }

        // Try LuckPerms integration first
        Boolean luckPermsResult = hasLuckPermsPermission(player, permission);
        if (luckPermsResult != null) {
            // LuckPerms has an explicit answer (true or false)
            return luckPermsResult;
        }

        // LuckPerms not available or permission undefined - fall back to vanilla permission level
        // Special case: level 0 means "allow all players"
        if (fallbackLevel == 0) {
            return true; // Allow all players
        }

        return source.hasPermissionLevel(fallbackLevel);
    }

    /**
     * Check LuckPerms permission using reflection to avoid hard dependency
     * Returns null if LuckPerms is not available or permission is undefined
     * Returns true/false only if permission is explicitly set
     */
    private static Boolean hasLuckPermsPermission(ServerPlayerEntity player, String permission) {
        try {
            // Try to get LuckPerms API
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckPermsApi = luckPermsClass.getMethod("getApi").invoke(null);

            // Get UserManager
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);

            // Get User by UUID
            Object user = userManager.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(userManager, player.getUuid());

            if (user == null) {
                // User not loaded in LuckPerms, fall back to vanilla
                return null;
            }

            // Get CachedPermissionData
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);

            // Check permission
            Object queryOptions = null;
            try {
                // Try to get QueryOptions for the player's current context
                Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                Class<?> contextManagerClass = Class.forName("net.luckperms.api.context.ContextManager");
                Object contextManager = luckPermsApi.getClass().getMethod("getContextManager").invoke(luckPermsApi);
                queryOptions = contextManagerClass.getMethod("getQueryOptions", player.getClass())
                        .invoke(contextManager, player);
            } catch (Exception e) {
                // If we can't get QueryOptions, try with defaultContextualSubject
                try {
                    Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                    queryOptions = queryOptionsClass.getMethod("defaultContextualSubject").invoke(null);
                } catch (Exception e2) {
                    // Last resort - try without QueryOptions (older LuckPerms versions)
                    Object tristate = permissionData.getClass().getMethod("checkPermission", String.class)
                            .invoke(permissionData, permission);
                    String result = tristate.toString();
                    if ("TRUE".equals(result)) return true;
                    if ("FALSE".equals(result)) return false;
                    return null; // UNDEFINED - fall back to vanilla
                }
            }

            // Check permission with QueryOptions
            Object tristate = permissionData.getClass().getMethod("checkPermission", String.class, queryOptions.getClass())
                    .invoke(permissionData, permission, queryOptions);

            String result = tristate.toString();
            if ("TRUE".equals(result)) return true;
            if ("FALSE".equals(result)) return false;
            return null; // UNDEFINED - fall back to vanilla

        } catch (Exception e) {
            // LuckPerms not available or error occurred, fall back to vanilla
            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] LuckPerms not available or error checking permission '" + permission + "': " + e.getMessage());
            }
            return null;
        }
    }

    private static int executeShowAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Config config = Config.getInstance();

        source.sendMessage(Text.of("§e=== EasyPets Configuration ==="));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§7Use §f/petconfig <setting>§7 to see details about a specific setting"));
        source.sendMessage(Text.of("§7Use §f/petconfig <setting> <value>§7 to change a setting"));
        source.sendMessage(Text.of("§7Use §f/petconfig <setting> reset§7 to reset a setting"));
        source.sendMessage(Text.of("§7Use §f/petconfig reset all§7 to reset all settings"));
        source.sendMessage(Text.of(""));

        // Core Features
        source.sendMessage(Text.of("§6Core Features:"));
        source.sendMessage(Text.of("§f  enableChunkLoading: §" + (config.isChunkLoadingEnabled() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  teleportDistance: §b" + config.getTeleportDistance() + " blocks"));
        source.sendMessage(Text.of("§f  maxChunkDistance: §b" + config.getMaxChunkDistance() + " chunks"));
        source.sendMessage(Text.of("§f  navigationScanningRange: §b" + config.getNavigationScanningRange() + " blocks"));
        source.sendMessage(Text.of("§f  autoRecoverOnFirstJoin: §" + (config.shouldAutoRecoverOnFirstJoin() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of(""));

        // Dynamic Pet Running
        source.sendMessage(Text.of("§6Dynamic Pet Running:"));
        source.sendMessage(Text.of("§f  enableDynamicRunning: §" + (config.isDynamicRunningEnabled() ? "aEnabled" : "cDisabled")));
        if (config.isDynamicRunningEnabled()) {
            source.sendMessage(Text.of("§f  runningTargetDistance: §b" + config.getRunningTargetDistance() + " blocks"));
            source.sendMessage(Text.of("§f  maxRunningMultiplier: §b" + config.getMaxRunningMultiplier() + "x"));
            source.sendMessage(Text.of("§f  playerMovementThreshold: §b" + config.getPlayerMovementThreshold()));
        }
        source.sendMessage(Text.of(""));

        // Natural Regeneration
        source.sendMessage(Text.of("§6Natural Regeneration:"));
        source.sendMessage(Text.of("§f  enableNaturalRegen: §" + (config.isNaturalRegenEnabled() ? "aEnabled" : "cDisabled")));
        if (config.isNaturalRegenEnabled()) {
            source.sendMessage(Text.of("§f  regenDelayTicks: §b" + config.getRegenDelayTicks() + " ticks §7(" + (config.getRegenDelayTicks() / 20.0) + "s)"));
            source.sendMessage(Text.of("§f  regenAmountPerSecond: §b" + config.getRegenAmountPerSecond() + " health/sec"));
            source.sendMessage(Text.of("§f  regenMaxHealthPercent: §b" + (config.getRegenMaxHealthPercent() * 100) + "%"));
        }
        source.sendMessage(Text.of(""));

        // Save Options
        source.sendMessage(Text.of("§6Save Options:"));
        source.sendMessage(Text.of("§f  saveOnLocate: §" + (config.shouldSaveOnLocate() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of("§f  saveOnRecovery: §" + (config.shouldSaveOnRecovery() ? "aEnabled" : "cDisabled")));
        source.sendMessage(Text.of(""));

        // Debug
        source.sendMessage(Text.of("§6Debug:"));
        source.sendMessage(Text.of("§f  enableDebugLogging: §" + (config.isDebugLoggingEnabled() ? "aEnabled" : "cDisabled")));
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
        String settingName = StringArgumentType.getString(context, "setting");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(Text.of("§cUnknown setting: " + settingName));
            source.sendMessage(Text.of("§7Available settings: " + String.join(", ", SETTING_INFO.keySet())));
            return 0;
        }

        Config config = Config.getInstance();
        String currentValue = getCurrentValue(config, settingName);
        String defaultValue = getDefaultValue(settingName);

        source.sendMessage(Text.of("§e=== " + info.name + " ==="));
        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§6Current Value: §f" + currentValue));
        source.sendMessage(Text.of("§6Default Value: §7" + defaultValue));
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
        source.sendMessage(Text.of("§7  /petconfig " + settingName + " <value> §7- Change setting"));
        source.sendMessage(Text.of("§7  /petconfig " + settingName + " reset §7- Reset to default"));

        return 1;
    }

    private static int executeSetSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting");
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
                source.sendMessage(Text.of("§a[EasyPets] " + info.name + " set to: " + value));

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
        String settingName = StringArgumentType.getString(context, "setting");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(Text.of("§cUnknown setting: " + settingName));
            return 0;
        }

        Config config = Config.getInstance();
        String defaultValue = getDefaultValue(settingName);

        boolean success = setSetting(config, settingName, defaultValue);
        if (success) {
            config.saveConfig();
            source.sendMessage(Text.of("§a[EasyPets] " + info.name + " reset to default: " + defaultValue));
        } else {
            source.sendError(Text.of("§cFailed to reset " + settingName));
        }

        return 1;
    }

    private static int executeResetAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Config config = Config.getInstance();

        // Reset all settings to defaults
        resetAllSettings(config);
        config.saveConfig();

        source.sendMessage(Text.of("§a[EasyPets] All settings have been reset to default values"));
        source.sendMessage(Text.of("§7Use §f/petconfig§7 to view the current configuration"));

        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        Config.getInstance().reloadConfig();
        source.sendMessage(Text.of("§a[EasyPets] Configuration reloaded successfully"));

        return 1;
    }

    // Helper method to get default value for any setting
    private static String getDefaultValue(String settingName) {
        return Config.getDefaultValueFor(settingName);
    }

    // Helper method to reset all settings to defaults
    private static void resetAllSettings(Config config) {
        config.resetToDefaults();
    }

    private static String getCurrentValue(Config config, String settingName) {
        return switch (settingName) {
            case "enableChunkLoading" -> String.valueOf(config.isChunkLoadingEnabled());
            case "teleportDistance" -> String.valueOf(config.getTeleportDistance());
            case "maxChunkDistance" -> String.valueOf(config.getMaxChunkDistance());
            case "navigationScanningRange" -> String.valueOf(config.getNavigationScanningRange());
            case "autoRecoverOnFirstJoin" -> String.valueOf(config.shouldAutoRecoverOnFirstJoin());
            case "enableDynamicRunning" -> String.valueOf(config.isDynamicRunningEnabled());
            case "runningTargetDistance" -> String.valueOf(config.getRunningTargetDistance());
            case "maxRunningMultiplier" -> String.valueOf(config.getMaxRunningMultiplier());
            case "playerMovementThreshold" -> String.valueOf(config.getPlayerMovementThreshold());
            case "enableNaturalRegen" -> String.valueOf(config.isNaturalRegenEnabled());
            case "regenDelayTicks" -> String.valueOf(config.getRegenDelayTicks());
            case "regenAmountPerSecond" -> String.valueOf(config.getRegenAmountPerSecond());
            case "regenMaxHealthPercent" -> String.valueOf(config.getRegenMaxHealthPercent());
            case "saveOnLocate" -> String.valueOf(config.shouldSaveOnLocate());
            case "saveOnRecovery" -> String.valueOf(config.shouldSaveOnRecovery());
            case "enableDebugLogging" -> String.valueOf(config.isDebugLoggingEnabled());
            default -> "unknown";
        };
    }

    private static boolean setSetting(Config config, String settingName, String value) {
        try {
            switch (settingName) {
                case "enableChunkLoading" -> {
                    config.enableChunkLoading = Boolean.parseBoolean(value);
                    return true;
                }
                case "teleportDistance" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 100.0) {
                        config.teleportDistance = d;
                        return true;
                    }
                }
                case "maxChunkDistance" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 1 && i <= 10) {
                        config.maxChunkDistance = i;
                        return true;
                    }
                }
                case "navigationScanningRange" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 32 && i <= 1000) {
                        config.navigationScanningRange = i;
                        return true;
                    }
                }
                case "autoRecoverOnFirstJoin" -> {
                    config.autoRecoverOnFirstJoin = Boolean.parseBoolean(value);
                    return true;
                }
                case "enableDynamicRunning" -> {
                    config.enableDynamicRunning = Boolean.parseBoolean(value);
                    return true;
                }
                case "runningTargetDistance" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 50.0) {
                        config.runningTargetDistance = d;
                        return true;
                    }
                }
                case "maxRunningMultiplier" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 1.0 && d <= 10.0) {
                        config.maxRunningMultiplier = d;
                        return true;
                    }
                }
                case "playerMovementThreshold" -> {
                    double d = Double.parseDouble(value);
                    if (d >= 0.01 && d <= 1.0) {
                        config.playerMovementThreshold = d;
                        return true;
                    }
                }
                case "enableNaturalRegen" -> {
                    config.enableNaturalRegen = Boolean.parseBoolean(value);
                    return true;
                }
                case "regenDelayTicks" -> {
                    int i = Integer.parseInt(value);
                    if (i >= 20 && i <= 6000) {
                        config.regenDelayTicks = i;
                        return true;
                    }
                }
                case "regenAmountPerSecond" -> {
                    float f = Float.parseFloat(value);
                    if (f >= 0.01f && f <= 5.0f) {
                        config.regenAmountPerSecond = f;
                        return true;
                    }
                }
                case "regenMaxHealthPercent" -> {
                    float f = Float.parseFloat(value);
                    if (f >= 0.1f && f <= 1.0f) {
                        config.regenMaxHealthPercent = f;
                        return true;
                    }
                }
                case "saveOnLocate" -> {
                    config.saveOnLocate = Boolean.parseBoolean(value);
                    return true;
                }
                case "saveOnRecovery" -> {
                    config.saveOnRecovery = Boolean.parseBoolean(value);
                    return true;
                }
                case "enableDebugLogging" -> {
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
            case "enableChunkLoading" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Existing chunk tickets will auto-expire in 5 seconds"));
                }
            }
            case "autoRecoverOnFirstJoin" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Players will need to manually run /petrecovery when they first join"));
                } else {
                    source.sendMessage(Text.of("§7Note: Pet recovery will automatically run 5 seconds after first join"));
                }
            }
            case "enableDynamicRunning" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Pet speeds will return to vanilla behavior"));
                }
            }
            case "enableNaturalRegen" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Note: Pets will no longer regenerate health automatically"));
                }
            }
            case "enableDebugLogging" -> {
                if (Boolean.parseBoolean(value)) {
                    source.sendMessage(Text.of("§7Warning: Debug logging will create additional console output"));
                }
            }
            case "maxChunkDistance" -> {
                int distance = Integer.parseInt(value);
                if (distance > 4) {
                    source.sendMessage(Text.of("§7Warning: High chunk distances may impact server performance"));
                }
            }
            case "navigationScanningRange" -> {
                int range = Integer.parseInt(value);
                if (range > 500) {
                    source.sendMessage(Text.of("§7Warning: Very high navigation ranges may cause server lag"));
                } else if (range < 100) {
                    source.sendMessage(Text.of("§7Note: Very low navigation ranges may cause pets to teleport frequently"));
                }
            }
            case "teleportDistance" -> {
                double distance = Double.parseDouble(value);
                if (distance < 8.0) {
                    source.sendMessage(Text.of("§7Note: Very low teleport distances may cause pets to teleport frequently"));
                } else if (distance > 32.0) {
                    source.sendMessage(Text.of("§7Note: Very high teleport distances may cause pets to get lost more easily"));
                }
            }
            case "maxRunningMultiplier" -> {
                double multiplier = Double.parseDouble(value);
                if (multiplier > 4.0) {
                    source.sendMessage(Text.of("§7Warning: Very high running multipliers may look unnatural or cause pathfinding issues"));
                }
            }
            case "runningTargetDistance" -> {
                double minDist = Double.parseDouble(value);
                if (minDist < 3.0) {
                    source.sendMessage(Text.of("§7Note: Very low target distances may cause frequent speed changes"));
                }
            }
            case "regenAmountPerSecond" -> {
                float amount = Float.parseFloat(value);
                if (amount > 2.0f) {
                    source.sendMessage(Text.of("§7Warning: Very high regeneration rates may make pets overpowered"));
                }
            }
            case "regenDelayTicks" -> {
                int delay = Integer.parseInt(value);
                if (delay < 100) {
                    source.sendMessage(Text.of("§7Note: Very short delays may make pets regenerate too quickly after damage"));
                } else if (delay > 1200) {
                    source.sendMessage(Text.of("§7Note: Very long delays may make regeneration feel unresponsive"));
                }
            }
            case "regenMaxHealthPercent" -> {
                float percent = Float.parseFloat(value);
                if (percent < 0.8f) {
                    source.sendMessage(Text.of("§7Note: Low max health percentages mean pets will need manual healing"));
                }
            }
        }
    }

    // Helper class for storing setting information
    private static class SettingInfo {
        final String name;
        final String validRange;
        final String description;
        final String details;
        final String whenToUse;

        SettingInfo(String name, String validRange, String description, String details, String whenToUse) {
            this.name = name;
            this.validRange = validRange;
            this.description = description;
            this.details = details;
            this.whenToUse = whenToUse;
        }
    }
}