package com.vaultsurvival.plugin.access;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Admin command for managing player ranks and permissions.
 * /rank <player> <set|add|remove> <group>
 * /rank <player> info
 */
public class AccessCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final AccessService accessService;
    private final MessageFormatter fmt;

    public AccessCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.accessService = plugin.getServiceRegistry().get(AccessService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    /**
     * Shared command entry point. The console bridge calls this directly so a
     * conflicting Brigadier command cannot prevent local rank administration.
     */
    public boolean execute(CommandSender sender, String[] args) {
        // The server console is a trusted local administration channel, not an /op bypass.
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("vs.admin.rank")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.info("Usage: /rank <player> <set|add|remove|info> [group]"));
            return true;
        }

        String targetName = args[0];
        String action = args[1].toLowerCase();

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(fmt.playerNotFound(targetName));
            return true;
        }

        UUID targetUuid = target.getUniqueId();

        switch (action) {
            case "info":
                showInfo(sender, targetUuid, target.getName());
                break;
            case "set":
                if (args.length < 3) {
                    sender.sendMessage(fmt.error("Specify a group name."));
                    return true;
                }
                setRank(sender, targetUuid, target.getName(), args[2]);
                break;
            case "add":
                if (args.length < 3) {
                    sender.sendMessage(fmt.error("Specify a group name."));
                    return true;
                }
                addRank(sender, targetUuid, target.getName(), args[2]);
                break;
            case "remove":
                if (args.length < 3) {
                    sender.sendMessage(fmt.error("Specify a group name."));
                    return true;
                }
                removeRank(sender, targetUuid, target.getName(), args[2]);
                break;
            default:
                sender.sendMessage(fmt.error("Unknown action. Use: set, add, remove, info"));
                break;
        }

        return true;
    }

    private void showInfo(CommandSender sender, UUID uuid, String name) {
        String primary = accessService.getPrimaryGroup(uuid);
        String[] groups = accessService.getPlayerGroups(uuid);
        String prefix = accessService.getPrefix(uuid);
        int weight = accessService.getWeight(uuid);
        boolean isStaff = accessService.isStaff(uuid);

        sender.sendMessage(fmt.header("Player Info: " + name));
        sender.sendMessage(fmt.info("UUID: &e" + uuid));
        sender.sendMessage(fmt.info("Primary Group: &e" + primary));
        sender.sendMessage(fmt.info("Groups: &e" + String.join("&7, &e", groups)));
        sender.sendMessage(fmt.info("Prefix: &r" + prefix));
        sender.sendMessage(fmt.info("Weight: &e" + weight));
        sender.sendMessage(fmt.info("Staff: &e" + isStaff));
    }

    private void setRank(CommandSender sender, UUID uuid, String name, String group) {
        // Remove all existing groups
        String[] existing = accessService.getPlayerGroups(uuid);
        for (String g : existing) {
            accessService.removeFromGroup(uuid, g);
        }
        accessService.addToGroup(uuid, group, getSenderUuid(sender));
        if (targetUuidOnline(uuid) != null) accessService.refreshPlayerPermissions(targetUuidOnline(uuid));
        sender.sendMessage(fmt.success("Set " + name + "'s rank to &e" + group));

        // Audit log
        plugin.getAuditLogger().logAdminAction(getSenderUuid(sender), sender.getName(),
            "RANK_SET", name, "rank=" + group);
    }

    private void addRank(CommandSender sender, UUID uuid, String name, String group) {
        accessService.addToGroup(uuid, group, getSenderUuid(sender));
        if (targetUuidOnline(uuid) != null) accessService.refreshPlayerPermissions(targetUuidOnline(uuid));
        sender.sendMessage(fmt.success("Added &e" + group + "&a rank to " + name));

        plugin.getAuditLogger().logAdminAction(getSenderUuid(sender), sender.getName(),
            "RANK_ADD", name, "rank=" + group);
    }

    private void removeRank(CommandSender sender, UUID uuid, String name, String group) {
        accessService.removeFromGroup(uuid, group);
        if (targetUuidOnline(uuid) != null) accessService.refreshPlayerPermissions(targetUuidOnline(uuid));
        sender.sendMessage(fmt.success("Removed &e" + group + "&a rank from " + name));

        plugin.getAuditLogger().logAdminAction(getSenderUuid(sender), sender.getName(),
            "RANK_REMOVE", name, "rank=" + group);
    }

    private UUID getSenderUuid(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    private Player targetUuidOnline(UUID uuid) { return Bukkit.getPlayer(uuid); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Tab complete player names
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 2) {
            return Arrays.asList("set", "add", "remove", "info").stream()
                .filter(a -> a.startsWith(args[1].toLowerCase()))
                .toList();
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("set")
            || args[1].equalsIgnoreCase("add")
            || args[1].equalsIgnoreCase("remove"))) {
            return Arrays.asList("default", "supporter", "vip", "helper", "mod", "admin", "developer", "owner").stream()
                .filter(g -> g.startsWith(args[2].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
