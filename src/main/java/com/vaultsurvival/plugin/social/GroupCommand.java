package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class GroupCommand implements CommandExecutor, TabCompleter {
    private final GroupService service;
    private final MessageFormatter fmt;

    public GroupCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(GroupService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendUsage(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "disband" -> handleDisband(player);
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player, args);
            case "kick" -> handleKick(player, args);
            case "leave" -> handleLeave(player);
            case "info" -> handleInfo(player);
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleCreate(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /group create <name>")); return true; }
        var g = service.createGroup(player.getUniqueId(), args[1]);
        if (g != null) player.sendMessage(fmt.success("Group &e" + g.getName() + " &acreated! (#" + g.getId() + ")"));
        return true;
    }
    private boolean handleDisband(Player player) {
        var g = service.getPlayerGroup(player.getUniqueId());
        if (g == null) { player.sendMessage(fmt.error("You are not in a group.")); return true; }
        if (service.disbandGroup(g.getId(), player.getUniqueId())) player.sendMessage(fmt.success("Group disbanded."));
        return true;
    }
    private boolean handleInvite(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /group invite <player>")); return true; }
        var g = service.getPlayerGroup(player.getUniqueId());
        if (g == null) { player.sendMessage(fmt.error("You are not in a group.")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { player.sendMessage(fmt.playerNotFound(args[1])); return true; }
        service.inviteMember(g.getId(), player.getUniqueId(), target.getUniqueId());
        player.sendMessage(fmt.success("Invited &e" + target.getName() + " &ato join your group."));
        return true;
    }
    private boolean handleAccept(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /group accept <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        if (service.acceptInvite(id, player.getUniqueId())) player.sendMessage(fmt.success("Joined group!"));
        return true;
    }
    private boolean handleKick(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /group kick <player>")); return true; }
        var g = service.getPlayerGroup(player.getUniqueId());
        if (g == null) return true;
        @SuppressWarnings("deprecation") var t = Bukkit.getOfflinePlayer(args[1]);
        service.kickMember(g.getId(), player.getUniqueId(), t.getUniqueId());
        player.sendMessage(fmt.success("Kicked &e" + args[1]));
        return true;
    }
    private boolean handleLeave(Player player) {
        var g = service.getPlayerGroup(player.getUniqueId());
        if (g == null) { player.sendMessage(fmt.error("You are not in a group.")); return true; }
        if (service.leaveGroup(g.getId(), player.getUniqueId())) player.sendMessage(fmt.success("You left the group."));
        return true;
    }
    private boolean handleInfo(Player player) {
        var g = service.getPlayerGroup(player.getUniqueId());
        if (g == null) { player.sendMessage(fmt.info("You are not in a group.")); return true; }
        player.sendMessage(fmt.header("Group: " + g.getName() + " (#" + g.getId() + ")"));
        @SuppressWarnings("deprecation") String owner = Bukkit.getOfflinePlayer(g.getOwnerUuid()).getName();
        player.sendMessage(fmt.info("Owner: &e" + owner + " &8| Members: &e" + g.getMembers().size()));
        for (UUID m : g.getMembers()) {
            @SuppressWarnings("deprecation") String name = Bukkit.getOfflinePlayer(m).getName();
            player.sendMessage(fmt.info("  &7" + (name != null ? name : m.toString().substring(0,8))));
        }
        return true;
    }
    private void sendUsage(Player p) {
        p.sendMessage(fmt.header("Group Commands"));
        p.sendMessage(fmt.info("/group create <name> &8- Create a group"));
        p.sendMessage(fmt.info("/group invite <player> &8- Invite to your group"));
        p.sendMessage(fmt.info("/group accept <id> &8- Accept a group invite"));
        p.sendMessage(fmt.info("/group kick <player> &8- Kick member (owner)"));
        p.sendMessage(fmt.info("/group leave &8- Leave your group"));
        p.sendMessage(fmt.info("/group info &8- Group info"));
        p.sendMessage(fmt.info("/group disband &8- Disband group (owner)"));
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create","disband","invite","accept","kick","leave","info");
        return List.of();
    }
}
