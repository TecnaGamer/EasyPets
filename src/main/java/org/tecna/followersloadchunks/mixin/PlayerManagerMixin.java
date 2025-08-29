package org.tecna.followersloadchunks.mixin;

import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.PlayerManager;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.PetChunkManager;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void onPlayerDisconnect(ServerPlayerEntity player, CallbackInfo ci) {
        // Clean up all pet chunk tickets for this player when they disconnect
        PetChunkManager.cleanupPlayerPetTickets(player);
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        // Schedule restoration and auto-recovery check for when player has fully loaded
        player.getServer().execute(() -> {
            try {
                Thread.sleep(2000); // Wait for player to fully load
                player.getServer().execute(() -> {
                    // Restore pet chunk loading
                    PetChunkManager.restorePlayerPetChunkLoading(player);

                    // Check if this player needs auto-recovery
                    if (PetChunkManager.needsAutoRecovery(player)) {
                        System.out.println("[FollowersLoadChunks] Auto-running pet recovery for first-time user " + player.getGameProfile().getName());
                        player.sendMessage(net.minecraft.text.Text.of("§a[FollowersLoadChunks] Welcome! Auto-scanning for your pets..."));

                        // Clear the flag and run recovery
                        PetChunkManager.clearAutoRecoveryFlag(player);
                        org.tecna.followersloadchunks.PetRecoveryCommand.runPetRecoveryForPlayer(player, false);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}