package org.tecna.followersloadchunks;

import net.fabricmc.api.ModInitializer;
import org.tecna.followersloadchunks.config.ConfigCommand;
import org.tecna.followersloadchunks.config.Config;

public class Followersloadchunks implements ModInitializer {

    @Override
    public void onInitialize() {
        Config config = Config.getInstance();

        PetChunkTickets.initialize();
        PetRecoveryCommand.register();
        ConfigCommand.register();

        if (config.isDebugLoggingEnabled()) {
            System.out.println("[FollowersLoadChunks] Mod initialized with simplified chunk loading system");
            config.printCurrentConfig();
        }

        // No complex tick handlers needed - pets manage themselves!
    }
}