package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.ChunkLoadingPetTracker;
import org.tecna.followersloadchunks.PetChunkManager;
import org.tecna.followersloadchunks.PetChunkTickets;

import java.util.Map;
import java.util.UUID;

@Mixin(LivingEntity.class)
public class TameableEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onEntityTick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // Only process if this is a tameable entity AND we're on the server side
        if (entity instanceof TameableEntity pet && !pet.getWorld().isClient) {
            // Add synchronization to prevent concurrent modification with async ticking mods
            synchronized (this) {
                if (pet.isTamed() && pet.getOwner() instanceof ServerPlayerEntity owner) {
                    UUID petUUID = pet.getUuid();
                    boolean currentSitting = pet.isSitting();
                    Boolean previousSitting = PetChunkManager.getPreviousSittingState(petUUID);

                    // Update previous state
                    PetChunkManager.updatePreviousSittingState(petUUID, currentSitting);

                    // Check if pet is restricted (sitting, leashed, or in vehicle)
                    boolean isRestricted = currentSitting ||
                            isLeashed(pet) ||
                            isInVehicle(pet);

                    // Pet should load chunks only if owner is online AND in same dimension AND not restricted
                    boolean sameWorldAsOwner = pet.getWorld().getRegistryKey().equals(owner.getWorld().getRegistryKey());
                    boolean ownerOnline = !owner.isDisconnected();
                    boolean shouldLoadChunks = !isRestricted && ownerOnline && sameWorldAsOwner;

                    boolean isCurrentlyLoading = PetChunkManager.isPetActivelyLoadingChunks(petUUID);

                    // Handle state changes
                    if (previousSitting != null && previousSitting != currentSitting) {
                        // Pet sitting state changed
                        if (currentSitting && isCurrentlyLoading) {
                            // Pet just sat down - stop loading chunks immediately
                            stopLoadingChunks(pet, owner);
                            return;
                        } else if (!currentSitting && !isCurrentlyLoading && ownerOnline && sameWorldAsOwner && !isLeashed(pet) && !isInVehicle(pet)) {
                            // Pet just stood up - start loading chunks
                            startLoadingChunks(pet, owner);
                            return;
                        }
                    }

                    if (shouldLoadChunks) {
                        ChunkPos currentChunk = new ChunkPos(pet.getBlockPos());
                        ChunkPos oldChunk = PetChunkManager.getActivePetTickets().get(petUUID);

                        // Update if not loading chunks yet, or moved to a different chunk
                        if (!isCurrentlyLoading) {
                            startLoadingChunks(pet, owner);
                        } else if (oldChunk != null && !oldChunk.equals(currentChunk)) {
                            // Pet moved to different chunk - update chunk ticket location
                            updateChunkLocation(pet, owner, oldChunk, currentChunk);
                        }
                    } else if (isCurrentlyLoading) {
                        // Pet should stop loading chunks (sitting, owner offline, different dimension, or restricted)
                        stopLoadingChunks(pet, owner);
                    }
                } else if (entity instanceof TameableEntity) {
                    // Pet is not tamed or owner is null - clean up any tickets
                    UUID petUUID = entity.getUuid();
                    if (PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
                        ChunkPos chunkPos = PetChunkManager.removeActivePetTicket(petUUID);
                        if (chunkPos != null) {
                            try {
                                ServerWorld world = (ServerWorld) entity.getWorld();
                                world.getChunkManager().removeTicket(
                                        PetChunkTickets.PET_TICKET_TYPE,
                                        chunkPos,
                                        2
                                );
                            } catch (Exception e) {
                                // Ignore errors during cleanup to prevent crashes
                                System.out.println("[FollowersLoadChunks] Error during cleanup: " + e.getMessage());
                            }
                        }
                        PetChunkManager.removeSittingStateTracking(petUUID);
                    }
                }
            }
        }
    }

    // Helper method to check if pet is leashed
    private boolean isLeashed(TameableEntity pet) {
        try {
            return pet.isLeashed();
        } catch (Exception e) {
            return false;
        }
    }

    // Helper method to check if pet is in a vehicle
    private boolean isInVehicle(TameableEntity pet) {
        try {
            return pet.hasVehicle() || pet.getVehicle() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onEntityDeath(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (entity instanceof TameableEntity pet && pet.getOwner() instanceof ServerPlayerEntity owner) {
            stopLoadingChunks(pet, owner);
            // Clean up state tracking
            PetChunkManager.removeSittingStateTracking(pet.getUuid());
        }
    }

    private void startLoadingChunks(TameableEntity pet, ServerPlayerEntity owner) {
        ServerWorld world = (ServerWorld) pet.getWorld();
        ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
        UUID petUUID = pet.getUuid();

        // Double-check to prevent race conditions
        if (PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
            return; // Already loading chunks
        }

        // Check if this player has too many pets loading chunks
        Map<UUID, ChunkPos> activePetTickets = PetChunkManager.getActivePetTickets();
        long playerPetCount = activePetTickets.entrySet().stream()
                .filter(entry -> {
                    Entity entity = world.getEntity(entry.getKey());
                    return entity instanceof TameableEntity tameable &&
                            tameable.getOwner() != null &&
                            tameable.getOwner().getUuid().equals(owner.getUuid());
                })
                .count();

        if (playerPetCount >= 10) {
            owner.sendMessage(Text.of("§cWarning: You have " + playerPetCount + " pets loading chunks across the world. Consider gathering them to reduce server load."), false);
        }

        // Simple, efficient approach - just load the pet's chunk with good radius
        world.getChunkManager().addTicket(
                PetChunkTickets.PET_TICKET_TYPE,
                chunkPos,
                2  // Same level as ender pearls use
        );

        // Track locally
        PetChunkManager.addActivePetTicket(petUUID, chunkPos);

        // Add to player's persistent data
        ((ChunkLoadingPetTracker) owner).followersLoadChunks$addChunkLoadingPet(pet);
    }

    private void stopLoadingChunks(TameableEntity pet, ServerPlayerEntity owner) {
        UUID petUUID = pet.getUuid();
        ChunkPos chunkPos = PetChunkManager.removeActivePetTicket(petUUID);

        if (chunkPos != null) {
            ServerWorld world = (ServerWorld) pet.getWorld();
            world.getChunkManager().removeTicket(
                    PetChunkTickets.PET_TICKET_TYPE,
                    chunkPos,
                    2
            );

            // Remove from player's persistent data
            ((ChunkLoadingPetTracker) owner).followersLoadChunks$removeChunkLoadingPet(pet);
        }

        // Clean up state tracking
        PetChunkManager.removeSittingStateTracking(petUUID);
    }

    private void updateChunkLocation(TameableEntity pet, ServerPlayerEntity owner, ChunkPos oldChunk, ChunkPos newChunk) {
        ServerWorld world = (ServerWorld) pet.getWorld();
        UUID petUUID = pet.getUuid();

        // Double-check the pet is still in our tracking map
        if (!PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
            return;
        }

        // Calculate distance to determine if this is fast travel
        double distance = Math.sqrt(Math.pow(newChunk.x - oldChunk.x, 2) + Math.pow(newChunk.z - oldChunk.z, 2));
        int radius = distance > 5 ? 4 : 2; // Use larger radius for fast movement
        int overlap = distance > 10 ? 100 : 20; // Longer overlap for very fast movement

        // Add new chunk ticket FIRST with enhanced radius for safety
        world.getChunkManager().addTicket(
                PetChunkTickets.PET_TICKET_TYPE,
                newChunk,
                radius
        );

        // Update tracking map
        PetChunkManager.addActivePetTicket(petUUID, newChunk);

        // Schedule old chunk cleanup with overlap to prevent gaps
        world.getServer().execute(() -> {
            new Thread(() -> {
                try {
                    Thread.sleep(overlap); // Overlap period
                    world.getServer().execute(() -> {
                        world.getChunkManager().removeTicket(
                                PetChunkTickets.PET_TICKET_TYPE,
                                oldChunk,
                                2 // Remove with original radius
                        );
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }
}