package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.ChunkLoadingPetTracker;
import org.tecna.followersloadchunks.PetChunkTickets;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin implements ChunkLoadingPetTracker {

    // Track chunk-loading pets (wolves, cats, parrots, etc.)
    private final Set<UUID> chunkLoadingPets = new HashSet<>();

    // Current data version - increment this when changing NBT structure
    private static final int CURRENT_DATA_VERSION = 1;

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void writePetData(WriteView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Write version number first
        view.putInt("followersloadchunks_version", CURRENT_DATA_VERSION);

        if (!this.chunkLoadingPets.isEmpty()) {
            WriteView.ListView listView = view.getList("chunk_loading_pets");

            for (UUID petUUID : this.chunkLoadingPets) {
                // Find the pet to get its current data
                for (ServerWorld world : player.getServer().getWorlds()) {
                    if (world.getEntity(petUUID) instanceof TameableEntity pet) {
                        // For 1.21.7, use the correct method names
                        if (pet.isTamed() && pet.getOwner() == player && !pet.isInSittingPose()) {
                            WriteView writeView = listView.add();
                            writeView.putLong("pet_uuid_most", petUUID.getMostSignificantBits());
                            writeView.putLong("pet_uuid_least", petUUID.getLeastSignificantBits());
                            writeView.putString("world", pet.getWorld().getRegistryKey().getValue().toString());

                            // Use getBlockPos().getChunkPos() for 1.21.7
                            ChunkPos chunkPos = new ChunkPos(pet.getBlockPos());
                            writeView.putInt("chunk_x", chunkPos.x);
                            writeView.putInt("chunk_z", chunkPos.z);
                            writeView.putString("pet_type", pet.getClass().getSimpleName());
                        }
                        break;
                    }
                }
            }
        }
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void readPetData(ReadView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Check data version
        int dataVersion = view.getInt("followersloadchunks_version", 0);
        boolean isFirstTimeWithMod = dataVersion == 0 && view.getListReadView("chunk_loading_pets").isEmpty();

        if (dataVersion != CURRENT_DATA_VERSION) {
            // Handle migration or reset - log to server instead of sending messages
            if (dataVersion == 0) {
                if (isFirstTimeWithMod) {
                    System.out.println("[FollowersLoadChunks] First time connection for player " + player.getGameProfile().getName() + " - will auto-run pet recovery");
                    // Mark for auto-recovery instead of using instance flag
                    org.tecna.followersloadchunks.PetChunkManager.markPlayerForAutoRecovery(player);
                } else {
                    System.out.println("[FollowersLoadChunks] Migrating pet data from version 0 to " + CURRENT_DATA_VERSION + " for player " + player.getGameProfile().getName());
                }
            } else if (dataVersion > CURRENT_DATA_VERSION) {
                System.out.println("[FollowersLoadChunks] Warning: Player " + player.getGameProfile().getName() + " has pet data from version " + dataVersion + " (newer than supported " + CURRENT_DATA_VERSION + "), resetting data");
                this.chunkLoadingPets.clear();
                return;
            } else {
                System.out.println("[FollowersLoadChunks] Migrating pet data from version " + dataVersion + " to " + CURRENT_DATA_VERSION + " for player " + player.getGameProfile().getName());
            }
        }

        // Clear existing data
        this.chunkLoadingPets.clear();

        // Read pet data
        view.getListReadView("chunk_loading_pets").forEach(petView -> {
            long uuidMost = petView.getLong("pet_uuid_most", 0L);
            long uuidLeast = petView.getLong("pet_uuid_least", 0L);
            UUID petUUID = new UUID(uuidMost, uuidLeast);
            Optional<String> worldKey = petView.getOptionalString("world");
            Optional<String> petType = petView.getOptionalString("pet_type");

            if (worldKey.isPresent()) {
                int chunkX = petView.getInt("chunk_x", 0);
                int chunkZ = petView.getInt("chunk_z", 0);
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

                // Find the correct world
                for (ServerWorld world : player.getServer().getWorlds()) {
                    if (world.getRegistryKey().getValue().toString().equals(worldKey.get())) {
                        // Verify the pet still exists before adding ticket
                        if (world.getEntity(petUUID) instanceof TameableEntity pet) {
                            if (pet.isTamed() && pet.getOwnerReference() != null &&
                                    pet.getOwnerReference().getUuid().equals(player.getUuid())) {

                                // Add chunk ticket immediately
                                world.getChunkManager().addTicket(
                                        PetChunkTickets.PET_TICKET_TYPE,
                                        chunkPos,
                                        2
                                );

                                // Track this pet
                                this.chunkLoadingPets.add(petUUID);

                                // Force pet to stand when found (delayed execution)
                                player.getServer().execute(() -> {
                                    if (world.getEntity(petUUID) instanceof TameableEntity standingPet) {
                                        standingPet.setSitting(false);
                                    }
                                });
                            }
                        } else {
                            // Pet no longer exists, don't add to tracking
                            System.out.println("[FollowersLoadChunks] Skipping missing pet " + petUUID + " for player " + player.getGameProfile().getName());
                        }
                        break;
                    }
                }
            }
        });
    }

    // Interface implementation methods (must be public to match interface)
    public void followersLoadChunks$addChunkLoadingPet(TameableEntity pet) {
        if (pet.isTamed() && !pet.isSitting()) {
            this.chunkLoadingPets.add(pet.getUuid());
        }
    }

    public void followersLoadChunks$removeChunkLoadingPet(TameableEntity pet) {
        this.chunkLoadingPets.remove(pet.getUuid());
    }

    public boolean followersLoadChunks$isPetChunkLoading(UUID petUUID) {
        return this.chunkLoadingPets.contains(petUUID);
    }
}