package org.tecna.followersloadchunks.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.tecna.followersloadchunks.config.Config;

@Mixin(LivingEntity.class)
public class DynamicPetSpeedMixin {

    @Unique private static final Identifier SPEED_MODIFIER_ID = Identifier.of("followersloadchunks", "dynamic_pet_speed");
    @Unique private double lastPlayerX, lastPlayerY, lastPlayerZ;
    @Unique private double playerSpeed = 0.0;
    @Unique private int speedCalculationTimer = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // Only apply to tameable entities, but exclude parrots (handled separately)
        if (!(entity instanceof TameableEntity tameable)) return;
        if (!tameable.isTamed()) return;
        if (entity instanceof ParrotEntity) return; // Parrots handled by FlightMoveControlMixin

        PlayerEntity owner = (PlayerEntity) tameable.getOwner();
        if (owner == null) return;

        Config config = Config.getInstance();
        if (!config.isDynamicPetSpeedEnabled()) {
            removeSpeedModifier(tameable);
            return;
        }

        // Calculate distance to owner
        double distanceToOwner = tameable.distanceTo(owner);

        // Don't adjust speed if pet is sitting, very close, or busy with other activities
        if (shouldSkipSpeedAdjustment(tameable, distanceToOwner)) {
            removeSpeedModifier(tameable);
            return;
        }

        // Calculate player movement speed every 5 ticks
        speedCalculationTimer++;
        if (speedCalculationTimer >= 5) {
            speedCalculationTimer = 0;
            calculatePlayerSpeed(owner);
        }

        // Calculate target speed multiplier
        double targetSpeedMultiplier = calculateSpeedMultiplier(distanceToOwner, playerSpeed, config);

        // Apply the modifier
        applySpeedModifier(tameable, targetSpeedMultiplier);
    }

    @Unique
    private boolean shouldSkipSpeedAdjustment(TameableEntity tameable, double distanceToOwner) {
        // Skip if sitting
        if (tameable.isSitting()) return true;

        // Skip if very close
        if (distanceToOwner < 2.0) return true;

        // Skip if pet is chasing/fighting other mobs
        if (tameable.getTarget() != null) return true;
        if (tameable.isAttacking()) return true;

        return false;
    }

    @Unique
    private void calculatePlayerSpeed(PlayerEntity owner) {
        double currentX = owner.getX();
        double currentY = owner.getY();
        double currentZ = owner.getZ();

        if (lastPlayerX != 0 || lastPlayerY != 0 || lastPlayerZ != 0) {
            double deltaX = currentX - lastPlayerX;
            double deltaY = currentY - lastPlayerY;
            double deltaZ = currentZ - lastPlayerZ;

            double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
            playerSpeed = distance * 4; // Convert to blocks per second
        }

        lastPlayerX = currentX;
        lastPlayerY = currentY;
        lastPlayerZ = currentZ;
    }

    @Unique
    private double calculateSpeedMultiplier(double distance, double playerSpeed, Config config) {
        double targetDistance = config.getPetSpeedMinDistance(); // Default 6.0
        double maxMultiplier = config.getMaxPetSpeedMultiplier(); // Default 2.5
        double speedThreshold = config.getPlayerSpeedThreshold(); // Default 0.1

        // Far away pets always run fast
        if (distance > targetDistance * 2) {
            return maxMultiplier;
        }

        // Player not moving - normal speed
        if (playerSpeed <= speedThreshold) {
            return 1.0;
        }

        // Player moving - adjust based on distance to target
        if (distance > targetDistance) {
            // Pet behind target - speed up proportionally
            double speedBoost = (distance - targetDistance) / targetDistance;
            return 1.0 + Math.min(speedBoost, maxMultiplier - 1.0);
        } else {
            // Pet at or ahead of target - slightly slower to avoid overshooting
            return 0.9;
        }
    }

    @Unique
    private void applySpeedModifier(TameableEntity tameable, double multiplier) {
        // Only handle ground-based pets (parrots handled separately)
        var attributeInstance = tameable.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (attributeInstance == null) return;

        // Remove any existing modifier
        attributeInstance.removeModifier(SPEED_MODIFIER_ID);

        // Apply new modifier if different from 1.0
        if (Math.abs(multiplier - 1.0) > 0.01) {
            EntityAttributeModifier modifier = new EntityAttributeModifier(
                    SPEED_MODIFIER_ID,
                    multiplier - 1.0,
                    EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE
            );
            attributeInstance.addTemporaryModifier(modifier);
        }
    }

    @Unique
    private void removeSpeedModifier(TameableEntity tameable) {
        // Only handle ground-based pets (parrots handled separately)
        var movementInstance = tameable.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (movementInstance != null) {
            movementInstance.removeModifier(SPEED_MODIFIER_ID);
        }
    }
}