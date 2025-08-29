package org.tecna.followersloadchunks;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;

public class PetChunkTickets {

    // Register the pet chunk ticket type
    public static final ChunkTicketType PET_TICKET_TYPE = Registry.register(
            Registries.TICKET_TYPE,
            "pet_chunk_loader",
            new ChunkTicketType(
                    0L, // No expiration - stays loaded until manually removed
                    true, // Persist across server restarts
                    ChunkTicketType.Use.LOADING_AND_SIMULATION // Full chunk loading with entity ticking
            )
    );

    // Call this method from your mod initializer
    public static void initialize() {
        // Registration happens when this class is loaded
    }
}