package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Commands for the Crime & Police system.
 *
 * /crime wanted              — List wanted players in your district
 * /crime record [player]     — Show player's crime history (police/admin)
 * /crime bounty <player> <amount> — Set bounty on wanted player (police)
 * /crime arrest <player>     — Arrest a wanted player (police, within 10 blocks)
 * /crime fine <player> <amount> — Fine a wanted player (police)
 */
public class CrimeCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final CrimeService crimeService;
    private final MessageFormatter fmt;

    public CrimeCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.crimeService = plugin.getServiceRegistry().get(CrimeService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "wanted" -> handleWanted(sender);
            case "record" -> handleRecord(sender, args);
            case "bounty" -> handleBounty(sender, args);
            case "arrest" -> handleArrest(sender, args);
            case "fine" -> handleFine(sender, args);
            case "setjail" -> handleSetJail(sender);
            case "release" -> handleRelease(sender, args);
            case "jailed" -> handleJailed(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleWanted(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }

        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) return true;

        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) {
            sender.sendMessage(fmt.info("You are not in a district."));
            return true;
        }

        var wanted = crimeService.getWantedPlayers(d.getId());
        sender.sendMessage(fmt.header("Wanted in " + d.getName() + " (" + wanted.size() + ")"));
        if (wanted.isEmpty()) {
            sender.sendMessage(fmt.info("No wanted players. District is safe!"));
        } else {
            for (var w : wanted) {
                @SuppressWarnings("deprecation")
                String name = Bukkit.getOfflinePlayer(w.getCriminalUuid()).getName();
                if (name == null) name = w.getCriminalUuid().toString().substring(0, 8);
                sender.sendMessage(fmt.info(
                    "&e" + name + " &8| Crimes: &c" + w.getCrimeCount() +
                    " &8| Bounty: &6" + fmt.formatMoney(w.getBounty(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())
                ));
            }
        }
        return true;
    }

    private boolean handleRecord(CommandSender sender, String[] args) {
        UUID targetUuid;
        String targetName;
        if (args.length >= 2) {
            if (!sender.hasPermission("vs.crime.admin")) {
                sender.sendMessage(fmt.permissionDenied());
                return true;
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            targetUuid = target.getUniqueId();
            targetName = target.getName() != null ? target.getName() : args[1];
        } else if (sender instanceof Player player) {
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        } else {
            sender.sendMessage(fmt.error("Usage: /crime record <player>"));
            return true;
        }

        var records = crimeService.getCrimeRecord(targetUuid);
        sender.sendMessage(fmt.header("Crime Record: " + targetName + " (" + records.size() + ")"));
        if (records.isEmpty()) {
            sender.sendMessage(fmt.info("Clean record — no crimes."));
        } else {
            for (var r : records) {
                long ago = (System.currentTimeMillis() - r.getTimestamp()) / 60000;
                sender.sendMessage(fmt.info(
                    "&e#" + r.getId() + " &7" + r.getType() + " &8| " +
                    "&c" + r.getSeverity() + " &8| " +
                    "&f" + r.getBlockType() + " &8| " +
                    "&7" + ago + "m ago &8| " +
                    "District #" + r.getDistrictId()
                ));
            }
        }
        return true;
    }

    private boolean handleBounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /crime bounty <player> <amount>"));
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

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        long amount = parseLong(args[2]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }

        if (crimeService.setBounty(d.getId(), target.getUniqueId(), amount, player.getUniqueId())) {
            player.sendMessage(fmt.success("Bounty set on &e" + args[1] + " &afor &6" +
                fmt.formatMoney(amount, plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
        } else {
            player.sendMessage(fmt.error("Cannot set bounty. Player may not be wanted, or you lack police rank."));
        }
        return true;
    }

    private boolean handleArrest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /crime arrest <player>"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(fmt.playerNotFound(args[1]));
            return true;
        }

        crimeService.arrest(player.getUniqueId(), target.getUniqueId());
        return true;
    }

    private boolean handleFine(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /crime fine <player> <amount>"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(fmt.playerNotFound(args[1]));
            return true;
        }
        long amount = parseLong(args[2]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }

        crimeService.fine(player.getUniqueId(), target.getUniqueId(), amount);
        return true;
    }

    private boolean handleSetJail(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        if (!d.isMayor(player.getUniqueId())) { sender.sendMessage(fmt.error("Only the mayor can set jail location.")); return true; }

        var loc = player.getLocation();
        if (crimeService.setJailLocation(d.getId(), loc.getWorld().getName(),
            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), player.getUniqueId())) {
            player.sendMessage(fmt.success("Jail location set to your current position!"));
        }
        return true;
    }

    private boolean handleRelease(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /crime release <player>"));
            return true;
        }
        @SuppressWarnings("deprecation")
        var target = Bukkit.getOfflinePlayer(args[1]);
        crimeService.release(player.getUniqueId(), target.getUniqueId());
        return true;
    }

    private boolean handleJailed(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.info("You are not in a district.")); return true; }

        var jailed = crimeService.getJailedPlayers(d.getId());
        sender.sendMessage(fmt.header("Jailed in " + d.getName() + " (" + jailed.size() + ")"));
        if (jailed.isEmpty()) {
            sender.sendMessage(fmt.info("No players are currently jailed."));
        } else {
            long now = System.currentTimeMillis();
            for (var j : jailed) {
                @SuppressWarnings("deprecation")
                String name = Bukkit.getOfflinePlayer(j.getCriminalUuid()).getName();
                if (name == null) name = j.getCriminalUuid().toString().substring(0, 8);
                long remaining = (j.getJailUntil() - now) / 60000;
                sender.sendMessage(fmt.info(
                    "&e" + name + " &8| Remaining: &c" + remaining + " min" +
                    " &8| Crimes: &7" + j.getCrimeCount()
                ));
            }
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Crime & Police Commands"));
        sender.sendMessage(fmt.info("/crime wanted &8- Wanted players in your district"));
        sender.sendMessage(fmt.info("/crime record [player] &8- View crime history"));
        sender.sendMessage(fmt.info("/crime bounty <player> <amount> &8- Set bounty (police)"));
        sender.sendMessage(fmt.info("/crime arrest <player> &8- Arrest wanted player (police)"));
        sender.sendMessage(fmt.info("/crime fine <player> <amount> &8- Fine wanted player (police)"));
        sender.sendMessage(fmt.info("/crime setjail &8- Set jail location here (mayor)"));
        sender.sendMessage(fmt.info("/crime release <player> &8- Release jailed player (police)"));
        sender.sendMessage(fmt.info("/crime jailed &8- List jailed players in your district"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("wanted", "record", "bounty", "arrest", "fine", "setjail", "release", "jailed")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("bounty") || args[0].equalsIgnoreCase("arrest")
            || args[0].equalsIgnoreCase("fine") || args[0].equalsIgnoreCase("record"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }
}
