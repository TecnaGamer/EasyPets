package org.tecna.followersloadchunks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class Followersloadchunks implements ModInitializer {

    private static int tickCounter = 0;
    private static final int VALIDATION_INTERVAL = 6000; // Every 5 minutes (6000 ticks)

    @Override
    public void onInitialize() {
        PetChunkTickets.initialize();
        PetRecoveryCommand.register();

        // Register periodic validation
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= VALIDATION_INTERVAL) {
                // Run pet validation for all players
                PetChunkManager.runPeriodicValidation(server);
                tickCounter = 0;
            }
        });
    }
}