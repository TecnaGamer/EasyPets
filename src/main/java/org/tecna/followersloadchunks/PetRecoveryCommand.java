package org.tecna.followersloadchunks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.CommandDispatcher;
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
import org.tecna.followersloadchunks.ChunkLoadingPetTracker;
import org.tecna.followersloadchunks.PetChunkManager;
import org.tecna.followersloadchunks.PetChunkTickets;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static net.minecraft.server.command.CommandManager.literal;

public class PetRecoveryCommand {

    // Define roaming pet types that shouldn't load chunks automatically
    private static final Set<String> ROAMING_PET_TYPES = Set.of(
            "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:llama", "minecraft:trader_llama"
    );

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("petrecovery")
                    .requires(source -> source.hasPermissionLevel(2)) // Require OP
                    .executes(PetRecoveryCommand::executePetRecovery));

            dispatcher.register(literal("petlocator")
                    .requires(source -> source.hasPermissionLevel(2)) // Require OP
                    .executes(PetRecoveryCommand::executePetLocator));

            dispatcher.register(literal("debugregion")
                    .requires(source -> source.hasPermissionLevel(2)) // Require OP
                    .executes(PetRecoveryCommand::executeDebugRegion));

            dispatcher.register(literal("petstats")
                    .requires(source -> source.hasPermissionLevel(2)) // Require OP
                    .executes(PetRecoveryCommand::executePetStats)
                    .then(literal("player")
                            .then(net.minecraft.server.command.CommandManager.argument("playerName", net.minecraft.command.argument.EntityArgumentType.player())
                                    .executes(PetRecoveryCommand::executePetStatsForPlayer))));

            // Debug commands for troubleshooting
            dispatcher.register(literal("petdebug")
                    .requires(source -> source.hasPermissionLevel(4)) // Require admin
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

    private static void showPetStats(ServerCommandSource source, ServerPlayerEntity targetPlayer) {
        try {
            if (targetPlayer != null) {
                // Show detailed stats for specific player
                showDetailedPlayerStats(source, targetPlayer);
            } else {
                // Show overview for all players
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
            if (player instanceof ChunkLoadingPetTracker tracker) {
                // Count pets loading chunks for this player
                int playerPetCount = 0;

                // We need to iterate through loaded entities to count active pets
                for (ServerWorld world : source.getServer().getWorlds()) {
                    for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                        if (entity instanceof net.minecraft.entity.passive.TameableEntity pet) {
                            if (pet.isTamed() && pet.getOwner() == player && !pet.isSitting()) {
                                // Check if this pet is in the tracker
                                if (tracker.followersLoadChunks$isPetChunkLoading(pet.getUuid())) {
                                    playerPetCount++;
                                }
                            }
                        }
                    }
                }

                if (playerPetCount > 0) {
                    totalPlayersWithPets++;
                    totalChunkLoadingPets += playerPetCount;
                    source.sendMessage(Text.of("§f" + player.getGameProfile().getName() + ": §6" + playerPetCount + " pets loading chunks"));
                }
            }
        }

        source.sendMessage(Text.of(""));
        source.sendMessage(Text.of("§7Total: §e" + totalChunkLoadingPets + " pets §7loading chunks across §e" + totalPlayersWithPets + " players"));
        source.sendMessage(Text.of("§7Use §f/petstats player <name> §7for detailed info"));
    }

    private static void showDetailedPlayerStats(ServerCommandSource source, ServerPlayerEntity targetPlayer) {
        source.sendMessage(Text.of("§a=== Pet Stats for " + targetPlayer.getGameProfile().getName() + " ==="));

        if (!(targetPlayer instanceof ChunkLoadingPetTracker tracker)) {
            source.sendError(Text.of("Player data not available"));
            return;
        }

        List<PetDetails> loadingPets = new ArrayList<>();
        List<PetDetails> sittingPets = new ArrayList<>();
        List<PetDetails> independentPets = new ArrayList<>();

        // Find all pets for this player across all worlds
        for (ServerWorld world : source.getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof net.minecraft.entity.passive.TameableEntity pet) {
                    if (pet.isTamed() && pet.getOwner() == targetPlayer) {
                        String petType = pet.getClass().getSimpleName().replace("Entity", "");
                        String worldName = world.getRegistryKey().getValue().toString().replace("minecraft:", "");

                        // Get custom name if available
                        String displayName = pet.hasCustomName() ?
                                pet.getCustomName().getString() + " (" + petType + ")" :
                                petType;

                        // Check if pet is independent (IndyPets)
                        boolean isIndependent = isIndependentPet(pet);

                        PetDetails details = new PetDetails(
                                pet.getUuid(),
                                petType,
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
                        } else if (tracker.followersLoadChunks$isPetChunkLoading(pet.getUuid())) {
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

        // Start async recovery process
        runPetRecoveryForPlayer(player, false);
        return 1;
    }

    // Public method that can be called from other parts of the mod
    public static void runPetRecoveryForPlayer(ServerPlayerEntity player, boolean locateOnly) {
        recoverPlayerPets(player, locateOnly);
    }

    private static int executePetLocator(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.of("This command can only be run by players"));
            return 0;
        }

        // Start async location process
        recoverPlayerPets(player, true);
        return 1;
    }

    private static int executeDebugRegion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.of("This command can only be run by players"));
            return 0;
        }

        // Start async debug process
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

    // Debug command implementations
    private static int executeDebugCleanup(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        int removedTickets = PetChunkManager.getActivePetTicketCount();

        // Force cleanup all active pet tickets
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            PetChunkManager.cleanupPlayerPetTickets(player);
        }

        source.sendMessage(Text.of("§e=== Forced Cleanup Complete ==="));
        source.sendMessage(Text.of("§7Removed: §c" + removedTickets + " chunk tickets"));
        source.sendMessage(Text.of("§7Remaining: §a" + PetChunkManager.getActivePetTicketCount() + " tickets"));

        return 1;
    }

    private static int executeDebugTickets(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        int activeTickets = PetChunkManager.getActivePetTicketCount();
        source.sendMessage(Text.of("§e=== Active Chunk Tickets ==="));
        source.sendMessage(Text.of("§7Total tickets: §f" + activeTickets));

        if (activeTickets > 0) {
            source.sendMessage(Text.of(""));

            AtomicInteger count = new AtomicInteger(0);
            PetChunkManager.getActivePetTickets().forEach((petUUID, chunkPos) -> {
                count.incrementAndGet();
                if (count.get() > 15) {
                    if (count.get() == 16) {
                        source.sendMessage(Text.of("§7... and " + (activeTickets - 15) + " more"));
                    }
                    return;
                }

                // Try to find what this ticket belongs to
                String info = "§f" + petUUID.toString().substring(0, 8) + "... §7at §6" + chunkPos;

                // Try to find the pet
                for (ServerWorld world : source.getServer().getWorlds()) {
                    net.minecraft.entity.Entity entity = world.getEntity(petUUID);
                    if (entity instanceof net.minecraft.entity.passive.TameableEntity pet) {
                        String petName = pet.hasCustomName() ? pet.getCustomName().getString() : pet.getClass().getSimpleName();
                        String ownerName = pet.getOwner() instanceof ServerPlayerEntity owner ? owner.getGameProfile().getName() : "Unknown";
                        String worldName = world.getRegistryKey().getValue().toString().replace("minecraft:", "");
                        info = "§f" + petName + " §7(§6" + ownerName + "§7) in §e" + worldName;
                        break;
                    }
                }
                source.sendMessage(Text.of("  • " + info));
            });
        } else {
            source.sendMessage(Text.of("§7No active tickets"));
        }

        return 1;
    }

    private static int executeDebugReset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            ServerPlayerEntity targetPlayer = net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "playerName");

            // Clean up their tickets
            int beforeCount = PetChunkManager.getActivePetTicketCount();
            PetChunkManager.cleanupPlayerPetTickets(targetPlayer);
            int afterCount = PetChunkManager.getActivePetTicketCount();
            int removedCount = beforeCount - afterCount;

            // Clear their persistent data if possible
            if (targetPlayer instanceof ChunkLoadingPetTracker tracker) {
                // We can't directly access the private Set, but we can clear it by removing all current pets
                for (ServerWorld world : source.getServer().getWorlds()) {
                    for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                        if (entity instanceof net.minecraft.entity.passive.TameableEntity pet &&
                                pet.getOwner() == targetPlayer) {
                            tracker.followersLoadChunks$removeChunkLoadingPet(pet);
                        }
                    }
                }
            }

            source.sendMessage(Text.of("§e=== Player Reset Complete ==="));
            source.sendMessage(Text.of("§7Player: §f" + targetPlayer.getGameProfile().getName()));
            source.sendMessage(Text.of("§7Removed: §c" + removedCount + " chunk tickets"));
            source.sendMessage(Text.of("§7Cleared: §aAll persistent pet data"));

            targetPlayer.sendMessage(Text.of("§7[FollowersLoadChunks] Your pet chunk loading data has been reset by an admin"));

        } catch (Exception e) {
            source.sendError(Text.of("§cPlayer not found or error occurred: " + e.getMessage()));
        }

        return 1;
    }

    private static int executeDebugVersion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendMessage(Text.of("§e=== FollowersLoadChunks Info ==="));
        source.sendMessage(Text.of("§7Version: §fDevelopment Build"));
        source.sendMessage(Text.of("§7Data Format: §fv1"));
        source.sendMessage(Text.of("§7Active Tickets: §f" + PetChunkManager.getActivePetTicketCount()));
        source.sendMessage(Text.of(""));

        // Show online players with pet data
        source.sendMessage(Text.of("§7Online Players:"));
        int playersWithData = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player instanceof ChunkLoadingPetTracker) {
                playersWithData++;
                source.sendMessage(Text.of("  • §f" + player.getGameProfile().getName() + " §a✓"));
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

                Path worldPath = player.getServer().getSavePath(WorldSavePath.ROOT).normalize();
                Path entitiesPath = worldPath.resolve("entities");

                if (!Files.exists(entitiesPath)) {
                    player.sendMessage(Text.of("§cNo entities directory found"), false);
                    return;
                }

                // Find first region file
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

        StorageKey storageKey = new StorageKey("entities", player.getWorld().getRegistryKey(), "entities");

        try (RegionFile regionFile = new RegionFile(storageKey, regionPath, regionPath.getParent(), false)) {
            int totalChunks = 0;
            int chunksWithData = 0;

            // Check first few chunks for data
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

                                    // Check for entities
                                    if (chunkNbt.contains("Entities")) {
                                        Optional<NbtList> entitiesOpt = chunkNbt.getList("Entities");
                                        if (entitiesOpt.isPresent()) {
                                            NbtList entities = entitiesOpt.get();
                                            player.sendMessage(Text.of("§aFound " + entities.size() + " entities in chunk " + chunkPos), false);

                                            // Show first few entities
                                            for (int i = 0; i < Math.min(3, entities.size()); i++) {
                                                Optional<NbtCompound> entityOpt = entities.getCompound(i);
                                                if (entityOpt.isPresent()) {
                                                    NbtCompound entity = entityOpt.get();
                                                    String entityId = entity.getString("id", "unknown");
                                                    player.sendMessage(Text.of("§f  Entity " + i + ": " + entityId), false);
                                                    player.sendMessage(Text.of("§f  Keys: " + entity.getKeys().toString()), false);

                                                    // Show tameable-related fields if any
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
            try {
                if (locateOnly) {
                    player.sendMessage(Text.of("§e⚠ Pet locations shown are from the last world save and may not reflect current positions"));
                    player.sendMessage(Text.of("§aScanning for your pet locations..."));
                } else {
                    player.sendMessage(Text.of("§aScanning for your pets to recover..."));
                }

                List<PetInfo> standingPets = new ArrayList<>();
                List<PetInfo> sittingPets = new ArrayList<>();
                List<PetInfo> roamingPets = new ArrayList<>();
                List<PetInfo> independentPets = new ArrayList<>(); // New category for IndyPets
                int totalFiles = 0;
                int totalChunks = 0;

                // Count total files first for progress tracking
                int totalRegionFiles = countTotalRegionFiles(player);
                int processedFiles = 0;

                // Track which worlds we've processed to avoid duplicates
                Set<String> processedWorlds = new HashSet<>();

                for (ServerWorld world : player.getServer().getWorlds()) {
                    String worldName = world.getRegistryKey().getValue().toString();
                    processedWorlds.add(worldName);

                    int[] counts = scanWorldForPets(player, world, standingPets, sittingPets, roamingPets, independentPets, processedFiles, totalRegionFiles);
                    totalFiles += counts[0];
                    totalChunks += counts[1];
                    processedFiles += counts[0];
                }

                // Also scan any additional dimension folders that might exist
                Path worldPath = player.getServer().getSavePath(WorldSavePath.ROOT).normalize();
                int[] additionalCounts = scanAdditionalDimensions(player, worldPath, standingPets, sittingPets, roamingPets, independentPets);
                totalFiles += additionalCounts[0];
                totalChunks += additionalCounts[1];

                // Clear progress bar and show final results
                player.sendMessage(Text.of(" "), true); // Clear action bar
                player.sendMessage(Text.of("§7Scanned " + totalFiles + " region files and " + totalChunks + " chunks"));

                // Report findings
                if (standingPets.isEmpty() && sittingPets.isEmpty() && roamingPets.isEmpty() && independentPets.isEmpty()) {
                    player.sendMessage(Text.of("§eNo pets found. All your pets are either already loaded or don't exist."));
                    return;
                }

                if (locateOnly) {
                    // Just show locations
                    reportPetLocations(player, standingPets, sittingPets, roamingPets, independentPets);
                } else {
                    // Load chunks for standing pets (excluding roaming and independent ones)
                    loadPetChunks(player, standingPets, sittingPets, roamingPets, independentPets);
                }

            } catch (Exception e) {
                player.sendMessage(Text.of(" "), true); // Clear action bar
                player.sendMessage(Text.of("§cError during pet scan: " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }

    private static int countTotalRegionFiles(ServerPlayerEntity player) {
        int total = 0;
        try {
            Path worldPath = player.getServer().getSavePath(WorldSavePath.ROOT).normalize();

            for (ServerWorld world : player.getServer().getWorlds()) {
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

        player.sendMessage(Text.of(bar.toString()), true); // Send to action bar
    }

    private static void reportPetLocations(ServerPlayerEntity player, List<PetInfo> standingPets,
                                           List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        player.sendMessage(Text.of("§a=== Pet Locations ==="), false);

        // Group pets by dimension
        Map<String, List<PetInfo>> standingByDimension = standingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> sittingByDimension = sittingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> roamingByDimension = roamingPets.stream()
                .collect(groupingBy(pet -> pet.worldName));
        Map<String, List<PetInfo>> independentByDimension = independentPets.stream()
                .collect(groupingBy(pet -> pet.worldName));

        // Get all dimensions that have pets
        Set<String> allDimensions = new HashSet<>();
        allDimensions.addAll(standingByDimension.keySet());
        allDimensions.addAll(sittingByDimension.keySet());
        allDimensions.addAll(roamingByDimension.keySet());
        allDimensions.addAll(independentByDimension.keySet());

        if (allDimensions.isEmpty()) {
            player.sendMessage(Text.of("§7No pets found"), false);
            return;
        }

        // Sort dimensions for consistent display
        List<String> sortedDimensions = allDimensions.stream().sorted().toList();

        for (String dimension : sortedDimensions) {
            String dimensionColor = getDimensionColor(dimension);
            player.sendMessage(Text.of(""), false); // Spacing
            player.sendMessage(Text.of(dimensionColor + "=== " + dimension.toUpperCase() + " ==="), false);

            // Show standing pets for this dimension
            List<PetInfo> standingInDim = standingByDimension.getOrDefault(dimension, List.of());
            if (!standingInDim.isEmpty()) {
                player.sendMessage(Text.of("§2⚡ Following pets (" + standingInDim.size() + "):"), false);
                for (PetInfo pet : standingInDim) {
                    String status = getRestrictedPetStatus(pet);
                    player.sendMessage(Text.of("§f  • " + pet.getDisplayName() + " §7at " + pet.getLocationString() + status), false);
                }
            }

            // Show sitting pets for this dimension
            List<PetInfo> sittingInDim = sittingByDimension.getOrDefault(dimension, List.of());
            if (!sittingInDim.isEmpty()) {
                player.sendMessage(Text.of("§9⏸ Sitting pets (" + sittingInDim.size() + "):"), false);
                for (PetInfo pet : sittingInDim) {
                    player.sendMessage(Text.of("§f  • " + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            // Show roaming pets for this dimension
            List<PetInfo> roamingInDim = roamingByDimension.getOrDefault(dimension, List.of());
            if (!roamingInDim.isEmpty()) {
                player.sendMessage(Text.of("§6🐎 Roaming pets (" + roamingInDim.size() + "):"), false);
                for (PetInfo pet : roamingInDim) {
                    player.sendMessage(Text.of("§f  • " + pet.getDisplayName() + " §7at " + pet.getLocationString()), false);
                }
            }

            // Show independent pets for this dimension (IndyPets)
            List<PetInfo> independentInDim = independentByDimension.getOrDefault(dimension, List.of());
            if (!independentInDim.isEmpty()) {
                player.sendMessage(Text.of("§d🐾 Independent pets (" + independentInDim.size() + "):"), false);
                for (PetInfo pet : independentInDim) {
                    String homeInfo = pet.hasHomePos ? " §8[Home: " + pet.getHomePosString() + "]" : "";
                    player.sendMessage(Text.of("§f  • " + pet.getDisplayName() + " §7at " + pet.getLocationString() + homeInfo), false);
                }
            }

            if (standingInDim.isEmpty() && sittingInDim.isEmpty() && roamingInDim.isEmpty() && independentInDim.isEmpty()) {
                player.sendMessage(Text.of("§8  No pets in this dimension"), false);
            }
        }

        // Summary - only show categories with pets
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
            case "overworld" -> "§a";  // Green
            case "nether", "the_nether" -> "§c";  // Red
            case "end", "the_end" -> "§5";  // Purple
            default -> "§6";  // Orange for custom dimensions
        };
    }

    private static String getWorldDisplayName(ServerWorld world) {
        String worldName = world.getRegistryKey().getValue().toString();

        // Handle vanilla worlds
        if (worldName.equals("minecraft:overworld")) {
            return "overworld";
        } else if (worldName.equals("minecraft:the_nether")) {
            return "nether";
        } else if (worldName.equals("minecraft:the_end")) {
            return "end";
        } else {
            // Handle custom/modded worlds
            if (worldName.contains(":")) {
                String[] parts = worldName.split(":");
                if (parts.length >= 2) {
                    String namespace = parts[0];
                    String path = parts[1];

                    // If it's from minecraft namespace but not vanilla, show the path
                    if (namespace.equals("minecraft")) {
                        return path;
                    } else {
                        // For modded worlds, show namespace:path format but clean it up
                        return namespace + ":" + path;
                    }
                }
            }

            // Fallback: just remove minecraft: prefix if present
            return worldName.replace("minecraft:", "");
        }
    }

    private static void loadPetChunks(ServerPlayerEntity player, List<PetInfo> standingPets,
                                      List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        Set<ChunkPos> chunksLoaded = new HashSet<>();
        int petsToRecover = 0;
        int restrictedPets = 0;

        // Only load chunks for standing pets that aren't restricted or independent
        for (PetInfo pet : standingPets) {
            if (pet.isLeashed || pet.inVehicle) {
                restrictedPets++;
                continue; // Skip leashed or vehicle-riding pets
            }

            ChunkPos chunkPos = pet.chunkPos;
            if (!chunksLoaded.contains(chunkPos)) {
                // Load chunk temporarily
                pet.world.getChunkManager().addTicket(
                        PetChunkTickets.PET_TICKET_TYPE,
                        chunkPos,
                        3 // Higher priority to ensure loading
                );
                chunksLoaded.add(chunkPos);

                // Schedule cleanup after 60 seconds
                scheduleChunkCleanup(pet.world, chunkPos, 1200);
            }
            petsToRecover++;
        }

        // Report results
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
                                          List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets,
                                          int processedFiles, int totalFiles) {
        int filesScanned = 0;
        int chunksScanned = 0;

        try {
            Path worldPath = player.getServer().getSavePath(WorldSavePath.ROOT).normalize();
            List<Path> possibleEntityPaths = getPossibleEntityPaths(world, worldPath);

            String worldName = world.getRegistryKey().getValue().toString();

            // Try each possible path
            for (Path entitiesPath : possibleEntityPaths) {
                if (!Files.exists(entitiesPath)) {
                    continue;
                }

                // Scan all region files
                List<Path> regionFiles = Files.list(entitiesPath)
                        .filter(path -> path.toString().endsWith(".mca"))
                        .toList();

                if (!regionFiles.isEmpty()) {
                    for (Path regionPath : regionFiles) {
                        try {
                            updateProgressBar(player, processedFiles + filesScanned, totalFiles);
                            int chunks = scanRegionFileForPets(regionPath, player.getUuid(), world, standingPets, sittingPets, roamingPets, independentPets);
                            filesScanned++;
                            chunksScanned += chunks;
                        } catch (Exception e) {
                            player.sendMessage(Text.of("§cError scanning " + regionPath.getFileName() + ": " + e.getMessage()), false);
                        }
                    }

                    // Display how many region files were found for this world
                    String worldDisplayName = getWorldDisplayName(world);
                    player.sendMessage(Text.of("§7Found " + regionFiles.size() + " " + worldDisplayName + " region files"), false);

                    break; // Found and processed a valid entities directory, no need to try others
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
            // Overworld entities are in the root entities folder
            possibleEntityPaths.add(worldPath.resolve("entities"));
        } else if (worldName.equals("minecraft:the_nether")) {
            // Nether can be in multiple locations depending on version
            possibleEntityPaths.add(worldPath.resolve("DIM-1").resolve("entities"));
            possibleEntityPaths.add(worldPath.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("entities"));
        } else if (worldName.equals("minecraft:the_end")) {
            // End can be in multiple locations depending on version
            possibleEntityPaths.add(worldPath.resolve("DIM1").resolve("entities"));
            possibleEntityPaths.add(worldPath.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("entities"));
        } else {
            // Custom dimensions
            possibleEntityPaths.add(worldPath.resolve("dimensions")
                    .resolve(world.getRegistryKey().getValue().getNamespace())
                    .resolve(world.getRegistryKey().getValue().getPath())
                    .resolve("entities"));
        }

        return possibleEntityPaths;
    }

    private static int[] scanAdditionalDimensions(ServerPlayerEntity player, Path worldPath,
                                                  List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        int filesScanned = 0;
        int chunksScanned = 0;

        try {
            // Check for any dimension folders that might contain entities
            Set<String> knownDimensions = new HashSet<>();
            knownDimensions.add("overworld"); // Skip overworld as it's handled separately
            knownDimensions.add("the_nether");
            knownDimensions.add("the_end");

            // Check dimensions folder for custom dimensions
            Path dimensionsPath = worldPath.resolve("dimensions");
            if (Files.exists(dimensionsPath)) {
                Files.walk(dimensionsPath, 3)
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals("entities"))
                        .forEach(entitiesPath -> {
                            try {
                                // Extract dimension info from path
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
                                        // Note: We can't provide a ServerWorld for custom dimensions not loaded by the server
                                        // These would need special handling or could be skipped
                                    }
                                }
                            } catch (Exception e) {
                                // Skip problematic dimension folders
                            }
                        });
            }

            // Check legacy DIM folders that might not be handled by ServerWorlds
            // Only check these if we didn't already process them as ServerWorlds
            Set<String> processedWorlds = new HashSet<>();
            for (ServerWorld world : player.getServer().getWorlds()) {
                String worldName = world.getRegistryKey().getValue().toString();
                processedWorlds.add(worldName);
            }

            // Only check legacy dimensions if they weren't already processed
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
                    String dimensionDisplayName = dimensionName;
                    if (dimensionName.equals("overworld")) {
                        dimensionDisplayName = "overworld";
                    } else if (dimensionName.equals("nether")) {
                        dimensionDisplayName = "nether";
                    } else if (dimensionName.equals("end")) {
                        dimensionDisplayName = "end";
                    }

                    player.sendMessage(Text.of("§7Found " + regionFiles.size() + " " + dimensionDisplayName + " region files"), false);
                    // For dimensions, we'd need the appropriate ServerWorld reference
                    // This might require looking up the correct world by dimension type
                }
            }
        } catch (Exception e) {
            // Skip problematic legacy dimensions
        }
    }

    private static int scanRegionFileForPets(Path regionPath, UUID playerUUID, ServerWorld world,
                                             List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) throws IOException {
        String fileName = regionPath.getFileName().toString();
        String[] parts = fileName.replace(".mca", "").split("\\.");
        if (parts.length != 3) return 0;

        int chunksScanned = 0;

        try {
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);

            StorageKey storageKey = new StorageKey("entities", world.getRegistryKey(), "entities");
            try (RegionFile regionFile = new RegionFile(storageKey, regionPath, regionPath.getParent(), false)) {

                // Check ALL chunks in the region (32x32 = 1024 chunks)
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos chunkPos = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);

                        if (regionFile.hasChunk(chunkPos)) {
                            chunksScanned++;
                            try (DataInputStream inputStream = regionFile.getChunkInputStream(chunkPos)) {
                                if (inputStream != null) {
                                    // Read and parse chunk NBT data
                                    NbtCompound chunkNbt = NbtIo.readCompound(inputStream, NbtSizeTracker.ofUnlimitedBytes());
                                    if (chunkNbt != null) {
                                        parseChunkForPets(chunkNbt, playerUUID, world, chunkPos, standingPets, sittingPets, roamingPets, independentPets);
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
                                          ChunkPos chunkPos, List<PetInfo> standingPets, List<PetInfo> sittingPets, List<PetInfo> roamingPets, List<PetInfo> independentPets) {
        if (!chunkNbt.contains("Entities")) return;

        Optional<NbtList> entitiesOpt = chunkNbt.getList("Entities");
        if (entitiesOpt.isEmpty()) return;

        NbtList entities = entitiesOpt.get();

        for (int i = 0; i < entities.size(); i++) {
            Optional<NbtCompound> entityOpt = entities.getCompound(i);
            if (entityOpt.isEmpty()) continue;

            NbtCompound entity = entityOpt.get();

            // Check if this is a direct tameable entity owned by our player
            if (isTameablePetOwnedByPlayer(entity, playerUUID)) {
                PetInfo petInfo = createPetInfoFromNBT(entity, chunkPos, world);
                if (petInfo != null) {
                    categorizePet(petInfo, standingPets, sittingPets, roamingPets, independentPets);
                }
            }

            // Also check if this entity has passengers (vehicles with pets)
            if (entity.contains("Passengers")) {
                Optional<NbtList> passengersOpt = entity.getList("Passengers");
                if (passengersOpt.isPresent()) {
                    NbtList passengers = passengersOpt.get();

                    for (int j = 0; j < passengers.size(); j++) {
                        Optional<NbtCompound> passengerOpt = passengers.getCompound(j);
                        if (passengerOpt.isPresent()) {
                            NbtCompound passenger = passengerOpt.get();

                            // Check if this passenger is a tameable pet owned by our player
                            if (isTameablePetOwnedByPlayer(passenger, playerUUID)) {
                                PetInfo petInfo = createPetInfoFromNBT(passenger, chunkPos, world);
                                if (petInfo != null) {
                                    // Mark this pet as being in a vehicle
                                    PetInfo vehiclePetInfo = new PetInfo(
                                            petInfo.uuid, petInfo.type, petInfo.customName,
                                            petInfo.x, petInfo.y, petInfo.z, petInfo.chunkPos,
                                            petInfo.world, petInfo.sitting, petInfo.isLeashed, true, // inVehicle = true
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

        // Categorize pets with sitting taking priority over independence
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

        // Get pet UUID from int array format
        Optional<int[]> uuidArrayOpt = entity.getIntArray("UUID");
        if (uuidArrayOpt.isEmpty() || uuidArrayOpt.get().length != 4) return null;

        int[] uuidArray = uuidArrayOpt.get();
        long mostSigBits = (long)uuidArray[0] << 32 | (long)uuidArray[1] & 0xFFFFFFFFL;
        long leastSigBits = (long)uuidArray[2] << 32 | (long)uuidArray[3] & 0xFFFFFFFFL;
        UUID petUUID = new UUID(mostSigBits, leastSigBits);

        // Get custom name if available
        String customName = null;
        if (entity.contains("CustomName")) {
            customName = entity.getString("CustomName", null);
            if (customName != null && customName.startsWith("\"") && customName.endsWith("\"")) {
                customName = customName.substring(1, customName.length() - 1); // Remove quotes
            }
        }

        // Check if pet is leashed
        boolean isLeashed = entity.contains("leash");

        // Check if pet is independent (IndyPets)
        boolean isIndependent = IndyPetsHelper.isIndependentFromNBT(entity);

        // Get IndyPets home position if available
        boolean hasHomePos = false;
        int homeX = 0, homeY = 0, homeZ = 0;
        int[] homePos = IndyPetsHelper.getIndyPetsHomePos(entity);
        if (homePos != null) {
            hasHomePos = true;
            homeX = homePos[0];
            homeY = homePos[1];
            homeZ = homePos[2];
        }

        // Check if pet is in a vehicle by scanning all entities for vehicles with this pet as passenger
        boolean inVehicle = false;
        // This is expensive so we'll skip it for NBT-based detection for now
        // Vehicle detection will be done during live entity scanning

        return new PetInfo(petUUID, entityId, customName, x, y, z, chunkPos, world, sitting, isLeashed, inVehicle, isIndependent, hasHomePos, homeX, homeY, homeZ);
    }

    // Enhanced method to scan for pets in vehicles within loaded chunks
    private static void scanLoadedEntitiesForVehicles(ServerPlayerEntity player, List<PetInfo> standingPets) {
        for (ServerWorld world : player.getServer().getWorlds()) {
            // Look for vehicles with pet passengers
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity.hasPassengers()) {
                    for (net.minecraft.entity.Entity passenger : entity.getPassengerList()) {
                        if (passenger instanceof TameableEntity pet &&
                                pet.isTamed() &&
                                pet.getOwner() == player) {

                            // Mark existing standing pets as being in vehicles
                            UUID petUUID = pet.getUuid();
                            for (PetInfo petInfo : standingPets) {
                                if (petInfo.uuid.equals(petUUID)) {
                                    // Update the pet info to show it's in a vehicle
                                    // Since PetInfo is immutable, we need to create a new instance
                                    PetInfo updatedPetInfo = new PetInfo(
                                            petInfo.uuid, petInfo.type, petInfo.customName,
                                            petInfo.x, petInfo.y, petInfo.z, petInfo.chunkPos,
                                            petInfo.world, petInfo.sitting, petInfo.isLeashed, true,
                                            petInfo.isIndependent, petInfo.hasHomePos, petInfo.homeX, petInfo.homeY, petInfo.homeZ
                                    );

                                    // Replace in list
                                    int index = standingPets.indexOf(petInfo);
                                    if (index >= 0) {
                                        standingPets.set(index, updatedPetInfo);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isTameablePetOwnedByPlayer(NbtCompound entity, UUID playerUUID) {
        // Check if entity has an Owner field
        if (!entity.contains("Owner")) {
            return false;
        }

        // Filter out projectiles and non-pet entities
        String entityId = entity.getString("id", "");

        // Exclude projectiles and other non-pet entities with Owner fields
        if (entityId.contains("arrow") || entityId.contains("trident") ||
                entityId.contains("firework") || entityId.contains("fireball") ||
                entityId.contains("snowball") || entityId.contains("egg") ||
                entityId.contains("potion") || entityId.contains("pearl") ||
                entityId.contains("llama_spit") || entityId.contains("shulker_bullet")) {
            return false;
        }

        // Check for tameable indicators - real pets should have at least one of these
        boolean hasTameableFields = entity.contains("Sitting") ||     // Most pets
                entity.contains("Tame") ||        // Horses
                entity.contains("CollarColor") ||  // Wolves, cats
                entity.contains("variant") ||     // Most pets have variants
                entity.contains("Bred");          // Breeding animals

        if (!hasTameableFields) {
            return false;
        }

        // Get owner UUID from int array format
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
        world.getServer().execute(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(delayTicks * 50L); // Convert ticks to milliseconds
                    world.getServer().execute(() -> {
                        world.getChunkManager().removeTicket(
                                PetChunkTickets.PET_TICKET_TYPE,
                                chunkPos,
                                3
                        );
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    // Helper method to check if a live entity is independent (IndyPets integration)
    private static boolean isIndependentPet(TameableEntity pet) {
        // Only check for independence if IndyPets is actually installed
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
        final boolean isIndependent; // IndyPets support
        final boolean hasHomePos;   // IndyPets home position
        final int homeX, homeY, homeZ; // IndyPets home coordinates

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

        // Constructor for backwards compatibility (without IndyPets fields)
        PetInfo(UUID uuid, String type, String customName, double x, double y, double z,
                ChunkPos chunkPos, ServerWorld world, boolean sitting, boolean isLeashed, boolean inVehicle) {
            this(uuid, type, customName, x, y, z, chunkPos, world, sitting, isLeashed, inVehicle, false, false, 0, 0, 0);
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
        final String status;

        PetDetails(UUID uuid, String type, String displayName, double x, double y, double z, String worldName, boolean sitting, String status) {
            this.uuid = uuid;
            this.type = type;
            this.displayName = displayName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.worldName = worldName;
            this.sitting = sitting;
            this.status = status;
        }

        // Constructor for backwards compatibility
        PetDetails(UUID uuid, String type, String displayName, double x, double y, double z, String worldName, boolean sitting) {
            this(uuid, type, displayName, x, y, z, worldName, sitting, "");
        }

        String getLocationString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }
}