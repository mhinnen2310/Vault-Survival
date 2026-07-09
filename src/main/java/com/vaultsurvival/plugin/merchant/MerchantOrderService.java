package com.vaultsurvival.plugin.merchant;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface MerchantOrderService {

    /**
     * Create a new buy order. Merchant must be holding the desired item.
     * Physical cash is withdrawn from the merchant's inventory as escrow.
     *
     * @param merchant         The merchant player
     * @param pricePerItem     Price paid per delivered item
     * @param requiredQuantity Total quantity needed
     * @param partialDelivery  Whether partial deliveries are allowed
     * @return The created order, or null if failed (insufficient funds, no item in hand)
     */
    MerchantOrderData.Order createOrder(Player merchant, long pricePerItem, int requiredQuantity, boolean partialDelivery);

    /**
     * Add more escrow to an existing active order.
     */
    boolean addEscrow(Player merchant, int orderId, long amount);

    /**
     * Deliver matching items to an order. Items are consumed from player inventory.
     * Payout is deposited directly or to payout locker.
     *
     * @param supplier The player delivering items
     * @param orderId  The order to deliver to
     * @param quantity Number of items to deliver
     * @return Number of items actually delivered and paid for
     */
    int deliverItems(Player supplier, int orderId, int quantity);

    /**
     * Cancel an order. Remaining escrow is returned to the merchant.
     * Cannot cancel if DISPUTED.
     */
    boolean cancelOrder(UUID actor, int orderId);

    /**
     * Collect stored items from a filled/completed order into merchant's inventory.
     */
    List<ItemStack> collectStorage(Player merchant, int orderId);

    /**
     * Get an order by ID.
     */
    MerchantOrderData.Order getOrder(int orderId);

    /**
     * Get active (ACTIVE, PARTIALLY_FILLED) orders.
     */
    List<MerchantOrderData.Order> getActiveOrders();

    /**
     * Get all orders for a merchant.
     */
    List<MerchantOrderData.Order> getMerchantOrders(UUID merchantUuid);

    /**
     * Get deliveries for an order.
     */
    List<MerchantOrderData.Delivery> getDeliveries(int orderId);

    /**
     * Get all orders (admin).
     */
    List<MerchantOrderData.Order> getAllOrders();

    /**
     * Load all orders from database into memory.
     */
    void loadAll();

    /**
     * Check for expired orders.
     */
    void checkExpired();
}
