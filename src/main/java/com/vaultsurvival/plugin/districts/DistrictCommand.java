package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * District management commands.
 *
 * /district apply <name>              — Submit a district application
 * /district approve <id>              — Admin approve application
 * /district reject <id> <reason>      — Admin reject application
 * /district info [id]                 — District info (your district or specific)
 * /district invite <player>           — Invite player to your district
 * /district kick <player>             — Kick member from your district
 * /district role <player> <role>      — Set member role (mayor only)
 * /district deposit <amount>          — Deposit cash into treasury
 * /district withdraw <amount>         — Withdraw from treasury (treasurer+)
 * /district law <name> <true|false>   — Toggle a local law (council+)
 * /district list                      — List all districts
 * /district disband                   — Disband your district (mayor only)
 * /district applications              — List pending applications (admin)
 */
public class DistrictCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final DistrictService districtService;
    private final MessageFormatter fmt;

    public DistrictCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.districtService = plugin.getServiceRegistry().get(DistrictService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "apply" -> handleApply(sender, args);
            case "approve" -> handleApprove(sender, args);
            case "reject" -> handleReject(sender, args);
            case "info" -> handleInfo(sender, args);
            case "invite" -> handleInvite(sender, args);
            case "kick" -> handleKick(sender, args);
            case "role" -> handleRole(sender, args);
            case "deposit" -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "law" -> handleLaw(sender, args);
            case "list" -> handleList(sender);
            case "disband" -> handleDisband(sender);
            case "applications" -> handleApplications(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleApply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can apply for a district."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district apply <name>"));
            return true;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        districtService.apply(player, name);
        return true;
    }

    private boolean handleApprove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district approve <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        UUID adminUuid = sender instanceof Player p ? p.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (districtService.approve(id, adminUuid)) {
            sender.sendMessage(fmt.success("District #" + id + " approved!"));
        } else {
            sender.sendMessage(fmt.error("District not found or not in application status."));
        }
        return true;
    }

    private boolean handleReject(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /district reject <id> <reason>"));
            return true;
        }
        int id = parseInt(args[1]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        UUID adminUuid = sender instanceof Player p ? p.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (districtService.reject(id, adminUuid, reason)) {
            sender.sendMessage(fmt.success("District #" + id + " rejected."));
        } else {
            sender.sendMessage(fmt.error("District not found or not in application status."));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            int id = parseInt(args[1]);
            var d = districtService.getDistrict(id);
            if (d == null) { sender.sendMessage(fmt.error("District not found.")); return true; }
            showDistrictInfo(sender, d);
        } else if (sender instanceof Player player) {
            var d = districtService.getPlayerDistrict(player.getUniqueId());
            if (d == null) {
                sender.sendMessage(fmt.info("You are not in a district. Found one with &e/district apply <name>"));
            } else {
                showDistrictInfo(sender, d);
            }
        }
        return true;
    }

    private boolean handleInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district invite <player>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(fmt.playerNotFound(args[1])); return true; }
        if (districtService.inviteMember(d.getId(), player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(fmt.success("Invited &e" + target.getName() + " &ato join your district!"));
            target.sendMessage(fmt.success("You've been invited to join &e" + d.getName() + "&a!"));
        }
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district kick <player>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        // Look up member UUID by name from district's actual member list
        String targetName = args[1];
        UUID targetUuid = null;
        for (UUID memberUuid : d.getMembers()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(memberUuid).getName();
            if (targetName.equalsIgnoreCase(name)) {
                targetUuid = memberUuid;
                break;
            }
        }
        if (targetUuid == null) {
            player.sendMessage(fmt.error("That player is not a member of your district."));
            return true;
        }
        if (districtService.kickMember(d.getId(), player.getUniqueId(), targetUuid)) {
            player.sendMessage(fmt.success("Kicked &e" + targetName + " &afrom the district."));
        } else {
            player.sendMessage(fmt.error("Cannot kick that player. You need council+ rank, and you cannot kick the mayor."));
        }
        return true;
    }

    private boolean handleRole(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /district role <player> <role>"));
            sender.sendMessage(fmt.info("Roles: " + Arrays.stream(DistrictData.DistrictRole.values())
                .map(Enum::name).collect(Collectors.joining(", "))));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        DistrictData.DistrictRole role;
        try { role = DistrictData.DistrictRole.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Unknown role.")); return true;
        }
        // Look up member UUID by name from district's actual member list
        String targetName = args[1];
        UUID targetUuid = null;
        for (UUID memberUuid : d.getMembers()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(memberUuid).getName();
            if (targetName.equalsIgnoreCase(name)) {
                targetUuid = memberUuid;
                break;
            }
        }
        if (targetUuid == null) {
            player.sendMessage(fmt.error("That player is not a member of your district."));
            return true;
        }
        if (districtService.setRole(d.getId(), player.getUniqueId(), targetUuid, role)) {
            player.sendMessage(fmt.success("Set &e" + targetName + "'s &arole to &e" + role.name()));
        } else {
            player.sendMessage(fmt.error("You must be mayor to change roles."));
        }
        return true;
    }

    private boolean handleDeposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district deposit <amount>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        long amount = parseLong(args[1]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }
        districtService.depositTreasury(player, d.getId(), amount);
        return true;
    }

    private boolean handleWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district withdraw <amount>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        long amount = parseLong(args[1]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }
        districtService.withdrawTreasury(player, d.getId(), amount);
        return true;
    }

    private boolean handleLaw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /district law <name> <true|false>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        boolean enabled = args[2].equalsIgnoreCase("true");
        if (districtService.setLaw(d.getId(), player.getUniqueId(), args[1].toLowerCase(), enabled)) {
            player.sendMessage(fmt.success("Law &e" + args[1] + " &7= &e" + enabled));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var all = districtService.getAllDistricts();
        sender.sendMessage(fmt.header("Districts (" + all.size() + ")"));
        if (all.isEmpty()) {
            sender.sendMessage(fmt.info("No districts yet. Found one with &e/district apply <name>"));
        } else {
            for (var d : all) {
                sender.sendMessage(fmt.info(
                    "&e#" + d.getId() + " &f" + d.getName() +
                    " &7(" + d.getStatus() + ") &8| " +
                    d.getMemberCount() + " members &8| " +
                    "&6" + fmt.formatMoney(d.getTreasuryBalance(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())
                ));
            }
        }
        return true;
    }

    private boolean handleDisband(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        if (districtService.disband(d.getId(), player.getUniqueId())) {
            player.sendMessage(fmt.success("District disbanded."));
        } else {
            player.sendMessage(fmt.error("Only the mayor or an admin can disband the district."));
        }
        return true;
    }

    private boolean handleApplications(CommandSender sender) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        var apps = districtService.getApplications();
        sender.sendMessage(fmt.header("Pending Applications (" + apps.size() + ")"));
        if (apps.isEmpty()) {
            sender.sendMessage(fmt.info("No pending applications."));
        } else {
            for (var d : apps) {
                @SuppressWarnings("deprecation")
                var founder = Bukkit.getOfflinePlayer(d.getFounderUuid()).getName();
                sender.sendMessage(fmt.info(
                    "&e#" + d.getId() + " &f" + d.getName() +
                    " &8| Founder: &e" + founder +
                    " &8| Use &e/district approve " + d.getId() + " &8or &e/district reject " + d.getId()
                ));
            }
        }
        return true;
    }

    private void showDistrictInfo(CommandSender sender, DistrictData.District d) {
        sender.sendMessage(fmt.header("District: " + d.getName()));
        sender.sendMessage(fmt.info("ID: &e#" + d.getId() + " &8| Status: &e" + d.getStatus()));
        sender.sendMessage(fmt.info("Treasury: &6" + fmt.formatMoney(d.getTreasuryBalance(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Members (" + d.getMemberCount() + "):"));
        for (var entry : d.getRoles().entrySet()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8);
            sender.sendMessage(fmt.info("  &e" + entry.getValue().name() + " &8- &7" + name));
        }
        if (!d.getLaws().isEmpty()) {
            sender.sendMessage(fmt.info("Laws:"));
            d.getLaws().forEach((law, enabled) -> {
                sender.sendMessage(fmt.info("  " + (enabled ? "&a✔" : "&c✘") + " &7" + law));
            });
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("District Commands"));
        sender.sendMessage(fmt.info("/district apply <name> &8- Found a district"));
        sender.sendMessage(fmt.info("/district info [id] &8- District info"));
        sender.sendMessage(fmt.info("/district invite <player> &8- Invite to your district"));
        sender.sendMessage(fmt.info("/district kick <player> &8- Kick member"));
        sender.sendMessage(fmt.info("/district role <player> <role> &8- Set role"));
        sender.sendMessage(fmt.info("/district deposit <amount> &8- Deposit to treasury"));
        sender.sendMessage(fmt.info("/district withdraw <amount> &8- Withdraw from treasury"));
        sender.sendMessage(fmt.info("/district law <name> <true|false> &8- Toggle law"));
        sender.sendMessage(fmt.info("/district list &8- All districts"));
        sender.sendMessage(fmt.info("/district disband &8- Disband your district"));
        if (sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.info("/district approve <id> &8- Approve application"));
            sender.sendMessage(fmt.info("/district reject <id> <reason> &8- Reject application"));
            sender.sendMessage(fmt.info("/district applications &8- Pending applications"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("apply", "approve", "reject", "info", "invite", "kick",
                "role", "deposit", "withdraw", "law", "list", "disband", "applications")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("role")) {
            return Arrays.stream(DistrictData.DistrictRole.values())
                .map(Enum::name).filter(r -> r.startsWith(args[2].toUpperCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick")
            || args[0].equalsIgnoreCase("role"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }
}
