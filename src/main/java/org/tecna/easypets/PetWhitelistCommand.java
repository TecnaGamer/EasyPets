package org.tecna.easypets;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.tecna.easypets.translation.TranslationManager;
import org.tecna.easypets.util.PetWhitelistManager;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import static net.minecraft.server.command.CommandManager.*;

public class PetWhitelistCommand {

    // Helper method to create formatted text using server-side translations
    private static Text formatted(String color, String translationKey, Object... args) {
        return TranslationManager.getInstance().text(color, translationKey, args);
    }

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TYPE_SELECTOR_PATTERN = Pattern.compile("type=([a-z0-9_:\\-]+)");

    private static String getArgumentInput(CommandContext<ServerCommandSource> context, String argumentName) {
        String input = context.getInput();
        for (ParsedCommandNode<ServerCommandSource> node : context.getNodes()) {
            CommandNode<ServerCommandSource> commandNode = node.getNode();
            if (commandNode instanceof ArgumentCommandNode<?, ?> argumentNode && argumentNode.getName().equals(argumentName)) {
                return input.substring(node.getRange().getStart(), node.getRange().getEnd()).trim();
            }
        }
        return "";
    }

    private static Optional<String> extractEntityType(String rawSelector) {
        if (rawSelector.isEmpty()) {
            return Optional.empty();
        }

        if (UUID_PATTERN.matcher(rawSelector).matches()) {
            return Optional.empty();
        }

        Matcher matcher = TYPE_SELECTOR_PATTERN.matcher(rawSelector);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }

    private static String normalizeEntityTypeId(String entityType) {
        Identifier identifier = Identifier.tryParse(entityType);
        if (identifier == null) {
            // attempt to add minecraft namespace
            identifier = Identifier.tryParse("minecraft:" + entityType);
        }
        return identifier != null ? identifier.toString() : entityType;
    }


    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("petwhitelist")
                    .executes(PetWhitelistCommand::executeWhitelistHelp)
                    .then(literal("add")
                            .then(argument("entities", EntityArgumentType.entities())
                                    .executes(PetWhitelistCommand::executeAddEntities)))
                    .then(literal("remove")
                            .then(argument("entities", EntityArgumentType.entities())
                                    .executes(PetWhitelistCommand::executeRemoveEntities)))
                    .then(literal("list")
                            .executes(PetWhitelistCommand::executeListWhitelist))
                    .then(literal("clear")
                            .executes(PetWhitelistCommand::executeClearWhitelist)));
        });
    }

    private static int executeWhitelistHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        source.sendMessage(formatted("§a", "easypets.petwhitelist.title"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§7", "easypets.petwhitelist.help.add"));
        source.sendMessage(formatted("§7", "easypets.petwhitelist.help.remove"));
        source.sendMessage(formatted("§7", "easypets.petwhitelist.help.list"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§e", "easypets.petwhitelist.help.explanation"));
        source.sendMessage(formatted("§7", "easypets.petwhitelist.help.examples"));

        return 1;
    }

    private static int executeAddEntities(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        try {
            Collection<? extends Entity> targetEntities = EntityArgumentType.getOptionalEntities(context, "entities");
            String rawSelector = getArgumentInput(context, "entities");
            Optional<String> typeSelector = extractEntityType(rawSelector)
                    .filter(type -> !type.startsWith("!") && !type.startsWith("#"));
            boolean handledTypeSelector = false;

            int addedCount = 0;
            int alreadyWhitelistedCount = 0;
            int selfAttempts = 0;

            if (typeSelector.isPresent()) {
                String normalizedType = normalizeEntityTypeId(typeSelector.get());
                Identifier identifier = Identifier.tryParse(normalizedType);
                if (identifier == null || !Registries.ENTITY_TYPE.containsId(identifier)) {
                    source.sendError(formatted("§c", "easypets.petwhitelist.error.invalid_entity_type", normalizedType));
                    return 0;
                }

                boolean added = PetWhitelistManager.getInstance().addEntityTypeToWhitelist(player.getUuid(), normalizedType);
                if (added) {
                    source.sendMessage(formatted("§a", "easypets.petwhitelist.entity_added", normalizedType));
                    addedCount++;
                } else {
                    source.sendMessage(formatted("§e", "easypets.petwhitelist.entity_already_whitelisted", normalizedType));
                    alreadyWhitelistedCount++;
                }

                handledTypeSelector = true;
            }

            for (Entity targetEntity : targetEntities) {
                if (handledTypeSelector) {
                    break;
                }

                if (targetEntity instanceof ServerPlayerEntity targetPlayer) {
                    // Handle player entities
                    if (targetPlayer.getUuid().equals(player.getUuid())) {
                        selfAttempts++;
                        continue;
                    }

                    boolean added = PetWhitelistManager.getInstance().addPlayerToWhitelist(
                            player.getUuid(), 
                            targetPlayer.getUuid(), 
                            targetPlayer.getGameProfile().name()
                    );

                    if (added) {
                        addedCount++;
                        source.sendMessage(formatted("§a", "easypets.petwhitelist.player_added", targetPlayer.getGameProfile().name()));
                    } else {
                        alreadyWhitelistedCount++;
                        source.sendMessage(formatted("§e", "easypets.petwhitelist.player_already_whitelisted", targetPlayer.getGameProfile().name()));
                    }
                } else {
                    // Handle non-player entities - add by UUID for specific entities
                    UUID entityUUID = targetEntity.getUuid();
                    String entityType = net.minecraft.registry.Registries.ENTITY_TYPE.getId(targetEntity.getType()).toString();
                    
                    boolean added = PetWhitelistManager.getInstance().addEntityUUIDToWhitelist(player.getUuid(), entityUUID);

                    if (added) {
                        addedCount++;
                        String displayName = targetEntity.hasCustomName() ? 
                                targetEntity.getCustomName().getString() + " (" + entityType + ")" : 
                                entityType + " (" + entityUUID.toString().substring(0, 8) + "...)";
                        source.sendMessage(formatted("§a", "easypets.petwhitelist.entity_added", displayName));
                    } else {
                        alreadyWhitelistedCount++;
                        String displayName = targetEntity.hasCustomName() ? 
                                targetEntity.getCustomName().getString() : 
                                entityType + " (" + entityUUID.toString().substring(0, 8) + "...)";
                        source.sendMessage(formatted("§e", "easypets.petwhitelist.entity_already_whitelisted", displayName));
                    }
                }
            }
            
            if (selfAttempts > 0) {
                source.sendError(formatted("§c", "easypets.petwhitelist.error.cannot_whitelist_self"));
            }
            
            if (addedCount == 0 && alreadyWhitelistedCount == 0 && selfAttempts == 0) {
                source.sendError(formatted("§c", "easypets.command.error.entity_not_found"));
            }
            
        } catch (CommandSyntaxException e) {
            source.sendError(formatted("§c", "easypets.command.error.entity_not_found"));
        }

        return 1;
    }

    private static int executeRemoveEntities(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        try {
            Collection<? extends Entity> targetEntities = EntityArgumentType.getOptionalEntities(context, "entities");
            String rawSelector = getArgumentInput(context, "entities");
            Optional<String> typeSelector = extractEntityType(rawSelector)
                    .filter(type -> !type.startsWith("!") && !type.startsWith("#"));
            boolean handledTypeSelector = false;

            int removedCount = 0;
            int notWhitelistedCount = 0;
            
            if (typeSelector.isPresent()) {
                String normalizedType = normalizeEntityTypeId(typeSelector.get());
                Identifier identifier = Identifier.tryParse(normalizedType);
                if (identifier == null || !Registries.ENTITY_TYPE.containsId(identifier)) {
                    source.sendError(formatted("§c", "easypets.petwhitelist.error.invalid_entity_type", normalizedType));
                    return 0;
                }

                boolean removed = PetWhitelistManager.getInstance().removeEntityTypeFromWhitelist(player.getUuid(), normalizedType);
                if (removed) {
                    source.sendMessage(formatted("§a", "easypets.petwhitelist.entity_removed", normalizedType));
                    removedCount++;
                } else {
                    source.sendMessage(formatted("§e", "easypets.petwhitelist.entity_not_whitelisted", normalizedType));
                    notWhitelistedCount++;
                }

                handledTypeSelector = true;
            }

            for (Entity targetEntity : targetEntities) {
                if (handledTypeSelector) {
                    break;
                }

                if (targetEntity instanceof ServerPlayerEntity targetPlayer) {
                    // Handle player entities
                    boolean removed = PetWhitelistManager.getInstance().removePlayerFromWhitelist(
                            player.getUuid(), 
                            targetPlayer.getUuid()
                    );

                    if (removed) {
                        removedCount++;
                        source.sendMessage(formatted("§a", "easypets.petwhitelist.player_removed", targetPlayer.getGameProfile().name()));
                    } else {
                        notWhitelistedCount++;
                        source.sendMessage(formatted("§e", "easypets.petwhitelist.player_not_whitelisted", targetPlayer.getGameProfile().name()));
                    }
                } else {
                    // Handle non-player entities - remove by UUID for specific entities
                    UUID entityUUID = targetEntity.getUuid();
                    String entityType = net.minecraft.registry.Registries.ENTITY_TYPE.getId(targetEntity.getType()).toString();
                    
                    boolean removed = PetWhitelistManager.getInstance().removeEntityUUIDFromWhitelist(player.getUuid(), entityUUID);

                    if (removed) {
                        removedCount++;
                        String displayName = targetEntity.hasCustomName() ? 
                                targetEntity.getCustomName().getString() + " (" + entityType + ")" : 
                                entityType + " (" + entityUUID.toString().substring(0, 8) + "...)";
                        source.sendMessage(formatted("§a", "easypets.petwhitelist.entity_removed", displayName));
                    } else {
                        notWhitelistedCount++;
                        String displayName = targetEntity.hasCustomName() ? 
                                targetEntity.getCustomName().getString() : 
                                entityType + " (" + entityUUID.toString().substring(0, 8) + "...)";
                        source.sendMessage(formatted("§e", "easypets.petwhitelist.entity_not_whitelisted", displayName));
                    }
                }
            }
            
            if (removedCount == 0 && notWhitelistedCount == 0) {
                source.sendError(formatted("§c", "easypets.command.error.entity_not_found"));
            }
            
        } catch (Exception e) {
            source.sendError(formatted("§c", "easypets.command.error.entity_not_found"));
        }

        return 1;
    }


    private static int executeListWhitelist(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        Set<UUID> whitelistedPlayers = PetWhitelistManager.getInstance().getWhitelistedPlayers(player.getUuid());
        Set<String> whitelistedEntities = PetWhitelistManager.getInstance().getWhitelistedEntityTypes(player.getUuid());
        Set<UUID> whitelistedEntityUUIDs = PetWhitelistManager.getInstance().getWhitelistedEntityUUIDs(player.getUuid());

        source.sendMessage(formatted("§a", "easypets.petwhitelist.list_title"));
        source.sendMessage(Text.empty());

        if (!whitelistedPlayers.isEmpty()) {
            source.sendMessage(formatted("§6", "easypets.petwhitelist.list_players", whitelistedPlayers.size()));
            for (UUID playerUUID : whitelistedPlayers) {
                // Try to get current player name
                ServerPlayerEntity whitelistedPlayer = source.getServer().getPlayerManager().getPlayer(playerUUID);
                if (whitelistedPlayer != null) {
                    source.sendMessage(formatted("§f", "easypets.petwhitelist.list_player_online", whitelistedPlayer.getGameProfile().name()));
                } else {
                    source.sendMessage(formatted("§7", "easypets.petwhitelist.list_player_offline", playerUUID.toString()));
                }
            }
        }

        if (!whitelistedEntities.isEmpty()) {
            if (!whitelistedPlayers.isEmpty()) {
                source.sendMessage(Text.empty());
            }
            source.sendMessage(formatted("§6", "easypets.petwhitelist.list_entities", whitelistedEntities.size()));
            whitelistedEntities.stream()
                    .sorted()
                    .forEach(entityType -> source.sendMessage(formatted("§f", "easypets.petwhitelist.list_entity", entityType)));
        }

        if (!whitelistedEntityUUIDs.isEmpty()) {
            if (!whitelistedPlayers.isEmpty() || !whitelistedEntities.isEmpty()) {
                source.sendMessage(Text.empty());
            }
            source.sendMessage(formatted("§6", "easypets.petwhitelist.list_entity_uuids", whitelistedEntityUUIDs.size()));
            whitelistedEntityUUIDs.stream()
                    .map(uuid -> uuid.toString())
                    .sorted()
                    .forEach(uuidString -> source.sendMessage(formatted("§f", "easypets.petwhitelist.list_entity_uuid", uuidString)));
        }

        if (whitelistedPlayers.isEmpty() && whitelistedEntities.isEmpty() && whitelistedEntityUUIDs.isEmpty()) {
            source.sendMessage(formatted("§7", "easypets.petwhitelist.list_empty"));
        }

        return 1;
    }

    private static int executeClearWhitelist(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(formatted("§c", "easypets.command.error.players_only"));
            return 0;
        }

        int totalCount = PetWhitelistManager.getInstance().getTotalWhitelistCount(player.getUuid());
        
        if (totalCount == 0) {
            source.sendMessage(formatted("§e", "easypets.petwhitelist.already_empty"));
            return 1;
        }

        PetWhitelistManager.getInstance().clearWhitelist(player.getUuid());
        source.sendMessage(formatted("§a", "easypets.petwhitelist.cleared", totalCount));

        return 1;
    }
}
