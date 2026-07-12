package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.core.MoneyAmounts;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Admin commands for managing physical currency.
 * /cash inspect - Inspect the cash item in hand
 * /cash trace <id> - Trace a cash item by UUID
 * /cash mint <amount> [player] - Mint new cash
 * /cash invalidate <id> - Invalidate a cash item
 * /cash scan [player] - Scan inventory for cash
 * /cash stats - Show economy statistics
 */
public class CurrencyCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currencyService;
    private final MessageFormatter fmt;

    public CurrencyCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currencyService = plugin.getServiceRegistry().get(CurrencyService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vs.cash.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "inspect" -> handleInspect(sender);
            case "trace" -> handleTrace(sender, args);
            case "mint" -> handleMint(sender, args);
            case "invalidate" -> handleInvalidate(sender, args);
            case "scan" -> handleScan(sender, args);
            case "stats" -> handleStats(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    private void handleInspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can inspect held items."));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!currencyService.isCashItem(held)) {
            sender.sendMessage(fmt.error("You are not holding a cash item."));
            return;
        }

        UUID cashUuid = currencyService.getCashUuid(held);
        CashItemData data = currencyService.getCashData(held);

        sender.sendMessage(fmt.header("Cash Item"));
        sender.sendMessage(fmt.info("UUID: &e" + cashUuid));
        if (data != null) {
            sender.sendMessage(fmt.info("Amount: &e" + fmt.formatMoney(data.getAmount(),
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            sender.sendMessage(fmt.info("State: &e" + data.getState().name()));
            sender.sendMessage(fmt.info("Location: &e" + data.getLocationType() + " / " + data.getLocationId()));
            sender.sendMessage(fmt.info("Owner: &e" + data.getOwnerUuid()));
            sender.sendMessage(fmt.info("Created: &e" + data.getCreatedAt()));
            sender.sendMessage(fmt.info("Valid: &e" + (currencyService.validateCash(held) ? "&aYes" : "&cNo")));
        } else {
            sender.sendMessage(fmt.error("No database record found! This cash is counterfeit."));
        }
    }

    private void handleTrace(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /cash trace <UUID>"));
            return;
        }

        try {
            UUID cashUuid = UUID.fromString(args[1]);
            CashItemData data = currencyService.getCashData(cashUuid);

            if (data == null) {
                sender.sendMessage(fmt.error("No cash record found for that UUID."));
                return;
            }

            sender.sendMessage(fmt.header("Cash Trace"));
            sender.sendMessage(fmt.info("UUID: &e" + data.getCashUuid()));
            sender.sendMessage(fmt.info("Amount: &e" + data.getAmount()));
            sender.sendMessage(fmt.info("State: &e" + data.getState().name()));
            sender.sendMessage(fmt.info("Location: &e" + data.getLocationType() + " &8→ &e" + data.getLocationId()));
            sender.sendMessage(fmt.info("Owner: &e" + data.getOwnerUuid()));
            sender.sendMessage(fmt.info("Created by: &e" + data.getCreatedBy()));
            sender.sendMessage(fmt.info("Created at: &e" + data.getCreatedAt()));
            sender.sendMessage(fmt.info("Last seen: &e" + data.getLastSeenAt()));
            sender.sendMessage(fmt.info("Valid: &e" + (data.isValid() ? "Yes" : "No")));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Invalid UUID format."));
        }
    }

    private void handleMint(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /cash mint <amount> [player]"));
            return;
        }

        long amount;
        try {
            amount = MoneyAmounts.parse(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(fmt.error("Invalid amount."));
            return;
        }

        if (amount <= 0) {
            sender.sendMessage(fmt.error("Amount must be positive."));
            return;
        }

        UUID creator = sender instanceof Player p ? p.getUniqueId() : null;
        Player target = null;

        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(fmt.playerNotFound(args[2]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(fmt.error("Console must specify a target player."));
            return;
        }

        ItemStack cashItem = currencyService.mintCash(amount, creator, target.getUniqueId());
        target.getInventory().addItem(cashItem);

        String currencyName = amount == 1
            ? plugin.getConfigManager().getCurrencyName()
            : plugin.getConfigManager().getCurrencyNamePlural();

        sender.sendMessage(fmt.success("Minted &6" + amount + " &e" + currencyName + "&a for &e" + target.getName()));
        if (target != sender) {
            target.sendMessage(fmt.success("You received &6" + amount + " &e" + currencyName + "&a from the Mint."));
        }

        plugin.getAuditLogger().logAdminAction(
            creator, sender.getName(), "CASH_MINT", target.getName(), "amount=" + amount
        );
    }

    private void handleInvalidate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /cash invalidate <UUID>"));
            return;
        }

        try {
            UUID cashUuid = UUID.fromString(args[1]);
            CashItemData data = currencyService.getCashData(cashUuid);

            if (data == null) {
                sender.sendMessage(fmt.error("No cash record found."));
                return;
            }

            // Find online players holding this cash, invalidate it
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && cashUuid.equals(currencyService.getCashUuid(item))) {
                        currencyService.invalidateCash(item, "ADMIN");
                        player.getInventory().remove(item);
                        break;
                    }
                }
            }
            // Also invalidate the DB record if no physical item was found
            currencyService.invalidateCashByUuid(cashUuid, "ADMIN");

            sender.sendMessage(fmt.success("Invalidated cash item " + args[1]));
            plugin.getAuditLogger().logAdminAction(
                sender instanceof Player p ? p.getUniqueId() : null,
                sender.getName(), "CASH_INVALIDATE", "CASH",
                "uuid=" + args[1] + " admin_action"
            );
        } catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Invalid UUID format."));
        }
    }

    private void handleScan(CommandSender sender, String[] args) {
        Player target;

        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(fmt.playerNotFound(args[1]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(fmt.error("Console must specify a target player."));
            return;
        }

        List<CashItemData> cashItems = currencyService.scanInventory(target);

        sender.sendMessage(fmt.header("Cash Scan: " + target.getName()));
        if (cashItems.isEmpty()) {
            sender.sendMessage(fmt.info("No cash items found."));
        } else {
            long total = 0;
            for (CashItemData data : cashItems) {
                total += data.getAmount();
                sender.sendMessage(fmt.info("&6" + data.getAmount() + " coins &8- &7" + data.getCashUuid()));
            }
            sender.sendMessage(fmt.info("Total: &6" + total + " coins"));
        }
    }

    private void handleStats(CommandSender sender) {
        CurrencyStats stats = currencyService.getStats();

        sender.sendMessage(fmt.header("Economy Statistics"));
        sender.sendMessage(fmt.info("Circulating (player inventory): &6" + stats.getTotalCashInCirculation()));
        sender.sendMessage(fmt.info("In Vaults: &6" + stats.getTotalCashInVaults()));
        sender.sendMessage(fmt.info("In AH Escrow: &6" + stats.getTotalCashInEscrow()));
        sender.sendMessage(fmt.info("In Auction Lockers: &6" + stats.getTotalCashInLockers()));
        sender.sendMessage(fmt.info("In District Treasuries: &6" + stats.getTotalCashInTreasuries()));
        sender.sendMessage(fmt.info("Dropped (on ground): &6" + stats.getTotalCashDropped()));
        sender.sendMessage(fmt.info("Spent: &6" + stats.getTotalCashSpent()));
        sender.sendMessage(fmt.info("Invalidated: &6" + stats.getTotalCashInvalidated()));
        sender.sendMessage(fmt.info("Active cash items: &e" + stats.getActiveCashItemCount()));
        sender.sendMessage(fmt.info("Total ever created: &6" + stats.getTotalEverCreated()));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Cash Commands"));
        sender.sendMessage(fmt.info("/cash inspect &8- Inspect held cash item"));
        sender.sendMessage(fmt.info("/cash trace <uuid> &8- Trace a cash item"));
        sender.sendMessage(fmt.info("/cash mint <amount> [player] &8- Mint new cash"));
        sender.sendMessage(fmt.info("/cash invalidate <uuid> &8- Invalidate cash"));
        sender.sendMessage(fmt.info("/cash scan [player] &8- Scan inventory for cash"));
        sender.sendMessage(fmt.info("/cash stats &8- Economy statistics"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("inspect", "trace", "mint", "invalidate", "scan", "stats").stream()
                .filter(a -> a.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mint")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
