package com.vaultsurvival.plugin.merchant;

import java.util.UUID;

public class MerchantOrderData {

    public enum OrderStatus {
        ACTIVE,
        PARTIALLY_FILLED,
        FILLED,
        EXPIRED,
        CANCELLED,
        DISPUTED
    }

    public static class Order {
        private final int id;
        private final UUID merchantUuid;
        private final String itemTemplate; // Base64 serialized ItemStack of desired item
        private final String itemDisplay; // Human-readable item name
        private final long pricePerItem;
        private final int requiredQuantity;
        private int filledQuantity;
        private boolean partialDelivery;
        private long escrowAmount; // Total escrow locked for this order
        private long remainingEscrow; // Escrow still available for payouts
        private OrderStatus status;
        private final long createdAt;

        public Order(int id, UUID merchantUuid, String itemTemplate, String itemDisplay,
                     long pricePerItem, int requiredQuantity, int filledQuantity,
                     boolean partialDelivery, long escrowAmount, long remainingEscrow,
                     OrderStatus status, long createdAt) {
            this.id = id;
            this.merchantUuid = merchantUuid;
            this.itemTemplate = itemTemplate;
            this.itemDisplay = itemDisplay;
            this.pricePerItem = pricePerItem;
            this.requiredQuantity = requiredQuantity;
            this.filledQuantity = filledQuantity;
            this.partialDelivery = partialDelivery;
            this.escrowAmount = escrowAmount;
            this.remainingEscrow = remainingEscrow;
            this.status = status;
            this.createdAt = createdAt;
        }

        public int getId() { return id; }
        public UUID getMerchantUuid() { return merchantUuid; }
        public String getItemTemplate() { return itemTemplate; }
        public String getItemDisplay() { return itemDisplay; }
        public long getPricePerItem() { return pricePerItem; }
        public int getRequiredQuantity() { return requiredQuantity; }
        public int getFilledQuantity() { return filledQuantity; }
        public void setFilledQuantity(int filledQuantity) { this.filledQuantity = filledQuantity; }
        public boolean isPartialDelivery() { return partialDelivery; }
        public long getEscrowAmount() { return escrowAmount; }
        public void setEscrowAmount(long escrowAmount) { this.escrowAmount = escrowAmount; }
        public long getRemainingEscrow() { return remainingEscrow; }
        public void setRemainingEscrow(long remainingEscrow) { this.remainingEscrow = remainingEscrow; }
        public OrderStatus getStatus() { return status; }
        public void setStatus(OrderStatus status) { this.status = status; }
        public long getCreatedAt() { return createdAt; }

        public int getRemainingQuantity() {
            return Math.max(0, requiredQuantity - filledQuantity);
        }

        public boolean isFilled() {
            return filledQuantity >= requiredQuantity;
        }
    }

    public static class StoredItem {
        private final int id;
        private final int orderId;
        private final String itemData; // Base64 serialized delivered ItemStack
        private final boolean claimed;

        public StoredItem(int id, int orderId, String itemData, boolean claimed) {
            this.id = id;
            this.orderId = orderId;
            this.itemData = itemData;
            this.claimed = claimed;
        }

        public int getId() { return id; }
        public int getOrderId() { return orderId; }
        public String getItemData() { return itemData; }
        public boolean isClaimed() { return claimed; }
    }

    public static class Delivery {
        private final int id;
        private final int orderId;
        private final UUID supplierUuid;
        private final int quantityDelivered;
        private final long payoutAmount;
        private final long timestamp;

        public Delivery(int id, int orderId, UUID supplierUuid, int quantityDelivered,
                        long payoutAmount, long timestamp) {
            this.id = id;
            this.orderId = orderId;
            this.supplierUuid = supplierUuid;
            this.quantityDelivered = quantityDelivered;
            this.payoutAmount = payoutAmount;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public int getOrderId() { return orderId; }
        public UUID getSupplierUuid() { return supplierUuid; }
        public int getQuantityDelivered() { return quantityDelivered; }
        public long getPayoutAmount() { return payoutAmount; }
        public long getTimestamp() { return timestamp; }
    }
}
