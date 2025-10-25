package org.tecna.easypets.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.tecna.easypets.config.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player glow effects using client-bound packets
 * Only the player who runs the findpets command will see their pets glow
 */
public class PerPlayerGlowManager {
    
    // Track active glow sessions per player
    private static final Map<UUID, PlayerGlowSession> activeGlowSessions = new ConcurrentHashMap<>();
    
    // Entity flag for glowing effect (bit 6)
    private static final byte GLOWING_FLAG = 0x40;
    
    /**
     * Apply per-player glow effect to pets using real teams for colors
     */
    public static void applyGlowEffect(ServerPlayerEntity player, List<Entity> pets, Map<Entity, String> petStates) {
        UUID playerId = player.getUuid();
        
        // Cancel existing session if any
        PlayerGlowSession existingSession = activeGlowSessions.get(playerId);
        if (existingSession != null) {
            existingSession.cleanup();
        }
        
        // Create new glow session
        PlayerGlowSession session = new PlayerGlowSession(player, pets, petStates);
        activeGlowSessions.put(playerId, session);
        session.start();
    }
    
    /**
     * Remove glow effect for a specific player
     */
    public static void removeGlowEffect(UUID playerId) {
        PlayerGlowSession session = activeGlowSessions.remove(playerId);
        if (session != null) {
            session.cleanup();
        }
    }
    
    /**
     * Get active glow session for a player
     */
    public static PlayerGlowSession getGlowSession(UUID playerId) {
        return activeGlowSessions.get(playerId);
    }
    
    /**
     * Clean up all glow sessions (called on server shutdown)
     */
    public static void cleanupAll() {
        for (PlayerGlowSession session : activeGlowSessions.values()) {
            session.cleanup();
        }
        activeGlowSessions.clear();
    }
    
    public static class PlayerGlowSession {
        private final ServerPlayerEntity player;
        private final MinecraftServer server;
        private final Map<Entity, String> petStates;
        private final Map<String, Team> colorTeams; // State -> Real Team
        private final Set<Entity> glowingPets;
        private volatile boolean active = true;
        private final String playerPrefix;
        private volatile long lastUpdateTime = 0;
        private static final long UPDATE_COOLDOWN_MS = 100; // Minimum 100ms between updates
        
        // Color mapping for different pet states
        private static final Map<String, Formatting> COLOR_MAP = Map.of(
            "following", Formatting.GREEN,
            "sitting", Formatting.BLUE, 
            "roaming", Formatting.GOLD,
            "independent", Formatting.LIGHT_PURPLE,
            "leashed", Formatting.RED
        );
        
        public PlayerGlowSession(ServerPlayerEntity player, List<Entity> pets, Map<Entity, String> petStates) {
            this.player = player;
            this.server = player.getEntityWorld().getServer();
            this.petStates = new HashMap<>(petStates);
            this.glowingPets = new HashSet<>(pets);
            this.colorTeams = new HashMap<>();
            
            // Generate unique team names for this session
            this.playerPrefix = "findpets_" + player.getUuidAsString().substring(0, 8);
        }
        
        public void start() {
            // Create real server-side teams for color control
            createRealTeams();
            
            // Apply glow effects to pets (per-player packets)
            applyGlowEffects();
            
            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Started per-player glow session for " + player.getGameProfile().name() + " with " + glowingPets.size() + " pets");
            }
        }
        
        public void updatePetStates(Map<Entity, String> newStates) {
            if (!active) return;
            
            // Throttle updates to prevent packet spam
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateTime < UPDATE_COOLDOWN_MS) {
                return; // Too soon since last update
            }
            
            // Check if states actually changed to avoid unnecessary operations
            if (this.petStates.equals(newStates)) {
                return; // No changes needed
            }
            
            lastUpdateTime = currentTime;
            
            // First remove all pets from current teams
            removeAllPetsFromTeams();
            
            // Update pet states and team assignments
            this.petStates.clear();
            this.petStates.putAll(newStates);
            
            // Re-apply team assignments with delay to avoid packet conflicts
            server.execute(() -> {
                if (active) {
                    applyGlowEffects();
                }
            });
        }
        
        public void cleanup() {
            active = false;
            
            if (player.isRemoved() || player.networkHandler == null) {
                // Still need to cleanup teams even if player is gone
                cleanupRealTeams();
                return;
            }
            
            // Remove glow effects (per-player packets)
            removeGlowEffects();
            
            // Remove real teams from server
            cleanupRealTeams();
            
            if (Config.getInstance().isDebugLoggingEnabled()) {
                System.out.println("[EasyPets] Cleaned up per-player glow session for " + player.getGameProfile().name());
            }
        }
        
        private void createRealTeams() {
            if (server == null) return;
            
            Scoreboard scoreboard = server.getScoreboard();
            
            for (Map.Entry<String, Formatting> entry : COLOR_MAP.entrySet()) {
                String state = entry.getKey();
                String teamName = playerPrefix + "_" + state;
                Formatting color = entry.getValue();
                
                // Remove existing team if it exists
                Team existingTeam = scoreboard.getTeam(teamName);
                if (existingTeam != null) {
                    scoreboard.removeTeam(existingTeam);
                }
                
                // Create new team with color
                Team team = scoreboard.addTeam(teamName);
                team.setShowFriendlyInvisibles(true);
                team.setColor(color);
                colorTeams.put(state, team);
            }
        }
        
        private void cleanupRealTeams() {
            if (server == null) return;
            
            Scoreboard scoreboard = server.getScoreboard();
            
            for (Team team : colorTeams.values()) {
                if (team != null) {
                    scoreboard.removeTeam(team);
                }
            }
            colorTeams.clear();
        }
        
        private void removeAllPetsFromTeams() {
            if (server == null) return;
            
            Scoreboard scoreboard = server.getScoreboard();
            
            for (Entity pet : glowingPets) {
                if (pet.isRemoved()) continue;
                
                String entityId = pet.getUuidAsString();
                
                // Remove from any team the entity might be on
                Team currentTeam = scoreboard.getScoreHolderTeam(entityId);
                if (currentTeam != null) {
                    try {
                        scoreboard.removeScoreHolderFromTeam(entityId, currentTeam);
                    } catch (Exception e) {
                        // Ignore errors - entity might not actually be on the team
                        if (Config.getInstance().isDebugLoggingEnabled()) {
                            System.out.println("[EasyPets] Could not remove " + entityId + " from team " + currentTeam.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        private void safeAddToTeam(String entityId, Team newTeam) {
            if (server == null || newTeam == null) return;
            
            Scoreboard scoreboard = server.getScoreboard();
            
            try {
                // Check if already on the correct team
                Team currentTeam = scoreboard.getScoreHolderTeam(entityId);
                if (currentTeam != null && currentTeam.equals(newTeam)) {
                    return; // Already on the correct team
                }
                
                // Remove from current team first if needed
                if (currentTeam != null) {
                    try {
                        scoreboard.removeScoreHolderFromTeam(entityId, currentTeam);
                    } catch (Exception e) {
                        if (Config.getInstance().isDebugLoggingEnabled()) {
                            System.out.println("[EasyPets] Could not remove " + entityId + " from current team: " + e.getMessage());
                        }
                    }
                }
                
                // Add to new team
                scoreboard.addScoreHolderToTeam(entityId, newTeam);
                
            } catch (Exception e) {
                if (Config.getInstance().isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Failed to assign " + entityId + " to team " + newTeam.getName() + ": " + e.getMessage());
                }
            }
        }
        
        private void applyGlowEffects() {
            for (Entity pet : glowingPets) {
                if (pet.isRemoved()) continue;
                
                // Apply glow effect using entity metadata packet
                applyGlowToPet(pet);
                
                // Assign to appropriate colored team (real server-side team)
                String state = petStates.get(pet);
                if (state != null && colorTeams.containsKey(state)) {
                    Team team = colorTeams.get(state);
                    if (team != null && server != null) {
                        // Safely add to team (removes from current team first)
                        safeAddToTeam(pet.getUuidAsString(), team);
                    }
                }
            }
        }
        
        private void removeGlowEffects() {
            for (Entity pet : glowingPets) {
                if (pet.isRemoved()) continue;
                
                // Remove glow effect using entity metadata packet
                removeGlowFromPet(pet);
            }
            
            // Remove all pets from teams
            removeAllPetsFromTeams();
        }
        
        private void applyGlowToPet(Entity pet) {
            // Use reflection to access the protected FLAGS field
            try {
                java.lang.reflect.Field flagsField = Entity.class.getDeclaredField("FLAGS");
                flagsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                TrackedData<Byte> flags = (TrackedData<Byte>) flagsField.get(null);
                
                DataTracker dataTracker = pet.getDataTracker();
                
                // Get the current flags from the actual entity
                byte currentFlags = dataTracker.get(flags);
                
                // Add glowing flag (bit 6)
                byte newFlags = (byte) (currentFlags | (1 << 6));
                
                // Create and send entity metadata packet
                EntityTrackerUpdateS2CPacket metadataPacket = new EntityTrackerUpdateS2CPacket(
                    pet.getId(),
                    List.of(DataTracker.SerializedEntry.of(flags, newFlags))
                );
                
                player.networkHandler.sendPacket(metadataPacket);
                
            } catch (Exception e) {
                if (Config.getInstance().isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Failed to apply glow effect via packet: " + e.getMessage());
                }
            }
        }
        
        private void removeGlowFromPet(Entity pet) {
            // Use reflection to access the protected FLAGS field
            try {
                java.lang.reflect.Field flagsField = Entity.class.getDeclaredField("FLAGS");
                flagsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                TrackedData<Byte> flags = (TrackedData<Byte>) flagsField.get(null);
                
                DataTracker dataTracker = pet.getDataTracker();
                
                // Get the current flags from the actual entity
                byte currentFlags = dataTracker.get(flags);
                
                // Remove glowing flag (bit 6)
                byte newFlags = (byte) (currentFlags & ~(1 << 6));
                
                // Create and send entity metadata packet
                EntityTrackerUpdateS2CPacket metadataPacket = new EntityTrackerUpdateS2CPacket(
                    pet.getId(),
                    List.of(DataTracker.SerializedEntry.of(flags, newFlags))
                );
                
                player.networkHandler.sendPacket(metadataPacket);
                
            } catch (Exception e) {
                if (Config.getInstance().isDebugLoggingEnabled()) {
                    System.out.println("[EasyPets] Failed to remove glow effect via packet: " + e.getMessage());
                }
            }
        }
        
        public boolean isActive() {
            return active && !player.isRemoved();
        }
        
        public Set<Entity> getGlowingPets() {
            return Collections.unmodifiableSet(glowingPets);
        }
    }
    
    // Removed FakeTeam class - now using real server-side teams for colors
}
