package org.tecna.easypets;

import net.fabricmc.api.ModInitializer;
import org.tecna.easypets.config.ConfigCommand;

/**
 * Fabric entry point that delegates to common initialization and registers
 * Fabric specific hooks.
 */
public class EasyPetsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        EasyPets.init();
        PetRecoveryCommand.register();
        ConfigCommand.register();
    }
}
