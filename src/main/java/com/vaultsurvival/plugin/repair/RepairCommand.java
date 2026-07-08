package com.vaultsurvival.plugin.repair;

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
 * Commands for the Repairmen system.
 *
 * /repair status              — Show repair points and wage status for your district
 * /repair pay                 — Manually pay daily wage (mayor/treasurer)
 * /repair emergency <damageId> — Force immediate restore using 5 repair points (council+)
 */
public class RepairCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final RepairService repairService;
    private final MessageFormatter fmt;

    public RepairCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.repairService = plugin.getServiceRegistry().get(RepairService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "pay" -> handlePay(sender);
            case "emergency" -> handleEmergency(sender, args);
            case "npc" -> handleNpc(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }

        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) {
            sender.sendMessage(fmt.error("District system not loaded."));
            return true;
        }

        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) {
            sender.sendMessage(fmt.info("You are not in a district. Join one to see repair status!"));
            return true;
        }

        var state = repairService.getState(d.getId());
        int dailyPoints = plugin.getConfigManager().getRestoreDailyPoints();
        int dailyWage = plugin.getConfigManager().getRestoreDailyWage();
        int normalDelay = plugin.getConfigManager().getRestoreNormalDelayMinutes();
        int exhaustedDelay = plugin.getConfigManager().getRestoreExhaustedDelayMinutes();

        sender.sendMessage(fmt.header("Repair Status: " + d.getName()));
        sender.sendMessage(fmt.info("Repair Points: &e" + state.getRepairPoints() + " &7/ " + dailyPoints));
        sender.sendMessage(fmt.info("Status: " + (state.isExhausted() ? "&cEXHAUSTED (slow restores)" : "&aACTIVE (fast restores)")));
        sender.sendMessage(fmt.info("Restore Delay: &e" + (state.isExhausted() ? exhaustedDelay : normalDelay) + " minutes"));
        sender.sendMessage(fmt.info("Daily Wage: &6" + fmt.formatMoney(dailyWage,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Treasury Balance: &6" + fmt.formatMoney(d.getTreasuryBalance(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        var damageService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.damage.DamageService.class);
        if (damageService != null) {
            int pending = damageService.getPendingDamage(d.getId()).size();
            sender.sendMessage(fmt.info("Pending Restores: &e" + pending));
        }

        return true;
    }

    private boolean handlePay(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;

        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) {
            sender.sendMessage(fmt.error("District system not loaded."));
            return true;
        }

        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) {
            sender.sendMessage(fmt.error("You are not in a district."));
            return true;
        }

        if (repairService.payWage(d.getId(), player.getUniqueId())) {
            // Success message is in the service
        } else {
            player.sendMessage(fmt.error("Could not pay wage. Check that the treasury has enough cash."));
        }
        return true;
    }

    private boolean handleEmergency(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /repair emergency <damageId>"));
            return true;
        }

        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) {
            sender.sendMessage(fmt.error("District system not loaded."));
            return true;
        }

        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) {
            sender.sendMessage(fmt.error("You are not in a district."));
            return true;
        }

        int damageId;
        try { damageId = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
            sender.sendMessage(fmt.error("Invalid damage ID."));
            return true;
        }

        repairService.emergencyRepair(d.getId(), damageId, player.getUniqueId());
        return true;
    }

    private boolean handleNpc(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) {
            sender.sendMessage(fmt.error("You are not in a district."));
            return true;
        }
        if (!d.isMayor(player.getUniqueId())) {
            sender.sendMessage(fmt.error("Only the mayor can create a repairman NPC."));
            return true;
        }
        repairService.createRepairmanNpc(d.getId(), player.getLocation());
        player.sendMessage(fmt.success("Repairman NPC created at your location!"));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Repair Commands"));
        sender.sendMessage(fmt.info("/repair status &8- View district repair points and status"));
        sender.sendMessage(fmt.info("/repair pay &8- Pay daily wage from treasury (mayor/treasurer)"));
        sender.sendMessage(fmt.info("/repair emergency <damageId> &8- Force restore block (5 pts, council+)"));
        sender.sendMessage(fmt.info("/repair npc &8- Create repairman NPC at your location (mayor)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "pay", "emergency", "npc")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
