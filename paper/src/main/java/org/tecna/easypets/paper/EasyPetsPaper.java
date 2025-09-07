package org.tecna.easypets.paper;

import org.bukkit.plugin.java.JavaPlugin;
import org.tecna.easypets.EasyPets;

/**
 * Paper plugin entry point.
 */
public class EasyPetsPaper extends JavaPlugin {
    @Override
    public void onEnable() {
        EasyPets.init();
    }
}
