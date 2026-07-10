package com.vaultsurvival.plugin.market;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of MarketService.
 *
 * The Auction Hall is a physical location in spawn. Sellers place items into
 * escrow. Buyers pay with physical cash. Sellers collect earnings from their
 * physical Auction Locker (stored as cash_items with state IN_AUCTION_LOCKER).
 *
 * All purchases are atomic — if any step fails, the entire transaction rolls back.
 */
public class MarketServiceImpl implements MarketService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;

    public MarketServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
    }

    // ========================================================================
    // Create Listing
    // ========================================================================

    @Override
    public UUID createListing(Player seller, long price, MarketData.Category category, int hours) {
        ItemStack item = seller.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            seller.sendMessage(fmt.error("You must hold the item you want to sell in your hand."));
            return null;
        }

        if (price <= 0) {
            seller.sendMessage(fmt.error("Price must be greater than 0."));
            return null;
        }

        int maximumHours = plugin.getConfigManager().getConfig().getInt("market.max_listing_duration_hours", 168);
        if (hours <= 0 || hours > maximumHours) {
            seller.sendMessage(fmt.error("Duration must be between 1 and " + maximumHours + " hours."));
            return null;
        }

        // Serialize item to Base64
        String itemData = serializeItem(item.clone());

        // Remove item from player hand
        seller.getInventory().setItemInMainHand(null);

        UUID listingUuid = UUID.randomUUID();
        UUID sellerUuid = seller.getUniqueId();

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert listing
                String listingSql = "INSERT INTO auction_listings " +
                    "(listing_uuid, seller_uuid, category, item_data, price, created_at, expires_at, status) " +
                    "VALUES (?, ?, ?, ?, ?, datetime('now'), datetime('now', ? || ' hours'), 'ACTIVE')";
                try (PreparedStatement ps = conn.prepareStatement(listingSql)) {
                    ps.setString(1, listingUuid.toString());
                    ps.setString(2, sellerUuid.toString());
                    ps.setString(3, category.name());
                    ps.setString(4, itemData);
                    ps.setLong(5, price);
                    ps.setString(6, String.valueOf(hours));
                    ps.executeUpdate();
                }

                // Insert escrow item
                String escrowSql = "INSERT INTO auction_escrow_items (listing_uuid, item_data) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(escrowSql)) {
                    ps.setString(1, listingUuid.toString());
                    ps.setString(2, itemData);
                    ps.executeUpdate();
                }

                conn.commit();

                audit.log(sellerUuid, seller.getName(), "AH_LISTING_CREATE", "LISTING",
                    listingUuid.toString(), "price=" + price + " category=" + category.name());

                seller.sendMessage(fmt.success("Listing created for &6" + fmt.formatMoney(price,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
                seller.sendMessage(fmt.info("Duration: &e" + hours + "h &7| Category: &e" + category.name()));
                seller.sendMessage(fmt.info("Listing UUID: &e" + listingUuid.toString().substring(0, 8) + "..."));

            } catch (SQLException e) {
                conn.rollback();
                // Return item to player
                seller.getInventory().setItemInMainHand(deserializeItem(itemData));
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create listing", e);
            seller.sendMessage(fmt.error("Failed to create listing. Item returned."));
            return null;
        }

        return listingUuid;
    }

    // ========================================================================
    // Cancel Listing
    // ========================================================================

    @Override
    public boolean cancelListing(Player player, UUID listingUuid) {
        MarketData.Listing listing = getListing(listingUuid);
        if (listing == null || listing.getStatus() != MarketData.ListingStatus.ACTIVE) {
            player.sendMessage(fmt.error("Listing not found or no longer active."));
            return false;
        }

        if (!listing.getSellerUuid().equals(player.getUniqueId())) {
            player.sendMessage(fmt.error("You don't own this listing."));
            return false;
        }

        // Get the escrowed item
        String itemData = getEscrowItemData(listingUuid);
        if (itemData == null) {
            player.sendMessage(fmt.error("Escrow item not found. Contact an admin."));
            return false;
        }

        // Mark listing as cancelled
        String sql = "UPDATE auction_listings SET status = 'CANCELLED' WHERE listing_uuid = ?";
        try {
            plugin.getDatabase().executeUpdate(sql, listingUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to cancel listing", e);
            return false;
        }

        // Return item to player
        ItemStack item = deserializeItem(itemData);
        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            player.sendMessage(fmt.info("Inventory full. Item dropped at your feet."));
        } else {
            player.getInventory().addItem(item);
        }

        audit.log(player.getUniqueId(), player.getName(), "AH_LISTING_CANCEL", "LISTING",
            listingUuid.toString(), "cancelled by seller");

        player.sendMessage(fmt.success("Listing cancelled. Item returned."));
        return true;
    }

    // ========================================================================
    // Buy Listing (Atomic Transaction)
    // ========================================================================

    @Override
    public boolean buyListing(Player buyer, UUID listingUuid) {
        MarketData.Listing listing = getListing(listingUuid);
        if (listing == null || listing.getStatus() != MarketData.ListingStatus.ACTIVE) {
            buyer.sendMessage(fmt.error("This listing is no longer available."));
            return false;
        }

        if (listing.getSellerUuid().equals(buyer.getUniqueId())) {
            buyer.sendMessage(fmt.error("You cannot buy your own listing."));
            return false;
        }

        // Check buyer has enough cash (inventory scan only, no removal yet)
        long buyerCash = currency.getPlayerCashTotal(buyer.getUniqueId());
        if (buyerCash < listing.getPrice()) {
            buyer.sendMessage(fmt.error("You don't have enough cash. Need: &6" +
                fmt.formatMoney(listing.getPrice(),
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) +
                " &7| Have: &6" + fmt.formatMoney(buyerCash,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
            return false;
        }

        // Check buyer inventory has space for the purchased item
        if (buyer.getInventory().firstEmpty() == -1) {
            buyer.sendMessage(fmt.error("Your inventory is full! Make space for the purchased item."));
            return false;
        }

        // Get escrowed item data
        String itemData = getEscrowItemData(listingUuid);
        if (itemData == null) {
            buyer.sendMessage(fmt.error("Escrow item not found. Contact an admin."));
            return false;
        }

        // Calculate tax
        double taxRate = plugin.getConfigManager().getMarketTaxPercent() / 100.0;
        long taxAmount = (long) Math.ceil(listing.getPrice() * taxRate);
        long sellerEarnings = listing.getPrice() - taxAmount;

        // Phase 1: All DB operations in a transaction (no inventory changes yet)
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 1: Lock the listing to prevent race conditions
                String lockSql = "UPDATE auction_listings SET status = 'SOLD', sold_to = ?, sold_at = datetime('now') " +
                                 "WHERE listing_uuid = ? AND status = 'ACTIVE'";
                try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                    ps.setString(1, buyer.getUniqueId().toString());
                    ps.setString(2, listingUuid.toString());
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        conn.rollback();
                        buyer.sendMessage(fmt.error("Listing was already purchased by someone else."));
                        return false;
                    }
                }

                // Step 2: Mark buyer's cash as SPENT (DB only, physical removal done after commit)
                long spent = spendPlayerCashForPurchase(conn, buyer.getUniqueId(), listing.getPrice());
                if (spent < listing.getPrice()) {
                    conn.rollback();
                    revertListingStatus(conn, listingUuid);
                    buyer.sendMessage(fmt.error("Failed to process payment. Your cash has not been taken."));
                    return false;
                }

                // Step 3: Create seller locker cash (IN_AUCTION_LOCKER)
                UUID lockerCashUuid = UUID.randomUUID();
                String lockerSql = "INSERT INTO cash_items (cash_uuid, amount, state, created_at, last_seen_at, " +
                                   "location_type, location_id, owner_uuid, created_by) " +
                                   "VALUES (?, ?, 'IN_AUCTION_LOCKER', datetime('now'), datetime('now'), " +
                                   "'LOCKER', ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(lockerSql)) {
                    ps.setString(1, lockerCashUuid.toString());
                    ps.setLong(2, sellerEarnings);
                    ps.setString(3, listing.getSellerUuid().toString());
                    ps.setString(4, listing.getSellerUuid().toString());
                    ps.setString(5, buyer.getUniqueId().toString());
                    ps.executeUpdate();
                }

                // Step 4: Record transaction
                String txSql = "INSERT INTO auction_transactions " +
                               "(listing_uuid, buyer_uuid, seller_uuid, price, tax, completed_at) " +
                               "VALUES (?, ?, ?, ?, ?, datetime('now'))";
                try (PreparedStatement ps = conn.prepareStatement(txSql)) {
                    ps.setString(1, listingUuid.toString());
                    ps.setString(2, buyer.getUniqueId().toString());
                    ps.setString(3, listing.getSellerUuid().toString());
                    ps.setLong(4, listing.getPrice());
                    ps.setLong(5, taxAmount);
                    ps.executeUpdate();
                }

                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
                logger.log(Level.SEVERE, "Buy transaction failed, rolled back. No cash taken.", e);
                buyer.sendMessage(fmt.error("Transaction failed. Your cash is safe — nothing was taken."));
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to buy listing", e);
            buyer.sendMessage(fmt.error("Internal error during purchase. Your cash is safe."));
            return false;
        }

        // Phase 2: After successful commit, handle physical inventory
        // Remove spent cash items from buyer inventory
        removeSpentCashFromInventory(buyer, listing.getPrice());

        // Give purchased item to buyer
        ItemStack purchasedItem = deserializeItem(itemData);
        buyer.getInventory().addItem(purchasedItem);

        audit.log(buyer.getUniqueId(), buyer.getName(), "AH_PURCHASE", "LISTING",
            listingUuid.toString(),
            "price=" + listing.getPrice() + " tax=" + taxAmount + " seller=" + listing.getSellerUuid());

        buyer.sendMessage(fmt.success("Purchased item for &6" + fmt.formatMoney(listing.getPrice(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        // Trigger display sold animation if DisplayService is available
        var displayService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.display.DisplayService.class);
        if (displayService != null) {
            // Find the display slot showing this listing and animate
            for (var slot : displayService.getAllSlots()) {
                if (listing.getListingUuid().equals(slot.getCurrentListingUuid())) {
                    displayService.soldAnimation(slot.getId(), buyer.getName(), listing.getPrice());
                    break;
                }
            }
        }

        // Notify seller if online
        var seller = plugin.getServer().getPlayer(listing.getSellerUuid());
        if (seller != null) {
            seller.sendMessage(fmt.success("Your listing sold for &6" + fmt.formatMoney(listing.getPrice(),
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) + "&a!"));
            seller.sendMessage(fmt.info("Earnings: &6" + fmt.formatMoney(sellerEarnings,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) + " &7(visit AH to collect)"));
        }

        return true;
    }

    private void revertListingStatus(Connection conn, UUID listingUuid) throws SQLException {
        String sql = "UPDATE auction_listings SET status = 'ACTIVE', sold_to = NULL, sold_at = NULL " +
                     "WHERE listing_uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, listingUuid.toString());
            ps.executeUpdate();
        }
    }

    // ========================================================================
    // Collect Earnings
    // ========================================================================

    @Override
    public boolean collectEarnings(Player seller) {
        long balance = getLockerBalance(seller.getUniqueId());
        if (balance <= 0) {
            seller.sendMessage(fmt.error("You have no earnings to collect."));
            return false;
        }

        // Get all locker cash items
        List<String> cashUuids = new ArrayList<>();
        long totalCollected = 0;
        String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                         "WHERE state = 'IN_AUCTION_LOCKER' AND location_id = ?";

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, seller.getUniqueId().toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String uuid = rs.getString("cash_uuid");
                        long amount = rs.getLong("amount");
                        cashUuids.add(uuid);
                        totalCollected += amount;
                    }
                }

                if (cashUuids.isEmpty()) {
                    conn.rollback();
                    return false;
                }

                // Mark all locker cash as SPENT (we'll mint new ACTIVE cash)
                String spendSql = "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') " +
                                  "WHERE cash_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(spendSql)) {
                    for (String uuid : cashUuids) {
                        ps.setString(1, uuid);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();

                // Mint new physical cash for the seller
                ItemStack collectedCash = currency.mintCash(totalCollected,
                    seller.getUniqueId(), seller.getUniqueId());

                if (seller.getInventory().firstEmpty() == -1) {
                    seller.getWorld().dropItemNaturally(seller.getLocation(), collectedCash);
                    seller.sendMessage(fmt.warn("Inventory full! Cash dropped at your feet."));
                } else {
                    seller.getInventory().addItem(collectedCash);
                }

                audit.log(seller.getUniqueId(), seller.getName(), "AH_COLLECT_EARNINGS", "LOCKER",
                    "", "amount=" + totalCollected);

                seller.sendMessage(fmt.success("Collected &6" + fmt.formatMoney(totalCollected,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) + " &afrom your Auction Locker!"));

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to collect earnings", e);
            seller.sendMessage(fmt.error("Failed to collect earnings. Please try again."));
            return false;
        }

        return true;
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public List<MarketData.Listing> getActiveListings(MarketData.Category category) {
        List<MarketData.Listing> listings = new ArrayList<>();
        String sql;
        if (category != null) {
            sql = "SELECT * FROM auction_listings WHERE status = 'ACTIVE' AND category = ? " +
                  "ORDER BY created_at DESC LIMIT 50";
        } else {
            sql = "SELECT * FROM auction_listings WHERE status = 'ACTIVE' " +
                  "ORDER BY created_at DESC LIMIT 50";
        }

        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (category != null) {
                ps.setString(1, category.name());
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                listings.add(mapListing(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get active listings", e);
        }
        return listings;
    }

    @Override
    public List<MarketData.Listing> getSellerListings(UUID sellerUuid) {
        List<MarketData.Listing> listings = new ArrayList<>();
        String sql = "SELECT * FROM auction_listings WHERE seller_uuid = ? " +
                     "ORDER BY created_at DESC LIMIT 50";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sellerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                listings.add(mapListing(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get seller listings", e);
        }
        return listings;
    }

    @Override
    public long getLockerBalance(UUID playerUuid) {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items " +
                     "WHERE state = 'IN_AUCTION_LOCKER' AND location_id = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get locker balance", e);
        }
        return 0;
    }

    @Override
    public MarketData.Listing inspectListing(UUID listingUuid) {
        return getListing(listingUuid);
    }

    @Override
    public int expireStaleListings() {
        String sql = "UPDATE auction_listings SET status = 'EXPIRED' " +
                     "WHERE status = 'ACTIVE' AND expires_at < datetime('now')";
        try {
            // Can't get affected rows from executeUpdate, so just do it
            plugin.getDatabase().executeUpdate(sql);
            logger.info("Expired stale auction listings");
            return 0; // SQLite doesn't easily return affected rows through our helper
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to expire stale listings", e);
        }
        return 0;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Mark player's cash items as SPENT in the DB (DB-only, no inventory changes).
     * Called inside the buy transaction. Returns total amount successfully spent.
     */
    private long spendPlayerCashForPurchase(Connection conn, UUID playerUuid, long amount) throws SQLException {
        String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                         "WHERE state = 'ACTIVE' AND owner_uuid = ? ORDER BY amount ASC";

        long remaining = amount;
        List<UUID> toSpend = new ArrayList<>();
        UUID partialUuid = null;
        long partialNewAmount = 0;

        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next() && remaining > 0) {
                UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                long cashAmount = rs.getLong("amount");

                if (cashAmount <= remaining) {
                    toSpend.add(cashUuid);
                    remaining -= cashAmount;
                } else {
                    partialUuid = cashUuid;
                    partialNewAmount = cashAmount - remaining;
                    remaining = 0;
                }
            }
        }

        if (!toSpend.isEmpty()) {
            String spendSql = "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') WHERE cash_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(spendSql)) {
                for (UUID uuid : toSpend) {
                    ps.setString(1, uuid.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        if (partialUuid != null) {
            String partialSql = "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(partialSql)) {
                ps.setLong(1, partialNewAmount);
                ps.setString(2, partialUuid.toString());
                ps.executeUpdate();
            }
        }

        return amount - remaining; // total successfully spent
    }

    /**
     * Remove spent cash items from player inventory after a successful DB commit.
     * Scans for cash items and removes them until the specified amount is covered.
     */
    private void removeSpentCashFromInventory(Player player, long amount) {
        long remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && currency.isCashItem(item)) {
                long itemAmount = currency.getCashAmount(item);
                if (itemAmount <= remaining) {
                    remaining -= itemAmount;
                    player.getInventory().setItem(i, null);
                } else {
                    // Partial: this shouldn't happen since DB already handles partials,
                    // but handle it gracefully
                    remaining = 0;
                }
            }
        }
    }

    private MarketData.Listing getListing(UUID listingUuid) {
        String sql = "SELECT * FROM auction_listings WHERE listing_uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, listingUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapListing(rs);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get listing: " + listingUuid, e);
        }
        return null;
    }

    private String getEscrowItemData(UUID listingUuid) {
        String sql = "SELECT item_data FROM auction_escrow_items WHERE listing_uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, listingUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("item_data");
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get escrow item", e);
        }
        return null;
    }

    private MarketData.Listing mapListing(ResultSet rs) throws SQLException {
        UUID listingUuid = UUID.fromString(rs.getString("listing_uuid"));
        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
        MarketData.Category category;
        try {
            category = MarketData.Category.valueOf(rs.getString("category"));
        } catch (IllegalArgumentException e) {
            category = MarketData.Category.MISC;
        }
        long price = rs.getLong("price");
        String itemData = rs.getString("item_data");
        String createdAt = rs.getString("created_at");
        String expiresAt = rs.getString("expires_at");

        MarketData.Listing listing = new MarketData.Listing(
            listingUuid, sellerUuid, category, itemData, price, createdAt, expiresAt
        );

        String status = rs.getString("status");
        listing.setStatus(MarketData.ListingStatus.valueOf(status));

        String soldTo = rs.getString("sold_to");
        if (soldTo != null) listing.setSoldTo(UUID.fromString(soldTo));
        listing.setSoldAt(rs.getString("sold_at"));

        return listing;
    }

    // Serialization

    private static String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
            boos.writeObject(item);
            boos.close();
            return Base64Coder.encodeLines(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    private static ItemStack deserializeItem(String data) {
        try {
            byte[] bytes = Base64Coder.decodeLines(data);
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
            return (ItemStack) bois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }
}
