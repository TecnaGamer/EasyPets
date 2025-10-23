package org.tecna.easypets.config;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.tecna.easypets.translation.TranslationManager;

import java.util.HashMap;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ConfigCommand {

    // Complete setting information for all config options
    private static final Map<String, SettingInfo> SETTING_INFO = new HashMap<>();
    
    // Helper method to create formatted text using server-side translations
    private static Text formatted(String color, String translationKey, Object... args) {
        return TranslationManager.getInstance().text(color, translationKey, args);
    }

    static {
        // Core settings - only store setting name and valid range, descriptions come from lang file
        SETTING_INFO.put("enableChunkLoading", new SettingInfo("enableChunkLoading", "boolean"));
        SETTING_INFO.put("teleportDistance", new SettingInfo("teleportDistance", "1.0-∞"));
        SETTING_INFO.put("maxChunkDistance", new SettingInfo("maxChunkDistance", "1-10"));
        SETTING_INFO.put("navigationScanningRange", new SettingInfo("navigationScanningRange", "8-1000"));
        SETTING_INFO.put("autoRecoverOnFirstJoin", new SettingInfo("autoRecoverOnFirstJoin", "boolean"));
        
        // Dynamic Pet Running settings
        SETTING_INFO.put("enableDynamicRunning", new SettingInfo("enableDynamicRunning", "boolean"));
        SETTING_INFO.put("runningTargetDistance", new SettingInfo("runningTargetDistance", "1.0-50.0"));
        SETTING_INFO.put("maxRunningMultiplier", new SettingInfo("maxRunningMultiplier", "1.0-10.0"));
        SETTING_INFO.put("playerMovementThreshold", new SettingInfo("playerMovementThreshold", "0.01-1.0"));
        
        // Natural Regeneration settings
        SETTING_INFO.put("enableNaturalRegen", new SettingInfo("enableNaturalRegen", "boolean"));
        SETTING_INFO.put("regenDelayTicks", new SettingInfo("regenDelayTicks", "20-6000"));
        SETTING_INFO.put("regenAmountPerSecond", new SettingInfo("regenAmountPerSecond", "0.01-5.0"));
        SETTING_INFO.put("regenMaxHealthPercent", new SettingInfo("regenMaxHealthPercent", "0.1-1.0"));
        
        // Save options
        SETTING_INFO.put("saveOnLocate", new SettingInfo("saveOnLocate", "boolean"));
        SETTING_INFO.put("saveOnRecovery", new SettingInfo("saveOnRecovery", "boolean"));
        
        // Language option - range will be dynamically determined
        SETTING_INFO.put("language", new SettingInfo("language", "language code (e.g. en_us, es_es)"));
        
        // Debug option
        SETTING_INFO.put("enableDebugLogging", new SettingInfo("enableDebugLogging", "boolean"));
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
                                context.getSource().sendMessage(formatted("§7", "easypets.config.usage_hint"));
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
                                                    builder.suggest("12"); // Default (vanilla)
                                                    builder.suggest("24"); // A bit further
                                                    builder.suggest("48"); // Much further
                                                    builder.suggest("96"); // Very far
                                                    builder.suggest("192"); // Extra far for large builds
                                                    builder.suggest("384");
                                                    builder.suggest("768");
                                                } else if (settingName.equals("maxChunkDistance")) {
                                                    builder.suggest("1");
                                                    builder.suggest("2"); // Default
                                                    builder.suggest("3");
                                                    builder.suggest("4");
                                                } else if (settingName.equals("navigationScanningRange")) {
                                                    builder.suggest("16");
                                                    builder.suggest("64");
                                                    builder.suggest("128");
                                                    builder.suggest("256");
                                                    builder.suggest("512");
                                                } else if (settingName.equals("runningTargetDistance")) {
                                                    builder.suggest("4.0");  // Default
                                                    builder.suggest("6.0");  // Further
                                                    builder.suggest("8.0");  // Much further
                                                } else if (settingName.equals("maxRunningMultiplier")) {
                                                    builder.suggest("1.6");  // Default
                                                    builder.suggest("1.8");  // Faster
                                                    builder.suggest("2.0");  // Very fast
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
                                                } else if (settingName.equals("language")) {
                                                    // Dynamically suggest available languages
                                                    TranslationManager.getInstance().getAvailableLanguages()
                                                            .forEach(builder::suggest);
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

        source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.config.title") + " ==="));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§7", "easypets.config.usage_view"));
        source.sendMessage(formatted("§7", "easypets.config.usage_change"));
        source.sendMessage(formatted("§7", "easypets.config.usage_reset"));
        source.sendMessage(formatted("§7", "easypets.config.usage_reset_all"));
        source.sendMessage(Text.empty());

        // Core Features
        source.sendMessage(formatted("§6", "easypets.config.category.core"));
        String enabledStr = TranslationManager.getInstance().translate("easypets.config.enabled");
        String disabledStr = TranslationManager.getInstance().translate("easypets.config.disabled");
        
        source.sendMessage(Text.literal("§f  enableChunkLoading: §" + (config.isChunkLoadingEnabled() ? "a" + enabledStr : "c" + disabledStr)));
        source.sendMessage(Text.literal("§f  teleportDistance: §b" + config.getTeleportDistance() + " blocks"));
        source.sendMessage(Text.literal("§f  maxChunkDistance: §b" + config.getMaxChunkDistance() + " chunks"));
        source.sendMessage(Text.literal("§f  navigationScanningRange: §b" + config.getNavigationScanningRange() + " blocks"));
        source.sendMessage(Text.literal("§f  autoRecoverOnFirstJoin: §" + (config.shouldAutoRecoverOnFirstJoin() ? "a" + enabledStr : "c" + disabledStr)));
        source.sendMessage(Text.empty());

        // Dynamic Pet Running
        source.sendMessage(formatted("§6", "easypets.config.category.running"));
        source.sendMessage(Text.literal("§f  enableDynamicRunning: §" + (config.isDynamicRunningEnabled() ? "a" + enabledStr : "c" + disabledStr)));
        if (config.isDynamicRunningEnabled()) {
            source.sendMessage(Text.literal("§f  runningTargetDistance: §b" + config.getRunningTargetDistance() + " blocks"));
            source.sendMessage(Text.literal("§f  maxRunningMultiplier: §b" + config.getMaxRunningMultiplier() + "x"));
            source.sendMessage(Text.literal("§f  playerMovementThreshold: §b" + config.getPlayerMovementThreshold()));
        }
        source.sendMessage(Text.empty());

        // Natural Regeneration
        source.sendMessage(formatted("§6", "easypets.config.category.regen"));
        source.sendMessage(Text.literal("§f  enableNaturalRegen: §" + (config.isNaturalRegenEnabled() ? "a" + enabledStr : "c" + disabledStr)));
        if (config.isNaturalRegenEnabled()) {
            source.sendMessage(Text.literal("§f  regenDelayTicks: §b" + config.getRegenDelayTicks() + " ticks §7(" + (config.getRegenDelayTicks() / 20.0) + "s)"));
            source.sendMessage(Text.literal("§f  regenAmountPerSecond: §b" + config.getRegenAmountPerSecond() + " health/sec"));
            source.sendMessage(Text.literal("§f  regenMaxHealthPercent: §b" + (config.getRegenMaxHealthPercent() * 100) + "%"));
        }
        source.sendMessage(Text.empty());

        // Save Options
        source.sendMessage(formatted("§6", "easypets.config.category.save"));
        source.sendMessage(Text.literal("§f  saveOnLocate: §" + (config.shouldSaveOnLocate() ? "a" + enabledStr : "c" + disabledStr)));
        source.sendMessage(Text.literal("§f  saveOnRecovery: §" + (config.shouldSaveOnRecovery() ? "a" + enabledStr : "c" + disabledStr)));
        source.sendMessage(Text.empty());
        
        // Language
        source.sendMessage(formatted("§6", "Language:"));
        var availableLangs = TranslationManager.getInstance().getAvailableLanguages();
        source.sendMessage(Text.literal("§f  language: §b" + config.getLanguage() + " §7(" + availableLangs.size() + " available)"));
        source.sendMessage(Text.empty());

        // Debug
        source.sendMessage(formatted("§6", "easypets.config.category.debug"));
        source.sendMessage(Text.literal("§f  enableDebugLogging: §" + (config.isDebugLoggingEnabled() ? "a" + enabledStr : "c" + disabledStr)));
        source.sendMessage(Text.empty());

        return 1;
    }

    private static int executeShowSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(formatted("§c", "easypets.command.error.unknown_setting", settingName));
            source.sendMessage(formatted("§7", "easypets.config.available_settings", String.join(", ", SETTING_INFO.keySet())));
            return 0;
        }

        Config config = Config.getInstance();
        String currentValue = getCurrentValue(config, settingName);
        String defaultValue = getDefaultValue(settingName);

        source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.config.setting.title", info.name) + " ==="));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§6", "easypets.config.setting.current_value", "§f" + currentValue));
        source.sendMessage(formatted("§6", "easypets.config.setting.default_value", "§7" + defaultValue));
        
        // For language setting, show available languages instead of generic range
        if (settingName.equals("language")) {
            var availableLanguages = TranslationManager.getInstance().getAvailableLanguages();
            source.sendMessage(formatted("§6", "easypets.config.setting.valid_range", "§7" + String.join(", ", availableLanguages)));
        } else {
            source.sendMessage(formatted("§6", "easypets.config.setting.valid_range", "§7" + info.validRange));
        }
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§6", "easypets.config.setting.description"));
        source.sendMessage(Text.literal("§f  " + TranslationManager.getInstance().translate("easypets.config.setting." + settingName + ".description")));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§6", "easypets.config.setting.details"));
        source.sendMessage(Text.literal("§f  " + TranslationManager.getInstance().translate("easypets.config.setting." + settingName + ".details")));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§6", "easypets.config.setting.when_to_use"));
        source.sendMessage(Text.literal("§f  " + TranslationManager.getInstance().translate("easypets.config.setting." + settingName + ".when_to_use")));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§7", "easypets.config.setting.commands"));
        source.sendMessage(formatted("§7", "easypets.config.setting.command_change", settingName));
        source.sendMessage(formatted("§7", "easypets.config.setting.command_reset", settingName));

        return 1;
    }

    private static int executeSetSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting");
        String value = StringArgumentType.getString(context, "value");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(formatted("§c", "easypets.command.error.unknown_setting", settingName));
            return 0;
        }

        Config config = Config.getInstance();

        try {
            boolean success = setSetting(config, settingName, value);
            if (success) {
                config.saveConfig();
                source.sendMessage(formatted("§a", "easypets.config.set_success", info.name, value));

                // Show any relevant warnings or notes
                showSettingWarnings(source, settingName, value);
            } else {
                source.sendError(formatted("§c", "easypets.command.error.invalid_value", value, settingName));
                source.sendMessage(formatted("§7", "easypets.config.setting.valid_range", info.validRange));
            }
        } catch (Exception e) {
            source.sendError(formatted("§c", "easypets.command.error.setting_failed", settingName, e.getMessage()));
            source.sendMessage(formatted("§7", "easypets.config.setting.valid_range", info.validRange));
        }

        return 1;
    }

    private static int executeResetSetting(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String settingName = StringArgumentType.getString(context, "setting");

        SettingInfo info = SETTING_INFO.get(settingName);
        if (info == null) {
            source.sendError(formatted("§c", "easypets.command.error.unknown_setting", settingName));
            return 0;
        }

        Config config = Config.getInstance();
        String defaultValue = getDefaultValue(settingName);

        boolean success = setSetting(config, settingName, defaultValue);
        if (success) {
            config.saveConfig();
            source.sendMessage(formatted("§a", "easypets.config.reset_success", info.name, defaultValue));
        } else {
            source.sendError(formatted("§c", "easypets.command.error.reset_failed", settingName));
        }

        return 1;
    }

    private static int executeResetAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Config config = Config.getInstance();

        // Reset all settings to defaults
        resetAllSettings(config);
        config.saveConfig();

        source.sendMessage(formatted("§a", "easypets.config.reset_all_success"));
        source.sendMessage(formatted("§7", "easypets.config.reset_all_hint"));

        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        Config.getInstance().reloadConfig();
        source.sendMessage(formatted("§a", "easypets.config.reload_success"));

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
            case "language" -> config.getLanguage();
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
                    if (d >= 1.0) {
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
                    if (i >= 8 && i <= 1000) {
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
                case "language" -> {
                    // Normalize language code
                    String normalizedLang = value.toLowerCase().replace("-", "_");
                    config.language = normalizedLang;
                    // Reload translations with new language
                    TranslationManager.getInstance().reloadLanguage(normalizedLang);
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
                    source.sendMessage(formatted("§7", "easypets.config.warning.chunk_loading_disabled"));
                }
            }
            case "autoRecoverOnFirstJoin" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.auto_recover_disabled"));
                } else {
                    source.sendMessage(formatted("§7", "easypets.config.warning.auto_recover_enabled"));
                }
            }
            case "enableDynamicRunning" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.dynamic_running_disabled"));
                }
            }
            case "enableNaturalRegen" -> {
                if (!Boolean.parseBoolean(value)) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.natural_regen_disabled"));
                }
            }
            case "enableDebugLogging" -> {
                if (Boolean.parseBoolean(value)) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.debug_logging_enabled"));
                }
            }
            case "maxChunkDistance" -> {
                int distance = Integer.parseInt(value);
                if (distance > 4) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.high_chunk_distance"));
                }
            }
            case "navigationScanningRange" -> {
                int range = Integer.parseInt(value);
                if (range > 300) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.high_navigation_range"));
                } else if (range < 16) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.low_navigation_range"));
                }
            }
            case "teleportDistance" -> {
                double distance = Double.parseDouble(value);
                if (distance < 8.0) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.low_teleport_distance"));
                } else if (distance > 48.0) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.high_teleport_distance"));
                }
            }
            case "maxRunningMultiplier" -> {
                double multiplier = Double.parseDouble(value);
                if (multiplier > 2.0) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.high_running_multiplier"));
                }
            }
            case "runningTargetDistance" -> {
                double minDist = Double.parseDouble(value);
                if (minDist < 4.0) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.low_target_distance"));
                }
            }
            case "regenAmountPerSecond" -> {
                float amount = Float.parseFloat(value);
                if (amount > 1.0f) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.high_regen_rate"));
                }
            }
            case "regenDelayTicks" -> {
                int delay = Integer.parseInt(value);
                if (delay < 100) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.short_regen_delay"));
                } else if (delay > 1200) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.long_regen_delay"));
                }
            }
            case "regenMaxHealthPercent" -> {
                float percent = Float.parseFloat(value);
                if (percent < 0.8f) {
                    source.sendMessage(formatted("§7", "easypets.config.warning.low_max_health"));
                }
            }
        }
    }

    // Helper class for storing setting information
    // Descriptions, details, and whenToUse are now loaded from lang file dynamically
    private static class SettingInfo {
        final String name;
        final String validRange;

        SettingInfo(String name, String validRange) {
            this.name = name;
            this.validRange = validRange;
        }
    }
}