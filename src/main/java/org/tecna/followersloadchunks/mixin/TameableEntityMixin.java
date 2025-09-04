package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.ChunkLoadingPetTracker;
import org.tecna.followersloadchunks.PetChunkManager;
import org.tecna.followersloadchunks.PetChunkTickets;
import org.tecna.followersloadchunks.IndyPetsHelper;
import org.tecna.followersloadchunks.config.Config;

import java.util.UUID;

@Mixin(LivingEntity.class)
public class TameableEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onEntityTick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // Only process if this is a tameable entity AND we're on the server side
        if (entity instanceof TameableEntity pet && !pet.getWorld().isClient) {
            // Skip all chunk loading logic if disabled in config
            Config config = Config.getInstance();
            if (!config.isChunkLoadingEnabled()) {
                return;
            }

            // Add synchronization to prevent concurrent modification with async ticking mods
            synchronized (this) {
                try {
                    // Null safety checks
                    if (pet.getWorld() == null || pet.getOwner() == null) {
                        return;
                    }

                    if (pet.isTamed() && pet.getOwner() instanceof ServerPlayerEntity owner) {
                        // Additional null checks for server state
                        if (owner.getServer() == null || owner.getWorld() == null) {
                            return;
                        }

                        UUID petUUID = pet.getUuid();
                        if (petUUID == null) {
                            return;
                        }

                        boolean currentSitting = pet.isSitting();
                        Boolean previousSitting = PetChunkManager.getPreviousSittingState(petUUID);

                        // Update previous state
                        PetChunkManager.updatePreviousSittingState(petUUID, currentSitting);

                        // Check if pet is restricted (sitting, leashed, in vehicle, or independent)
                        boolean isRestricted = currentSitting ||
                                isLeashed(pet) ||
                                isInVehicle(pet) ||
                                isIndependent(pet);

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
                            } else if (!currentSitting && !isCurrentlyLoading && ownerOnline && sameWorldAsOwner && !isLeashed(pet) && !isInVehicle(pet) && !isIndependent(pet)) {
                                // Pet just stood up - start loading chunks (only if not independent)
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
                            // Pet should stop loading chunks (sitting, owner offline, different dimension, restricted, or independent)
                            stopLoadingChunks(pet, owner);
                        }
                    } else if (entity instanceof TameableEntity) {
                        // Pet is not tamed or owner is null - clean up any tickets
                        UUID petUUID = entity.getUuid();
                        if (petUUID != null && PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
                            ChunkPos chunkPos = PetChunkManager.removeActivePetTicket(petUUID);
                            if (chunkPos != null && entity.getWorld() instanceof ServerWorld world) {
                                try {
                                    if (world.getChunkManager() != null) {
                                        world.getChunkManager().removeTicket(
                                                PetChunkTickets.PET_TICKET_TYPE,
                                                chunkPos,
                                                config.getMaxChunkLoadingDistance()
                                        );
                                    }
                                } catch (Exception e) {
                                    // Ignore errors during cleanup to prevent crashes
                                    if (config.isDebugLoggingEnabled()) {
                                        System.out.println("[FollowersLoadChunks] Error during cleanup: " + e.getMessage());
                                    }
                                }
                            }
                            PetChunkManager.removeSittingStateTracking(petUUID);
                        }
                    }
                } catch (Exception e) {
                    // Catch any unexpected errors to prevent crashes during server startup
                    Config debugConfig = Config.getInstance();
                    if (debugConfig.isDebugLoggingEnabled()) {
                        System.out.println("[FollowersLoadChunks] Error in pet tick processing: " + e.getMessage());
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

    // Helper method to check if pet is independent (IndyPets integration)
    private boolean isIndependent(TameableEntity pet) {
        // Only check for independence if IndyPets is actually installed
        if (!IndyPetsHelper.isIndyPetsLoaded()) {
            return false;
        }
        return IndyPetsHelper.isPetIndependent(pet);
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
        try {
            Config config = Config.getInstance();

            // Null safety checks
            if (pet == null || owner == null || pet.getWorld() == null || !(pet.getWorld() instanceof ServerWorld)) {
                return;
            }

            ServerWorld world = (ServerWorld) pet.getWorld();
            if (world.getChunkManager() == null) {
                return;
            }

            ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
            UUID petUUID = pet.getUuid();

            if (petUUID == null) {
                return;
            }

            // Double-check to prevent race conditions
            if (PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
                return; // Already loading chunks
            }

            // Load the pet's chunk with configured radius
            world.getChunkManager().addTicket(
                    PetChunkTickets.PET_TICKET_TYPE,
                    chunkPos,
                    config.getMaxChunkLoadingDistance()
            );

            // Track locally
            PetChunkManager.addActivePetTicket(petUUID, chunkPos);

            // Add to player's persistent data
            if (owner instanceof ChunkLoadingPetTracker tracker) {
                tracker.followersLoadChunks$addChunkLoadingPet(pet);
            }

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Started chunk loading for pet " + petUUID + " at " + chunkPos);
            }
        } catch (Exception e) {
            Config config = Config.getInstance();
            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Error starting chunk loading for pet: " + e.getMessage());
            }
        }
    }

    private void stopLoadingChunks(TameableEntity pet, ServerPlayerEntity owner) {
        try {
            Config config = Config.getInstance();

            // Null safety checks
            if (pet == null || owner == null) {
                return;
            }

            UUID petUUID = pet.getUuid();
            if (petUUID == null) {
                return;
            }

            ChunkPos chunkPos = PetChunkManager.removeActivePetTicket(petUUID);

            if (chunkPos != null && pet.getWorld() instanceof ServerWorld world) {
                if (world.getChunkManager() != null) {
                    world.getChunkManager().removeTicket(
                            PetChunkTickets.PET_TICKET_TYPE,
                            chunkPos,
                            config.getMaxChunkLoadingDistance()
                    );
                }

                // Remove from player's persistent data
                if (owner instanceof ChunkLoadingPetTracker tracker) {
                    tracker.followersLoadChunks$removeChunkLoadingPet(pet);
                }
            }

            // Clean up state tracking
            PetChunkManager.removeSittingStateTracking(petUUID);

            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Stopped chunk loading for pet " + petUUID + " at " + chunkPos);
            }
        } catch (Exception e) {
            Config config = Config.getInstance();
            if (config.isDebugLoggingEnabled()) {
                System.out.println("[FollowersLoadChunks] Error stopping chunk loading: " + e.getMessage());
            }
        }
    }

    private void updateChunkLocation(TameableEntity pet, ServerPlayerEntity owner, ChunkPos oldChunk, ChunkPos newChunk) {
        Config config = Config.getInstance();
        ServerWorld world = (ServerWorld) pet.getWorld();
        UUID petUUID = pet.getUuid();

        // Double-check the pet is still in our tracking map
        if (!PetChunkManager.isPetActivelyLoadingChunks(petUUID)) {
            return;
        }

        // Calculate distance to determine if this is fast travel
        double distance = Math.sqrt(Math.pow(newChunk.x - oldChunk.x, 2) + Math.pow(newChunk.z - oldChunk.z, 2));

        // Use config values for fast movement detection
        boolean isFastMovement = config.isFastMovementDetectionEnabled() && distance > config.getFastMovementThreshold();
        int radius = isFastMovement ? Math.max(4, config.getMaxChunkLoadingDistance()) : config.getMaxChunkLoadingDistance();
        int overlap = isFastMovement ? config.getFastMovementOverlapTicks() : config.getChunkUnloadDelayTicks() / 60; // Shorter overlap for normal movement

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
                                config.getMaxChunkLoadingDistance() // Remove with configured radius
                        );
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });

        if (config.isDebugLoggingEnabled()) {
            System.out.println("[FollowersLoadChunks] Updated chunk location for pet " + petUUID + " from " + oldChunk + " to " + newChunk + " (distance: " + distance + ", fast: " + isFastMovement + ")");
        }
    }
}