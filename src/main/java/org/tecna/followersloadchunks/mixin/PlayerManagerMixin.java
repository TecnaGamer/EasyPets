package org.tecna.followersloadchunks.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.SimplePetTracker;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void onPlayerDisconnect(ServerPlayerEntity player, CallbackInfo ci) {
        // Simple cleanup - just clear the tracking set
        // Tickets will auto-expire since they're not being renewed
        if (player instanceof SimplePetTracker tracker) {
            tracker.getChunkLoadingPets().clear();
        }
    }
}