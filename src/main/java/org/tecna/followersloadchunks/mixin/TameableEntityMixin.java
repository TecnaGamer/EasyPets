package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.SimplePetTracker;

@Mixin(LivingEntity.class)
public class TameableEntityMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onEntityDeath(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (entity instanceof TameableEntity pet && pet.getOwner() instanceof ServerPlayerEntity owner) {
            // Just remove from player's tracking - tickets will auto-expire
            if (owner instanceof SimplePetTracker tracker) {
                tracker.removeChunkLoadingPet(pet.getUuid());
            }
        }
    }

    // No more tick injection - player handles everything!
}
