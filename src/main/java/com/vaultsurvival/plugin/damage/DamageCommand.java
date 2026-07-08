package com.vaultsurvival.plugin.damage;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Commands for inspecting and managing temporary district damage.
 *
 * /damage info              — Show pending damage at your location
 * /damage list [districtId] — List all pending damage (admin/mayor)
 * /damage restore <id>      — Force immediate restoration
 */
public class DamageCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final DamageService damageService;
    private final MessageFormatter fmt;

    public DamageCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.damageService = plugin.getServiceRegistry().get(DamageService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "info" -> handleInfo(sender);
            case "list" -> handleList(sender, args);
            case "restore" -> handleRestore(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }
        var damageList = damageService.getDamageAt(player.getLocation());
        sender.sendMessage(fmt.header("Temporary Damage at Your Location"));
        if (damageList.isEmpty()) {
            sender.sendMessage(fmt.info("No pending damage here."));
        } else {
            for (var d : damageList) {
                String actorName = "Unknown";
                var offline = org.bukkit.Bukkit.getOfflinePlayer(d.getActorUuid());
                if (offline.getName() != null) actorName = offline.getName();

                long remaining = (d.getScheduledRestoreTime() - System.currentTimeMillis()) / 1000;
                String timeStr = remaining > 0 ? (remaining / 60) + "m " + (remaining % 60) + "s" : "due now";

                sender.sendMessage(fmt.info(
                    "&e#" + d.getId() + " &7" + d.getType() + " &8| " +
                    "&f" + d.getOriginalBlock() + " &8→ &7restores in &e" + timeStr +
                    " &8| By: &7" + actorName +
                    " &8| Class: &e" + d.getBlockClass()
                ));
            }
        }
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.damage.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        int districtFilter = -1;
        if (args.length >= 2) {
            try { districtFilter = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                sender.sendMessage(fmt.error("Invalid district ID."));
                return true;
            }
        }
        final int filterId = districtFilter;

        var all = damageService.getAllDamage();
        long pendingCount = all.stream()
            .filter(r -> !r.isRestored() && (filterId < 0 || r.getDistrictId() == filterId))
            .count();
        sender.sendMessage(fmt.header("Pending Damage (" + pendingCount + ")"));
        for (var d : all) {
            if (d.isRestored()) continue;
            if (filterId >= 0 && d.getDistrictId() != filterId) continue;

            String actorName = "Unknown";
            var offline = org.bukkit.Bukkit.getOfflinePlayer(d.getActorUuid());
            if (offline.getName() != null) actorName = offline.getName();

            long remaining = (d.getScheduledRestoreTime() - System.currentTimeMillis()) / 1000;
            String timeStr = remaining > 0 ? (remaining / 60) + "m" : "due";

            sender.sendMessage(fmt.info(
                "&e#" + d.getId() + " &7" + d.getType() + " &8| " +
                "District &e#" + d.getDistrictId() + " &8| " +
                "&f" + d.getOriginalBlock() + " &8| " +
                "&e" + d.getWorldName() + " " + d.getX() + "," + d.getY() + "," + d.getZ() +
                " &8| &e" + timeStr + " &8| &7" + actorName
            ));
        }
        return true;
    }

    private boolean handleRestore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.damage.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /damage restore <id>"));
            return true;
        }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
            sender.sendMessage(fmt.error("Invalid ID."));
            return true;
        }
        if (damageService.forceRestore(id)) {
            sender.sendMessage(fmt.success("Damage record #" + id + " force-restored."));
        } else {
            sender.sendMessage(fmt.error("Damage record not found or already restored."));
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Temporary Damage Commands"));
        sender.sendMessage(fmt.info("/damage info &8- Pending damage at your location"));
        if (sender.hasPermission("vs.damage.admin")) {
            sender.sendMessage(fmt.info("/damage list [districtId] &8- All pending damage"));
            sender.sendMessage(fmt.info("/damage restore <id> &8- Force immediate restore"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("info", "list", "restore")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
