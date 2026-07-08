package com.vaultsurvival.plugin.market;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Data models for the physical Auction Hall system.
 *
 * There is no global /ah. Players must physically visit the Auction Hall
 * to sell, buy, and collect earnings.
 *
 * Items are stored in escrow. Payments use physical cash.
 * Seller earnings go into a physical Auction Locker — not a digital balance.
 */
public class MarketData {

    /** Possible states for a listing. */
    public enum ListingStatus { ACTIVE, SOLD, CANCELLED, EXPIRED }

    /** Listing categories for browsing. */
    public enum Category {
        MISC, TOOLS, COMBAT, BUILDING, FOOD, POTIONS, ENCHANTED, RARE
    }

    /**
     * An active (or completed) listing in the Auction Hall.
     */
    public static class Listing {
        private final UUID listingUuid;
        private final UUID sellerUuid;
        private final Category category;
        private final String itemData; // Base64 serialized ItemStack
        private final long price;
        private final String createdAt;
        private final String expiresAt;
        private ListingStatus status;
        private UUID soldTo;
        private String soldAt;

        public Listing(UUID listingUuid, UUID sellerUuid, Category category,
                       String itemData, long price, String createdAt, String expiresAt) {
            this.listingUuid = listingUuid;
            this.sellerUuid = sellerUuid;
            this.category = category;
            this.itemData = itemData;
            this.price = price;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.status = ListingStatus.ACTIVE;
        }

        public UUID getListingUuid() { return listingUuid; }
        public UUID getSellerUuid() { return sellerUuid; }
        public Category getCategory() { return category; }
        public String getItemData() { return itemData; }
        public long getPrice() { return price; }
        public String getCreatedAt() { return createdAt; }
        public String getExpiresAt() { return expiresAt; }
        public ListingStatus getStatus() { return status; }
        public void setStatus(ListingStatus status) { this.status = status; }
        public UUID getSoldTo() { return soldTo; }
        public void setSoldTo(UUID soldTo) { this.soldTo = soldTo; }
        public String getSoldAt() { return soldAt; }
        public void setSoldAt(String soldAt) { this.soldAt = soldAt; }
    }
}
