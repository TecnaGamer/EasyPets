package org.tecna.easypets;

import org.tecna.easypets.config.Config;

/**
 * Common entry point for EasyPets across different loaders.
 */
public class EasyPets {

    /**
     * Initialize shared EasyPets logic.
     */
    public static void init() {
        Config config = Config.getInstance();

        PetChunkTickets.initialize();

        System.out.println("EasyPets initialized!");

        if (config.isDebugLoggingEnabled()) {
            config.printCurrentConfig();
        }
    }
}
