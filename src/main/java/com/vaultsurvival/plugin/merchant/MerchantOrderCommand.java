package com.vaultsurvival.plugin.merchant;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopData;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class MerchantOrderCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final MerchantOrderService service;
    private final MerchantShopService shopService;
    private final MessageFormatter fmt;

    public MerchantOrderCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(MerchantOrderService.class);
        this.shopService = plugin.getServiceRegistry().get(MerchantShopService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("This command can only be used by players."));
            return true;
        }

        // Permission check - all merchant commands require vs.merchant.use
        if (!player.hasPermission("vs.merchant.use")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "orders":
                return handleOrdersList(player);

            case "order":
                return handleOrder(player, args);

            case "shop":
                return handleShop(player, args);
        }

        // /merchant help
        showHelp(player);
        return true;
    }

    private boolean handleOrdersList(Player player) {
        List<MerchantOrderData.Order> merchantOrders = service.getMerchantOrders(player.getUniqueId());
        if (merchantOrders.isEmpty()) {
            player.sendMessage(fmt.info("You have no buy orders. Create one with &e/merchant order create&7."));
            return true;
        }

        player.sendMessage(fmt.header("Your Buy Orders"));
        for (MerchantOrderData.Order order : merchantOrders) {
            String statusColor = switch (order.getStatus()) {
                case ACTIVE -> "&a";
                case PARTIALLY_FILLED -> "&e";
                case FILLED -> "&6";
                case CANCELLED -> "&c";
                case EXPIRED -> "&8";
                case DISPUTED -> "&4";
            };
            player.sendMessage(fmt.info(
                statusColor + "#" + order.getId() + " &7| " +
                order.getItemDisplay() + " &7| " +
                "&6" + fmt.formatMoney(order.getPricePerItem(),
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) + " &7each | " +
                order.getFilledQuantity() + "/" + order.getRequiredQuantity() + " | " +
                statusColor + order.getStatus().name()
            ));
        }
        return true;
    }

    private boolean handleOrder(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(fmt.info("Usage: &e/merchant order <create|list|cancel|deliver|collect>"));
            player.sendMessage(fmt.info("  &ecreate &7- Create a new buy order (hold the item)"));
            player.sendMessage(fmt.info("  &elist &7- List all active orders"));
            player.sendMessage(fmt.info("  &ecancel <id> &7- Cancel your order"));
            player.sendMessage(fmt.info("  &edeliver <id> &7- Deliver items to an order"));
            player.sendMessage(fmt.info("  &ecollect <id> &7- Collect delivered items from storage"));
            return true;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "create":
                return handleCreate(player, args);
            case "list":
                return handleList(player);
            case "cancel":
                return handleCancel(player, args);
            case "deliver":
                return handleDeliver(player, args);
            case "collect":
                return handleCollect(player, args);
        }

        player.sendMessage(fmt.error("Unknown sub-command: " + sub));
        return true;
    }

    private boolean handleCollect(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(fmt.info("Usage: &e/merchant order collect <id>"));
            return true;
        }

        int orderId;
        try {
            orderId = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid order ID."));
            return true;
        }

        service.collectStorage(player, orderId);
        return true;
    }

    private boolean handleCreate(Player player, String[] args) {
        // /merchant order create <price> <quantity> [partial]
        if (args.length < 4) {
            player.sendMessage(fmt.info("Usage: &e/merchant order create <price> <quantity> [partial]"));
            player.sendMessage(fmt.info("  Hold the item you want to buy in your main hand."));
            player.sendMessage(fmt.info("  Example: &7/merchant order create 100 64"));
            player.sendMessage(fmt.info("  Partial: &7/merchant order create 100 64 partial"));
            return true;
        }

        long price;
        try {
            price = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid price."));
            return true;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid quantity."));
            return true;
        }

        boolean partial = args.length > 4 && "partial".equalsIgnoreCase(args[4]);

        service.createOrder(player, price, quantity, partial);
        return true;
    }

    private boolean handleList(Player player) {
        List<MerchantOrderData.Order> active = service.getActiveOrders();
        if (active.isEmpty()) {
            player.sendMessage(fmt.info("No active buy orders."));
            return true;
        }

        player.sendMessage(fmt.header("Active Buy Orders"));
        for (MerchantOrderData.Order order : active) {
            player.sendMessage(fmt.info(
                "&e#" + order.getId() + " &7| " +
                order.getItemDisplay() + " &7| " +
                "&6" + fmt.formatMoney(order.getPricePerItem(),
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) + " &7each | " +
                "Need: &e" + order.getRemainingQuantity() + " &7| " +
                (order.isPartialDelivery() ? "&aPartial OK" : "&cFull Only")
            ));
        }
        player.sendMessage(fmt.info("Deliver: &e/merchant order deliver <id> &7with matching items in inventory."));
        return true;
    }

    private boolean handleCancel(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(fmt.info("Usage: &e/merchant order cancel <id>"));
            return true;
        }

        int orderId;
        try {
            orderId = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid order ID."));
            return true;
        }

        if (service.cancelOrder(player.getUniqueId(), orderId)) {
            // Message sent by service
        } else {
            player.sendMessage(fmt.error("Cannot cancel order #" + orderId +
                ". It may not be yours, or is already completed/cancelled."));
        }
        return true;
    }

    private boolean handleDeliver(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(fmt.info("Usage: &e/merchant order deliver <id> [quantity]"));
            player.sendMessage(fmt.info("  Delivers matching items from your inventory."));
            return true;
        }

        int orderId;
        try {
            orderId = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid order ID."));
            return true;
        }

        int quantity = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;
        if (quantity <= 0) {
            player.sendMessage(fmt.error("Quantity must be greater than 0."));
            return true;
        }

        service.deliverItems(player, orderId, quantity);
        return true;
    }

    // ========================================================================
    // Shop Commands
    // ========================================================================

    private boolean handleShop(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(fmt.info("Usage: &e/merchant shop <create|list|stock|prices|collect>"));
            player.sendMessage(fmt.info("  &ecreate &7- Create a merchant shop NPC"));
            player.sendMessage(fmt.info("  &elist &7- List your shops"));
            player.sendMessage(fmt.info("  &estock <id> <slot> <qty> &7- Add stock (hold item)"));
            player.sendMessage(fmt.info("  &eprices <id> <slot> <price> &7- Set item price"));
            player.sendMessage(fmt.info("  &ecollect <id> &7- Collect shop earnings"));
            return true;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "create":
                return handleShopCreate(player, args);
            case "list":
                return handleShopList(player);
            case "stock":
                return handleShopStock(player, args);
            case "prices":
                return handleShopPrices(player, args);
            case "collect":
                return handleShopCollect(player, args);
        }

        player.sendMessage(fmt.error("Unknown shop sub-command: " + sub));
        return true;
    }

    private boolean handleShopCreate(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(fmt.info("Usage: &e/merchant shop create <name>"));
            player.sendMessage(fmt.info("  Creates a shop NPC at your location."));
            return true;
        }

        String shopName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (shopName.length() > 32) {
            player.sendMessage(fmt.error("Shop name must be 32 characters or less."));
            return true;
        }

        shopService.createShop(player, shopName);
        return true;
    }

    private boolean handleShopList(Player player) {
        var shops = shopService.getMerchantShops(player.getUniqueId());
        if (shops.isEmpty()) {
            player.sendMessage(fmt.info("You have no shops. Create one with &e/merchant shop create <name>&7."));
            return true;
        }

        player.sendMessage(fmt.header("Your Shops"));
        for (var shop : shops) {
            var items = shopService.getShopItems(shop.getId());
            int totalStock = items.stream().mapToInt(MerchantShopData.ShopItem::getStock).sum();
            player.sendMessage(fmt.info(
                "&e#" + shop.getId() + " &7| &f" + shop.getName() +
                " &7| Items: &e" + items.size() + " &7| Stock: &e" + totalStock +
                " &7| NPC: &e#" + shop.getNpcId()
            ));
        }
        return true;
    }

    private boolean handleShopStock(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage(fmt.info("Usage: &e/merchant shop stock <id> <slot> <quantity>"));
            player.sendMessage(fmt.info("  Hold the item you want to stock in your main hand."));
            player.sendMessage(fmt.info("  Slots: 0-53"));
            return true;
        }

        int shopId, slot, quantity;
        try {
            shopId = Integer.parseInt(args[2]);
            slot = Integer.parseInt(args[3]);
            quantity = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid number."));
            return true;
        }

        shopService.addStock(player, shopId, slot, quantity);
        return true;
    }

    private boolean handleShopPrices(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage(fmt.info("Usage: &e/merchant shop prices <id> <slot> <price>"));
            return true;
        }

        int shopId, slot;
        long price;
        try {
            shopId = Integer.parseInt(args[2]);
            slot = Integer.parseInt(args[3]);
            price = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid number."));
            return true;
        }

        shopService.setPrice(player, shopId, slot, price);
        return true;
    }

    private boolean handleShopCollect(Player player, String[] args) {
        var shops = shopService.getMerchantShops(player.getUniqueId());
        long totalPending = 0;
        try {
            var payouts = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.social.PayoutLockerService.class);
            totalPending = payouts.getPendingTotal(player.getUniqueId());
        } catch (Exception ignored) {}

        player.sendMessage(fmt.header("Shop Earnings"));
        if (shops.isEmpty()) {
            player.sendMessage(fmt.info("You have no shops."));
        } else {
            for (var shop : shops) {
                var sales = shopService.getSales(shop.getId());
                long shopTotal = sales.stream().mapToLong(MerchantShopData.Sale::getNetEarnings).sum();
                player.sendMessage(fmt.info(
                    "&e#" + shop.getId() + " &7| &f" + shop.getName() +
                    " &7| Sales: &e" + sales.size() +
                    " &7| Revenue: &6" + fmt.formatMoney(shopTotal,
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())));
            }
        }
        player.sendMessage(fmt.info("Pending payouts: &6" + fmt.formatMoney(totalPending,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        player.sendMessage(fmt.info("Use &e/payouts claim &7to collect your earnings."));
        return true;
    }

    private void showHelp(Player player) {
        player.sendMessage(fmt.header("Merchant Commands"));
        player.sendMessage(fmt.info("&e/merchant orders &7- View your buy orders"));
        player.sendMessage(fmt.info("&e/merchant order create &7- Create a new buy order"));
        player.sendMessage(fmt.info("&e/merchant order list &7- List all active orders"));
        player.sendMessage(fmt.info("&e/merchant order cancel <id> &7- Cancel your order"));
        player.sendMessage(fmt.info("&e/merchant order deliver <id> &7- Deliver items to an order"));
        player.sendMessage(fmt.info("&e/merchant order collect <id> &7- Collect delivered items from storage"));
        player.sendMessage(fmt.info("&e/merchant shop create &7- Create a merchant shop NPC"));
        player.sendMessage(fmt.info("&e/merchant shop list &7- List your shops"));
        player.sendMessage(fmt.info("&e/merchant shop stock <id> <slot> <qty> &7- Add stock to shop"));
        player.sendMessage(fmt.info("&e/merchant shop prices <id> <slot> <price> &7- Set item price"));
        player.sendMessage(fmt.info("&e/merchant shop collect <id> &7- Collect shop earnings"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List.of("orders", "order", "shop").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .forEach(completions::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("order")) {
            List.of("create", "list", "cancel", "deliver", "collect").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .forEach(completions::add);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("order")) {
            String sub = args[1].toLowerCase();
            if ("cancel".equals(sub) || "deliver".equals(sub) || "collect".equals(sub)) {
                // Suggest order IDs from the player's orders
                if (sender instanceof Player player) {
                    var orders = service.getMerchantOrders(player.getUniqueId());
                    for (var order : orders) {
                        completions.add(String.valueOf(order.getId()));
                    }
                }
            }
        } else if (args.length >= 4 && args[0].equalsIgnoreCase("order") &&
                   "deliver".equalsIgnoreCase(args[1]) && args.length == 4) {
            completions.add("<quantity>");
        } else if (args.length == 5 && args[0].equalsIgnoreCase("order") &&
                   "create".equalsIgnoreCase(args[1])) {
            List.of("partial").stream()
                .filter(s -> s.startsWith(args[4].toLowerCase()))
                .forEach(completions::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("shop")) {
            List.of("create", "list", "stock", "prices", "collect").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .forEach(completions::add);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("shop")) {
            String sub = args[1].toLowerCase();
            if ("stock".equals(sub) || "prices".equals(sub) || "collect".equals(sub)) {
                if (sender instanceof Player player) {
                    for (var shop : shopService.getMerchantShops(player.getUniqueId())) {
                        completions.add(String.valueOf(shop.getId()));
                    }
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("shop")
                   && ("stock".equalsIgnoreCase(args[1]) || "prices".equalsIgnoreCase(args[1]))) {
            completions.add("<slot>");
        } else if (args.length == 5 && args[0].equalsIgnoreCase("shop")) {
            if ("stock".equalsIgnoreCase(args[1])) {
                completions.add("<quantity>");
            } else if ("prices".equalsIgnoreCase(args[1])) {
                completions.add("<price>");
            }
        }

        return completions;
    }
}
