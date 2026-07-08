package com.vaultsurvival.plugin.market;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Auction Hall commands.
 *
 * /ah sell <price> [category] [hours]  — List the item in your hand for sale
 * /ah buy <listing_uuid>               — Buy a listing
 * /ah collect                           — Collect earnings from your Auction Locker
 * /ah listings [category]               — Browse active listings
 * /ah cancel <listing_uuid>             — Cancel your listing and recover the item
 * /ah inspect <listing_uuid>            — Admin inspect a listing
 */
public class MarketCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final MarketService market;
    private final RegionService regions;
    private final MessageFormatter fmt;

    public MarketCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.market = plugin.getServiceRegistry().get(MarketService.class);
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use the Auction Hall."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "sell" -> handleSell(player, args);
            case "buy" -> handleBuy(player, args);
            case "collect" -> handleCollect(player);
            case "listings" -> handleListings(player, args);
            case "cancel" -> handleCancel(player, args);
            case "inspect" -> handleInspect(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleSell(Player player, String[] args) {
        if (!player.hasPermission("vs.market.sell")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (regions != null && !regions.isAllowed(player.getLocation(), RegionData.RuleFlag.MARKET_INTERACTION_ALLOWED)) {
            player.sendMessage(fmt.error("Auction Hall interaction is not allowed in this area. Visit the Auction Hall!"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /ah sell <price> [category] [hours]"));
            player.sendMessage(fmt.info("Hold the item in your main hand when listing."));
            return true;
        }

        long price;
        try {
            price = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Invalid price. Use a number."));
            return true;
        }

        MarketData.Category category = MarketData.Category.MISC;
        if (args.length >= 3) {
            try {
                category = MarketData.Category.valueOf(args[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage(fmt.error("Unknown category. Options: " +
                    String.join(", ", Arrays.stream(MarketData.Category.values())
                        .map(Enum::name).toList())));
                return true;
            }
        }

        int hours = plugin.getConfigManager().getMarketListingDurationHours();
        if (args.length >= 4) {
            try {
                hours = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage(fmt.error("Invalid hours."));
                return true;
            }
        }

        market.createListing(player, price, category, hours);
        return true;
    }

    private boolean handleBuy(Player player, String[] args) {
        if (!player.hasPermission("vs.market.buy")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (regions != null && !regions.isAllowed(player.getLocation(), RegionData.RuleFlag.MARKET_INTERACTION_ALLOWED)) {
            player.sendMessage(fmt.error("Auction Hall interaction is not allowed in this area. Visit the Auction Hall!"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /ah buy <listing_uuid>"));
            player.sendMessage(fmt.info("Find listing UUIDs with &e/ah listings"));
            return true;
        }

        try {
            UUID listingUuid = UUID.fromString(args[1]);
            market.buyListing(player, listingUuid);
        } catch (IllegalArgumentException e) {
            player.sendMessage(fmt.error("Invalid listing UUID format."));
        }
        return true;
    }

    private boolean handleCollect(Player player) {
        if (!player.hasPermission("vs.market.sell")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }

        long balance = market.getLockerBalance(player.getUniqueId());
        if (balance <= 0) {
            player.sendMessage(fmt.info("Your Auction Locker is empty. Sell items to earn coins!"));
            return true;
        }

        player.sendMessage(fmt.info("Your locker has &6" + fmt.formatMoney(balance,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural()) + "&7. Collecting..."));
        market.collectEarnings(player);
        return true;
    }

    private boolean handleListings(Player player, String[] args) {
        MarketData.Category category = null;
        if (args.length >= 2) {
            try {
                category = MarketData.Category.valueOf(args[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage(fmt.error("Unknown category. Options: " +
                    String.join(", ", Arrays.stream(MarketData.Category.values())
                        .map(Enum::name).toList())));
                return true;
            }
        }

        List<MarketData.Listing> listings = market.getActiveListings(category);

        String header = category != null ?
            "Auction Hall — " + category.name() :
            "Auction Hall — All Listings";

        player.sendMessage(fmt.header(header));

        if (listings.isEmpty()) {
            player.sendMessage(fmt.info("No active listings" +
                (category != null ? " in " + category.name() : "") + "."));
        } else {
            for (var listing : listings) {
                String id = listing.getListingUuid().toString().substring(0, 8);
                player.sendMessage(fmt.info(
                    "&e" + id + " &8| " +
                    "&6" + fmt.formatMoney(listing.getPrice(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural()) + " &8| " +
                    "&7" + listing.getCategory().name() + " &8| " +
                    "&e/ah buy " + listing.getListingUuid()));
            }
        }

        long lockerBal = market.getLockerBalance(player.getUniqueId());
        if (lockerBal > 0) {
            player.sendMessage(fmt.info("Your locker: &6" + fmt.formatMoney(lockerBal,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) + " &8- &e/ah collect"));
        }

        return true;
    }

    private boolean handleCancel(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /ah cancel <listing_uuid>"));
            player.sendMessage(fmt.info("Find your listing UUIDs with &e/ah listings &7(admin) or check your active listings."));
            return true;
        }

        try {
            UUID listingUuid = UUID.fromString(args[1]);
            market.cancelListing(player, listingUuid);
        } catch (IllegalArgumentException e) {
            player.sendMessage(fmt.error("Invalid listing UUID format."));
        }
        return true;
    }

    private boolean handleInspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.market.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /ah inspect <listing_uuid>"));
            return true;
        }

        try {
            UUID listingUuid = UUID.fromString(args[1]);
            MarketData.Listing listing = market.inspectListing(listingUuid);

            if (listing == null) {
                sender.sendMessage(fmt.error("Listing not found."));
                return true;
            }

            sender.sendMessage(fmt.header("Listing Inspect"));
            sender.sendMessage(fmt.info("UUID: &e" + listing.getListingUuid()));
            sender.sendMessage(fmt.info("Seller: &e" +
                (Bukkit.getOfflinePlayer(listing.getSellerUuid()).getName() != null
                    ? Bukkit.getOfflinePlayer(listing.getSellerUuid()).getName()
                    : listing.getSellerUuid().toString().substring(0, 8))));
            sender.sendMessage(fmt.info("Price: &6" + fmt.formatMoney(listing.getPrice(),
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            sender.sendMessage(fmt.info("Category: &e" + listing.getCategory().name()));
            sender.sendMessage(fmt.info("Status: &e" + listing.getStatus().name()));
            sender.sendMessage(fmt.info("Created: &7" + listing.getCreatedAt()));
            sender.sendMessage(fmt.info("Expires: &7" + listing.getExpiresAt()));
            if (listing.getSoldTo() != null) {
                sender.sendMessage(fmt.info("Sold to: &e" +
                    (Bukkit.getOfflinePlayer(listing.getSoldTo()).getName() != null
                        ? Bukkit.getOfflinePlayer(listing.getSoldTo()).getName()
                        : listing.getSoldTo().toString().substring(0, 8))));
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Invalid listing UUID format."));
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Auction Hall Commands"));
        if (sender.hasPermission("vs.market.sell")) {
            sender.sendMessage(fmt.info("/ah sell <price> [category] [hours] &8- List held item"));
        }
        if (sender.hasPermission("vs.market.buy")) {
            sender.sendMessage(fmt.info("/ah buy <listing_uuid> &8- Buy a listing"));
        }
        sender.sendMessage(fmt.info("/ah listings [category] &8- Browse active listings"));
        sender.sendMessage(fmt.info("/ah collect &8- Collect earnings from locker"));
        sender.sendMessage(fmt.info("/ah cancel <listing_uuid> &8- Cancel your listing"));
        if (sender.hasPermission("vs.breach.admin.log")) {
            sender.sendMessage(fmt.info("/ah inspect <listing_uuid> &8- Admin inspect"));
        }
        sender.sendMessage(fmt.info("&7Categories: &e" + String.join(", ",
            Arrays.stream(MarketData.Category.values()).map(Enum::name).toList())));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("sell", "buy", "listings", "collect", "cancel", "inspect")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            return List.of("100", "500", "1000", "5000");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            return Arrays.stream(MarketData.Category.values())
                .map(Enum::name)
                .filter(c -> c.toLowerCase().startsWith(args[2].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("listings")) {
            return Arrays.stream(MarketData.Category.values())
                .map(Enum::name)
                .filter(c -> c.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
