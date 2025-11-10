package org.tecna.easypets;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.tecna.easypets.translation.TranslationManager;

import static net.minecraft.server.command.CommandManager.literal;

public class EasyPetsHelpCommand {

    private static Text formatted(String color, String translationKey, Object... args) {
        return TranslationManager.getInstance().text(color, translationKey, args);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("easypets")
                    .executes(EasyPetsHelpCommand::executeHelp));
        });
    }

    private static int executeHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendMessage(formatted("§a", "easypets.help.title"));
        source.sendMessage(formatted("§7", "easypets.help.description"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§e", "easypets.help.section.player"));
        source.sendMessage(formatted("§7", "easypets.help.command.findpets"));
        source.sendMessage(formatted("§7", "easypets.help.command.petlocator"));
        source.sendMessage(formatted("§7", "easypets.help.command.petrecovery"));
        source.sendMessage(formatted("§7", "easypets.help.command.calmpets"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§e", "easypets.help.section.whitelist"));
        source.sendMessage(formatted("§7", "easypets.help.command.petwhitelist.add"));
        source.sendMessage(formatted("§7", "easypets.help.command.petwhitelist.remove"));
        source.sendMessage(formatted("§7", "easypets.help.command.petwhitelist.list"));
        source.sendMessage(formatted("§7", "easypets.help.command.petwhitelist.clear"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§e", "easypets.help.section.management"));
        source.sendMessage(formatted("§7", "easypets.help.command.petconfig"));
        source.sendMessage(formatted("§7", "easypets.help.command.petstats"));
        source.sendMessage(Text.empty());
        source.sendMessage(formatted("§6", "easypets.help.section.more"));
        source.sendMessage(formatted("§7", "easypets.help.command.petdebug"));
        source.sendMessage(formatted("§7", "easypets.help.command.debugregion"));
        source.sendMessage(formatted("§8", "easypets.help.footer"));

        return 1;
    }
}
