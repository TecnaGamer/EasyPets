// Updated ServerPlayerEntityMixin.java - Add automatic first-time pet recovery
package org.tecna.easypets.mixin;

import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.easypets.PetChunkTickets;
import org.tecna.easypets.PetRecoveryCommand;
import org.tecna.easypets.SimplePetTracker;
import org.tecna.easypets.config.Config;

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

    // Track if first-time recovery has been performed
    @Unique
    private boolean hasPerformedFirstTimeRecovery = false;

    // Track ticks since join to delay auto-recovery
    @Unique
    private int ticksSinceJoin = 0;

    // Flag to indicate this is a fresh join
    @Unique
    private boolean justJoined = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onPlayerTick(CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Increment join counter
        ticksSinceJoin++;

        // Check for first-time auto recovery (wait 5 seconds after join)
        if (justJoined && ticksSinceJoin == 100 && !hasPerformedFirstTimeRecovery) {
            justJoined = false;
            performFirstTimeRecovery(player);
        }

        // Only update every 20 ticks (1 second) like ender pearls
        if (player.age % 20 == 0) {
            updatePetChunkTickets(player);
        }
    }

    @Unique
    private void performFirstTimeRecovery(ServerPlayerEntity player) {
        Config config = Config.getInstance();

        // Check if auto-recovery is enabled
        if (!config.shouldAutoRecoverOnFirstJoin()) {
            if (config.isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Auto-recovery disabled in config for player: " + player.getGameProfile().getName());
            }
            return;
        }

        // Mark as performed so it doesn't run again
        hasPerformedFirstTimeRecovery = true;

        if (config.isDebugLoggingEnabled()) {
            System.out.println("[EasyPets] Performing first-time pet recovery for player: " + player.getGameProfile().getName());
        }

        // Send welcome message
        player.sendMessage(Text.of("§e[EasyPets] Welcome! Running automatic pet recovery..."), false);
        player.sendMessage(Text.of("§7This will only happen once. Use /petrecovery to run manually."), false);

        // Optional save (if enabled) using the new SaveUtil
        if (config.shouldSaveOnRecovery()) {
            try {
                player.sendMessage(Text.of("§7[EasyPets] Ensuring world is saved for accurate pet data..."), false);

                // Use SaveUtil which executes vanilla save-all flush command
                Boolean saveResult = org.tecna.easypets.util.SaveUtil.triggerFullSave(player.getServer()).get();

                if (saveResult) {
                    if (config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] World save completed successfully for first-time recovery");
                    }
                    Thread.sleep(1500); // Give save time to complete
                } else {
                    if (config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] World save failed for first-time recovery, continuing anyway");
                    }
                }

            } catch (Exception e) {
                if (config.isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Exception during first-time recovery save, continuing anyway: " + e.getMessage());
                }
                // Don't let save errors prevent recovery
            }
        }

        // Run pet recovery automatically (this should still work even if save fails)
        PetRecoveryCommand.runPetRecoveryForPlayer(player, false);
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
                    Config.getInstance().getMaxChunkDistance()
            );
        }

        // Update tracking
        this.petChunkPositions.clear();
        this.petChunkPositions.putAll(currentPets);
    }

    // Helper method for independence check
    @Unique
    private boolean isIndependent(TameableEntity pet) {
        if (!org.tecna.easypets.IndyPetsHelper.isIndyPetsLoaded()) {
            return false;
        }
        return org.tecna.easypets.IndyPetsHelper.isPetIndependent(pet);
    }

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void writePetData(WriteView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        // Save first-time recovery flag
        view.putBoolean("easypets_first_recovery_done", hasPerformedFirstTimeRecovery);

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

        // Read first-time recovery flag
        hasPerformedFirstTimeRecovery = view.getBoolean("easypets_first_recovery_done", false);

        // If this is the first time loading (no flag found), mark for auto-recovery
        if (!hasPerformedFirstTimeRecovery) {
            justJoined = true;
            ticksSinceJoin = 0;

            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Player " + player.getGameProfile().getName() + " joining for first time with EasyPets");
            }
        }

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
                            Config.getInstance().getMaxChunkDistance()
                    );

                    // Track this pet
                    this.petChunkPositions.put(petUUID, chunkPos);

                    if (Config.getInstance().isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] Restored chunk ticket for pet " + petUUID + " at " + chunkPos + " in " + worldKey);
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