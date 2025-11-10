package org.tecna.easypets;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.tecna.easypets.translation.TranslationManager;
import org.tecna.easypets.util.PetWhitelistManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

public class CalmPetsCommand {
    
    // Helper method to create formatted text using server-side translations
    private static Text formatted(String color, String translationKey, Object... args) {
        return TranslationManager.getInstance().text(color, translationKey, args);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("calmpets")
                    .executes(CalmPetsCommand::executeCalmPets));
        });
    }

    private static int executeCalmPets(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        List<String> calmedPets = new ArrayList<>();
        int totalCalmed = 0;

        // Search through all loaded worlds for the player's pets
        for (ServerWorld world : source.getServer().getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof TameableEntity pet && pet.isTamed()) {
                    // Check if this pet belongs to the player
                    if (pet.getOwner() == player) {
                        boolean wasCalmed = calmPet(pet, player.getUuid());
                        if (wasCalmed) {
                            totalCalmed++;
                            String petName = pet.hasCustomName() ? 
                                    pet.getCustomName().getString() : 
                                    getPetTypeName(pet);
                            calmedPets.add(petName);
                        }
                    }
                }
            }
        }

        if (totalCalmed > 0) {
            source.sendMessage(formatted("§a", "easypets.calmpets.success", totalCalmed));
            if (totalCalmed <= 5) {
                // Show individual pet names if there aren't too many
                source.sendMessage(formatted("§7", "easypets.calmpets.pets_calmed", String.join(", ", calmedPets)));
            }
            source.sendMessage(formatted("§7", "easypets.calmpets.explanation"));
        } else {
            source.sendMessage(formatted("§e", "easypets.calmpets.no_attacking_pets"));
        }

        return 1;
    }

    /**
     * Attempts to calm a pet by clearing its attack target and adding target to whitelist
     * @param pet The pet to calm
     * @param ownerUUID The UUID of the pet's owner
     * @return true if the pet was successfully calmed (had an attack target that was cleared)
     */
    private static boolean calmPet(TameableEntity pet, UUID ownerUUID) {
        if (!(pet instanceof MobEntity mobPet)) {
            return false;
        }

        // Check if the pet currently has an attack target
        LivingEntity target = mobPet.getTarget();
        if (target != null) {
            // Clear the attack target
            mobPet.setTarget(null);
            
            // Add the target to whitelist to prevent re-targeting
            if (target instanceof ServerPlayerEntity playerTarget) {
                PetWhitelistManager.getInstance().addPlayerToWhitelist(ownerUUID, playerTarget.getUuid(), playerTarget.getGameProfile().name());
            } else {
                PetWhitelistManager.getInstance().addEntityUUIDToWhitelist(ownerUUID, target.getUuid());
            }
            
            return true;
        }
        
        return false;
    }

    /**
     * Gets a user-friendly name for the pet type
     */
    private static String getPetTypeName(Entity pet) {
        String entityId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(pet.getType()).toString();
        String petType = entityId.replace("minecraft:", "");
        // Capitalize first letter for display
        return petType.substring(0, 1).toUpperCase() + petType.substring(1);
    }
}
