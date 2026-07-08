package com.vaultsurvival.plugin.breach;

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
 * Commands for the breach system.
 *
 * /breach kit            — Spawn a breach kit (admin)
 * /breach start          — Start a breach on the vault you're looking at
 * /breach cancel         — Cancel your active breach
 * /breach log <uuid>     — View breach logs for a vault (admin)
 * /breach logplayer <name> — View breach logs for a player (admin)
 * /breach escapecooldown <player> — Check escape cooldown (admin)
 */
public class BreachCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final BreachService breachService;
    private final MessageFormatter fmt;

    public BreachCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.breachService = plugin.getServiceRegistry().get(BreachService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "kit" -> handleKit(sender);
            case "start" -> handleStart(sender);
            case "cancel" -> handleCancel(sender);
            case "log" -> handleLog(sender, args);
            case "logplayer" -> handleLogPlayer(sender, args);
            case "escapecooldown" -> handleEscapeCooldown(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleKit(CommandSender sender) {
        if (!sender.hasPermission("vs.breach.admin.kit")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can receive breach kits."));
            return true;
        }

        var kit = breachService.createBreachKit();
        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), kit);
            player.sendMessage(fmt.info("Your inventory was full. Breach kit dropped at your feet."));
        } else {
            player.getInventory().addItem(kit);
        }
        player.sendMessage(fmt.success("Received a &c&lBreach Kit&a."));
        player.sendMessage(fmt.info("Use it by right-clicking a vault or &e/breach start"));
        return true;
    }

    private boolean handleStart(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can breach vaults."));
            return true;
        }

        if (!sender.hasPermission("vs.breach.use")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        // Find vault the player is looking at
        var block = player.getTargetBlockExact(5);
        if (block == null) {
            player.sendMessage(fmt.error("You must be looking at a vault to breach it."));
            return true;
        }

        var vault = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.vaults.VaultService.class).getVaultAt(block.getLocation());

        if (vault == null) {
            player.sendMessage(fmt.error("You are not looking at a vault. Look at a vault block."));
            player.sendMessage(fmt.info("Vault blocks look like &eBarrels &7placed by the vault system."));
            return true;
        }

        breachService.startBreach(player, vault.getVaultUuid());
        return true;
    }

    private boolean handleCancel(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can cancel a breach."));
            return true;
        }

        if (!breachService.isBreaching(player.getUniqueId())) {
            player.sendMessage(fmt.error("You are not currently breaching a vault."));
            return true;
        }

        breachService.cancelBreach(player.getUniqueId(), "Cancelled by player");
        player.sendMessage(fmt.info("Breach cancelled. Your breach kit was consumed."));
        return true;
    }

    private boolean handleLog(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.breach.admin.log")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /breach log <vault_uuid>"));
            return true;
        }

        try {
            UUID vaultUuid = UUID.fromString(args[1]);
            List<BreachData.BreachLogEntry> logs = breachService.getBreachLogs(vaultUuid);

            sender.sendMessage(fmt.header("Breach Logs — Vault " + vaultUuid.toString().substring(0, 8) + "..."));
            if (logs.isEmpty()) {
                sender.sendMessage(fmt.info("No breach attempts on this vault."));
            } else {
                for (var entry : logs) {
                    String status = entry.isSuccess() ? "&aSUCCESS" : "&cFAILED";
                    String thief = "&e" + (Bukkit.getOfflinePlayer(entry.getThiefUuid()).getName() != null
                        ? Bukkit.getOfflinePlayer(entry.getThiefUuid()).getName()
                        : entry.getThiefUuid().toString().substring(0, 8));
                    sender.sendMessage(fmt.info(
                        status + " &8| " + thief +
                        " &8| Stole: &6" + fmt.formatMoney(entry.getStolenAmount(),
                            plugin.getConfigManager().getCurrencyName(),
                            plugin.getConfigManager().getCurrencyNamePlural()) +
                        " &8| Score: &e" + String.format("%.0f%%", entry.getBreachScore() * 100) +
                        " &8| " + (entry.getStartedAt() != null ? entry.getStartedAt().substring(0, 16) : "?")
                    ));
                }
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Invalid UUID format."));
        }
        return true;
    }

    private boolean handleLogPlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.breach.admin.log")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /breach logplayer <player>"));
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore()) {
            sender.sendMessage(fmt.playerNotFound(args[1]));
            return true;
        }

        List<BreachData.BreachLogEntry> logs = breachService.getBreachLogsByThief(target.getUniqueId());

        sender.sendMessage(fmt.header("Breach Logs — " + target.getName()));
        if (logs.isEmpty()) {
            sender.sendMessage(fmt.info("No breach attempts by this player."));
        } else {
            for (var entry : logs) {
                String status = entry.isSuccess() ? "&aSUCCESS" : "&cFAILED";
                sender.sendMessage(fmt.info(
                    status + " &8| Vault: &e" + entry.getVaultUuid().toString().substring(0, 8) +
                    " &8| Stole: &6" + fmt.formatMoney(entry.getStolenAmount(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural()) +
                    " &8| Score: &e" + String.format("%.0f%%", entry.getBreachScore() * 100) +
                    " &8| " + (entry.getStartedAt() != null ? entry.getStartedAt().substring(0, 16) : "?")
                ));
            }
        }
        return true;
    }

    private boolean handleEscapeCooldown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.breach.admin.log")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /breach escapecooldown <player>"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(fmt.playerNotFound(args[1]));
            return true;
        }

        if (breachService.isTeleportBlocked(target.getUniqueId())) {
            sender.sendMessage(fmt.info("&e" + target.getName() + " &7has an active escape cooldown."));
        } else {
            sender.sendMessage(fmt.info("&e" + target.getName() + " &7is not on escape cooldown."));
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Breach Commands"));
        if (sender.hasPermission("vs.breach.admin.kit")) {
            sender.sendMessage(fmt.info("/breach kit &8- Spawn a breach kit"));
        }
        if (sender.hasPermission("vs.breach.use")) {
            sender.sendMessage(fmt.info("/breach start &8- Breach the vault you're looking at"));
            sender.sendMessage(fmt.info("/breach cancel &8- Cancel your active breach"));
        }
        if (sender.hasPermission("vs.breach.admin.log")) {
            sender.sendMessage(fmt.info("/breach log <vault_uuid> &8- View breach logs"));
            sender.sendMessage(fmt.info("/breach logplayer <name> &8- View player's breaches"));
            sender.sendMessage(fmt.info("/breach escapecooldown <player> &8- Check cooldown"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("kit", "start", "cancel", "log", "logplayer", "escapecooldown")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("escapecooldown")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("logplayer")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
