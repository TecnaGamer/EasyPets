package org.tecna.easypets;

import net.fabricmc.api.ModInitializer;
import org.tecna.easypets.config.ConfigCommand;
import org.tecna.easypets.config.Config;
import org.tecna.easypets.translation.TranslationManager;

public class EasyPets implements ModInitializer {

    @Override
    public void onInitialize() {
        Config config = Config.getInstance();
        
        // Initialize translations with configured language
        TranslationManager.getInstance().initialize(config.getLanguage());

        PetChunkTickets.initialize();
        PetRecoveryCommand.register();
        ConfigCommand.register();

        System.out.println("[EasyPets] " + TranslationManager.getInstance().translate("easypets.init.message"));

        if (config.isDebugLoggingEnabled()) {
            config.printCurrentConfig();
        }

        // No complex tick handlers needed - pets manage themselves!
    }
}