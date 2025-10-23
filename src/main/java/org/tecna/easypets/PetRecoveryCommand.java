package org.tecna.easypets;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.*;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.tecna.easypets.config.Config;
import org.tecna.easypets.translation.TranslationManager;
import org.tecna.easypets.util.SaveUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.stream.Collectors.groupingBy;
import static net.minecraft.server.command.CommandManager.literal;
import java.util.stream.Stream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PetRecoveryCommand {

    // Define roaming pet types that shouldn't load chunks automatically
    private static final Set<String> ROAMING_PET_TYPES = Set.of(
            "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:llama", "minecraft:trader_llama"
    );

    // Spam protection - track players currently running scans
    private static final Set<UUID> playersCurrentlyScanning = new HashSet<>();
    
    // Glow effect management
    private static final ScheduledExecutorService glowScheduler = Executors.newScheduledThreadPool(2);
    private static final Map<UUID, GlowSession> activeGlowSessions = new ConcurrentHashMap<>();
    
    // Helper method to create formatted text using server-side translations
    private static Text formatted(String color, String translationKey, Object... args) {
        return TranslationManager.getInstance().text(color, translationKey, args);
    }
    
    private static Text formatted(String color, Text text) {
        return Text.literal(color).append(text);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Player commands - available to ALL players (no .requires() restriction)
            dispatcher.register(literal("petrecovery")
                    .executes(PetRecoveryCommand::executePetRecovery));

            dispatcher.register(literal("petlocator")
                    .executes(PetRecoveryCommand::executePetLocator));

            dispatcher.register(literal("petglow")
                    .executes(PetRecoveryCommand::executePetGlow));

            // Admin commands - require permissions
            dispatcher.register(literal("debugregion")
                    .requires(source -> hasPermission(source, "easypets.admin.debugregion", 2))
                    .executes(PetRecoveryCommand::executeDebugRegion));

            dispatcher.register(literal("petstats")
                    .requires(source -> hasPermission(source, "easypets.admin.petstats", 2))
                    .executes(PetRecoveryCommand::executePetStats)
                    .then(literal("player")
                            .then(net.minecraft.server.command.CommandManager.argument("playerName", net.minecraft.command.argument.EntityArgumentType.player())
                                    .executes(PetRecoveryCommand::executePetStatsForPlayer))));

            // Debug commands for troubleshooting
            dispatcher.register(literal("petdebug")
                    .requires(source -> hasPermission(source, "easypets.admin.debug", 4))
                    .then(literal("cleanup")
                            .executes(PetRecoveryCommand::executeDebugCleanup))
                    .then(literal("tickets")
                            .executes(PetRecoveryCommand::executeDebugTickets))
                    .then(literal("reset")
                            .then(net.minecraft.server.command.CommandManager.argument("playerName", net.minecraft.command.argument.EntityArgumentType.player())
                                    .executes(PetRecoveryCommand::executeDebugReset)))
                    .then(literal("version")
                            .executes(PetRecoveryCommand::executeDebugVersion)));
        });
    }

    /**
     * Check permissions using LuckPerms if available, otherwise fall back to vanilla permission levels
     */
    private static boolean hasPermission(ServerCommandSource source, String permission, int fallbackLevel) {
        // Console/command blocks always have permission for admin commands
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return true;
        }

        // Try LuckPerms integration first
        Boolean luckPermsResult = hasLuckPermsPermission(player, permission);
        if (luckPermsResult != null) {
            return luckPermsResult;
        }

        // Fall back to vanilla permission level
        return source.hasPermissionLevel(fallbackLevel);
    }

    /**
     * Check LuckPerms permission using reflection to avoid hard dependency
     * Returns null if LuckPerms is not available or permission is undefined
     */
    private static Boolean hasLuckPermsPermission(ServerPlayerEntity player, String permission) {
        try {
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckPermsApi = luckPermsClass.getMethod("getApi").invoke(null);

            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = userManager.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(userManager, player.getUuid());

            if (user == null) {
                return null; // User not loaded, fall back to vanilla
            }

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);

            // Try to get QueryOptions
            Object queryOptions = null;
            try {
                Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                Class<?> contextManagerClass = Class.forName("net.luckperms.api.context.ContextManager");
                Object contextManager = luckPermsApi.getClass().getMethod("getContextManager").invoke(luckPermsApi);
                queryOptions = contextManagerClass.getMethod("getQueryOptions", player.getClass())
                        .invoke(contextManager, player);
            } catch (Exception e) {
                try {
                    Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                    queryOptions = queryOptionsClass.getMethod("defaultContextualSubject").invoke(null);
                } catch (Exception e2) {
                    // Fallback for older LuckPerms versions
                    Object tristate = permissionData.getClass().getMethod("checkPermission", String.class)
                            .invoke(permissionData, permission);
                    String result = tristate.toString();
                    if ("TRUE".equals(result)) return true;
                    if ("FALSE".equals(result)) return false;
                    return null;
                }
            }

            Object tristate = permissionData.getClass().getMethod("checkPermission", String.class, queryOptions.getClass())
                    .invoke(permissionData, permission, queryOptions);

            String result = tristate.toString();
            if ("TRUE".equals(result)) return true;
            if ("FALSE".equals(result)) return false;
            return null; // UNDEFINED - fall back to vanilla

        } catch (Exception e) {
            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] LuckPerms not available: " + e.getMessage());
            }
            return null;
        }
    }

    private static void showPetStats(ServerCommandSource source, ServerPlayerEntity targetPlayer) {
        try {
            if (targetPlayer != null) {
                showDetailedPlayerStats(source, targetPlayer);
            } else {
                showOverallStats(source);
            }
        } catch (Exception e) {
            source.sendError(formatted("§c", "easypets.command.error.stats_retrieval", e.getMessage()));
        }
    }

    private static void showOverallStats(ServerCommandSource source) {
        source.sendMessage(Text.literal("§a=== " + TranslationManager.getInstance().translate("easypets.petstats.title") + " ==="));

        int totalChunkLoadingPets = 0;
        int totalPlayersWithPets = 0;

        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                Set<UUID> playerPets = tracker.getChunkLoadingPets();
                int playerPetCount = playerPets.size();

                if (playerPetCount > 0) {
                    totalPlayersWithPets++;
                    totalChunkLoadingPets += playerPetCount;
                    source.sendMessage(Text.literal("§f" + player.getGameProfile().name() + ": §6" + playerPetCount + " pets loading chunks"));
                }
            }
        }

        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§7", "easypets.petstats.total", "§e" + totalChunkLoadingPets, "§e" + totalPlayersWithPets));
        source.sendMessage(formatted("§7", "easypets.petstats.usage_hint"));
    }

    private static void showDetailedPlayerStats(ServerCommandSource source, ServerPlayerEntity targetPlayer) {
        source.sendMessage(Text.literal("§a=== " + TranslationManager.getInstance().translate("easypets.petstats.player_title", targetPlayer.getGameProfile().name()) + " ==="));

        if (!(targetPlayer instanceof SimplePetTracker tracker)) {
            source.sendError(formatted("§c", "easypets.petstats.player_data_unavailable"));
            return;
        }

        List<PetDetails> loadingPets = new ArrayList<>();
        List<PetDetails> sittingPets = new ArrayList<>();
        List<PetDetails> independentPets = new ArrayList<>();

        Set<UUID> chunkLoadingPetUUIDs = tracker.getChunkLoadingPets();

        for (ServerWorld world : source.getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof net.minecraft.entity.passive.TameableEntity pet) {
                    if (pet.isTamed() && pet.getOwner() == targetPlayer) {
                        // Fix: Use entity type ID instead of class name
                        String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
                        String petType = entityId.replace("minecraft:", "");
                        // Capitalize first letter for display
                        petType = petType.substring(0, 1).toUpperCase() + petType.substring(1);

                        String worldName = world.getRegistryKey().getValue().toString().replace("minecraft:", "");

                        String displayName = pet.hasCustomName() ?
                                pet.getCustomName().getString() + " (" + petType + ")" :
                                petType;

                        boolean isIndependent = isIndependentPet(pet);

                        PetDetails details = new PetDetails(
                                pet.getUuid(),
                                entityId, // Use full entity ID for consistency
                                displayName,
                                pet.getX(),
                                pet.getY(),
                                pet.getZ(),
                                worldName,
                                pet.isSitting()
                        );

                        if (isIndependent) {
                            independentPets.add(details);
                        } else if (pet.isSitting()) {
                            sittingPets.add(details);
                        } else if (chunkLoadingPetUUIDs.contains(pet.getUuid())) {
                            loadingPets.add(details);
                        }
                    }
                }
            }
        }

        if (!loadingPets.isEmpty()) {
            source.sendMessage(formatted("§6", "easypets.petstats.loading_chunks", loadingPets.size()));
            for (PetDetails pet : loadingPets) {
                source.sendMessage(formatted("§f", "easypets.petstats.location", "• " + pet.displayName, pet.getLocationString(), pet.worldName));
            }
        } else {
            source.sendMessage(formatted("§7", "easypets.petstats.no_loading_chunks"));
        }

        if (!sittingPets.isEmpty()) {
            source.sendMessage(Text.empty());
            source.sendMessage(formatted("§3", "easypets.petstats.sitting", sittingPets.size()));
            for (PetDetails pet : sittingPets) {
                source.sendMessage(formatted("§f", "easypets.petstats.location", "• " + pet.displayName, pet.getLocationString(), pet.worldName));
            }
        }

        if (!independentPets.isEmpty()) {
            source.sendMessage(Text.empty());
            source.sendMessage(formatted("§3", "easypets.petstats.independent", independentPets.size()));
            for (PetDetails pet : independentPets) {
                source.sendMessage(formatted("§f", "easypets.petstats.location", "• " + pet.displayName, pet.getLocationString(), pet.worldName));
            }
        }

        if (loadingPets.isEmpty() && sittingPets.isEmpty() && independentPets.isEmpty()) {
            source.sendMessage(formatted("§7", "easypets.petstats.no_pets"));
        }
    }

    private static int executePetRecovery(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        synchronized (playersCurrentlyScanning) {
            if (playersCurrentlyScanning.contains(playerUUID)) {
                source.sendError(formatted("§c", "easypets.command.error.already_scanning"));
                return 0;
            }
            playersCurrentlyScanning.add(playerUUID);
        }

        recoverPlayerPets(player, false);
        return 1;
    }

    public static void runPetRecoveryForPlayer(ServerPlayerEntity player, boolean locateOnly) {
        recoverPlayerPets(player, locateOnly);
    }

    private static int executePetLocator(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        synchronized (playersCurrentlyScanning) {
            if (playersCurrentlyScanning.contains(playerUUID)) {
                source.sendError(formatted("§c", "easypets.command.error.already_scanning"));
                return 0;
            }
            playersCurrentlyScanning.add(playerUUID);
        }

        recoverPlayerPets(player, true);
        return 1;
    }

    private static int executeDebugRegion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        debugRegionFile(player);
        return 1;
    }

    private static int executePetStats(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        showPetStats(source, null);
        return 1;
    }

    private static int executePetStatsForPlayer(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        try {
            ServerPlayerEntity targetPlayer = net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "playerName");
            showPetStats(source, targetPlayer);
        } catch (Exception e) {
            source.sendError(formatted("§c", "easypets.command.error.player_not_found"));
        }
        return 1;
    }

    private static int executeDebugCleanup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        int totalPets = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                int playerPets = tracker.getChunkLoadingPets().size();
                totalPets += playerPets;
                tracker.getChunkLoadingPets().clear();
            }
        }

        source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.debug.cleanup_title") + " ==="));
        source.sendMessage(formatted("§7", "easypets.debug.cleared_tracking", "§c" + totalPets));
        source.sendMessage(formatted("§7", "easypets.debug.tickets_expire"));

        return 1;
    }

    private static int executeDebugTickets(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        int totalTracked = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                totalTracked += tracker.getChunkLoadingPets().size();
            }
        }

        source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.debug.tracked_pets_title") + " ==="));
        source.sendMessage(formatted("§7", "easypets.debug.total_tracked", "§f" + totalTracked));
        source.sendMessage(formatted("§7", "easypets.debug.tracking_note"));
        source.sendMessage(formatted("§7", "easypets.debug.tickets_note"));

        return 1;
    }

    private static int executeDebugReset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            ServerPlayerEntity targetPlayer = net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "playerName");

            int petCount = 0;
            if (targetPlayer instanceof SimplePetTracker tracker) {
                petCount = tracker.getChunkLoadingPets().size();
                tracker.getChunkLoadingPets().clear();
            }

            source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.debug.reset_title") + " ==="));
            source.sendMessage(formatted("§7", "easypets.debug.reset_player", "§f" + targetPlayer.getGameProfile().name()));
            source.sendMessage(formatted("§7", "easypets.debug.cleared_tracking", "§c" + petCount));

            targetPlayer.sendMessage(formatted("§7", "easypets.debug.reset_notification"));

        } catch (Exception e) {
            source.sendError(formatted("§c", "easypets.debug.error_occurred", e.getMessage()));
        }

        return 1;
    }

    private static int executeDebugVersion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendMessage(Text.literal("§e=== " + TranslationManager.getInstance().translate("easypets.debug.version_title") + " ==="));
        source.sendMessage(formatted("§7", "easypets.debug.version"));
        source.sendMessage(formatted("§7", "easypets.debug.ticket_system"));
        source.sendMessage(formatted("§7", "easypets.debug.based_on"));
        source.sendMessage(Text.empty());

        source.sendMessage(formatted("§7", "easypets.debug.online_players"));
        int playersWithData = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                int petCount = tracker.getChunkLoadingPets().size();
                playersWithData++;
                source.sendMessage(formatted("§f", "easypets.debug.player_entry", player.getGameProfile().name(), "§6" + petCount));
            }
        }

        if (playersWithData == 0) {
            source.sendMessage(formatted("§7", "easypets.debug.no_player_data"));
        }

        return 1;
    }

    private static void debugRegionFile(ServerPlayerEntity player) {
        CompletableFuture.runAsync(() -> {
            try {
                player.sendMessage(formatted("§a", "easypets.debug.region.start"), false);

                Path worldPath = player.getEntityWorld().getServer().getSavePath(WorldSavePath.ROOT).normalize();
                Path entitiesPath = worldPath.resolve("entities");

                if (!Files.exists(entitiesPath)) {
                    player.sendMessage(formatted("§c", "easypets.debug.region.no_directory"), false);
                    return;
                }

                Optional<Path> firstRegion = Files.list(entitiesPath)
                        .filter(path -> path.toString().endsWith(".mca"))
                        .findFirst();

                if (firstRegion.isEmpty()) {
                    player.sendMessage(formatted("§c", "easypets.debug.region.no_files"), false);
                    return;
                }

                Path regionPath = firstRegion.get();
                player.sendMessage(formatted("§7", "easypets.debug.region.debugging", regionPath.getFileName()), false);

                debugSingleRegionFile(regionPath, player);

            } catch (Exception e) {
                player.sendMessage(formatted("§c", "easypets.debug.region.error", e.getMessage()), false);
                e.printStackTrace();
            }
        });
    }

    private static void debugSingleRegionFile(Path regionPath, ServerPlayerEntity player) throws IOException {
        String fileName = regionPath.getFileName().toString();
        String[] parts = fileName.replace(".mca", "").split("\\.");
        if (parts.length != 3) {
            player.sendMessage(formatted("§c", "easypets.debug.region.invalid_format"), false);
            return;
        }

        int regionX = Integer.parseInt(parts[1]);
        int regionZ = Integer.parseInt(parts[2]);
        player.sendMessage(formatted("§7", "easypets.debug.region.coordinates", regionX, regionZ), false);

        StorageKey storageKey = new StorageKey("entities", player.getEntityWorld().getRegistryKey(), "entities");

        try (RegionFile regionFile = new RegionFile(storageKey, regionPath, regionPath.getParent(), false)) {
            int totalChunks = 0;
            int chunksWithData = 0;

            for (int x = 0; x < 5 && x < 32; x++) {
                for (int z = 0; z < 5 && z < 32; z++) {
                    ChunkPos chunkPos = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);
                    totalChunks++;

                    if (regionFile.hasChunk(chunkPos)) {
                        chunksWithData++;
                        player.sendMessage(formatted("§7", "easypets.debug.region.chunk_has_data", chunkPos), false);

                        try (DataInputStream inputStream = regionFile.getChunkInputStream(chunkPos)) {
                            if (inputStream != null) {
                                NbtCompound chunkNbt = NbtIo.readCompound(inputStream, NbtSizeTracker.ofUnlimitedBytes());
                                if (chunkNbt != null) {
                                    player.sendMessage(formatted("§7", "easypets.debug.region.chunk_keys", chunkNbt.getKeys().toString()), false);

                                    if (chunkNbt.contains("Entities")) {
                                        Optional<NbtList> entitiesOpt = chunkNbt.getList("Entities");
                                        if (entitiesOpt.isPresent()) {
                                            NbtList entities = entitiesOpt.get();
                                            player.sendMessage(formatted("§a", "easypets.debug.region.entities_found", entities.size(), chunkPos), false);

                                            for (int i = 0; i < Math.min(3, entities.size()); i++) {
                                                Optional<NbtCompound> entityOpt = entities.getCompound(i);
                                                if (entityOpt.isPresent()) {
                                                    NbtCompound entity = entityOpt.get();
                                                    String entityId = entity.getString("id", "unknown");
                                                    player.sendMessage(formatted("§f", "easypets.debug.region.entity_info", i, entityId), false);
                                                    player.sendMessage(formatted("§f", "easypets.debug.region.entity_keys", entity.getKeys().toString()), false);

                                                    if (entityId.contains("wolf") || entityId.contains("cat") || entityId.contains("parrot")) {
                                                        player.sendMessage(formatted("§6", "easypets.debug.region.potential_pet"), false);
                                                        entity.getKeys().forEach(key -> {
                                                            if (key.toLowerCase().contains("owner") || key.toLowerCase().contains("tame") || key.toLowerCase().contains("sit") || key.toLowerCase().contains("allowedtofollow") || key.toLowerCase().contains("indypets")) {
                                                                player.sendMessage(formatted("§6", "easypets.debug.region.pet_data", key, entity.get(key).toString()), false);
                                                            }
                                                        });
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        player.sendMessage(formatted("§c", "easypets.debug.region.no_entities_key"), false);
                                    }
                                } else {
                                    player.sendMessage(formatted("§c", "easypets.debug.region.nbt_read_error"), false);
                                }
                            }
                        } catch (Exception e) {
                            player.sendMessage(formatted("§c", "easypets.debug.region.chunk_error", chunkPos, e.getMessage()), false);
                        }
                    }
                }
            }

            player.sendMessage(formatted("§7", "easypets.debug.region.summary", totalChunks, chunksWithData), false);
        }
    }


    private static void recoverPlayerPets(ServerPlayerEntity player, boolean locateOnly) {
        CompletableFuture.runAsync(() -> {
            UUID playerUUID = player.getUuid();
            try {
                Config config = Config.getInstance();
                String operation = locateOnly ? "pet location scan" : "pet recovery";

                // Handle world save if enabled using the new SaveUtil
                if ((locateOnly && config.shouldSaveOnLocate()) || (!locateOnly && config.shouldSaveOnRecovery())) {
                    player.sendMessage(formatted("§7", "easypets.recovery.saving_world"));

                    try {
                        // Use the new SaveUtil which executes vanilla save-all flush command
                        Boolean saveResult = SaveUtil.triggerFullSave(player.getEntityWorld().getServer()).get();

                        if (saveResult) {
                            player.sendMessage(formatted("§a", "easypets.recovery.save_complete"));
                            if (config.isDebugLoggingEnabled()) {
                                System.out.println("[EasyPets] World save completed successfully for " + operation);
                            }
                            // Give save operation time to complete fully
                            //Thread.sleep(2000);
                        } else {
                            player.sendMessage(formatted("§c", "easypets.recovery.save_failed"));
                            if (config.isDebugLoggingEnabled()) {
                                System.out.println("[EasyPets] Save operation failed for player: " + player.getGameProfile().name());
                            }
                        }
                    } catch (Exception e) {
                        player.sendMessage(formatted("§c", "easypets.recovery.save_error"));
                        if (config.isDebugLoggingEnabled()) {
                            System.out.println("[EasyPets] Save operation exception: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                } else {
                    if (config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] Skipping world save for " + operation + " (disabled in config)");
                    }
                }

                if (locateOnly) {
                    if (config.shouldSaveOnLocate()) {
                        player.sendMessage(formatted("§a", "easypets.recovery.scanning_locate"));
                    } else {
                        player.sendMessage(formatted("§e", "easypets.recovery.locate_warning"));
                        player.sendMessage(formatted("§a", "easypets.recovery.scanning_locate"));
                    }
                } else {
                    player.sendMessage(formatted("§a", "easypets.recovery.scanning_recover"));
                }

                // Rest of the pet scanning logic remains the same...
                List<PetInfo> standingPets = new ArrayList<>();
                List<PetInfo> sittingPets = new ArrayList<>();
                List<PetInfo> roamingPets = new ArrayList<>();
                List<PetInfo> independentPets = new ArrayList<>();
                Set<UUID> foundPetUUIDs = new HashSet<>();
                int totalFiles = 0;
                int totalChunks = 0;

                int totalRegionFiles = countTotalRegionFiles(player);
                int processedFiles = 0;

                for (ServerWorld world : player.getEntityWorld().getServer().getWorlds()) {
                    int[] counts = scanWorldForPets(player, world, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs, processedFiles, totalRegionFiles);
                    totalFiles += counts[0];
                    totalChunks += counts[1];
                    processedFiles += counts[0];
                }

                Path worldPath = player.getEntityWorld().getServer().getSavePath(WorldSavePath.ROOT).normalize();
                int[] additionalCounts = scanAdditionalDimensions(player, worldPath, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs);
                totalFiles += additionalCounts[0];
                totalChunks += additionalCounts[1];

                player.sendMessage(Text.empty(), true);
                player.sendMessage(formatted("§7", "easypets.recovery.scanned_summary", totalFiles, totalChunks));

                // Update positions of loaded pets for more accurate data and add any loaded pets not found in file scan
                Set<UUID> loadedPetUUIDs = new HashSet<>();
                if (locateOnly) {
                    int updatedCount = updateLoadedPetPositions(player, standingPets, sittingPets, roamingPets, independentPets, loadedPetUUIDs, foundPetUUIDs);
                    if (updatedCount > 0 && config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] Updated positions for " + updatedCount + " loaded pets");
                    }
                    
                    // Add any loaded pets that weren't found in the file scan
                    int addedCount = addMissingLoadedPets(player, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs, loadedPetUUIDs);
                    if (addedCount > 0 && config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] Added " + addedCount + " loaded pets that weren't in file scan");
                    }
                }

                if (standingPets.isEmpty() && sittingPets.isEmpty() && roamingPets.isEmpty() && independentPets.isEmpty()) {
                    player.sendMessage(formatted("§e", "easypets.recovery.no_pets_found"));
                    return;
                }

                if (locateOnly) {
                    reportPetLocations(player, standingPets, sittingPets, roamingPets, independentPets, loadedPetUUIDs);
                } else {
                    loadPetChunks(player, standingPets, sittingPets, roamingPets, independentPets);
                }

            } catch (Exception e) {
                player.sendMessage(Text.empty(), true);
                player.sendMessage(formatted("§c", "easypets.recovery.error", e.getMessage()));
                Config config = Config.getInstance();
                if (config.isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Exception in recoverPlayerPets: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                synchronized (playersCurrentlyScanning) {
                    playersCurrentlyScanning.remove(playerUUID);
                }
                if (Config.getInstance().isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Removed player " + player.getGameProfile().name() + " from scanning set");
                }
            }
        });
    }

    /**
     * Updates pet positions in the lists by checking currently loaded entities.
     * Loaded pets have more current positions than those saved in chunk NBT data.
     * @param loadedPetUUIDs Set to populate with UUIDs of loaded pets
     * @param foundPetUUIDs Set of pet UUIDs found in file scan
     * @return Number of pets whose positions were updated
     */
    private static int updateLoadedPetPositions(ServerPlayerEntity player, List<PetInfo> standingPets,
                                                 List<PetInfo> sittingPets, List<PetInfo> roamingPets,
                                                 List<PetInfo> independentPets, Set<UUID> loadedPetUUIDs,
                                                 Set<UUID> foundPetUUIDs) {
        int updatedCount = 0;
        Map<UUID, TameableEntity> loadedPets = new HashMap<>();

        // First, collect all loaded pets owned by the player
        for (ServerWorld world : player.getEntityWorld().getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet) {
                    if (pet.isTamed() && pet.getOwner() == player) {
                        loadedPets.put(pet.getUuid(), pet);
                        loadedPetUUIDs.add(pet.getUuid());
                    }
                }
            }
        }

        // Update positions in all pet lists
        updatedCount += updatePetListPositions(standingPets, loadedPets);
        updatedCount += updatePetListPositions(sittingPets, loadedPets);
        updatedCount += updatePetListPositions(roamingPets, loadedPets);
        updatedCount += updatePetListPositions(independentPets, loadedPets);

        return updatedCount;
    }

    /**
     * Adds loaded pets that weren't found in the file scan.
     * This handles pets that have moved to unsaved chunks (e.g., following a fast-moving player).
     * @return Number of pets added
     */
    private static int addMissingLoadedPets(ServerPlayerEntity player, List<PetInfo> standingPets,
                                            List<PetInfo> sittingPets, List<PetInfo> roamingPets,
                                            List<PetInfo> independentPets, Set<UUID> foundPetUUIDs,
                                            Set<UUID> loadedPetUUIDs) {
        int addedCount = 0;

        // Iterate through all loaded pets and add any that weren't found in the file scan
        for (ServerWorld world : player.getEntityWorld().getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet) {
                    if (pet.isTamed() && pet.getOwner() == player) {
                        UUID petUUID = pet.getUuid();
                        
                        // If this pet wasn't found in the file scan, add it now
                        if (!foundPetUUIDs.contains(petUUID)) {
                            PetInfo petInfo = createPetInfoFromEntity(pet, world);
                            if (petInfo != null) {
                                categorizePet(petInfo, standingPets, sittingPets, roamingPets, independentPets);
                                foundPetUUIDs.add(petUUID);
                                addedCount++;
                            }
                        }
                    }
                }
            }
        }

        return addedCount;
    }

    /**
     * Creates a PetInfo object from a loaded entity
     */
    private static PetInfo createPetInfoFromEntity(TameableEntity pet, ServerWorld world) {
        try {
            String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
            String customName = pet.hasCustomName() ? pet.getCustomName().getString() : null;
            double x = pet.getX();
            double y = pet.getY();
            double z = pet.getZ();
            ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
            boolean sitting = pet.isSitting();
            boolean isLeashed = pet.isLeashed();
            boolean inVehicle = pet.hasVehicle();
            boolean isIndependent = isIndependentPet(pet);
            
            // Try to get IndyPets home position if available
            boolean hasHomePos = false;
            int homeX = 0, homeY = 0, homeZ = 0;
            // Note: We can't easily get home position from the entity without NBT access
            // This is acceptable since these are loaded pets that weren't in the file scan
            
            return new PetInfo(pet.getUuid(), entityId, customName, x, y, z, chunkPos, world,
                             sitting, isLeashed, inVehicle, isIndependent, hasHomePos, homeX, homeY, homeZ);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper method to update positions in a specific pet list
     */
    private static int updatePetListPositions(List<PetInfo> petList, Map<UUID, TameableEntity> loadedPets) {
        int updated = 0;
        for (int i = 0; i < petList.size(); i++) {
            PetInfo pet = petList.get(i);
            TameableEntity loadedPet = loadedPets.get(pet.uuid);

            if (loadedPet != null) {
                // Pet is loaded - use its current position
                double currentX = loadedPet.getX();
                double currentY = loadedPet.getY();
                double currentZ = loadedPet.getZ();

                // Check if position has changed significantly (more than 0.1 blocks)
                if (Math.abs(currentX - pet.x) > 0.1 || Math.abs(currentY - pet.y) > 0.1 || Math.abs(currentZ - pet.z) > 0.1) {
                    ChunkPos currentChunkPos = new ChunkPos(loadedPet.getBlockPos());
                    ServerWorld currentWorld = (ServerWorld) loadedPet.getEntityWorld();
                    boolean currentSitting = loadedPet.isSitting();
                    boolean currentLeashed = loadedPet.isLeashed();
                    boolean currentInVehicle = loadedPet.hasVehicle();

                    // Create updated PetInfo with current position and state
                    PetInfo updatedPet = new PetInfo(
                            pet.uuid, pet.type, pet.customName,
                            currentX, currentY, currentZ,
                            currentChunkPos, currentWorld,
                            currentSitting, currentLeashed, currentInVehicle,
                            pet.isIndependent, pet.hasHomePos, pet.homeX, pet.homeY, pet.homeZ
                    );

                    petList.set(i, updatedPet);
                    updated++;
                }
            }
        }
        return updated;
    }

    private static int countTotalRegionFiles(ServerPlayerEntity player) {
        int total = 0;
        try {
            Path worldPath = player.getEntityWorld().getServer().getSavePath(WorldSavePath.ROOT).normalize();

            for (ServerWorld world : player.getEntityWorld().getServer().getWorlds()) {
                List<Path> possiblePaths = getPossibleEntityPaths(world, worldPath);
                for (Path entitiesPath : possiblePaths) {
                    if (Files.exists(entitiesPath)) {
                        total += (int) Files.list(entitiesPath)
                                .filter(path -> path.toString().endsWith(".mca"))
                                .count();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Return 0 if can't count
        }
        return total;
    }

    private static void updateProgressBar(ServerPlayerEntity player, int processed, int total) {
        if (total == 0) return;

        double percentage = (double) processed / total * 100;
        int barLength = 20;
        int filledLength = (int) (percentage / 100 * barLength);

        StringBuilder bar = new StringBuilder("§7[");
        for (int i = 0; i < barLength; i++) {
            if (i < filledLength) {
                bar.append("§a■");
            } else {
                bar.append("§8■");
            }
        }
        bar.append("§7] §e").append(String.format("%.1f", percentage)).append("%");
        bar.append(" §7(").append(processed).append("/").append(total).append(")");

        player.sendMessage(Text.literal(bar.toString()), true);
    }

    private static void reportPetLocations(ServerPlayerEntity player, List<PetInfo> standingPets,
                                           List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets,
                                           Set<UUID> loadedPetUUIDs) {
        player.sendMessage(Text.literal("§a=== " + TranslationManager.getInstance().translate("easypets.locator.title") + " ==="), false);
        if (!loadedPetUUIDs.isEmpty()) {
            player.sendMessage(formatted("§7", "easypets.locator.loaded_symbol"), false);
        }

        Map<String, List<PetInfo>> standingByDimension = standingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> sittingByDimension = sittingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> roamingByDimension = roamingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> independentByDimension = independentPets.stream()
                .collect(groupingBy(pet -> pet.worldName));

        Set<String> allDimensions = new HashSet<>();
        allDimensions.addAll(standingByDimension.keySet());
        allDimensions.addAll(sittingByDimension.keySet());
        allDimensions.addAll(roamingByDimension.keySet());
        allDimensions.addAll(independentByDimension.keySet());

        if (allDimensions.isEmpty()) {
            player.sendMessage(formatted("§7", "easypets.locator.no_pets"), false);
            return;
        }

        List<String> sortedDimensions = allDimensions.stream().sorted().toList();

        for (String dimension : sortedDimensions) {
            String dimensionColor = getDimensionColor(dimension);
            player.sendMessage(Text.empty(), false);
            player.sendMessage(Text.literal(dimensionColor + "=== " + dimension.toUpperCase() + " ==="), false);

            List<PetInfo> standingInDim = standingByDimension.getOrDefault(dimension, List.of());
            if (!standingInDim.isEmpty()) {
                player.sendMessage(formatted("§2", "easypets.locator.following", standingInDim.size()), false);
                for (PetInfo pet : standingInDim) {
                    String status = getRestrictedPetStatus(pet);
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.literal("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString() + status), false);
                }
            }

            List<PetInfo> sittingInDim = sittingByDimension.getOrDefault(dimension, List.of());
            if (!sittingInDim.isEmpty()) {
                player.sendMessage(formatted("§9", "easypets.locator.sitting", sittingInDim.size()), false);
                for (PetInfo pet : sittingInDim) {
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.literal("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            List<PetInfo> roamingInDim = roamingByDimension.getOrDefault(dimension, List.of());
            if (!roamingInDim.isEmpty()) {
                player.sendMessage(formatted("§6", "easypets.locator.roaming", roamingInDim.size()), false);
                for (PetInfo pet : roamingInDim) {
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.literal("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            List<PetInfo> independentInDim = independentByDimension.getOrDefault(dimension, List.of());
            if (!independentInDim.isEmpty()) {
                player.sendMessage(formatted("§d", "easypets.locator.independent", independentInDim.size()), false);
                for (PetInfo pet : independentInDim) {
                    String homeInfo = pet.hasHomePos ? TranslationManager.getInstance().translate("easypets.locator.home_pos", pet.getHomePosString()) : "";
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.literal("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString() + homeInfo), false);
                }
            }

            if (standingInDim.isEmpty() && sittingInDim.isEmpty() && roamingInDim.isEmpty() && independentInDim.isEmpty()) {
                player.sendMessage(formatted("§8", "easypets.locator.no_dimension_pets"), false);
            }
        }

        player.sendMessage(Text.empty(), false);
        int totalPets = standingPets.size() + sittingPets.size() + roamingPets.size() + independentPets.size();

        List<String> summaryParts = new ArrayList<>();
        if (standingPets.size() > 0) summaryParts.add("§2" + standingPets.size() + " " + TranslationManager.getInstance().translate("easypets.locator.summary_following"));
        if (sittingPets.size() > 0) summaryParts.add("§9" + sittingPets.size() + " " + TranslationManager.getInstance().translate("easypets.locator.summary_sitting"));
        if (roamingPets.size() > 0) summaryParts.add("§6" + roamingPets.size() + " " + TranslationManager.getInstance().translate("easypets.locator.summary_roaming"));
        if (independentPets.size() > 0) summaryParts.add("§d" + independentPets.size() + " " + TranslationManager.getInstance().translate("easypets.locator.summary_independent"));

        if (summaryParts.isEmpty()) {
            player.sendMessage(formatted("§7", "easypets.locator.total", "§e0 pets"), false);
        } else {
            String summaryText = String.join(" §7+ ", summaryParts);
            player.sendMessage(Text.literal("§7" + TranslationManager.getInstance().translate("easypets.locator.total") + ": " + summaryText + " §7" + TranslationManager.getInstance().translate("easypets.locator.total_pets", "§e" + totalPets)), false);
        }
    }

    private static String getRestrictedPetStatus(PetInfo pet) {
        if (pet.inVehicle) {
            return TranslationManager.getInstance().translate("easypets.locator.status_vehicle");
        } else if (pet.isLeashed) {
            return TranslationManager.getInstance().translate("easypets.locator.status_leashed");
        }
        return "";
    }

    private static String getDimensionColor(String dimension) {
        return switch (dimension.toLowerCase()) {
            case "overworld" -> "§a";
            case "nether", "the_nether" -> "§c";
            case "end", "the_end" -> "§5";
            default -> "§6";
        };
    }

    private static String getWorldDisplayName(ServerWorld world) {
        String worldName = world.getRegistryKey().getValue().toString();

        if (worldName.equals("minecraft:overworld")) {
            return "overworld";
        } else if (worldName.equals("minecraft:the_nether")) {
            return "nether";
        } else if (worldName.equals("minecraft:the_end")) {
            return "end";
        } else {
            if (worldName.contains(":")) {
                String[] parts = worldName.split(":");
                if (parts.length >= 2) {
                    String namespace = parts[0];
                    String path = parts[1];

                    if (namespace.equals("minecraft")) {
                        return path;
                    } else {
                        return namespace + ":" + path;
                    }
                }
            }
            return worldName.replace("minecraft:", "");
        }
    }

    private static void loadPetChunks(ServerPlayerEntity player, List<PetInfo> standingPets,
                                      List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        Set<ChunkPos> chunksLoaded = new HashSet<>();
        int petsToRecover = 0;
        int restrictedPets = 0;

        for (PetInfo pet : standingPets) {
            if (pet.isLeashed || pet.inVehicle) {
                restrictedPets++;
                continue;
            }

            ChunkPos chunkPos = pet.chunkPos;
            if (!chunksLoaded.contains(chunkPos)) {
                pet.world.getChunkManager().addTicket(
                        PetChunkTickets.PET_TICKET_TYPE,
                        chunkPos,
                        3
                );
                chunksLoaded.add(chunkPos);

                scheduleChunkCleanup(pet.world, chunkPos, 1200);
            }
            petsToRecover++;
        }

        Text message;

        if (petsToRecover > 0) {
            message = formatted("§a", "easypets.recovery.found_pets", petsToRecover);
        } else {
            message = formatted("§e", "easypets.recovery.no_following_pets");
        }

        if (restrictedPets > 0) {
            message = message.copy().append(Text.literal("\n")).append(formatted("§c", "easypets.recovery.restricted_pets", restrictedPets));
        }

        if (!roamingPets.isEmpty()) {
            message = message.copy().append(Text.literal("\n")).append(formatted("§6", "easypets.recovery.roaming_pets", roamingPets.size()));
        }

        if (!independentPets.isEmpty()) {
            message = message.copy().append(Text.literal("\n")).append(formatted("§d", "easypets.recovery.independent_pets", independentPets.size()));
        }

        if (!sittingPets.isEmpty()) {
            message = message.copy().append(Text.literal("\n")).append(formatted("§3", "easypets.recovery.sitting_pets", sittingPets.size()));
        }

        message = message.copy().append(Text.literal("\n")).append(formatted("§3", "easypets.recovery.use_locator"));

        player.sendMessage(message, false);
    }

    private static int[] scanWorldForPets(ServerPlayerEntity player, ServerWorld world,
                                          List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets, Set<UUID> foundPetUUIDs,
                                          int processedFiles, int totalFiles) {
        int filesScanned = 0;
        int chunksScanned = 0;

        try {
            Path worldPath = player.getEntityWorld().getServer().getSavePath(WorldSavePath.ROOT).normalize();
            List<Path> possibleEntityPaths = getPossibleEntityPaths(world, worldPath);

            for (Path entitiesPath : possibleEntityPaths) {
                if (!Files.exists(entitiesPath)) {
                    continue;
                }

                List<Path> regionFiles = Files.list(entitiesPath)
                        .filter(path -> path.toString().endsWith(".mca"))
                        .toList();

                if (!regionFiles.isEmpty()) {
                    for (Path regionPath : regionFiles) {
                        try {
                            updateProgressBar(player, processedFiles + filesScanned, totalFiles);
                            int chunks = scanRegionFileForPets(regionPath, player.getUuid(), world, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs);
                            filesScanned++;
                            chunksScanned += chunks;
                        } catch (Exception e) {
                            player.sendMessage(formatted("§c", "easypets.scan.region_error", regionPath.getFileName(), e.getMessage()), false);
                        }
                    }

                    String worldDisplayName = getWorldDisplayName(world);
                    player.sendMessage(formatted("§7", "easypets.scan.world_found", regionFiles.size(), worldDisplayName), false);

                    break;
                }
            }

        } catch (Exception e) {
            player.sendMessage(formatted("§c", "easypets.scan.world_error", world.getRegistryKey().getValue(), e.getMessage()), false);
        }

        return new int[]{filesScanned, chunksScanned};
    }

    private static List<Path> getPossibleEntityPaths(ServerWorld world, Path worldPath) {
        List<Path> possibleEntityPaths = new ArrayList<>();
        String worldName = world.getRegistryKey().getValue().toString();

        if (worldName.equals("minecraft:overworld")) {
            possibleEntityPaths.add(worldPath.resolve("entities"));
        } else if (worldName.equals("minecraft:the_nether")) {
            possibleEntityPaths.add(worldPath.resolve("DIM-1").resolve("entities"));
            possibleEntityPaths.add(worldPath.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("entities"));
        } else if (worldName.equals("minecraft:the_end")) {
            possibleEntityPaths.add(worldPath.resolve("DIM1").resolve("entities"));
            possibleEntityPaths.add(worldPath.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("entities"));
        } else {
            possibleEntityPaths.add(worldPath.resolve("dimensions")
                    .resolve(world.getRegistryKey().getValue().getNamespace())
                    .resolve(world.getRegistryKey().getValue().getPath())
                    .resolve("entities"));
        }

        return possibleEntityPaths;
    }

    private static int[] scanAdditionalDimensions(ServerPlayerEntity player, Path worldPath,
                                                  List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets, Set<UUID> foundPetUUIDs) {
        int filesScanned = 0;
        int chunksScanned = 0;

        try {
            Set<String> knownDimensions = new HashSet<>();
            knownDimensions.add("overworld");
            knownDimensions.add("the_nether");
            knownDimensions.add("the_end");

            Path dimensionsPath = worldPath.resolve("dimensions");
            if (Files.exists(dimensionsPath)) {
                Files.walk(dimensionsPath, 3)
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals("entities"))
                        .forEach(entitiesPath -> {
                            try {
                                Path dimensionPath = entitiesPath.getParent();
                                Path namespacePath = dimensionPath.getParent();
                                String namespace = namespacePath.getFileName().toString();
                                String dimensionName = dimensionPath.getFileName().toString();
                                String fullDimensionName = namespace + ":" + dimensionName;

                                if (!knownDimensions.contains(dimensionName)) {
                                    player.sendMessage(formatted("§7", "easypets.scan.custom_dimension", fullDimensionName), false);

                                    List<Path> regionFiles = Files.list(entitiesPath)
                                            .filter(path -> path.toString().endsWith(".mca"))
                                            .toList();

                                    if (!regionFiles.isEmpty()) {
                                        player.sendMessage(formatted("§7", "easypets.scan.custom_dimension_files", regionFiles.size(), fullDimensionName), false);
                                    }
                                }
                            } catch (Exception e) {
                                // Skip problematic dimension folders
                            }
                        });
            }

            Set<String> processedWorlds = new HashSet<>();
            for (ServerWorld world : player.getEntityWorld().getServer().getWorlds()) {
                String worldName = world.getRegistryKey().getValue().toString();
                processedWorlds.add(worldName);
            }

            if (!processedWorlds.contains("minecraft:the_nether")) {
                checkLegacyDimension(player, worldPath.resolve("DIM-1").resolve("entities"), "nether", standingPets, sittingPets, roamingPets, independentPets);
            }
            if (!processedWorlds.contains("minecraft:the_end")) {
                checkLegacyDimension(player, worldPath.resolve("DIM1").resolve("entities"), "end", standingPets, sittingPets, roamingPets, independentPets);
            }

        } catch (Exception e) {
            player.sendMessage(formatted("§c", "easypets.scan.additional_error", e.getMessage()), false);
        }

        return new int[]{filesScanned, chunksScanned};
    }

    private static void checkLegacyDimension(ServerPlayerEntity player, Path entitiesPath, String dimensionName,
                                             List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        try {
            if (Files.exists(entitiesPath)) {
                List<Path> regionFiles = Files.list(entitiesPath)
                        .filter(path -> path.toString().endsWith(".mca"))
                        .toList();

                if (!regionFiles.isEmpty()) {
                    player.sendMessage(formatted("§7", "easypets.scan.world_found", regionFiles.size(), dimensionName), false);
                }
            }
        } catch (Exception e) {
            // Skip problematic legacy dimensions
        }
    }

    private static int scanRegionFileForPets(Path regionPath, UUID playerUUID, ServerWorld world,
                                             List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets, Set<UUID> foundPetUUIDs) throws IOException {
        String fileName = regionPath.getFileName().toString();
        String[] parts = fileName.replace(".mca", "").split("\\.");
        if (parts.length != 3) return 0;

        int chunksScanned = 0;

        try {
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);

            StorageKey storageKey = new StorageKey("entities", world.getRegistryKey(), "entities");
            try (RegionFile regionFile = new RegionFile(storageKey, regionPath, regionPath.getParent(), false)) {

                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos chunkPos = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);

                        if (regionFile.hasChunk(chunkPos)) {
                            chunksScanned++;
                            try (DataInputStream inputStream = regionFile.getChunkInputStream(chunkPos)) {
                                if (inputStream != null) {
                                    NbtCompound chunkNbt = NbtIo.readCompound(inputStream, NbtSizeTracker.ofUnlimitedBytes());
                                    if (chunkNbt != null) {
                                        parseChunkForPets(chunkNbt, playerUUID, world, chunkPos, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs);
                                    }
                                }
                            } catch (Exception e) {
                                // Skip problematic chunks but continue
                            }
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            // Invalid region file name format
        }

        return chunksScanned;
    }

    private static void parseChunkForPets(NbtCompound chunkNbt, UUID playerUUID, ServerWorld world,
                                          ChunkPos chunkPos, List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets, Set<UUID> foundPetUUIDs) {
        if (!chunkNbt.contains("Entities")) return;

        Optional<NbtList> entitiesOpt = chunkNbt.getList("Entities");
        if (entitiesOpt.isEmpty()) return;

        NbtList entities = entitiesOpt.get();

        for (int i = 0; i < entities.size(); i++) {
            Optional<NbtCompound> entityOpt = entities.getCompound(i);
            if (entityOpt.isEmpty()) continue;

            NbtCompound entity = entityOpt.get();

            if (isTameablePetOwnedByPlayer(entity, playerUUID)) {
                PetInfo petInfo = createPetInfoFromNBT(entity, chunkPos, world);
                if (petInfo != null && !foundPetUUIDs.contains(petInfo.uuid)) {
                    foundPetUUIDs.add(petInfo.uuid);
                    categorizePet(petInfo, standingPets, sittingPets, roamingPets, independentPets);
                }
            }

            if (entity.contains("Passengers")) {
                Optional<NbtList> passengersOpt = entity.getList("Passengers");
                if (passengersOpt.isPresent()) {
                    NbtList passengers = passengersOpt.get();

                    for (int j = 0; j < passengers.size(); j++) {
                        Optional<NbtCompound> passengerOpt = passengers.getCompound(j);
                        if (passengerOpt.isPresent()) {
                            NbtCompound passenger = passengerOpt.get();

                            if (isTameablePetOwnedByPlayer(passenger, playerUUID)) {
                                PetInfo petInfo = createPetInfoFromNBT(passenger, chunkPos, world);
                                if (petInfo != null && !foundPetUUIDs.contains(petInfo.uuid)) {
                                    foundPetUUIDs.add(petInfo.uuid);
                                    PetInfo vehiclePetInfo = new PetInfo(
                                            petInfo.uuid, petInfo.type, petInfo.customName,
                                            petInfo.x, petInfo.y, petInfo.z, petInfo.chunkPos,
                                            petInfo.world, petInfo.sitting, petInfo.isLeashed, true,
                                            petInfo.isIndependent, petInfo.hasHomePos, petInfo.homeX, petInfo.homeY, petInfo.homeZ
                                    );
                                    categorizePet(vehiclePetInfo, standingPets, sittingPets, roamingPets, independentPets);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void categorizePet(PetInfo petInfo, List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        String entityId = petInfo.type;

        if (petInfo.sitting) {
            sittingPets.add(petInfo);
        } else if (petInfo.isIndependent) {
            independentPets.add(petInfo);
        } else if (ROAMING_PET_TYPES.contains(entityId)) {
            roamingPets.add(petInfo);
        } else {
            standingPets.add(petInfo);
        }
    }

    private static PetInfo createPetInfoFromNBT(NbtCompound entity, ChunkPos chunkPos, ServerWorld world) {
        Optional<NbtList> posOpt = entity.getList("Pos");
        if (posOpt.isEmpty()) return null;

        NbtList pos = posOpt.get();
        if (pos.size() < 3) return null;

        Optional<Double> xOpt = pos.getDouble(0);
        Optional<Double> yOpt = pos.getDouble(1);
        Optional<Double> zOpt = pos.getDouble(2);

        if (xOpt.isEmpty() || yOpt.isEmpty() || zOpt.isEmpty()) return null;

        double x = xOpt.get();
        double y = yOpt.get();
        double z = zOpt.get();

        boolean sitting = entity.getByte("Sitting", (byte)0) != 0;
        String entityId = entity.getString("id", "unknown");

        Optional<int[]> uuidArrayOpt = entity.getIntArray("UUID");
        if (uuidArrayOpt.isEmpty() || uuidArrayOpt.get().length != 4) return null;

        int[] uuidArray = uuidArrayOpt.get();
        long mostSigBits = (long)uuidArray[0] << 32 | (long)uuidArray[1] & 0xFFFFFFFFL;
        long leastSigBits = (long)uuidArray[2] << 32 | (long)uuidArray[3] & 0xFFFFFFFFL;
        UUID petUUID = new UUID(mostSigBits, leastSigBits);

        String customName = null;
        if (entity.contains("CustomName")) {
            customName = entity.getString("CustomName", null);
            if (customName != null && customName.startsWith("\"") && customName.endsWith("\"")) {
                customName = customName.substring(1, customName.length() - 1);
            }
        }

        boolean isLeashed = entity.contains("leash");
        boolean isIndependent = IndyPetsHelper.isIndependentFromNBT(entity);

        boolean hasHomePos = false;
        int homeX = 0, homeY = 0, homeZ = 0;
        int[] homePos = IndyPetsHelper.getIndyPetsHomePos(entity);
        if (homePos != null) {
            hasHomePos = true;
            homeX = homePos[0];
            homeY = homePos[1];
            homeZ = homePos[2];
        }

        boolean inVehicle = false;

        return new PetInfo(petUUID, entityId, customName, x, y, z, chunkPos, world, sitting, isLeashed, inVehicle, isIndependent, hasHomePos, homeX, homeY, homeZ);
    }

    private static boolean isTameablePetOwnedByPlayer(NbtCompound entity, UUID playerUUID) {
        if (!entity.contains("Owner")) {
            return false;
        }

        String entityId = entity.getString("id", "");

        if (entityId.contains("arrow") || entityId.contains("trident") ||
                entityId.contains("firework") || entityId.contains("fireball") ||
                entityId.contains("snowball") || entityId.contains("egg") ||
                entityId.contains("potion") || entityId.contains("pearl") ||
                entityId.contains("llama_spit") || entityId.contains("shulker_bullet")) {
            return false;
        }

        boolean hasTameableFields = entity.contains("Sitting") ||
                entity.contains("Tame") ||
                entity.contains("CollarColor") ||
                entity.contains("variant") ||
                entity.contains("Bred");

        if (!hasTameableFields) {
            return false;
        }

        Optional<int[]> ownerArrayOpt = entity.getIntArray("Owner");
        if (ownerArrayOpt.isEmpty() || ownerArrayOpt.get().length != 4) {
            return false;
        }

        int[] ownerArray = ownerArrayOpt.get();
        long mostSigBits = (long)ownerArray[0] << 32 | (long)ownerArray[1] & 0xFFFFFFFFL;
        long leastSigBits = (long)ownerArray[2] << 32 | (long)ownerArray[3] & 0xFFFFFFFFL;
        UUID ownerUUID = new UUID(mostSigBits, leastSigBits);

        return playerUUID.equals(ownerUUID);
    }

    private static void scheduleChunkCleanup(ServerWorld world, ChunkPos chunkPos, int delayTicks) {
        Config config = Config.getInstance();

        world.getServer().execute(() -> {
            new Thread(() -> {
                try {
                    int actualDelay = Math.max(delayTicks, 1200);
                    Thread.sleep(actualDelay * 50L);

                    world.getServer().execute(() -> {
                        world.getChunkManager().removeTicket(
                                PetChunkTickets.PET_TICKET_TYPE,
                                chunkPos,
                                config.getMaxChunkDistance()
                        );
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    private static boolean isIndependentPet(TameableEntity pet) {
        if (!IndyPetsHelper.isIndyPetsLoaded()) {
            return false;
        }
        return IndyPetsHelper.isPetIndependent(pet);
    }

    private static class PetInfo {
        final UUID uuid;
        final String type;
        final String customName;
        final double x, y, z;
        final ChunkPos chunkPos;
        final ServerWorld world;
        final String worldName;
        final boolean sitting;
        final boolean isLeashed;
        final boolean inVehicle;
        final boolean isIndependent;
        final boolean hasHomePos;
        final int homeX, homeY, homeZ;

        PetInfo(UUID uuid, String type, String customName, double x, double y, double z,
                ChunkPos chunkPos, ServerWorld world, boolean sitting, boolean isLeashed, boolean inVehicle,
                boolean isIndependent, boolean hasHomePos, int homeX, int homeY, int homeZ) {
            this.uuid = uuid;
            this.type = type;
            this.customName = customName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.chunkPos = chunkPos;
            this.world = world;
            this.worldName = world.getRegistryKey().getValue().toString().replace("minecraft:", "");
            this.sitting = sitting;
            this.isLeashed = isLeashed;
            this.inVehicle = inVehicle;
            this.isIndependent = isIndependent;
            this.hasHomePos = hasHomePos;
            this.homeX = homeX;
            this.homeY = homeY;
            this.homeZ = homeZ;
        }

        String getDisplayName() {
            String typeName = type.replace("minecraft:", "");
            typeName = typeName.substring(0, 1).toUpperCase() + typeName.substring(1);

            if (customName != null && !customName.isEmpty()) {
                return customName + " (" + typeName + ")";
            }
            return typeName;
        }

        String getLocationString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }

        String getHomePosString() {
            if (hasHomePos) {
                return String.format("%d, %d, %d", homeX, homeY, homeZ);
            }
            return "Unknown";
        }
    }

    private static class PetDetails {
        final UUID uuid;
        final String type;
        final String displayName;
        final double x, y, z;
        final String worldName;
        final boolean sitting;

        PetDetails(UUID uuid, String type, String displayName, double x, double y, double z, String worldName, boolean sitting) {
            this.uuid = uuid;
            this.type = type;
            this.displayName = displayName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.worldName = worldName;
            this.sitting = sitting;
        }

        String getLocationString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    private static int executePetGlow(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        synchronized (playersCurrentlyScanning) {
            if (playersCurrentlyScanning.contains(playerUUID)) {
                source.sendError(formatted("§c", "easypets.command.error.already_scanning"));
                return 0;
            }
            playersCurrentlyScanning.add(playerUUID);
        }

        try {
            applyGlowingEffectToPets(player);
        } finally {
            synchronized (playersCurrentlyScanning) {
                playersCurrentlyScanning.remove(playerUUID);
            }
        }
        return 1;
    }

    private static void applyGlowingEffectToPets(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) return;

        UUID playerId = player.getUuid();
        String playerPrefix = "petglow_" + player.getUuidAsString().substring(0, 8);
        
        // Cancel existing glow session if running
        GlowSession existingSession = activeGlowSessions.get(playerId);
        if (existingSession != null) {
            existingSession.cancel();
            cleanupPlayerTeams(server, playerPrefix);
        }
        
        // Create teams for different pet states with different colors
        Map<String, Team> colorTeams = new HashMap<>();
        Map<String, Formatting> colorMap = Map.of(
            "following", Formatting.GREEN,
            "sitting", Formatting.BLUE, 
            "roaming", Formatting.GOLD,
            "independent", Formatting.LIGHT_PURPLE,
            "leashed", Formatting.RED
        );
        
        // Remove existing teams if they exist and create new ones
        for (String colorKey : colorMap.keySet()) {
            String teamName = playerPrefix + "_" + colorKey;
            Team existingTeam = server.getScoreboard().getTeam(teamName);
            if (existingTeam != null) {
                server.getScoreboard().removeTeam(existingTeam);
            }
            
            Team team = server.getScoreboard().addTeam(teamName);
            team.setShowFriendlyInvisibles(true);
            team.setColor(colorMap.get(colorKey));
            colorTeams.put(colorKey, team);
        }
        
        List<PetInfo> standingPets = new ArrayList<>();
        List<PetInfo> sittingPets = new ArrayList<>();
        List<PetInfo> roamingPets = new ArrayList<>();
        List<PetInfo> independentPets = new ArrayList<>();
        Set<UUID> foundPetUUIDs = new HashSet<>();
        int glowingPets = 0;

        // Find all loaded pets first (for immediate effect)
        for (ServerWorld world : server.getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                // Check for tameable entities (wolves, cats, parrots, etc.)
                if (entity instanceof TameableEntity pet && pet.isTamed() && pet.getOwner() == player) {
                    applyGlowToPet(pet, server, colorTeams, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs, world);
                    glowingPets++;
                }
                // Check for horses, donkeys, mules, llamas
                else if (entity instanceof AbstractHorseEntity horse && horse.isTame() && horse.getOwner() == player) {
                    applyGlowToHorse(horse, server, colorTeams, standingPets, sittingPets, roamingPets, independentPets, foundPetUUIDs, world);
                    glowingPets++;
                }
            }
        }
        
        // Create and start new glow session with dynamic state tracking
        GlowSession session = new GlowSession(server, player, playerPrefix, colorTeams, colorMap);
        activeGlowSessions.put(playerId, session);
        session.start();
        
        // Report results to player
        reportGlowingPets(player, standingPets, sittingPets, roamingPets, independentPets, glowingPets);
    }
    
    private static void applyGlowToPet(TameableEntity pet, MinecraftServer server, Map<String, Team> colorTeams,
                                       List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, 
                                       List<PetInfo> independentPets, Set<UUID> foundPetUUIDs, ServerWorld world) {
        // Apply glowing effect
        StatusEffectInstance glowingEffect = new StatusEffectInstance(
                StatusEffects.GLOWING, 
                600, // 30 seconds (20 ticks per second)
                0, 
                false, 
                false, 
                true
        );
        pet.addStatusEffect(glowingEffect);
        
        // Determine pet state and assign to appropriate colored team
        String petState;
        String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
        
        if (pet.getVehicle() != null) { // Pet is in a boat or other vehicle
            petState = "leashed"; // Use red color for pets in vehicles
        } else if (pet.isLeashed()) {
            petState = "leashed";
        } else if (pet.isSitting()) {
            petState = "sitting"; // Sitting takes priority over independent
        } else if (isIndependentPet(pet)) {
            petState = "independent";
        } else if (ROAMING_PET_TYPES.contains(entityId)) {
            petState = "roaming";
        } else {
            petState = "following";
        }
        
        // Add pet to the appropriate colored team
        Team petTeam = colorTeams.get(petState);
        if (petTeam != null) {
            server.getScoreboard().addScoreHolderToTeam(pet.getUuidAsString(), petTeam);
        }
        
        foundPetUUIDs.add(pet.getUuid());
        
        // Categorize pet for reporting
        String displayName = pet.hasCustomName() ?
                pet.getCustomName().getString() + " (" + entityId.replace("minecraft:", "") + ")" :
                entityId.replace("minecraft:", "");
        
        PetInfo petInfo = new PetInfo(
                pet.getUuid(),
                entityId,
                pet.hasCustomName() ? pet.getCustomName().getString() : null,
                pet.getX(),
                pet.getY(),
                pet.getZ(),
                pet.getChunkPos(),
                world,
                pet.isSitting(),
                pet.isLeashed(),
                pet.getVehicle() != null, // Check if pet is in vehicle
                isIndependentPet(pet),
                false, // hasHomePos - not available from loaded entity
                0, 0, 0 // home coordinates
        );
        
        if (pet.isSitting()) {
            sittingPets.add(petInfo); // Sitting takes priority for categorization too
        } else if (isIndependentPet(pet)) {
            independentPets.add(petInfo);
        } else if (ROAMING_PET_TYPES.contains(entityId)) {
            roamingPets.add(petInfo);
        } else {
            standingPets.add(petInfo);
        }
    }
    
    private static void applyGlowToHorse(AbstractHorseEntity horse, MinecraftServer server, Map<String, Team> colorTeams,
                                         List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, 
                                         List<PetInfo> independentPets, Set<UUID> foundPetUUIDs, ServerWorld world) {
        // Apply glowing effect
        StatusEffectInstance glowingEffect = new StatusEffectInstance(
                StatusEffects.GLOWING, 
                600, // 30 seconds (20 ticks per second)
                0, 
                false, 
                false, 
                true
        );
        horse.addStatusEffect(glowingEffect);
        
        // Determine horse state
        String petState;
        String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(horse.getType()).toString();
        
        if (horse.getVehicle() != null) { // Horse is in a vehicle (unlikely but possible)
            petState = "leashed"; // Use red color
        } else if (horse.isLeashed()) {
            petState = "leashed";
        } else {
            petState = "roaming"; // All horses are considered roaming pets
        }
        
        // Add horse to the appropriate colored team
        Team petTeam = colorTeams.get(petState);
        if (petTeam != null) {
            server.getScoreboard().addScoreHolderToTeam(horse.getUuidAsString(), petTeam);
        }
        
        foundPetUUIDs.add(horse.getUuid());
        
        // Categorize horse for reporting
        String displayName = horse.hasCustomName() ?
                horse.getCustomName().getString() + " (" + entityId.replace("minecraft:", "") + ")" :
                entityId.replace("minecraft:", "");
        
        PetInfo petInfo = new PetInfo(
                horse.getUuid(),
                entityId,
                horse.hasCustomName() ? horse.getCustomName().getString() : null,
                horse.getX(),
                horse.getY(),
                horse.getZ(),
                horse.getChunkPos(),
                world,
                false, // Horses don't sit
                horse.isLeashed(),
                horse.getVehicle() != null, // Check if horse is in vehicle
                false, // Horses aren't independent pets
                false, // hasHomePos - not available from loaded entity
                0, 0, 0 // home coordinates
        );
        
        // All horses go to roaming pets category
        roamingPets.add(petInfo);
    }
    
    private static String getPetGlowColor(TameableEntity pet) {
        if (pet.isLeashed()) {
            return "§c"; // Red for leashed pets
        } else if (pet.isSitting()) {
            return "§9"; // Blue for sitting pets  
        } else if (pet.hasVehicle()) {
            return "§6"; // Gold for pets in vehicles
        } else if (isIndependentPet(pet)) {
            return "§d"; // Magenta for independent pets
        } else {
            String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
            if (ROAMING_PET_TYPES.contains(entityId)) {
                return "§6"; // Gold for roaming pets
            }
            return "§2"; // Green for following pets
        }
    }
    
    private static void reportGlowingPets(ServerPlayerEntity player, List<PetInfo> standingPets,
                                          List<PetInfo> sittingPets, List<PetInfo> roamingPets, 
                                          List<PetInfo> independentPets, int glowingPets) {
        player.sendMessage(Text.literal("§a=== " + TranslationManager.getInstance().translate("easypets.petglow.title") + " ==="), false);
        
        if (glowingPets == 0) {
            player.sendMessage(formatted("§7", "easypets.petglow.no_pets"), false);
            return;
        }
        
        player.sendMessage(formatted("§a", "easypets.petglow.applied", glowingPets), false);
        player.sendMessage(formatted("§7", "easypets.petglow.duration"), false);
        player.sendMessage(Text.empty(), false);
        
        // Show color legend
        player.sendMessage(formatted("§7", "easypets.petglow.legend"), false);
        if (!standingPets.isEmpty()) {
            player.sendMessage(formatted("§2", "easypets.petglow.legend.following", standingPets.size()), false);
        }
        if (!sittingPets.isEmpty()) {
            player.sendMessage(formatted("§9", "easypets.petglow.legend.sitting", sittingPets.size()), false);
        }
        if (!roamingPets.isEmpty()) {
            player.sendMessage(formatted("§6", "easypets.petglow.legend.roaming", roamingPets.size()), false);
        }
        if (!independentPets.isEmpty()) {
            player.sendMessage(formatted("§d", "easypets.petglow.legend.independent", independentPets.size()), false);
        }
        
        // Count leashed pets from all categories
        long leashedCount = Stream.of(standingPets, sittingPets, roamingPets, independentPets)
                .flatMap(List::stream)
                .filter(pet -> pet.isLeashed)
                .count();
        
        if (leashedCount > 0) {
            player.sendMessage(formatted("§c", "easypets.petglow.legend.leashed", leashedCount), false);
        }
        
        player.sendMessage(Text.empty(), false);
        player.sendMessage(formatted("§3", "easypets.petglow.hint"), false);
    }
    
    private static void cleanupPlayerTeams(MinecraftServer server, String playerPrefix) {
        Map<String, Formatting> colorMap = Map.of(
            "following", Formatting.GREEN,
            "sitting", Formatting.BLUE, 
            "roaming", Formatting.GOLD,
            "independent", Formatting.LIGHT_PURPLE,
            "leashed", Formatting.RED
        );
        
        for (String colorKey : colorMap.keySet()) {
            String teamName = playerPrefix + "_" + colorKey;
            Team cleanupTeam = server.getScoreboard().getTeam(teamName);
            if (cleanupTeam != null) {
                server.getScoreboard().removeTeam(cleanupTeam);
            }
        }
    }
    
    private static class GlowSession {
        private final MinecraftServer server;
        private final ServerPlayerEntity player;
        private final String playerPrefix;
        private final Map<String, Team> colorTeams;
        private final Map<String, Formatting> colorMap;
        private ScheduledFuture<?> updateTask;
        private ScheduledFuture<?> cleanupTask;
        private volatile boolean cancelled = false;
        
        public GlowSession(MinecraftServer server, ServerPlayerEntity player, String playerPrefix, 
                          Map<String, Team> colorTeams, Map<String, Formatting> colorMap) {
            this.server = server;
            this.player = player;
            this.playerPrefix = playerPrefix;
            this.colorTeams = colorTeams;
            this.colorMap = colorMap;
        }
        
        public void start() {
            // Update pet states every tick (50ms)
            updateTask = glowScheduler.scheduleAtFixedRate(this::updatePetStates, 50, 50, TimeUnit.MILLISECONDS);
            
            // Schedule cleanup after 30 seconds
            cleanupTask = glowScheduler.schedule(this::cleanup, 32, TimeUnit.SECONDS);
        }
        
        public void cancel() {
            cancelled = true;
            if (updateTask != null && !updateTask.isCancelled()) {
                updateTask.cancel(false);
            }
            if (cleanupTask != null && !cleanupTask.isCancelled()) {
                cleanupTask.cancel(false);
            }
        }
        
        private void updatePetStates() {
            if (cancelled || player.isRemoved()) {
                cleanup();
                return;
            }
            
            server.execute(() -> {
                try {
                    // Update all pets' team assignments based on current state
                    for (ServerWorld world : server.getWorlds()) {
                        for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                            if (entity instanceof TameableEntity pet && pet.isTamed() && pet.getOwner() == player) {
                                updatePetTeamAssignment(pet);
                            } else if (entity instanceof AbstractHorseEntity horse && horse.isTame() && horse.getOwner() == player) {
                                updateHorseTeamAssignment(horse);
                            }
                        }
                    }
                } catch (Exception e) {
                    // If there's an error, clean up to prevent issues
                    cleanup();
                }
            });
        }
        
        private void updatePetTeamAssignment(TameableEntity pet) {
            // Determine current pet state
            String petState;
            String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
            
            if (pet.getVehicle() != null) {
                petState = "leashed"; // Use red color for pets in vehicles
            } else if (pet.isLeashed()) {
                petState = "leashed";
            } else if (pet.isSitting()) {
                petState = "sitting"; // Sitting takes priority over independent
            } else if (isIndependentPet(pet)) {
                petState = "independent";
            } else if (ROAMING_PET_TYPES.contains(entityId)) {
                petState = "roaming";
            } else {
                petState = "following";
            }
            
            // Remove pet from all teams first
            for (Team team : colorTeams.values()) {
                team.getPlayerList().remove(pet.getUuidAsString());
            }
            
            // Add pet to the appropriate colored team
            Team petTeam = colorTeams.get(petState);
            if (petTeam != null) {
                server.getScoreboard().addScoreHolderToTeam(pet.getUuidAsString(), petTeam);
            }
        }
        
        private void updateHorseTeamAssignment(AbstractHorseEntity horse) {
            // Determine current horse state
            String petState;
            
            if (horse.getVehicle() != null) {
                petState = "leashed"; // Use red color
            } else if (horse.isLeashed()) {
                petState = "leashed";
            } else {
                petState = "roaming"; // All horses are considered roaming pets
            }
            
            // Remove horse from all teams first
            for (Team team : colorTeams.values()) {
                team.getPlayerList().remove(horse.getUuidAsString());
            }
            
            // Add horse to the appropriate colored team
            Team petTeam = colorTeams.get(petState);
            if (petTeam != null) {
                server.getScoreboard().addScoreHolderToTeam(horse.getUuidAsString(), petTeam);
            }
        }
        
        private void cleanup() {
            cancel();
            server.execute(() -> {
                cleanupPlayerTeams(server, playerPrefix);
                activeGlowSessions.remove(player.getUuid());
            });
        }
    }
}