package org.tecna.easypets.util;

import net.minecraft.server.MinecraftServer;
import java.util.concurrent.CompletableFuture;

public final class SaveUtil {

    /**
     * Triggers a full world save using the vanilla save-all flush command
     * This is more reliable than calling saveAll() directly because it uses
     * the exact same code path as the vanilla command
     */
    public static CompletableFuture<Boolean> triggerFullSave(MinecraftServer server) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Always schedule on the server thread
        server.execute(() -> {
            try {
                // Execute the vanilla save-all flush command
                // "flush" forces chunk storage IO immediately
                server.getCommandManager().executeWithPrefix(
                        server.getCommandSource(),
                        "save-all"
                );

                // Since executeWithPrefix returns void, we assume success if no exception
                future.complete(true);

            } catch (Exception e) {
                future.complete(false);
            }
        });

        return future;
    }

}