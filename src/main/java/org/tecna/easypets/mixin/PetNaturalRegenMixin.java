package org.tecna.easypets.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.tecna.easypets.config.Config;

@Mixin(LivingEntity.class)
public class PetNaturalRegenMixin {

    @Unique private int lastDamageTick = 0;
    @Unique private int regenTickCounter = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // Only apply to tamed pets with owners
        if (!(entity instanceof TameableEntity tameable)) return;
        if (!tameable.isTamed()) return;
        if (!(tameable.getOwner() instanceof PlayerEntity)) return;

        Config config = Config.getInstance();
        if (!config.isNaturalRegenEnabled()) return;

        // Skip if pet is at max health or dead
        if (entity.getHealth() >= entity.getMaxHealth() || entity.isDead()) return;

        // Check if enough time has passed since last damage
        int currentTick = entity.age;
        int timeSinceLastDamage = currentTick - lastDamageTick;

        if (timeSinceLastDamage >= config.getRegenDelayTicks()) {
            // Only check for regen every 20 ticks (1 second) for performance
            regenTickCounter++;
            if (regenTickCounter >= 20) {
                regenTickCounter = 0;

                // Check if we should regen based on max health percentage
                float currentHealthPercent = entity.getHealth() / entity.getMaxHealth();
                if (currentHealthPercent < config.getRegenMaxHealthPercent()) {
                    // Apply regeneration
                    float newHealth = Math.min(
                            entity.getHealth() + config.getRegenAmountPerSecond(),
                            entity.getMaxHealth() * config.getRegenMaxHealthPercent()
                    );

                    entity.setHealth(newHealth);

                    if (config.isDebugLoggingEnabled()) {
                        System.out.println("[EasyPets] Pet " + entity.getUuid() +
                                " regenerated to " + newHealth + "/" + entity.getMaxHealth() + " health");
                    }
                }
            }
        }
    }

    @Inject(method = "damage", at = @At("HEAD"))
    private void onDamage(net.minecraft.server.world.ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // Only track damage for tamed pets
        if (entity instanceof TameableEntity tameable && tameable.isTamed()) {
            // Reset the damage timer
            lastDamageTick = entity.age;
            regenTickCounter = 0; // Reset regen counter to prevent immediate regen

            Config config = Config.getInstance();
            if (config.isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Pet " + entity.getUuid() +
                        " took damage, resetting regen timer");
            }
        }
    }
}