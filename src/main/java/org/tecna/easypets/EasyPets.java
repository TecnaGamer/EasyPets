package org.tecna.easypets;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.tecna.easypets.config.ConfigCommand;
import org.tecna.easypets.config.Config;
import org.tecna.easypets.translation.TranslationManager;
import org.tecna.easypets.util.PerPlayerGlowManager;

public class EasyPets implements ModInitializer {

    @Override
    public void onInitialize() {
        Config config = Config.getInstance();
        
        // Initialize translations with configured language
        TranslationManager.getInstance().initialize(config.getLanguage());

        PetChunkTickets.initialize();
        PetRecoveryCommand.register();
        ConfigCommand.register();

        // Register server shutdown handler to clean up glow sessions
        ServerLifecycleEvents.SERVER_STOPPING.register((server) -> {
            PerPlayerGlowManager.cleanupAll();
            if (config.isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Cleaned up all glow sessions on server shutdown");
            }
        });

        System.out.println("[EasyPets] " + TranslationManager.getInstance().translate("easypets.init.message"));

        if (config.isDebugLoggingEnabled()) {
            config.printCurrentConfig();
        }

        // No complex tick handlers needed - pets manage themselves!
    }
}