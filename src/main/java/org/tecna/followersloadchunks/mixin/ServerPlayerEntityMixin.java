// Updated ServerPlayerEntityMixin.java - Add persistence like ender pearls
package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.PetChunkTickets;
import org.tecna.followersloadchunks.SimplePetTracker;
import org.tecna.followersloadchunks.config.Config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin implements SimplePetTracker {
    // Track pets and their last known positions
    @Unique
    private final Map<UUID, ChunkPos> petChunkPositions = new HashMap<>();
    @Unique

    @Inject(method = "tick", at = @At("HEAD"))
    private void onPlayerTick(CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Only update every 20 ticks (1 second) like ender pearls
        if (player.age % 20 == 0) {
            updatePetChunkTickets(player);
        }
    }

    @Unique
    private void updatePetChunkTickets(ServerPlayerEntity player) {
        if (!Config.getInstance().isChunkLoadingEnabled()) {
            return;
        }


        // Find all current pets that should load chunks
        Map<UUID, ChunkPos> currentPets = new HashMap<>();

        for (ServerWorld world : player.getServer().getWorlds()) {
            // Only check pets in the same dimension as player
            if (!world.getRegistryKey().equals(player.getWorld().getRegistryKey())) {
                continue;
            }

            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet &&
                        pet.isTamed() &&
                        pet.getOwner() == player &&
                        !pet.isSitting() &&
                        !pet.isLeashed() &&
                        !isIndependent(pet)) {

                    currentPets.put(pet.getUuid(), pet.getChunkPos());
                }
            }
        }

        // Add/renew tickets for current pets (like ender pearl system)
        for (Map.Entry<UUID, ChunkPos> entry : currentPets.entrySet()) {
            UUID petUUID = entry.getKey();
            ChunkPos chunkPos = entry.getValue();

            // Add ticket with short expiry (like ender pearls)
            ((ServerWorld) player.getWorld()).getChunkManager().addTicket(
                    PetChunkTickets.PET_TICKET_TYPE,
                    chunkPos,
                    Config.getInstance().getMaxChunkLoadingDistance()
            );
        }

        // Update tracking
        this.petChunkPositions.clear();
        this.petChunkPositions.putAll(currentPets);
    }

    // Helper method for independence check
    @Unique
    private boolean isIndependent(TameableEntity pet) {
        if (!org.tecna.followersloadchunks.IndyPetsHelper.isIndyPetsLoaded()) {
            return false;
        }
        return org.tecna.followersloadchunks.IndyPetsHelper.isPetIndependent(pet);
    }

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void writePetData(WriteView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Save pet chunk loading data like ender pearls
        if (!this.petChunkPositions.isEmpty()) {
            WriteView.ListView listView = view.getList("chunk_loading_pets");

            for (Map.Entry<UUID, ChunkPos> entry : this.petChunkPositions.entrySet()) {
                UUID petUUID = entry.getKey();
                ChunkPos chunkPos = entry.getValue();

                // Verify pet still exists and should be loading chunks
                boolean shouldSave = false;
                for (ServerWorld world : player.getServer().getWorlds()) {
                    if (world.getEntity(petUUID) instanceof TameableEntity pet) {
                        if (pet.isTamed() && pet.getOwner() == player &&
                                !pet.isSitting() && !pet.isLeashed() && !isIndependent(pet)) {
                            shouldSave = true;
                            break;
                        }
                    }
                }

                if (shouldSave) {
                    WriteView writeView = listView.add();
                    writeView.putLong("pet_uuid_most", petUUID.getMostSignificantBits());
                    writeView.putLong("pet_uuid_least", petUUID.getLeastSignificantBits());
                    writeView.putInt("chunk_x", chunkPos.x);
                    writeView.putInt("chunk_z", chunkPos.z);
                    writeView.putString("world", player.getWorld().getRegistryKey().getValue().toString());
                }
            }
        }
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void readPetData(ReadView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Clear existing data
        this.petChunkPositions.clear();

        // Read and restore pet chunk tickets like ender pearls
        view.getListReadView("chunk_loading_pets").forEach(petView -> {
            long uuidMost = petView.getLong("pet_uuid_most", 0L);
            long uuidLeast = petView.getLong("pet_uuid_least", 0L);
            UUID petUUID = new UUID(uuidMost, uuidLeast);

            int chunkX = petView.getInt("chunk_x", 0);
            int chunkZ = petView.getInt("chunk_z", 0);
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

            String worldKey = petView.getString("world", "");

            // Find the correct world and immediately add ticket
            for (ServerWorld world : player.getServer().getWorlds()) {
                if (world.getRegistryKey().getValue().toString().equals(worldKey)) {
                    // Immediately add chunk ticket like ender pearls do
                    world.getChunkManager().addTicket(
                            PetChunkTickets.PET_TICKET_TYPE,
                            chunkPos,
                            Config.getInstance().getMaxChunkLoadingDistance()
                    );

                    // Track this pet
                    this.petChunkPositions.put(petUUID, chunkPos);

                    if (Config.getInstance().isDebugLoggingEnabled()) {
                        System.out.println("[FollowersLoadChunks] Restored chunk ticket for pet " + petUUID + " at " + chunkPos + " in " + worldKey);
                    }
                    break;
                }
            }
        });
    }

    // Interface implementation
    @Override
    public void addChunkLoadingPet(UUID petUUID) {
        // Not used in this system - player manages everything
    }

    @Override
    public void removeChunkLoadingPet(UUID petUUID) {
        this.petChunkPositions.remove(petUUID);
    }

    @Override
    public boolean hasChunkLoadingPet(UUID petUUID) {
        return this.petChunkPositions.containsKey(petUUID);
    }

    @Override
    public Set<UUID> getChunkLoadingPets() {
        return new HashSet<>(this.petChunkPositions.keySet());
    }
}