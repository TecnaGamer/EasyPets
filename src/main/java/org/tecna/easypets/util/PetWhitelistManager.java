package org.tecna.easypets.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.tecna.easypets.config.Config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PetWhitelistManager {
    private static final String WHITELIST_FILE_NAME = "easypets_whitelist.json";
    private static PetWhitelistManager INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Map from player UUID to their whitelist data
    private final Map<UUID, PlayerWhitelistData> playerWhitelists = new ConcurrentHashMap<>();

    public static class PlayerWhitelistData {
        public Set<UUID> whitelistedPlayers = new HashSet<>();
        public Set<String> whitelistedEntityTypes = new HashSet<>();
        public Set<UUID> whitelistedEntityUUIDs = new HashSet<>();
        
        public PlayerWhitelistData() {}
    }

    private PetWhitelistManager() {
        loadWhitelists();
    }

    public static PetWhitelistManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PetWhitelistManager();
        }
        return INSTANCE;
    }

    /**
     * Check if an entity is whitelisted for a specific player's pets
     */
    public boolean isWhitelisted(UUID petOwnerUUID, Entity target) {
        PlayerWhitelistData data = playerWhitelists.get(petOwnerUUID);
        if (data == null) {
            return false;
        }

        // Check if target is a whitelisted player
        if (target instanceof ServerPlayerEntity player) {
            return data.whitelistedPlayers.contains(player.getUuid());
        }

        // Check if this specific entity UUID is whitelisted
        if (data.whitelistedEntityUUIDs.contains(target.getUuid())) {
            return true;
        }

        // Check if target's entity type is whitelisted
        String entityTypeId = Registries.ENTITY_TYPE.getId(target.getType()).toString();
        return data.whitelistedEntityTypes.contains(entityTypeId);
    }

    /**
     * Add a player to the whitelist
     */
    public boolean addPlayerToWhitelist(UUID ownerUUID, UUID targetPlayerUUID, String targetPlayerName) {
        PlayerWhitelistData data = playerWhitelists.computeIfAbsent(ownerUUID, k -> new PlayerWhitelistData());
        boolean added = data.whitelistedPlayers.add(targetPlayerUUID);
        if (added) {
            saveWhitelists();
        }
        return added;
    }

    /**
     * Remove a player from the whitelist
     */
    public boolean removePlayerFromWhitelist(UUID ownerUUID, UUID targetPlayerUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        if (data == null) {
            return false;
        }
        boolean removed = data.whitelistedPlayers.remove(targetPlayerUUID);
        if (removed) {
            saveWhitelists();
        }
        return removed;
    }

    /**
     * Add an entity type to the whitelist
     */
    public boolean addEntityTypeToWhitelist(UUID ownerUUID, String entityTypeId) {
        // Validate entity type exists
        Identifier entityId = Identifier.tryParse(entityTypeId);
        if (entityId == null || !Registries.ENTITY_TYPE.containsId(entityId)) {
            return false;
        }

        PlayerWhitelistData data = playerWhitelists.computeIfAbsent(ownerUUID, k -> new PlayerWhitelistData());
        boolean added = data.whitelistedEntityTypes.add(entityTypeId);
        if (added) {
            saveWhitelists();
        }
        return added;
    }

    /**
     * Add an individual entity UUID to the whitelist
     */
    public boolean addEntityUUIDToWhitelist(UUID ownerUUID, UUID entityUUID) {
        PlayerWhitelistData data = playerWhitelists.computeIfAbsent(ownerUUID, k -> new PlayerWhitelistData());
        boolean added = data.whitelistedEntityUUIDs.add(entityUUID);
        if (added) {
            saveWhitelists();
        }
        return added;
    }

    /**
     * Remove an individual entity UUID from the whitelist
     */
    public boolean removeEntityUUIDFromWhitelist(UUID ownerUUID, UUID entityUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        if (data == null) {
            return false;
        }
        boolean removed = data.whitelistedEntityUUIDs.remove(entityUUID);
        if (removed) {
            saveWhitelists();
        }
        return removed;
    }

    /**
     * Remove an entity type from the whitelist
     */
    public boolean removeEntityTypeFromWhitelist(UUID ownerUUID, String entityTypeId) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        if (data == null) {
            return false;
        }
        boolean removed = data.whitelistedEntityTypes.remove(entityTypeId);
        if (removed) {
            saveWhitelists();
        }
        return removed;
    }

    /**
     * Get whitelisted players for a specific owner
     */
    public Set<UUID> getWhitelistedPlayers(UUID ownerUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        return data != null ? new HashSet<>(data.whitelistedPlayers) : new HashSet<>();
    }

    /**
     * Get whitelisted entity types for a specific owner
     */
    public Set<String> getWhitelistedEntityTypes(UUID ownerUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        return data != null ? new HashSet<>(data.whitelistedEntityTypes) : new HashSet<>();
    }

    /**
     * Get whitelisted specific entity UUIDs for a specific owner
     */
    public Set<UUID> getWhitelistedEntityUUIDs(UUID ownerUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        return data != null ? new HashSet<>(data.whitelistedEntityUUIDs) : new HashSet<>();
    }

    /**
     * Clear all whitelist entries for a player
     */
    public void clearWhitelist(UUID ownerUUID) {
        playerWhitelists.remove(ownerUUID);
        saveWhitelists();
    }

    /**
     * Get total number of whitelisted entries for a player
     */
    public int getTotalWhitelistCount(UUID ownerUUID) {
        PlayerWhitelistData data = playerWhitelists.get(ownerUUID);
        if (data == null) {
            return 0;
        }
        return data.whitelistedPlayers.size() + data.whitelistedEntityTypes.size() + data.whitelistedEntityUUIDs.size();
    }

    private void loadWhitelists() {
        Path whitelistPath = FabricLoader.getInstance().getConfigDir().resolve(WHITELIST_FILE_NAME);

        if (Files.exists(whitelistPath)) {
            try {
                String json = Files.readString(whitelistPath);
                Type type = new TypeToken<Map<String, PlayerWhitelistData>>(){}.getType();
                Map<String, PlayerWhitelistData> rawData = GSON.fromJson(json, type);
                
                if (rawData != null) {
                    // Convert string UUIDs back to UUID keys
                    playerWhitelists.clear();
                    for (Map.Entry<String, PlayerWhitelistData> entry : rawData.entrySet()) {
                        try {
                            UUID playerUUID = UUID.fromString(entry.getKey());
                            playerWhitelists.put(playerUUID, entry.getValue());
                        } catch (IllegalArgumentException e) {
                            if (Config.getInstance().isDebugLoggingEnabled()) {
                                System.err.println("[EasyPets] Invalid UUID in whitelist file: " + entry.getKey());
                            }
                        }
                    }
                }

                if (Config.getInstance().isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Loaded pet whitelist for " + playerWhitelists.size() + " players");
                }
            } catch (Exception e) {
                System.err.println("[EasyPets] Failed to load pet whitelist: " + e.getMessage());
                if (Config.getInstance().isDebugLoggingEnabled()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void saveWhitelists() {
        Path whitelistPath = FabricLoader.getInstance().getConfigDir().resolve(WHITELIST_FILE_NAME);

        try {
            // Convert UUID keys to strings for JSON serialization
            Map<String, PlayerWhitelistData> rawData = new HashMap<>();
            for (Map.Entry<UUID, PlayerWhitelistData> entry : playerWhitelists.entrySet()) {
                rawData.put(entry.getKey().toString(), entry.getValue());
            }

            String json = GSON.toJson(rawData);
            Files.writeString(whitelistPath, json);
            
            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Saved pet whitelist to " + WHITELIST_FILE_NAME);
            }
        } catch (IOException e) {
            System.err.println("[EasyPets] Failed to save pet whitelist: " + e.getMessage());
            if (Config.getInstance().isDebugLoggingEnabled()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Get all valid entity types that can be whitelisted (commonly hostile mobs)
     */
    public static List<String> getSuggestedEntityTypes() {
        return Arrays.asList(
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
            "minecraft:enderman", "minecraft:witch", "minecraft:pillager", "minecraft:vindicator",
            "minecraft:evoker", "minecraft:ravager", "minecraft:phantom", "minecraft:drowned",
            "minecraft:husk", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:blaze",
            "minecraft:ghast", "minecraft:piglin", "minecraft:hoglin", "minecraft:zoglin",
            "minecraft:piglin_brute", "minecraft:guardian", "minecraft:elder_guardian",
            "minecraft:shulker", "minecraft:silverfish", "minecraft:endermite", "minecraft:slime",
            "minecraft:magma_cube", "minecraft:vex"
        );
    }
}
