package org.tecna.easypets;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;

public class PetChunkTickets {
    // Longer-lived ticket since player renews every 20 ticks
    // Similar to ENDER_PEARL: loads chunks, enables simulation, resets idle timeout
    public static final ChunkTicketType PET_TICKET_TYPE = Registry.register(
            Registries.TICKET_TYPE,
            "pet_chunk_loader",
            new ChunkTicketType(
                    100L, // 5 seconds expiry - gives buffer for player renewal
                    ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION | ChunkTicketType.RESETS_IDLE_TIMEOUT
            )
    );

    public static void initialize() {
        // Registration happens when this class is loaded
    }
}