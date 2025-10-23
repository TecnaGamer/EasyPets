package org.tecna.easypets;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.nbt.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;
import org.tecna.easypets.config.Config;
import org.tecna.easypets.util.SaveUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.stream.Collectors.groupingBy;
import static net.minecraft.server.command.CommandManager.literal;

public class PetRecoveryCommand {

    // Define roaming pet types that shouldn't load chunks automatically
    private static final Set<String> ROAMING_PET_TYPES = Set.of(
            "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:llama", "minecraft:trader_llama"
    );

    // Spam protection - track players currently running scans
    private static final Set<UUID> playersCurrentlyScanning = new HashSet<>();

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Player commands - available to ALL players (no .requires() restriction)
            dispatcher.register(literal("petrecovery")
                    .executes(PetRecoveryCommand::executePetRecovery));

            dispatcher.register(literal("petlocator")
                    .executes(PetRecoveryCommand::executePetLocator));

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
            source.sendError(Text.of("Error retrieving pet stats: " + e.getMessage()));
        }
    }

    private static void showOverallStats(ServerCommandSource source) {
        source.sendMessage(Text.of("§a=== Pet Chunk Loading Stats ==="));

        int totalChunkLoadingPets = 0;
        int totalPlayersWithPets = 0;

        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                Set<UUID> playerPets = tracker.getChunkLoadingPets();
                int playerPetCount = playerPets.size();

                if (playerPetCount > 0) {
                    totalPlayersWithPets++;
                    totalChunkLoadingPets += playerPetCount;
                    source.sendMessage(Text.of("§f" + player.getGameProfile().name() + ": §6" + playerPetCount + " pets loading chunks"));
                }
            }
        }

        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§7Total: §e" + totalChunkLoadingPets + " pets §7loading chunks across §e" + totalPlayersWithPets + " players"));
        source.sendMessage(Text.of("§7Use §f/petstats player <name> §7for detailed info"));
    }

    private static void showDetailedPlayerStats(ServerCommandSource source, ServerPlayerEntity targetPlayer) {
        source.sendMessage(Text.of("§a=== Pet Stats for " + targetPlayer.getGameProfile().name() + " ==="));

        if (!(targetPlayer instanceof SimplePetTracker tracker)) {
            source.sendError(Text.of("Player data not available"));
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
            source.sendMessage(Text.of("§6Pets loading chunks (" + loadingPets.size() + "):"));
            for (PetDetails pet : loadingPets) {
                source.sendMessage(Text.of("§f• " + pet.displayName + " at " + pet.getLocationString() + " in " + pet.worldName));
            }
        } else {
            source.sendMessage(Text.of("§7No pets currently loading chunks"));
        }

        if (!sittingPets.isEmpty()) {
            source.sendMessage(Text.of(""));
            source.sendMessage(Text.of("§3Sitting pets (" + sittingPets.size() + "):"));
            for (PetDetails pet : sittingPets) {
                source.sendMessage(Text.of("§f• " + pet.displayName + " at " + pet.getLocationString() + " in " + pet.worldName));
            }
        }

        if (!independentPets.isEmpty()) {
            source.sendMessage(Text.of(""));
            source.sendMessage(Text.of("§dIndependent pets (" + independentPets.size() + "):"));
            for (PetDetails pet : independentPets) {
                source.sendMessage(Text.of("§f• " + pet.displayName + " at " + pet.getLocationString() + " in " + pet.worldName));
            }
        }

        if (loadingPets.isEmpty() && sittingPets.isEmpty() && independentPets.isEmpty()) {
            source.sendMessage(Text.of("§7No pets found for this player"));
        }
    }

    private static int executePetRecovery(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.of("This command can only be run by players"));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        synchronized (playersCurrentlyScanning) {
            if (playersCurrentlyScanning.contains(playerUUID)) {
                source.sendError(Text.of("§cYou are already running a pet scan. Please wait for it to complete."));
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
            source.sendError(Text.of("This command can only be run by players"));
            return 0;
        }

        UUID playerUUID = player.getUuid();
        synchronized (playersCurrentlyScanning) {
            if (playersCurrentlyScanning.contains(playerUUID)) {
                source.sendError(Text.of("§cYou are already running a pet scan. Please wait for it to complete."));
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
            source.sendError(Text.of("This command can only be run by players"));
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
            source.sendError(Text.of("Player not found"));
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

        source.sendMessage(Text.of("§e=== Forced Cleanup Complete ==="));
        source.sendMessage(Text.of("§7Cleared tracking for: §c" + totalPets + " pets"));
        source.sendMessage(Text.of("§7Tickets will auto-expire in 3 seconds"));

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

        source.sendMessage(Text.of("§e=== Tracked Pets ==="));
        source.sendMessage(Text.of("§7Total tracked pets: §f" + totalTracked));
        source.sendMessage(Text.of("§7Note: With the simplified system, we only track pet UUIDs"));
        source.sendMessage(Text.of("§7Actual chunk tickets auto-expire and aren't centrally tracked"));

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

            source.sendMessage(Text.of("§e=== Player Reset Complete ==="));
            source.sendMessage(Text.of("§7Player: §f" + targetPlayer.getGameProfile().name()));
            source.sendMessage(Text.of("§7Cleared tracking for: §c" + petCount + " pets"));

            targetPlayer.sendMessage(Text.of("§7[EasyPets] Your pet chunk loading data has been reset by an admin"));

        } catch (Exception e) {
            source.sendError(Text.of("§cPlayer not found or error occurred: " + e.getMessage()));
        }

        return 1;
    }

    private static int executeDebugVersion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendMessage(Text.of("§e=== EasyPets Info ==="));
        source.sendMessage(Text.of("§7Version: §fSimplified System"));
        source.sendMessage(Text.of("§7Ticket System: §fAuto-expiring (60 ticks)"));
        source.sendMessage(Text.of("§7Based on: §fVanilla Ender Pearl system"));
        source.sendMessage(Text.of(""));

        source.sendMessage(Text.of("§7Online Players:"));
        int playersWithData = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof SimplePetTracker tracker) {
                int petCount = tracker.getChunkLoadingPets().size();
                playersWithData++;
                source.sendMessage(Text.of("  • §f" + player.getGameProfile().name() + " §7(§6" + petCount + " pets§7)"));
            }
        }

        if (playersWithData == 0) {
            source.sendMessage(Text.of("§7No players with pet data"));
        }

        return 1;
    }

    private static void debugRegionFile(ServerPlayerEntity player) {
        CompletableFuture.runAsync(() -> {
            try {
                player.sendMessage(Text.of("§aDebugging first region file..."), false);

                Path worldPath = player.getEntityWorld().getServer().getSavePath(WorldSavePath.ROOT).normalize();
                Path entitiesPath = worldPath.resolve("entities");

                if (!Files.exists(entitiesPath)) {
                    player.sendMessage(Text.of("§cNo entities directory found"), false);
                    return;
                }

                Optional<Path> firstRegion = Files.list(entitiesPath)
                        .filter(path -> path.toString().endsWith(".mca"))
                        .findFirst();

                if (firstRegion.isEmpty()) {
                    player.sendMessage(Text.of("§cNo region files found"), false);
                    return;
                }

                Path regionPath = firstRegion.get();
                player.sendMessage(Text.of("§7Debugging: " + regionPath.getFileName()), false);

                debugSingleRegionFile(regionPath, player);

            } catch (Exception e) {
                player.sendMessage(Text.of("§cError during debug: " + e.getMessage()), false);
                e.printStackTrace();
            }
        });
    }

    private static void debugSingleRegionFile(Path regionPath, ServerPlayerEntity player) throws IOException {
        String fileName = regionPath.getFileName().toString();
        String[] parts = fileName.replace(".mca", "").split("\\.");
        if (parts.length != 3) {
            player.sendMessage(Text.of("§cInvalid region file name format"), false);
            return;
        }

        int regionX = Integer.parseInt(parts[1]);
        int regionZ = Integer.parseInt(parts[2]);
        player.sendMessage(Text.of("§7Region coordinates: " + regionX + ", " + regionZ), false);

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
                        player.sendMessage(Text.of("§7Chunk " + chunkPos + " has data"), false);

                        try (DataInputStream inputStream = regionFile.getChunkInputStream(chunkPos)) {
                            if (inputStream != null) {
                                NbtCompound chunkNbt = NbtIo.readCompound(inputStream, NbtSizeTracker.ofUnlimitedBytes());
                                if (chunkNbt != null) {
                                    player.sendMessage(Text.of("§7Chunk NBT keys: " + chunkNbt.getKeys().toString()), false);

                                    if (chunkNbt.contains("Entities")) {
                                        Optional<NbtList> entitiesOpt = chunkNbt.getList("Entities");
                                        if (entitiesOpt.isPresent()) {
                                            NbtList entities = entitiesOpt.get();
                                            player.sendMessage(Text.of("§aFound " + entities.size() + " entities in chunk " + chunkPos), false);

                                            for (int i = 0; i < Math.min(3, entities.size()); i++) {
                                                Optional<NbtCompound> entityOpt = entities.getCompound(i);
                                                if (entityOpt.isPresent()) {
                                                    NbtCompound entity = entityOpt.get();
                                                    String entityId = entity.getString("id", "unknown");
                                                    player.sendMessage(Text.of("§f  Entity " + i + ": " + entityId), false);
                                                    player.sendMessage(Text.of("§f  Keys: " + entity.getKeys().toString()), false);

                                                    if (entityId.contains("wolf") || entityId.contains("cat") || entityId.contains("parrot")) {
                                                        player.sendMessage(Text.of("§6  This is a potential pet!"), false);
                                                        entity.getKeys().forEach(key -> {
                                                            if (key.toLowerCase().contains("owner") || key.toLowerCase().contains("tame") || key.toLowerCase().contains("sit") || key.toLowerCase().contains("allowedtofollow") || key.toLowerCase().contains("indypets")) {
                                                                player.sendMessage(Text.of("§6  " + key + " = " + entity.get(key).toString()), false);
                                                            }
                                                        });
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        player.sendMessage(Text.of("§cNo 'Entities' key found in chunk NBT"), false);
                                    }
                                } else {
                                    player.sendMessage(Text.of("§cCould not read chunk NBT"), false);
                                }
                            }
                        } catch (Exception e) {
                            player.sendMessage(Text.of("§cError reading chunk " + chunkPos + ": " + e.getMessage()), false);
                        }
                    }
                }
            }

            player.sendMessage(Text.of("§7Checked " + totalChunks + " chunk positions, " + chunksWithData + " had data"), false);
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
                    player.sendMessage(Text.of("§7[EasyPets] Saving world to ensure accurate pet data..."));

                    try {
                        // Use the new SaveUtil which executes vanilla save-all flush command
                        Boolean saveResult = SaveUtil.triggerFullSave(player.getEntityWorld().getServer()).get();

                        if (saveResult) {
                            player.sendMessage(Text.of("§a[EasyPets] World save completed successfully"));
                            if (config.isDebugLoggingEnabled()) {
                                System.out.println("[EasyPets] World save completed successfully for " + operation);
                            }
                            // Give save operation time to complete fully
                            //Thread.sleep(2000);
                        } else {
                            player.sendMessage(Text.of("§c[EasyPets] Warning: World save failed - pet data may be outdated"));
                            if (config.isDebugLoggingEnabled()) {
                                System.out.println("[EasyPets] Save operation failed for player: " + player.getGameProfile().name());
                            }
                        }
                    } catch (Exception e) {
                        player.sendMessage(Text.of("§c[EasyPets] Warning: Save operation encountered an error - continuing anyway"));
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
                        player.sendMessage(Text.of("§aScanning for your pet locations..."));
                    } else {
                        player.sendMessage(Text.of("§e⚠ Pet locations shown are from the last world save and may not reflect current positions"));
                        player.sendMessage(Text.of("§aScanning for your pet locations..."));
                    }
                } else {
                    player.sendMessage(Text.of("§aScanning for your pets to recover..."));
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

                player.sendMessage(Text.of(" "), true);
                player.sendMessage(Text.of("§7Scanned " + totalFiles + " region files and " + totalChunks + " chunks"));

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
                    player.sendMessage(Text.of("§eNo pets found. All your pets are either already loaded or don't exist."));
                    return;
                }

                if (locateOnly) {
                    reportPetLocations(player, standingPets, sittingPets, roamingPets, independentPets, loadedPetUUIDs);
                } else {
                    loadPetChunks(player, standingPets, sittingPets, roamingPets, independentPets);
                }

            } catch (Exception e) {
                player.sendMessage(Text.of(" "), true);
                player.sendMessage(Text.of("§cError during pet scan: " + e.getMessage()));
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

        player.sendMessage(Text.of(bar.toString()), true);
    }

    private static void reportPetLocations(ServerPlayerEntity player, List<PetInfo> standingPets,
                                           List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets,
                                           Set<UUID> loadedPetUUIDs) {
        player.sendMessage(Text.of("§a=== Pet Locations ==="), false);
        if (!loadedPetUUIDs.isEmpty()) {
            player.sendMessage(Text.of("§7(§a✓§7 = Loaded in memory)"), false);
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
            player.sendMessage(Text.of("§7No pets found"), false);
            return;
        }

        List<String> sortedDimensions = allDimensions.stream().sorted().toList();

        for (String dimension : sortedDimensions) {
            String dimensionColor = getDimensionColor(dimension);
            player.sendMessage(Text.of(""), false);
            player.sendMessage(Text.of(dimensionColor + "=== " + dimension.toUpperCase() + " ==="), false);

            List<PetInfo> standingInDim = standingByDimension.getOrDefault(dimension, List.of());
            if (!standingInDim.isEmpty()) {
                player.sendMessage(Text.of("§2⚡ Following pets (" + standingInDim.size() + "):"), false);
                for (PetInfo pet : standingInDim) {
                    String status = getRestrictedPetStatus(pet);
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.of("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString() + status), false);
                }
            }

            List<PetInfo> sittingInDim = sittingByDimension.getOrDefault(dimension, List.of());
            if (!sittingInDim.isEmpty()) {
                player.sendMessage(Text.of("§9⸭ Sitting pets (" + sittingInDim.size() + "):"), false);
                for (PetInfo pet : sittingInDim) {
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.of("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            List<PetInfo> roamingInDim = roamingByDimension.getOrDefault(dimension, List.of());
            if (!roamingInDim.isEmpty()) {
                player.sendMessage(Text.of("§6🐎 Roaming pets (" + roamingInDim.size() + "):"), false);
                for (PetInfo pet : roamingInDim) {
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.of("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            List<PetInfo> independentInDim = independentByDimension.getOrDefault(dimension, List.of());
            if (!independentInDim.isEmpty()) {
                player.sendMessage(Text.of("§d🐾 Independent pets (" + independentInDim.size() + "):"), false);
                for (PetInfo pet : independentInDim) {
                    String homeInfo = pet.hasHomePos ? " §8[Home: " + pet.getHomePosString() + "]" : "";
                    String icon = loadedPetUUIDs.contains(pet.uuid) ? "§a✓" : "§f•";
                    player.sendMessage(Text.of("  " + icon + " §f" + pet.getDisplayName() + " §7at " + pet.getLocationString() + homeInfo), false);
                }
            }

            if (standingInDim.isEmpty() && sittingInDim.isEmpty() && roamingInDim.isEmpty() && independentInDim.isEmpty()) {
                player.sendMessage(Text.of("§8  No pets in this dimension"), false);
            }
        }

        player.sendMessage(Text.of(""), false);
        int totalPets = standingPets.size() + sittingPets.size() + roamingPets.size() + independentPets.size();

        List<String> summaryParts = new ArrayList<>();
        if (standingPets.size() > 0) summaryParts.add("§2" + standingPets.size() + " following");
        if (sittingPets.size() > 0) summaryParts.add("§9" + sittingPets.size() + " sitting");
        if (roamingPets.size() > 0) summaryParts.add("§6" + roamingPets.size() + " roaming");
        if (independentPets.size() > 0) summaryParts.add("§d" + independentPets.size() + " independent");

        if (summaryParts.isEmpty()) {
            player.sendMessage(Text.of("§7Total: §e0 pets"), false);
        } else {
            String summaryText = String.join(" §7+ ", summaryParts);
            player.sendMessage(Text.of("§7Total: " + summaryText + " §7= §e" + totalPets + " pets"), false);
        }
    }

    private static String getRestrictedPetStatus(PetInfo pet) {
        if (pet.inVehicle) {
            return " §c[IN VEHICLE]";
        } else if (pet.isLeashed) {
            return " §c[LEASHED]";
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

        StringBuilder message = new StringBuilder();

        if (petsToRecover > 0) {
            message.append("§aFound ").append(petsToRecover).append(" following pets! Chunks loaded, they should teleport to you soon.");
        } else {
            message.append("§eNo following pets found that can be recovered.");
        }

        if (restrictedPets > 0) {
            message.append("\n§c").append(restrictedPets).append(" pets are leashed or in vehicles and won't teleport.");
        }

        if (!roamingPets.isEmpty()) {
            message.append("\n§6").append(roamingPets.size()).append(" roaming pets (horses/llamas) found - they won't auto-teleport.");
        }

        if (!independentPets.isEmpty()) {
            message.append("\n§d").append(independentPets.size()).append(" independent pets found - they won't auto-teleport or load chunks.");
        }

        if (!sittingPets.isEmpty()) {
            message.append("\n§3").append(sittingPets.size()).append(" sitting pets found.");
        }

        message.append("\n§3Use /petlocator to see coordinates of all pets.");

        player.sendMessage(Text.of(message.toString()), false);
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
                            player.sendMessage(Text.of("§cError scanning " + regionPath.getFileName() + ": " + e.getMessage()), false);
                        }
                    }

                    String worldDisplayName = getWorldDisplayName(world);
                    player.sendMessage(Text.of("§7Found " + regionFiles.size() + " " + worldDisplayName + " region files"), false);

                    break;
                }
            }

        } catch (Exception e) {
            player.sendMessage(Text.of("§cError scanning world " + world.getRegistryKey().getValue() + ": " + e.getMessage()), false);
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
                                    player.sendMessage(Text.of("§7Found custom dimension: " + fullDimensionName), false);

                                    List<Path> regionFiles = Files.list(entitiesPath)
                                            .filter(path -> path.toString().endsWith(".mca"))
                                            .toList();

                                    if (!regionFiles.isEmpty()) {
                                        player.sendMessage(Text.of("§7Found " + regionFiles.size() + " region files in " + fullDimensionName), false);
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
            player.sendMessage(Text.of("§cError scanning additional dimensions: " + e.getMessage()), false);
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
                    player.sendMessage(Text.of("§7Found " + regionFiles.size() + " " + dimensionName + " region files"), false);
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
}