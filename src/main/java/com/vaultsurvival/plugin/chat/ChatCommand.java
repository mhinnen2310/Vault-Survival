package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Handles chat channel switching, aliases, settings, and preview commands.
 */
public class ChatCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final ChatFormatService chatFormatService;
    private final ChatChannelService chatChannelService;

    public ChatCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
        this.chatFormatService = new ChatFormatService(plugin);
        this.chatChannelService = plugin.getServiceRegistry().get(ChatChannelService.class);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can preview chat."));
            return true;
        }

        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("chat")) {
            return handleChat(player, args);
        }
        if (commandName.equals("chatsettings")) {
            return handleSettings(player);
        }
        if (commandName.equals("chatpreview")) {
            return handlePreview(player, args);
        }

        ChatChannel aliasChannel = ChatChannel.from(commandName);
        if (aliasChannel == null) {
            player.sendMessage(fmt.error("Unknown chat command."));
            return true;
        }
        if (!plugin.getConfigManager().areChatAliasesEnabled()) {
            player.sendMessage(fmt.error("Chat aliases are disabled. Use /chat <channel>."));
            return true;
        }
        if (args.length == 0) {
            chatChannelService.setActiveChannel(player, aliasChannel);
            return true;
        }
        chatChannelService.send(player, aliasChannel, String.join(" ", args));
        return true;
    }

    private boolean handleChat(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(fmt.header("Chat"));
            player.sendMessage(fmt.info("Current channel: &e" + chatChannelService.getActiveChannel(player).displayName()));
            player.sendMessage(fmt.info("Channels: &e" + ChatChannelService.channelList()));
            player.sendMessage(fmt.info("Use /chat <channel> or aliases like /dc <message>."));
            return true;
        }

        ChatChannel channel = ChatChannel.from(args[0]);
        if (channel == null) {
            player.sendMessage(fmt.error("Unknown channel. Use: " + ChatChannelService.channelList()));
            return true;
        }
        chatChannelService.setActiveChannel(player, channel);
        return true;
    }

    private boolean handleSettings(Player player) {
        player.sendMessage(fmt.header("Chat Settings"));
        player.sendMessage(fmt.info("Current channel: &e" + chatChannelService.getActiveChannel(player).displayName()));
        player.sendMessage(fmt.info("Default channel: &e" + chatChannelService.getDefaultChannel().displayName()));
        player.sendMessage(fmt.info("Local radius: &e" + plugin.getConfigManager().getChatLocalRadius() + " blocks"));
        player.sendMessage(fmt.info("Remember last channel: &e" + plugin.getConfigManager().shouldRememberLastChatChannel()));
        player.sendMessage(fmt.info("Staff spy enabled: &e" + plugin.getConfigManager().isChatSpyEnabled()));
        player.sendMessage(fmt.info("Use /vsmenu settings for the dialog menu."));
        return true;
    }

    private boolean handlePreview(Player player, String[] args) {
        String testMessage = args.length > 0 ? String.join(" ", args) : "Hello, world!";
        String rankLabel = chatFormatService.getRankLabel(player);
        String districtLabel = chatFormatService.getDistrictLabel(player);
        ChatChannel channel = chatChannelService.getActiveChannel(player);
        String preview = chatChannelService.preview(player, channel, testMessage);

        player.sendMessage(fmt.header("Chat Preview"));
        player.sendMessage(fmt.info("Channel: &e" + channel.displayName()));
        player.sendMessage(fmt.info("Rank label: &r" + rankLabel));
        player.sendMessage(fmt.info("District label: &r" + districtLabel));
        player.sendMessage("");
        player.sendMessage(fmt.info("Your messages will look like:"));
        player.sendMessage(fmt.deserialize(preview));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("chat") && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.stream(ChatChannel.values())
                .map(ChatChannel::id)
                .filter(id -> id.startsWith(prefix))
                .toList();
        }
        return List.of();
    }
}
