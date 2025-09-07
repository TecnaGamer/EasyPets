package org.tecna.easypets;

import net.fabricmc.api.ModInitializer;
import org.tecna.easypets.config.ConfigCommand;
import org.tecna.easypets.config.Config;

public class EasyPets implements ModInitializer {

    @Override
    public void onInitialize() {
        Config config = Config.getInstance();

        PetChunkTickets.initialize();
        PetRecoveryCommand.register();
        ConfigCommand.register();

        System.out.println("EasyPets initialized!");

        if (config.isDebugLoggingEnabled()) {
            config.printCurrentConfig();
        }

        // No complex tick handlers needed - pets manage themselves!
    }
}