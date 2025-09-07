package org.tecna.easypets.mixin;

import net.minecraft.entity.passive.TameableEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.tecna.easypets.config.Config;

@Mixin(TameableEntity.class)
public class TameableEntityOverrides {

    @Inject(method = "shouldTryTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void overrideTeleportDistance(CallbackInfoReturnable<Boolean> cir) {
        TameableEntity pet = (TameableEntity) (Object) this;

        if (pet.getOwner() != null) {
            Config config = Config.getInstance();
            double configuredDistance = config.getTeleportDistanceSquared();

            // Use configured distance instead of vanilla hardcoded 144.0 (12^2)
            boolean shouldTeleport = pet.squaredDistanceTo(pet.getOwner()) >= configuredDistance;
            cir.setReturnValue(shouldTeleport);
        }
    }
}