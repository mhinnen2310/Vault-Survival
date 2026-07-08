package com.vaultsurvival.plugin.market;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for the physical Auction Hall.
 *
 * There is no global /ah — players must physically visit the Auction Hall.
 * Items are held in escrow until sold. Payments use physical cash.
 * Seller earnings go into a physical Auction Locker for later collection.
 */
public interface MarketService {

    /**
     * Create a new listing. The item in the player's main hand is escrowed.
     *
     * @param seller    The player creating the listing.
     * @param price     Sale price in coins.
     * @param category  Category for browsing.
     * @param hours     Listing duration in hours.
     * @return Listing UUID, or null on failure.
     */
    UUID createListing(Player seller, long price, MarketData.Category category, int hours);

    /**
     * Cancel an active listing and return the item to the seller.
     */
    boolean cancelListing(Player player, UUID listingUuid);

    /**
     * Buy a listing. Must have enough physical cash in inventory.
     * This is an atomic transaction — all steps succeed or none do.
     *
     * @return true on success, false on failure (with error message sent to buyer).
     */
    boolean buyListing(Player buyer, UUID listingUuid);

    /**
     * Collect seller earnings from their Auction Locker.
     * Converts locker balance into physical cash items.
     */
    boolean collectEarnings(Player seller);

    /**
     * Get all active listings, optionally filtered by category.
     */
    List<MarketData.Listing> getActiveListings(MarketData.Category category);

    /**
     * Get all listings by a seller.
     */
    List<MarketData.Listing> getSellerListings(UUID sellerUuid);

    /**
     * Get the balance in a player's Auction Locker.
     */
    long getLockerBalance(UUID playerUuid);

    /**
     * Admin inspect a listing with full details.
     */
    MarketData.Listing inspectListing(UUID listingUuid);

    /**
     * Expire listings past their expiration date.
     * Called periodically (e.g., on server startup or schedule).
     */
    int expireStaleListings();
}
