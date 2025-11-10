package org.tecna.easypets.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.easypets.util.PetWhitelistManager;

@Mixin(MobEntity.class)
public class PetTargetingMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void onSetTarget(LivingEntity target, CallbackInfo ci) {
        MobEntity mobEntity = (MobEntity) (Object) this;

        // Only apply whitelist logic to tamed pets
        if (!(mobEntity instanceof TameableEntity pet) || !pet.isTamed()) {
            return;
        }

        // If no target is being set, allow it (clearing target)
        if (target == null) {
            return;
        }

        // Get the pet's owner
        LivingEntity owner = pet.getOwner();
        if (owner == null) {
            return;
        }

        // Check if the target is whitelisted for this pet owner
        if (PetWhitelistManager.getInstance().isWhitelisted(owner.getUuid(), target)) {
            // Cancel the targeting - pet won't attack whitelisted entities
            ci.cancel();
        }
    }
}
