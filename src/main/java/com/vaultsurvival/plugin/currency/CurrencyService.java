package com.vaultsurvival.plugin.currency;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for the physical currency system.
 * Every coin exists as a physical item with a server-authoritative database record.
 */
public interface CurrencyService {

    /**
     * Mint new physical cash. Creates a DB record and returns a physical ItemStack.
     * Only callable by admins or the Mint.
     *
     * @param amount    The face value of the cash.
     * @param creator   The UUID of the player/entity creating this cash.
     * @param recipient Optional: if set, the cash is assigned to this player.
     * @return The physical ItemStack representing the cash.
     */
    ItemStack mintCash(long amount, UUID creator, UUID recipient);

    /** Rebuild the physical item for an existing authoritative cash record. */
    ItemStack materializeCash(UUID cashUuid);

    /**
     * Split a cash item into two. The original is invalidated and two new items are created.
     * Useful for making change.
     *
     * @param cashItem  The cash item to split.
     * @param splitAmount How much to split off.
     * @return Array of [remaining, split] ItemStacks, or null if invalid.
     */
    ItemStack[] splitCash(ItemStack cashItem, long splitAmount);

    /**
     * Merge two cash items into one. Both originals are invalidated.
     *
     * @param cash1 First cash item.
     * @param cash2 Second cash item.
     * @return The merged ItemStack, or null if either is invalid.
     */
    ItemStack mergeCash(ItemStack cash1, ItemStack cash2);

    /**
     * Validate a cash ItemStack. Checks that it has a valid PDC tag,
     * the corresponding DB record exists, is in a valid state, and the amount matches.
     *
     * @param item The item to validate.
     * @return true if the item represents valid, active cash.
     */
    boolean validateCash(ItemStack item);

    /**
     * Invalidate a cash item. Marks it as SPENT or INVALIDATED in the DB.
     * The physical item should be destroyed after this call.
     *
     * @param cashItem The cash item to invalidate.
     * @param reason   Reason for invalidation (SPENT, COUNTERFEIT, DUPLICATE, etc.)
     */
    void invalidateCash(ItemStack cashItem, String reason);

    /**
     * Invalidate a cash item by UUID directly (no physical item needed).
     * Used by admin commands when the physical item can't be located.
     */
    void invalidateCashByUuid(UUID cashUuid, String reason);

    /**
     * Get the amount stored in a cash item (from DB, not the item itself).
     *
     * @param cashItem The cash item.
     * @return The amount, or 0 if invalid.
     */
    long getCashAmount(ItemStack cashItem);

    /**
     * Get the UUID of a cash item.
     *
     * @param cashItem The cash item.
     * @return The cash UUID, or null if not a cash item.
     */
    UUID getCashUuid(ItemStack cashItem);

    /**
     * Get the full data record for a cash item.
     */
    CashItemData getCashData(ItemStack cashItem);

    /**
     * Get the full data record by UUID.
     */
    CashItemData getCashData(UUID cashUuid);

    /**
     * Scan a player's entire inventory for cash items and validate each.
     * Invalidates any counterfeit/duplicate cash found.
     *
     * @param player The player to scan.
     * @return List of all valid cash items found.
     */
    List<CashItemData> scanInventory(Player player);

    /**
     * Check if an item is a cash item (has valid PDC tag).
     */
    boolean isCashItem(ItemStack item);

    /**
     * Update the location of a cash item in the database.
     */
    void updateCashLocation(UUID cashUuid, String locationType, String locationId);

    /**
     * Transfer cash ownership from one player to another.
     * Updates both the DB record and the physical item's PDC.
     */
    boolean transferCash(ItemStack cashItem, UUID fromPlayer, UUID toPlayer);

    /**
     * Get the total cash value in a player's inventory.
     */
    long getPlayerCashTotal(UUID playerUuid);

    /**
     * Remove a specific amount of cash from a player's inventory.
     * Handles splitting as needed. Returns list of items removed (for escrow).
     */
    List<ItemStack> withdrawCash(Player player, long amount);

    /**
     * Deposit cash items into a player's inventory.
     * Attempts to merge with existing cash items when possible.
     */
    void depositCash(Player player, List<ItemStack> cashItems);

    /**
     * Get economy statistics for admin use.
     */
    CurrencyStats getStats();
}
