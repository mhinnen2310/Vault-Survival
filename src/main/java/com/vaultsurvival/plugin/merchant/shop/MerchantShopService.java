package com.vaultsurvival.plugin.merchant.shop;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface MerchantShopService {

    /**
     * Create a new merchant shop NPC at the player's location.
     * Only players with MERCHANT role in a district can create shops.
     */
    MerchantShopData.Shop createShop(Player merchant, String shopName);

    /**
     * Add stock to a shop slot from items in the merchant's inventory.
     * Items are serialized and stored in the shop's virtual inventory.
     */
    boolean addStock(Player merchant, int shopId, int slot, int quantity);

    /**
     * Set or update the price for a shop slot.
     */
    boolean setPrice(Player merchant, int shopId, int slot, long price);

    /**
     * Remove stock from a shop slot and return items to the merchant.
     */
    boolean removeStock(Player merchant, int shopId, int slot, int quantity);

    /**
     * Buy an item from a shop. Player pays physical cash.
     * Tax goes to district treasury. Net earnings go to merchant payout locker.
     */
    boolean buyItem(Player buyer, int shopId, int slot, int quantity);

    /**
     * Get a shop by ID.
     */
    MerchantShopData.Shop getShop(int shopId);

    /**
     * Get a shop by its NPC ID.
     */
    MerchantShopData.Shop getShopByNpcId(int npcId);

    /**
     * Get all shops owned by a merchant.
     */
    List<MerchantShopData.Shop> getMerchantShops(UUID merchantUuid);

    /**
     * Get items for a shop.
     */
    List<MerchantShopData.ShopItem> getShopItems(int shopId);

    /**
     * Get sales history for a shop.
     */
    List<MerchantShopData.Sale> getSales(int shopId);

    /**
     * Get all shops.
     */
    List<MerchantShopData.Shop> getAllShops();

    /**
     * Open the shop GUI for a player interacting with a merchant shop NPC.
     */
    void openShopGui(Player player, int npcId);

    /**
     * Load all shops from database.
     */
    void loadAll();
}
