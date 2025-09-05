package org.tecna.followersloadchunks;

import java.util.Set;
import java.util.UUID;

public interface SimplePetTracker {
    void addChunkLoadingPet(UUID petUUID);
    void removeChunkLoadingPet(UUID petUUID);
    boolean hasChunkLoadingPet(UUID petUUID);
    Set<UUID> getChunkLoadingPets();
}