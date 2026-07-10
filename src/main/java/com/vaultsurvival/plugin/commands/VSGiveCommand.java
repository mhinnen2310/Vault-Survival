package com.vaultsurvival.plugin.commands;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.breach.BreachService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.staffmode.StaffmodeData;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VSGive command — spawns custom plugin items for testing.
 * Only usable by players in staffmode bypass mode (/staffmode *).
 *
 * /vsgive cash <amount>     — Spawn physical cash
 * /vsgive breachkit          — Spawn a breach kit
 * /vsgive vault              — Spawn a vault block item for placement
 */
public class VSGiveCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final BreachService breach;
    private final Map<UUID, StaffmodeData> staffData;
    private final MessageFormatter fmt;

    public VSGiveCommand(VaultSurvivalPlugin plugin, Map<UUID, StaffmodeData> staffData) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.breach = plugin.getServiceRegistry().get(BreachService.class);
        this.staffData = staffData;
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use this command."));
            return true;
        }

        // Must be in staffmode bypass mode
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive() || !data.isBypassMode()) {
            player.sendMessage(fmt.error("You must be in staffmode bypass mode to use this command."));
            player.sendMessage(fmt.info("Use &e/staffmode &7then &e/staffmode *&7 to enable bypass."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "cash" -> handleCash(player, args);
            case "breachkit" -> handleBreachKit(player);
            case "vault" -> handleVault(player);
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleCash(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /vsgive cash <amount>"));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid amount. Use a number like: /vsgive cash 1000"));
            return true;
        }

        long maximumAmount = plugin.getConfigManager().isStaffSandbox() ? 1_000_000_000_000L : 100_000_000L;
        if (amount <= 0 || amount > maximumAmount) {
            player.sendMessage(fmt.error("Amount must be between 1 and " + String.format("%,d", maximumAmount) + "."));
            return true;
        }

        ItemStack cash = currency.mintCash(amount, player.getUniqueId(), player.getUniqueId());

        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), cash);
            player.sendMessage(fmt.success("Created &6" + fmt.formatMoney(amount,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) + " &a(dropped at feet, inventory full)"));
        } else {
            player.getInventory().addItem(cash);
            player.sendMessage(fmt.success("Created &6" + fmt.formatMoney(amount,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) + " &ain your inventory"));
        }
        return true;
    }

    private boolean handleBreachKit(Player player) {
        ItemStack kit = breach.createBreachKit();

        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), kit);
            player.sendMessage(fmt.success("Created a &c&lBreach Kit &a(dropped at feet)."));
        } else {
            player.getInventory().addItem(kit);
            player.sendMessage(fmt.success("Created a &c&lBreach Kit &ain your inventory."));
        }
        return true;
    }

    private boolean handleVault(Player player) {
        Material vaultMat;
        try {
            vaultMat = Material.valueOf(plugin.getConfigManager().getVaultMaterial());
        } catch (IllegalArgumentException e) {
            vaultMat = Material.BARREL;
        }

        ItemStack vaultItem = new ItemStack(vaultMat);
        var meta = vaultItem.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageFormatter.deserializeLegacy("&6&lVault Block"));
            meta.lore(List.of(
                MessageFormatter.deserializeLegacy("&7Place this to create a vault."),
                MessageFormatter.deserializeLegacy("&7Use &e/vault place <tier> &7instead of placing directly."),
                MessageFormatter.deserializeLegacy("&8&oFor testing placement visualization only.")
            ));
            vaultItem.setItemMeta(meta);
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), vaultItem);
            player.sendMessage(fmt.success("Created a &6Vault Block &a(dropped at feet)."));
        } else {
            player.getInventory().addItem(vaultItem);
            player.sendMessage(fmt.success("Created a &6Vault Block &ain your inventory."));
            player.sendMessage(fmt.info("Use &e/vault place <tier> &7to actually place a vault."));
        }
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(fmt.header("VSGive — Staff Testing Items"));
        player.sendMessage(fmt.info("/vsgive cash <amount> &8- Spawn physical cash"));
        player.sendMessage(fmt.info("/vsgive breachkit &8- Spawn a breach kit"));
        player.sendMessage(fmt.info("/vsgive vault &8- Spawn a vault block"));
        player.sendMessage(fmt.info("&7Requires: &estaffmode bypass mode &7(&e/staffmode *&7)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("cash", "breachkit", "vault")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cash")) {
            return List.of("100", "500", "1000", "5000", "10000", "50000", "100000");
        }
        return List.of();
    }
}
