package org.tecna.followersloadchunks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.tecna.followersloadchunks.config.ConfigCommand;
import org.tecna.followersloadchunks.config.Config;

public class Followersloadchunks implements ModInitializer {

    private static int tickCounter = 0;

    @Override
    public void onInitialize() {
        // Initialize configuration first
        Config config = Config.getInstance();

        PetChunkTickets.initialize();
        PetRecoveryCommand.register();
        ConfigCommand.register(); // Register configuration commands

        if (config.isDebugLoggingEnabled()) {
            System.out.println("[FollowersLoadChunks] Mod initialized with config:");
            config.printCurrentConfig();
        }

        // Register periodic validation if enabled
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Config currentConfig = Config.getInstance();

            if (currentConfig.isPeriodicValidationEnabled()) {
                tickCounter++;
                if (tickCounter >= currentConfig.getValidationIntervalTicks()) {
                    // Run pet validation for all players
                    PetChunkManager.runPeriodicValidation(server);
                    tickCounter = 0;
                }
            } else {
                // Reset counter if validation is disabled
                tickCounter = 0;
            }
        });
    }
}