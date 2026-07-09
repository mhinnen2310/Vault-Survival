package com.vaultsurvival.plugin.merchant;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.social.PayoutLockerService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;
    private final ConcurrentHashMap<Integer, MerchantOrderData.Order> orders = new ConcurrentHashMap<>();

    public MerchantOrderServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
    }

    // ========================================================================
    // Create Order
    // ========================================================================

    @Override
    public MerchantOrderData.Order createOrder(Player merchant, long pricePerItem, int requiredQuantity,
                                                boolean partialDelivery) {
        DistrictService districts;
        try { districts = plugin.getServiceRegistry().get(DistrictService.class); } catch (RuntimeException e) { merchant.sendMessage(fmt.error("District service unavailable.")); return null; }
        var district = districts.getPlayerDistrict(merchant.getUniqueId());
        if (district == null || !districts.canCreateMerchantNpc(merchant.getUniqueId(), district)) {
            merchant.sendMessage(fmt.error("Requires MERCHANT, CO_MAYOR, or MAYOR in an active district."));
            return null;
        }
        ItemStack heldItem = merchant.getInventory().getItemInMainHand();
        if (heldItem.getType().isAir()) {
            merchant.sendMessage(fmt.error("You must hold the item you want to buy in your hand."));
            return null;
        }

        if (pricePerItem <= 0) {
            merchant.sendMessage(fmt.error("Price per item must be greater than 0."));
            return null;
        }

        if (requiredQuantity <= 0 || requiredQuantity > 10000) {
            merchant.sendMessage(fmt.error("Quantity must be between 1 and 10,000."));
            return null;
        }

        long totalEscrow = pricePerItem * requiredQuantity;
        long playerCash = currency.getPlayerCashTotal(merchant.getUniqueId());
        if (playerCash < totalEscrow) {
            merchant.sendMessage(fmt.error("You don't have enough cash for the escrow. Need: &6" +
                fmt.formatMoney(totalEscrow,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) +
                " &7| Have: &6" + fmt.formatMoney(playerCash,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
            return null;
        }

        // Take a single item clone to use as the template (don't consume the held item)
        ItemStack template = heldItem.clone();
        template.setAmount(1);

        String itemTemplate = serializeItem(template);
        String itemDisplay = getItemDisplay(template);

        // Withdraw cash from merchant for escrow
        List<ItemStack> escrowCash = currency.withdrawCash(merchant, totalEscrow);
        long locked = escrowCash.stream().mapToLong(currency::getCashAmount).sum();
        if (locked < totalEscrow) {
            // Refund what was taken
            currency.depositCash(merchant, escrowCash);
            merchant.sendMessage(fmt.error("Failed to lock escrow. Try again."));
            return null;
        }

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Mark cash as in merchant order escrow
                for (ItemStack cash : escrowCash) {
                    UUID cashUuid = currency.getCashUuid(cash);
                    plugin.getDatabase().executeUpdate(
                        "UPDATE cash_items SET state = 'IN_MERCHANT_ESCROW', owner_uuid = NULL, " +
                        "location_type = 'MERCHANT_ORDER_ESCROW', location_id = 'TEMP', " +
                        "last_seen_at = datetime('now') WHERE cash_uuid = ?",
                        cashUuid.toString());
                }

                // Insert order
                long now = System.currentTimeMillis();
                String insertSql = "INSERT INTO merchant_orders " +
                    "(merchant_uuid, item_template, item_display, price_per_item, req_quantity, " +
                    "filled_quantity, partial_delivery, escrow_amount, remaining_escrow, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 'ACTIVE', ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, merchant.getUniqueId().toString());
                    ps.setString(2, itemTemplate);
                    ps.setString(3, itemDisplay);
                    ps.setLong(4, pricePerItem);
                    ps.setInt(5, requiredQuantity);
                    ps.setInt(6, partialDelivery ? 1 : 0);
                    ps.setLong(7, locked);
                    ps.setLong(8, locked);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    if (keys.next()) {
                        int orderId = keys.getInt(1);

                        // Update cash items with the actual order ID
                        for (ItemStack cash : escrowCash) {
                            UUID cashUuid = currency.getCashUuid(cash);
                            plugin.getDatabase().executeUpdate(
                                "UPDATE cash_items SET location_id = ? WHERE cash_uuid = ?",
                                String.valueOf(orderId), cashUuid.toString());
                        }

                        conn.commit();

                        MerchantOrderData.Order order = new MerchantOrderData.Order(
                            orderId, merchant.getUniqueId(), itemTemplate, itemDisplay,
                            pricePerItem, requiredQuantity, 0, partialDelivery,
                            locked, locked, MerchantOrderData.OrderStatus.ACTIVE, now);
                        orders.put(orderId, order);

                        audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_ORDER_CREATE",
                            "ORDER", String.valueOf(orderId),
                            "item=" + itemDisplay + " price=" + pricePerItem +
                            " qty=" + requiredQuantity + " escrow=" + locked);

                        merchant.sendMessage(fmt.success("Buy order #" + orderId + " created!"));
                        merchant.sendMessage(fmt.info("Item: &e" + itemDisplay + " &7| Price: &6" +
                            fmt.formatMoney(pricePerItem,
                                plugin.getConfigManager().getCurrencyName(),
                                plugin.getConfigManager().getCurrencyNamePlural()) +
                            " &7each | Qty: &e" + requiredQuantity));
                        merchant.sendMessage(fmt.info("Total escrow: &6" + fmt.formatMoney(locked,
                            plugin.getConfigManager().getCurrencyName(),
                            plugin.getConfigManager().getCurrencyNamePlural())));

                        return order;
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                // Refund cash to merchant
                currency.depositCash(merchant, escrowCash);
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create merchant order", e);
            merchant.sendMessage(fmt.error("Failed to create order. Cash returned."));
            return null;
        }

        // Shouldn't reach here
        currency.depositCash(merchant, escrowCash);
        return null;
    }

    @Override
    public boolean addEscrow(Player merchant, int orderId, long amount) {
        MerchantOrderData.Order order = orders.get(orderId);
        if (order == null || !order.getMerchantUuid().equals(merchant.getUniqueId())) {
            merchant.sendMessage(fmt.error("Order not found or not yours."));
            return false;
        }
        if (order.getStatus() != MerchantOrderData.OrderStatus.ACTIVE &&
            order.getStatus() != MerchantOrderData.OrderStatus.PARTIALLY_FILLED) {
            merchant.sendMessage(fmt.error("Order is not active. Cannot add escrow."));
            return false;
        }
        if (amount <= 0) {
            merchant.sendMessage(fmt.error("Amount must be greater than 0."));
            return false;
        }

        long playerCash = currency.getPlayerCashTotal(merchant.getUniqueId());
        if (playerCash < amount) {
            merchant.sendMessage(fmt.error("You don't have enough cash."));
            return false;
        }

        List<ItemStack> escrowCash = currency.withdrawCash(merchant, amount);
        long locked = escrowCash.stream().mapToLong(currency::getCashAmount).sum();

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (ItemStack cash : escrowCash) {
                    UUID cashUuid = currency.getCashUuid(cash);
                    plugin.getDatabase().executeUpdate(
                        "UPDATE cash_items SET state = 'IN_MERCHANT_ESCROW', owner_uuid = NULL, " +
                        "location_type = 'MERCHANT_ORDER_ESCROW', location_id = ?, " +
                        "last_seen_at = datetime('now') WHERE cash_uuid = ?",
                        String.valueOf(orderId), cashUuid.toString());
                }

                order.setEscrowAmount(order.getEscrowAmount() + locked);
                order.setRemainingEscrow(order.getRemainingEscrow() + locked);
                plugin.getDatabase().executeUpdate(
                    "UPDATE merchant_orders SET escrow_amount = ?, remaining_escrow = ? WHERE id = ?",
                    order.getEscrowAmount(), order.getRemainingEscrow(), orderId);

                conn.commit();

                audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_ORDER_ADD_ESCROW",
                    "ORDER", String.valueOf(orderId), "amount=" + locked);

                merchant.sendMessage(fmt.success("Added &6" + fmt.formatMoney(locked,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) +
                    " &ato order escrow."));
                return true;
            } catch (SQLException e) {
                conn.rollback();
                currency.depositCash(merchant, escrowCash);
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add escrow", e);
            merchant.sendMessage(fmt.error("Failed to add escrow. Cash returned."));
            return false;
        }
    }

    // ========================================================================
    // Deliver Items
    // ========================================================================

    @Override
    public int deliverItems(Player supplier, int orderId, int quantity) {
        MerchantOrderData.Order order = orders.get(orderId);
        if (order == null) {
            supplier.sendMessage(fmt.error("Order not found."));
            return 0;
        }
        if (order.getStatus() != MerchantOrderData.OrderStatus.ACTIVE &&
            order.getStatus() != MerchantOrderData.OrderStatus.PARTIALLY_FILLED) {
            supplier.sendMessage(fmt.error("This order is no longer active."));
            return 0;
        }
        if (order.getMerchantUuid().equals(supplier.getUniqueId())) {
            supplier.sendMessage(fmt.error("You cannot deliver to your own order."));
            return 0;
        }

        // Deserialize the item template to know what to match
        ItemStack template = deserializeItem(order.getItemTemplate());
        Material targetMaterial = template.getType();

        // Count how many matching items the player has
        int available = countMatchingItems(supplier, targetMaterial);
        if (available == 0) {
            supplier.sendMessage(fmt.error("You don't have any matching items: &e" + order.getItemDisplay()));
            return 0;
        }

        int maxDeliverable = order.getRemainingQuantity();
        // Clamp quantity to what's needed and available, respecting partial delivery setting
        int toDeliver;
        if (!order.isPartialDelivery()) {
            // Non-partial: can only deliver the exact remaining quantity
            if (quantity < maxDeliverable) {
                supplier.sendMessage(fmt.error("This order requires &e" + maxDeliverable +
                    " &cmore items. Partial delivery is disabled."));
                return 0;
            }
            toDeliver = Math.min(available, maxDeliverable);
        } else {
            toDeliver = Math.min(quantity, Math.min(available, maxDeliverable));
        }
        if (toDeliver <= 0) {
            supplier.sendMessage(fmt.error("Cannot deliver 0 items."));
            return 0;
        }

        long payoutPerItem = order.getPricePerItem();
        long totalPayout = payoutPerItem * toDeliver;

        // Check if enough escrow remains
        if (order.getRemainingEscrow() < totalPayout) {
            supplier.sendMessage(fmt.error("Order doesn't have enough escrow remaining. Contact the merchant."));
            return 0;
        }

        PayoutLockerService payouts;
        try {
            payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        } catch (Exception e) {
            supplier.sendMessage(fmt.error("Payout system unavailable."));
            return 0;
        }

        // Remove matching items from supplier inventory
        int delivered = removeMatchingItems(supplier, targetMaterial, toDeliver);
        if (delivered == 0) {
            return 0;
        }

        long actualPayout = payoutPerItem * delivered;

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Create storage entries for each delivered item
                long now = System.currentTimeMillis();
                for (int i = 0; i < delivered; i++) {
                    ItemStack storedItem = template.clone();
                    plugin.getDatabase().executeUpdate(
                        "INSERT INTO merchant_order_storage (order_id, item_data, claimed) VALUES (?, ?, 0)",
                        orderId, serializeItem(storedItem));
                }

                // Record delivery
                plugin.getDatabase().executeUpdate(
                    "INSERT INTO merchant_deliveries (order_id, supplier_uuid, quantity_delivered, " +
                    "payout_amount, timestamp) VALUES (?, ?, ?, ?, ?)",
                    orderId, supplier.getUniqueId().toString(), delivered, actualPayout, now);

                // Update order
                int newFilled = order.getFilledQuantity() + delivered;
                long newEscrow = order.getRemainingEscrow() - actualPayout;
                order.setFilledQuantity(newFilled);
                order.setRemainingEscrow(newEscrow);

                if (order.isFilled()) {
                    order.setStatus(MerchantOrderData.OrderStatus.FILLED);
                } else {
                    order.setStatus(MerchantOrderData.OrderStatus.PARTIALLY_FILLED);
                }

                plugin.getDatabase().executeUpdate(
                    "UPDATE merchant_orders SET filled_quantity = ?, remaining_escrow = ?, status = ? WHERE id = ?",
                    newFilled, newEscrow, order.getStatus().name(), orderId);

                // Spend escrow cash
                spendEscrowCash(conn, orderId, actualPayout);

                conn.commit();

                // Create payout locker entry for supplier
                payouts.storePayout(supplier.getUniqueId(), actualPayout,
                    "MERCHANT_ORDER", String.valueOf(orderId),
                    "delivered=" + delivered + " to order#" + orderId);

                audit.log(supplier.getUniqueId(), supplier.getName(), "MERCHANT_ORDER_DELIVER",
                    "ORDER", String.valueOf(orderId),
                    "delivered=" + delivered + " payout=" + actualPayout);

                supplier.sendMessage(fmt.success("Delivered &e" + delivered + "x " + order.getItemDisplay() +
                    " &ato order #" + orderId + "!"));
                supplier.sendMessage(fmt.info("Payout: &6" + fmt.formatMoney(actualPayout,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) +
                    " &7- Use &e/payouts claim &7to collect."));

                // Notify merchant if online
                Player merchant = Bukkit.getPlayer(order.getMerchantUuid());
                if (merchant != null) {
                    merchant.sendMessage(fmt.info("&e" + supplier.getName() + " &7delivered &e" +
                        delivered + "x " + order.getItemDisplay() + " &7to order #" + orderId +
                        " (" + newFilled + "/" + order.getRequiredQuantity() + ")"));
                }

                return delivered;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to process delivery", e);
            supplier.sendMessage(fmt.error("Delivery failed. Items returned."));
            // Return items - they were already removed, so give them back
            returnItems(supplier, template, delivered);
            return 0;
        }
    }

    // ========================================================================
    // Cancel Order
    // ========================================================================

    @Override
    public boolean cancelOrder(UUID actor, int orderId) {
        MerchantOrderData.Order order = orders.get(orderId);
        if (order == null) {
            return false;
        }
        if (!order.getMerchantUuid().equals(actor)) {
            return false;
        }
        if (order.getStatus() == MerchantOrderData.OrderStatus.CANCELLED ||
            order.getStatus() == MerchantOrderData.OrderStatus.DISPUTED) {
            return false;
        }
        if (order.getStatus() == MerchantOrderData.OrderStatus.FILLED) {
            return false;
        }

        long refundAmount = order.getRemainingEscrow();
        if (refundAmount <= 0) {
            // No escrow to refund, just mark cancelled
            order.setStatus(MerchantOrderData.OrderStatus.CANCELLED);
            try {
                plugin.getDatabase().executeUpdate(
                    "UPDATE merchant_orders SET status = 'CANCELLED' WHERE id = ?", orderId);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to cancel order", e);
            }
            audit.log(actor, "MERCHANT", "MERCHANT_ORDER_CANCEL",
                "ORDER", String.valueOf(orderId), "no_escrow_refund");
            return true;
        }

        // Refund remaining escrow to merchant via payout locker
        PayoutLockerService payouts;
        try {
            payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        } catch (Exception e) {
            return false;
        }

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Mark remaining escrow cash as spent (will be re-minted on claim)
                spendEscrowCash(conn, orderId, refundAmount);

                // Update order status
                order.setStatus(MerchantOrderData.OrderStatus.CANCELLED);
                order.setRemainingEscrow(0);
                plugin.getDatabase().executeUpdate(
                    "UPDATE merchant_orders SET status = 'CANCELLED', remaining_escrow = 0 WHERE id = ?",
                    orderId);

                conn.commit();

                // Store refund in payout locker
                payouts.storePayout(actor, refundAmount, "MERCHANT_ORDER_REFUND",
                    String.valueOf(orderId), "order cancelled, escrow refunded");

                audit.log(actor, "MERCHANT", "MERCHANT_ORDER_CANCEL",
                    "ORDER", String.valueOf(orderId), "refund=" + refundAmount);

                Player merchant = Bukkit.getPlayer(actor);
                if (merchant != null) {
                    merchant.sendMessage(fmt.success("Order #" + orderId + " cancelled."));
                    merchant.sendMessage(fmt.info("Refund: &6" + fmt.formatMoney(refundAmount,
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural()) +
                        " &7- Use &e/payouts claim &7to collect."));
                }

                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to cancel order", e);
            return false;
        }
    }

    // ========================================================================
    // Collect Storage
    // ========================================================================

    @Override
    public List<ItemStack> collectStorage(Player merchant, int orderId) {
        MerchantOrderData.Order order = orders.get(orderId);
        if (order == null || !order.getMerchantUuid().equals(merchant.getUniqueId())) {
            merchant.sendMessage(fmt.error("Order not found or not yours."));
            return Collections.emptyList();
        }

        List<MerchantOrderData.StoredItem> stored = getUnclaimedStorage(orderId);
        if (stored.isEmpty()) {
            merchant.sendMessage(fmt.error("No unclaimed items in storage for this order."));
            return Collections.emptyList();
        }

        List<ItemStack> items = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (MerchantOrderData.StoredItem storedItem : stored) {
                    ItemStack item = deserializeItem(storedItem.getItemData());
                    items.add(item);

                    plugin.getDatabase().executeUpdate(
                        "UPDATE merchant_order_storage SET claimed = 1 WHERE id = ?",
                        storedItem.getId());
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to collect storage", e);
            merchant.sendMessage(fmt.error("Failed to collect items. Try again."));
            return Collections.emptyList();
        }

        // Give items to merchant
        for (ItemStack item : items) {
            var overflow = merchant.getInventory().addItem(item);
            if (!overflow.isEmpty()) {
                for (ItemStack drop : overflow.values()) {
                    merchant.getWorld().dropItemNaturally(merchant.getLocation(), drop);
                }
            }
        }

        audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_ORDER_COLLECT",
            "ORDER", String.valueOf(orderId), "collected=" + items.size());

        merchant.sendMessage(fmt.success("Collected &e" + items.size() + " &aitems from order #" + orderId + " storage."));
        if (merchant.getInventory().firstEmpty() == -1) {
            merchant.sendMessage(fmt.warn("Some items dropped at your feet (inventory full)."));
        }

        return items;
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public MerchantOrderData.Order getOrder(int orderId) {
        return orders.get(orderId);
    }

    @Override
    public List<MerchantOrderData.Order> getActiveOrders() {
        return orders.values().stream()
            .filter(o -> o.getStatus() == MerchantOrderData.OrderStatus.ACTIVE ||
                         o.getStatus() == MerchantOrderData.OrderStatus.PARTIALLY_FILLED)
            .toList();
    }

    @Override
    public List<MerchantOrderData.Order> getMerchantOrders(UUID merchantUuid) {
        return orders.values().stream()
            .filter(o -> o.getMerchantUuid().equals(merchantUuid))
            .toList();
    }

    @Override
    public List<MerchantOrderData.Delivery> getDeliveries(int orderId) {
        List<MerchantOrderData.Delivery> deliveries = new ArrayList<>();
        String sql = "SELECT * FROM merchant_deliveries WHERE order_id = ? ORDER BY timestamp DESC";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                deliveries.add(new MerchantOrderData.Delivery(
                    rs.getInt("id"),
                    rs.getInt("order_id"),
                    UUID.fromString(rs.getString("supplier_uuid")),
                    rs.getInt("quantity_delivered"),
                    rs.getLong("payout_amount"),
                    rs.getLong("timestamp")
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get deliveries", e);
        }
        return deliveries;
    }

    @Override
    public List<MerchantOrderData.Order> getAllOrders() {
        return new ArrayList<>(orders.values());
    }

    @Override
    public void loadAll() {
        orders.clear();
        String sql = "SELECT * FROM merchant_orders";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MerchantOrderData.Order order = new MerchantOrderData.Order(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("merchant_uuid")),
                    rs.getString("item_template"),
                    rs.getString("item_display"),
                    rs.getLong("price_per_item"),
                    rs.getInt("req_quantity"),
                    rs.getInt("filled_quantity"),
                    rs.getInt("partial_delivery") != 0,
                    rs.getLong("escrow_amount"),
                    rs.getLong("remaining_escrow"),
                    MerchantOrderData.OrderStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at")
                );
                orders.put(order.getId(), order);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load merchant orders", e);
        }
        logger.info("Loaded " + orders.size() + " merchant orders");
    }

    @Override
    public void checkExpired() {
        // Orders don't automatically expire in this design.
        // They remain active until filled, cancelled, or manually expired.
        // This method is reserved for future auto-expiration logic.
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private List<MerchantOrderData.StoredItem> getUnclaimedStorage(int orderId) {
        List<MerchantOrderData.StoredItem> items = new ArrayList<>();
        String sql = "SELECT * FROM merchant_order_storage WHERE order_id = ? AND claimed = 0";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                items.add(new MerchantOrderData.StoredItem(
                    rs.getInt("id"),
                    rs.getInt("order_id"),
                    rs.getString("item_data"),
                    rs.getInt("claimed") != 0
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get unclaimed storage", e);
        }
        return items;
    }

    private int countMatchingItems(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int removeMatchingItems(Player player, Material material, int quantity) {
        int remaining = quantity;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == material) {
                int stackAmount = item.getAmount();
                if (stackAmount <= remaining) {
                    remaining -= stackAmount;
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }
        return quantity - remaining;
    }

    private void returnItems(Player player, ItemStack templateItem, int count) {
        templateItem.setAmount(count);
        var overflow = player.getInventory().addItem(templateItem);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private void spendEscrowCash(Connection conn, int orderId, long amount) throws SQLException {
        String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                         "WHERE state = 'IN_MERCHANT_ESCROW' AND location_type = 'MERCHANT_ORDER_ESCROW' " +
                         "AND location_id = ? ORDER BY amount ASC";
        long remaining = amount;
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setString(1, String.valueOf(orderId));
            ResultSet rs = ps.executeQuery();
            while (rs.next() && remaining > 0) {
                String cashUuid = rs.getString("cash_uuid");
                long cashAmount = rs.getLong("amount");
                if (cashAmount <= remaining) {
                    plugin.getDatabase().executeUpdate(
                        "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') WHERE cash_uuid = ?",
                        cashUuid);
                    remaining -= cashAmount;
                } else {
                    long newAmount = cashAmount - remaining;
                    plugin.getDatabase().executeUpdate(
                        "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?",
                        newAmount, cashUuid);
                    remaining = 0;
                }
            }
        }
    }

    private String getItemDisplay(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        // Format material name nicely
        String name = item.getType().name().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : name.toCharArray()) {
            if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else if (c == ' ') {
                sb.append(' ');
                capitalize = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // Serialization (same as MarketServiceImpl)

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
