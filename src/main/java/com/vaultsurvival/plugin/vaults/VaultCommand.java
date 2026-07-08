package com.vaultsurvival.plugin.vaults;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.vaults.VaultData.VaultTier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Commands for vault operations.
 * /vault place <tier>
 * /vault remove
 * /vault info
 * /vault deposit
 * /vault withdraw <amount>
 * /vault access <add|remove> <player>
 * /vault repair
 * /vault list
 * /vault inspect <uuid> (admin)
 */
public class VaultCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final VaultService vaultService;
    private final CurrencyService currencyService;
    private final MessageFormatter fmt;

    public VaultCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.vaultService = plugin.getServiceRegistry().get(VaultService.class);
        this.currencyService = plugin.getServiceRegistry().get(CurrencyService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use vault commands."));
            return true;
        }

        if (!sender.hasPermission("vs.vault.use")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "place" -> handlePlace(player, args);
            case "remove" -> handleRemove(player);
            case "info" -> handleInfo(player);
            case "deposit" -> handleDeposit(player);
            case "withdraw" -> handleWithdraw(player, args);
            case "access" -> handleAccess(player, args);
            case "repair" -> handleRepair(player);
            case "list" -> handleList(player);
            case "inspect" -> handleInspect(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handlePlace(Player player, String[] args) {
        if (!player.hasPermission("vs.vault.place")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /vault place <small|iron|reinforced|treasury|decoy>"));
            player.sendMessage(fmt.info("Tiers: small(10k), iron(50k), reinforced(200k), treasury(1M), decoy(5k)"));
            return true;
        }

        VaultTier tier;
        try {
            tier = VaultTier.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(fmt.error("Unknown tier. Use: small, iron, reinforced, treasury, decoy"));
            return true;
        }

        // Place vault at player's looking-at block or at player's location
        Location location = player.getTargetBlockExact(5) != null
            ? player.getTargetBlockExact(5).getLocation().add(0, 1, 0)
            : player.getLocation().add(0, 1, 0);

        // Check if there's already a block or vault here
        if (vaultService.isVaultBlock(location)) {
            player.sendMessage(fmt.error("There is already a vault here."));
            return true;
        }

        if (location.getBlock().getType() != Material.AIR) {
            player.sendMessage(fmt.error("That location is not empty."));
            return true;
        }

        UUID vaultUuid = vaultService.placeVault(player, location, tier);
        if (vaultUuid == null) {
            player.sendMessage(fmt.error("Failed to place vault."));
        }

        return true;
    }

    private boolean handleRemove(Player player) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        vaultService.removeVault(player, vault.getVaultUuid());
        return true;
    }

    private boolean handleInfo(Player player) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        showVaultInfo(player, vault);
        return true;
    }

    private boolean handleDeposit(Player player) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        if (!vaultService.canAccess(vault.getVaultUuid(), player.getUniqueId())) {
            player.sendMessage(fmt.error("You don't have access to this vault."));
            return true;
        }

        // Collect all cash items from player inventory
        List<ItemStack> cashItems = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && currencyService.isCashItem(item) && currencyService.validateCash(item)) {
                cashItems.add(item);
            }
        }

        if (cashItems.isEmpty()) {
            player.sendMessage(fmt.error("You have no cash in your inventory to deposit."));
            return true;
        }

        vaultService.depositCash(player, vault.getVaultUuid(), cashItems);
        return true;
    }

    private boolean handleWithdraw(Player player, String[] args) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /vault withdraw <amount>"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid amount."));
            return true;
        }

        vaultService.withdrawCash(player, vault.getVaultUuid(), amount);
        return true;
    }

    private boolean handleAccess(Player player, String[] args) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        if (!vault.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(fmt.error("Only the vault owner can manage access."));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(fmt.error("Usage: /vault access <add|remove> <player>"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (!target.hasPlayedBefore()) {
            player.sendMessage(fmt.playerNotFound(args[2]));
            return true;
        }

        String action = args[1].toLowerCase();
        if (action.equals("add")) {
            vaultService.grantAccess(vault.getVaultUuid(), target.getUniqueId(), "USER", player.getUniqueId());
            player.sendMessage(fmt.success("Granted vault access to &e" + target.getName()));
        } else if (action.equals("remove")) {
            vaultService.revokeAccess(vault.getVaultUuid(), target.getUniqueId());
            player.sendMessage(fmt.success("Revoked vault access from &e" + target.getName()));
        } else {
            player.sendMessage(fmt.error("Use: add or remove"));
        }

        return true;
    }

    private boolean handleRepair(Player player) {
        VaultData vault = getTargetedVault(player);
        if (vault == null) return true;

        vaultService.repairVault(player, vault.getVaultUuid());
        return true;
    }

    private boolean handleList(Player player) {
        List<VaultData> vaults = vaultService.getPlayerVaults(player.getUniqueId());

        player.sendMessage(fmt.header("Your Vaults"));
        if (vaults.isEmpty()) {
            player.sendMessage(fmt.info("You don't own any vaults."));
        } else {
            for (VaultData vault : vaults) {
                String status = vault.isLockedDown() ? " &c[LOCKDOWN]" : "";
                player.sendMessage(fmt.info("&e" + vault.getTier().getDisplayName() + status +
                    " &8- &6" + vault.getBalance() + " coins" +
                    " &8- &7" + vault.getWorld() + " " + vault.getX() + "," + vault.getY() + "," + vault.getZ()));
            }
        }
        return true;
    }

    private boolean handleInspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.vault.admin.inspect")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /vault inspect <uuid>"));
            return true;
        }

        try {
            UUID vaultUuid = UUID.fromString(args[1]);
            VaultData vault = vaultService.getVault(vaultUuid);

            if (vault == null) {
                sender.sendMessage(fmt.error("Vault not found."));
                return true;
            }

            showVaultInfo(sender, vault);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Invalid UUID format."));
        }

        return true;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private VaultData getTargetedVault(Player player) {
        Location loc = player.getTargetBlockExact(5) != null
            ? player.getTargetBlockExact(5).getLocation()
            : player.getLocation();

        VaultData vault = vaultService.getVaultAt(loc);
        if (vault == null) {
            player.sendMessage(fmt.error("You are not looking at a vault. Look at a vault block."));
        }
        return vault;
    }

    private void showVaultInfo(CommandSender sender, VaultData vault) {
        sender.sendMessage(fmt.header(vault.getTier().getDisplayName()));
        sender.sendMessage(fmt.info("UUID: &e" + vault.getVaultUuid()));
        sender.sendMessage(fmt.info("Owner: &e" + vault.getOwnerUuid()));
        sender.sendMessage(fmt.info("Location: &e" + vault.getWorld() + " " + vault.getX() + "," + vault.getY() + "," + vault.getZ()));
        sender.sendMessage(fmt.info("Balance: &6" + fmt.formatMoney(vault.getBalance(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Protected: &e" + fmt.formatMoney(vault.getProtectedAmount(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Capacity: &e" + fmt.formatMoney(vault.getCapacity(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Lockdown: &e" + (vault.isLockedDown() ? "Yes" : "No")));
        sender.sendMessage(fmt.info("Breachable: &e" + (vault.isBreachable() ? "Yes" : "No")));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Vault Commands"));
        sender.sendMessage(fmt.info("/vault place <tier> &8- Place a vault"));
        sender.sendMessage(fmt.info("/vault remove &8- Remove empty vault"));
        sender.sendMessage(fmt.info("/vault info &8- Vault details"));
        sender.sendMessage(fmt.info("/vault deposit &8- Deposit all cash from inventory"));
        sender.sendMessage(fmt.info("/vault withdraw <amount> &8- Withdraw cash"));
        sender.sendMessage(fmt.info("/vault access <add|remove> <player> &8- Manage access"));
        sender.sendMessage(fmt.info("/vault repair &8- Lift lockdown (costs 5% of balance)"));
        sender.sendMessage(fmt.info("/vault list &8- List your vaults"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("place", "remove", "info", "deposit", "withdraw", "access", "repair", "list", "inspect")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return Arrays.stream(VaultTier.values())
                .map(t -> t.name().toLowerCase())
                .filter(t -> t.startsWith(args[1].toLowerCase()))
                .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("access")) {
            return Arrays.asList("add", "remove").stream()
                .filter(a -> a.startsWith(args[2].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("access")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[3].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
