package org.tecna.followersloadchunks;

import net.minecraft.entity.passive.TameableEntity;
import java.util.UUID;

public interface ChunkLoadingPetTracker {
    void followersLoadChunks$addChunkLoadingPet(TameableEntity pet);
    void followersLoadChunks$removeChunkLoadingPet(TameableEntity pet);
    boolean followersLoadChunks$isPetChunkLoading(UUID petUUID);
}