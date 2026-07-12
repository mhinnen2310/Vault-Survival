package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MoneyAmounts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Player-facing exact-change command for one physical cash item in the main hand. */
public final class CashSplitCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;

    public CashSplitCommand(VaultSurvivalPlugin plugin, CurrencyService currency) {
        this.plugin = plugin;
        this.currency = currency;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageFormatter().error("Only players can split physical cash."));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(plugin.getMessageFormatter().info("Use /cashsplit <amount>, for example /cashsplit 10k."));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!currency.validateCash(held)) {
            player.sendMessage(plugin.getMessageFormatter().error("Hold one valid cash item in your main hand."));
            return true;
        }
        if (player.getInventory().firstEmpty() < 0) {
            player.sendMessage(plugin.getMessageFormatter().error("Keep one inventory slot empty for the split cash."));
            return true;
        }
        final long amount;
        try { amount = MoneyAmounts.parse(args[0]); }
        catch (NumberFormatException invalid) {
            player.sendMessage(plugin.getMessageFormatter().error("Invalid amount. Accepted examples: 1000, 10k, 1m, 1b."));
            return true;
        }
        ItemStack[] result = currency.splitCash(held.clone(), amount);
        if (result == null) {
            player.sendMessage(plugin.getMessageFormatter().error("The split must be above zero and below the held cash value."));
            return true;
        }
        player.getInventory().setItemInMainHand(result[0]);
        player.getInventory().addItem(result[1]);
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "CASH_PLAYER_SPLIT",
            "CASH", currency.getCashUuid(result[1]).toString(), "amount=" + amount);
        player.sendMessage(plugin.getMessageFormatter().success("Created exact change: &6" + MoneyAmounts.compact(amount) + "&a."));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("1", "10", "100", "1k", "10k", "1m") : List.of();
    }
}
