package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class FriendCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final FriendService service;
    private final MessageFormatter fmt;

    public FriendCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(FriendService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendUsage(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "add" -> handleAdd(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleAdd(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /friend add <player>")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { player.sendMessage(fmt.playerNotFound(args[1])); return true; }
        if (service.addFriend(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(fmt.success("Added &e" + target.getName() + " &aas a friend!"));
            target.sendMessage(fmt.info("&e" + player.getName() + " &7added you as a friend."));
        }
        return true;
    }

    private boolean handleRemove(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /friend remove <player>")); return true; }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        service.removeFriend(player.getUniqueId(), target.getUniqueId());
        player.sendMessage(fmt.success("Removed &e" + args[1] + " &afrom friends."));
        return true;
    }

    private boolean handleList(Player player) {
        var list = service.getFriends(player.getUniqueId());
        player.sendMessage(fmt.header("Friends (" + list.size() + ")"));
        if (list.isEmpty()) {
            player.sendMessage(fmt.info("No friends yet. Add one with &e/friend add <player>"));
        } else {
            for (var f : list) {
                Player online = Bukkit.getPlayer(f.getPlayerUuid());
                String status = online != null ? "&aONLINE" : "&7offline";
                player.sendMessage(fmt.info("&e" + f.getFriendName() + " &8| " + status));
            }
        }
        return true;
    }

    private void sendUsage(Player p) {
        p.sendMessage(fmt.header("Friend Commands"));
        p.sendMessage(fmt.info("/friend add <player> &8- Add a friend"));
        p.sendMessage(fmt.info("/friend remove <player> &8- Remove a friend"));
        p.sendMessage(fmt.info("/friend list &8- List your friends"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("add", "remove", "list");
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        return List.of();
    }
}
