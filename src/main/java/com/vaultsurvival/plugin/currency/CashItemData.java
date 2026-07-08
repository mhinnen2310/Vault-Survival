package com.vaultsurvival.plugin.currency;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Data model for a physical cash item.
 * Every cash item has a unique UUID tracked in the database.
 * The database record is authoritative - physical items without
 * a matching valid DB record are considered counterfeit.
 */
public class CashItemData {

    public enum CashState {
        /** Cash is in a player's inventory */
        ACTIVE,
        /** Cash is on the ground as a dropped item */
        DROPPED,
        /** Cash is stored in a vault */
        IN_VAULT,
        /** Cash is held in Auction Hall escrow */
        IN_AH_ESCROW,
        /** Cash is in a seller's auction locker */
        IN_AUCTION_LOCKER,
        /** Cash is held in trade escrow */
        IN_TRADE_ESCROW,
        /** Cash is in a district treasury */
        IN_DISTRICT_TREASURY,
        /** Cash has been spent (no longer exists as physical money) */
        SPENT,
        /** Cash has been invalidated (counterfeit, duplicated, etc.) */
        INVALIDATED;

        /**
         * Check if cash in this state can be moved or transferred.
         */
        public boolean isTransferable() {
            return this == ACTIVE || this == DROPPED;
        }

        /**
         * Check if cash in this state still represents valid money.
         */
        public boolean isValid() {
            return this != SPENT && this != INVALIDATED;
        }
    }

    private final UUID cashUuid;
    private long amount;
    private CashState state;
    private final Timestamp createdAt;
    private Timestamp lastSeenAt;
    private String locationType;  // INVENTORY, DROPPED, VAULT, ESCROW, etc.
    private String locationId;    // Player UUID, vault UUID, listing UUID, etc.
    private UUID ownerUuid;
    private UUID createdBy;

    public CashItemData(UUID cashUuid, long amount) {
        this.cashUuid = cashUuid;
        this.amount = amount;
        this.state = CashState.ACTIVE;
        this.createdAt = new Timestamp(System.currentTimeMillis());
        this.lastSeenAt = new Timestamp(System.currentTimeMillis());
    }

    // --- Getters and setters ---

    public UUID getCashUuid() { return cashUuid; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public CashState getState() { return state; }
    public void setState(CashState state) { this.state = state; }

    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Timestamp lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public String getLocationType() { return locationType; }
    public void setLocationType(String locationType) { this.locationType = locationType; }

    public String getLocationId() { return locationId; }
    public void setLocationId(String locationId) { this.locationId = locationId; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    /**
     * Check if this cash still has value and can be used.
     */
    public boolean isValid() {
        return state.isValid() && amount > 0;
    }
}
