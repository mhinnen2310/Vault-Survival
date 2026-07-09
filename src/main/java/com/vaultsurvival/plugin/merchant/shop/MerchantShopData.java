package com.vaultsurvival.plugin.merchant.shop;

import java.util.UUID;

public class MerchantShopData {

    public static class Shop {
        private final int id;
        private final UUID ownerUuid;
        private final int npcId;
        private final int districtId;
        private final String name;
        private final String worldName;
        private final long createdAt;

        public Shop(int id, UUID ownerUuid, int npcId, int districtId, String name,
                    String worldName, long createdAt) {
            this.id = id;
            this.ownerUuid = ownerUuid;
            this.npcId = npcId;
            this.districtId = districtId;
            this.name = name;
            this.worldName = worldName;
            this.createdAt = createdAt;
        }

        public int getId() { return id; }
        public UUID getOwnerUuid() { return ownerUuid; }
        public int getNpcId() { return npcId; }
        public int getDistrictId() { return districtId; }
        public String getName() { return name; }
        public String getWorldName() { return worldName; }
        public long getCreatedAt() { return createdAt; }
    }

    public static class ShopItem {
        private final int id;
        private final int shopId;
        private final int slot;
        private final String itemData; // Base64 serialized ItemStack
        private final String itemDisplay;
        private final int stock;
        private final long price;

        public ShopItem(int id, int shopId, int slot, String itemData, String itemDisplay,
                        int stock, long price) {
            this.id = id;
            this.shopId = shopId;
            this.slot = slot;
            this.itemData = itemData;
            this.itemDisplay = itemDisplay;
            this.stock = stock;
            this.price = price;
        }

        public int getId() { return id; }
        public int getShopId() { return shopId; }
        public int getSlot() { return slot; }
        public String getItemData() { return itemData; }
        public String getItemDisplay() { return itemDisplay; }
        public int getStock() { return stock; }
        public long getPrice() { return price; }
    }

    public static class Sale {
        private final int id;
        private final int shopId;
        private final UUID buyerUuid;
        private final String itemData;
        private final int quantity;
        private final long priceEach;
        private final long taxAmount;
        private final long timestamp;

        public Sale(int id, int shopId, UUID buyerUuid, String itemData, int quantity,
                    long priceEach, long taxAmount, long timestamp) {
            this.id = id;
            this.shopId = shopId;
            this.buyerUuid = buyerUuid;
            this.itemData = itemData;
            this.quantity = quantity;
            this.priceEach = priceEach;
            this.taxAmount = taxAmount;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public int getShopId() { return shopId; }
        public UUID getBuyerUuid() { return buyerUuid; }
        public String getItemData() { return itemData; }
        public int getQuantity() { return quantity; }
        public long getPriceEach() { return priceEach; }
        public long getTaxAmount() { return taxAmount; }
        public long getTimestamp() { return timestamp; }
        public long getTotalPaid() { return priceEach * quantity; }
        public long getNetEarnings() { return (priceEach * quantity) - taxAmount; }
    }
}
