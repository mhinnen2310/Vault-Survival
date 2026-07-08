package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /chatpreview shows what your chat messages will look like.
 */
public class ChatCommand implements CommandExecutor {

    private final MessageFormatter fmt;
    private final ChatFormatService chatFormatService;

    public ChatCommand(VaultSurvivalPlugin plugin) {
        this.fmt = plugin.getMessageFormatter();
        this.chatFormatService = new ChatFormatService(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can preview chat."));
            return true;
        }

        String testMessage = args.length > 0 ? String.join(" ", args) : "Hello, world!";
        String rankLabel = chatFormatService.getRankLabel(player);
        String districtLabel = chatFormatService.getDistrictLabel(player);
        String preview = chatFormatService.formatChat(player, testMessage);

        player.sendMessage(fmt.header("Chat Preview"));
        player.sendMessage(fmt.info("Rank label: &r" + rankLabel));
        player.sendMessage(fmt.info("District label: &r" + districtLabel));
        player.sendMessage("");
        player.sendMessage(fmt.info("Your messages will look like:"));
        player.sendMessage(fmt.deserialize(preview));
        return true;
    }
}
