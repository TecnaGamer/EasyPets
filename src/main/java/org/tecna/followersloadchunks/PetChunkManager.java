package org.tecna.followersloadchunks;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PetChunkManager {

    // Central tracking maps - moved from the Mixin
    private static final Map<UUID, ChunkPos> activePetTickets = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> previousSittingState = new ConcurrentHashMap<>();

    // Track players who need auto-recovery
    private static final Set<UUID> playersNeedingAutoRecovery = new HashSet<>();

    /**
     * Get the active pet tickets map
     */
    public static Map<UUID, ChunkPos> getActivePetTickets() {
        return activePetTickets;
    }

    /**
     * Get the previous sitting state map
     */
    public static Map<UUID, Boolean> getPreviousSittingState() {
        return previousSittingState;
    }

    /**
     * Mark a player as needing auto-recovery on their next connection
     */
    public static void markPlayerForAutoRecovery(ServerPlayerEntity player) {
        playersNeedingAutoRecovery.add(player.getUuid());
        System.out.println("[FollowersLoadChunks] Marked player " + player.getGameProfile().getName() + " for auto-recovery");
    }

    /**
     * Check if a player needs auto-recovery
     */
    public static boolean needsAutoRecovery(ServerPlayerEntity player) {
        return playersNeedingAutoRecovery.contains(player.getUuid());
    }

    /**
     * Clear auto-recovery flag for a player
     */
    public static void clearAutoRecoveryFlag(ServerPlayerEntity player) {
        playersNeedingAutoRecovery.remove(player.getUuid());
    }

    /**
     * Clean up all pet chunk tickets for a specific player when they disconnect
     */
    public static synchronized void cleanupPlayerPetTickets(ServerPlayerEntity player) {
        try {
            int totalTickets = activePetTickets.size();

            // Create a list of tickets to remove to avoid concurrent modification
            List<UUID> ticketsToRemove = new ArrayList<>();

            for (Map.Entry<UUID, ChunkPos> entry : activePetTickets.entrySet()) {
                UUID petUUID = entry.getKey();
                ChunkPos chunkPos = entry.getValue();

                boolean shouldRemove = false;

                // Find the pet to check if it belongs to this player
                for (ServerWorld world : player.getServer().getWorlds()) {
                    try {
                        Entity entity = world.getEntity(petUUID);
                        if (entity instanceof TameableEntity pet && pet.getOwner() == player) {
                            world.getChunkManager().removeTicket(
                                    PetChunkTickets.PET_TICKET_TYPE,
                                    chunkPos,
                                    2
                            );
                            shouldRemove = true;
                            break;
                        }
                    } catch (Exception e) {
                        // If we can't access the pet, assume it belongs to this player and clean up
                        System.out.println("[FollowersLoadChunks] Error checking pet ownership during cleanup: " + e.getMessage());
                        shouldRemove = true;
                        break;
                    }
                }

                if (shouldRemove) {
                    ticketsToRemove.add(petUUID);
                }
            }

            // Remove tickets from maps
            for (UUID petUUID : ticketsToRemove) {
                activePetTickets.remove(petUUID);
                previousSittingState.remove(petUUID);
            }

            int actualRemoved = ticketsToRemove.size();
            int remainingTickets = activePetTickets.size();

            // Log to server console
            System.out.println("[FollowersLoadChunks] Player " + player.getGameProfile().getName() +
                    " disconnected. Removed " + actualRemoved + " pet tickets. " +
                    remainingTickets + " tickets still active.");

        } catch (Exception e) {
            System.out.println("[FollowersLoadChunks] Error during player cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Restore pet chunk loading for a player when they reconnect
     */
    public static void restorePlayerPetChunkLoading(ServerPlayerEntity player) {
        if (!(player instanceof ChunkLoadingPetTracker tracker)) {
            return;
        }

        // Scan all worlds for this player's pets that should be loading chunks
        for (ServerWorld world : player.getServer().getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet) {
                    if (pet.isTamed() && pet.getOwner() == player && !pet.isSitting()) {
                        // Check if this pet was supposed to be loading chunks
                        if (tracker.followersLoadChunks$isPetChunkLoading(pet.getUuid())) {
                            // Restore chunk loading
                            ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
                            world.getChunkManager().addTicket(
                                    PetChunkTickets.PET_TICKET_TYPE,
                                    chunkPos,
                                    2
                            );

                            // Add back to active tracking
                            activePetTickets.put(pet.getUuid(), chunkPos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Add a pet ticket to active tracking
     */
    public static void addActivePetTicket(UUID petUUID, ChunkPos chunkPos) {
        activePetTickets.put(petUUID, chunkPos);
    }

    /**
     * Remove a pet ticket from active tracking
     */
    public static ChunkPos removeActivePetTicket(UUID petUUID) {
        return activePetTickets.remove(petUUID);
    }

    /**
     * Check if a pet is currently loading chunks
     */
    public static boolean isPetActivelyLoadingChunks(UUID petUUID) {
        return activePetTickets.containsKey(petUUID);
    }

    /**
     * Get the current number of active pet tickets (for debugging)
     */
    public static int getActivePetTicketCount() {
        return activePetTickets.size();
    }

    /**
     * Update previous sitting state for a pet
     */
    public static void updatePreviousSittingState(UUID petUUID, boolean sitting) {
        previousSittingState.put(petUUID, sitting);
    }

    /**
     * Get previous sitting state for a pet
     */
    public static Boolean getPreviousSittingState(UUID petUUID) {
        return previousSittingState.get(petUUID);
    }

    /**
     * Remove sitting state tracking for a pet
     */
    public static void removeSittingStateTracking(UUID petUUID) {
        previousSittingState.remove(petUUID);
    }

    /**
     * Validate that pets with chunk tickets are actually loaded and accessible
     */
    public static void validatePetChunkLoading(ServerPlayerEntity player) {
        if (!(player instanceof ChunkLoadingPetTracker tracker)) {
            return;
        }

        int missingPets = 0;
        int validatedPets = 0;

        // First pass: check pets that are currently loaded
        for (ServerWorld world : player.getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet && pet.getOwner() == player) {
                    UUID petUUID = pet.getUuid();

                    // Check if this pet should be loading chunks according to our tracker
                    if (tracker.followersLoadChunks$isPetChunkLoading(petUUID)) {
                        validatedPets++;

                        // Check if pet is currently sitting - use isInSittingPose() for 1.21.7
                        boolean currentSitting = pet.isInSittingPose();

                        // Verify the pet is actually accessible and not sitting
                        if (pet.isTamed() && !currentSitting) {
                            // Check if we have an active ticket for this pet
                            if (isPetActivelyLoadingChunks(petUUID)) {
                                validatedPets++;
                            } else {
                                // Pet should be loading but isn't - restore chunk loading
                                ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
                                world.getChunkManager().addTicket(
                                        PetChunkTickets.PET_TICKET_TYPE,
                                        chunkPos,
                                        2
                                );
                                addActivePetTicket(petUUID, chunkPos);
                                System.out.println("[FollowersLoadChunks] Restored chunk loading for pet " + petUUID + " belonging to " + player.getGameProfile().getName());
                            }
                        } else {
                            // Pet is sitting now, should not be loading chunks
                            if (isPetActivelyLoadingChunks(petUUID)) {
                                ChunkPos chunkPos = removeActivePetTicket(petUUID);
                                if (chunkPos != null) {
                                    world.getChunkManager().removeTicket(
                                            PetChunkTickets.PET_TICKET_TYPE,
                                            chunkPos,
                                            2
                                    );
                                }
                                tracker.followersLoadChunks$removeChunkLoadingPet(pet);
                            }
                        }
                    }
                }
            }
        }

        // Second pass: check for pets that should be loading chunks but weren't found
        Set<UUID> trackedPets = new HashSet<>();

        // Get all pets the tracker thinks should be loading chunks
        for (ServerWorld world : player.getServer().getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet && pet.getOwner() == player) {
                    if (tracker.followersLoadChunks$isPetChunkLoading(pet.getUuid())) {
                        trackedPets.add(pet.getUuid());
                    }
                }
            }
        }

        // Count pets with active tickets that weren't found in loaded entities
        for (UUID petUUID : activePetTickets.keySet()) {
            // Check if this pet belongs to our player by checking if it's in their tracker
            if (tracker.followersLoadChunks$isPetChunkLoading(petUUID)) {
                boolean petFound = false;
                for (ServerWorld world : player.getServer().getWorlds()) {
                    if (world.getEntity(petUUID) != null) {
                        petFound = true;
                        break;
                    }
                }
                if (!petFound) {
                    missingPets++;
                    System.out.println("[FollowersLoadChunks] Pet " + petUUID + " should be loaded but not found for player " + player.getGameProfile().getName());
                }
            }
        }

        // If we have missing pets, trigger immediate recovery (no need to wait for saves)
        if (missingPets > 0) {
            triggerImmediateRecovery(player, missingPets);
        }
    }

    /**
     * Trigger immediate pet recovery without waiting for save cycles
     */
    public static void triggerImmediateRecovery(ServerPlayerEntity player, int missingPetCount) {
        System.out.println("[FollowersLoadChunks] Triggering immediate recovery for " + missingPetCount + " missing pets for " + player.getGameProfile().getName());

        // Run recovery after a short delay to allow chunk loading to settle
        player.getServer().execute(() -> {
            new Thread(() -> {
                try {
                    // Short delay to allow any pending chunk operations to complete
                    Thread.sleep(5000); // 5 seconds instead of 30
                    player.getServer().execute(() -> {
                        if (!player.isDisconnected()) {
                            System.out.println("[FollowersLoadChunks] Executing auto-recovery for missing pets for " + player.getGameProfile().getName());
                            player.sendMessage(net.minecraft.text.Text.of("§7[FollowersLoadChunks] Detected unloaded pets, running recovery..."));

                            // Run pet recovery
                            try {
                                org.tecna.followersloadchunks.PetRecoveryCommand.runPetRecoveryForPlayer(player, false);
                            } catch (Exception e) {
                                System.out.println("[FollowersLoadChunks] Error during auto-recovery: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    /**
     * Run periodic validation for all online players
     */
    public static void runPeriodicValidation(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            validatePetChunkLoading(player);
        }
    }
}