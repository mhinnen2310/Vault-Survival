package com.vaultsurvival.plugin.store;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class StoreCommand implements CommandExecutor, TabCompleter {
    private final StoreService service;
    private final MessageFormatter fmt;
    private final VaultSurvivalPlugin plugin;

    public StoreCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(StoreService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { showCategories(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "list" -> handleList(player, args);
            case "buy" -> handleBuy(player, args);
            default -> { showCategories(player); yield true; }
        };
    }

    private void showCategories(Player player) {
        player.sendMessage(fmt.header("Cosmetic Store"));
        player.sendMessage(fmt.info("&e/store list <category> &8- Browse items"));
        player.sendMessage(fmt.info("&e/store buy <id> &8- Purchase an item"));
        player.sendMessage(fmt.info("Categories: &eChat Hats Effects Wings"));
    }

    private boolean handleList(Player player, String[] args) {
        String category = args.length >= 2 ? args[1] : null;
        var items = service.getItems(category);
        player.sendMessage(fmt.header("Store Items (" + items.size() + ")"));
        for (var i : items) {
            player.sendMessage(fmt.info("&e" + i.getId() + " &f" + i.getDisplayName() + " &8| &6" + i.getPrice() + " &8| &7" + i.getDescription()));
        }
        return true;
    }

    private boolean handleBuy(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /store buy <id>")); return true; }
        service.purchaseItem(player.getUniqueId(), args[1].toLowerCase());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("list", "buy");
        return List.of();
    }
}
