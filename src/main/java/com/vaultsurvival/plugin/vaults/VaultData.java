package com.vaultsurvival.plugin.vaults;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Data model for a vault.
 * Vaults are physical objects in the world that store cash records.
 */
public class VaultData {

    public enum VaultTier {
        /** 10k capacity, basic wooden appearance */
        SMALL(10000, "Small Vault"),
        /** 50k capacity, iron reinforced */
        IRON(50000, "Iron Vault"),
        /** 200k capacity, heavily reinforced */
        REINFORCED(200000, "Reinforced Vault"),
        /** 1M capacity, district-level */
        TREASURY(1000000, "District Treasury Vault"),
        /** Low capacity, appears valuable but stores little */
        DECOY(5000, "Decoy Vault");

        private final long capacity;
        private final String displayName;

        VaultTier(long capacity, String displayName) {
            this.capacity = capacity;
            this.displayName = displayName;
        }

        public long getCapacity() { return capacity; }
        public String getDisplayName() { return displayName; }
    }

    private final UUID vaultUuid;
    private VaultTier tier;
    private String world;
    private int x, y, z;
    private UUID ownerUuid;
    private long capacity;
    private boolean lockedDown;
    private Timestamp lockoutUntil;
    private Timestamp createdAt;
    private long balance; // Derived from cash_items, not stored directly

    public VaultData(UUID vaultUuid, VaultTier tier, UUID ownerUuid) {
        this.vaultUuid = vaultUuid;
        this.tier = tier;
        this.ownerUuid = ownerUuid;
        this.capacity = tier.getCapacity();
        this.lockedDown = false;
        this.createdAt = new Timestamp(System.currentTimeMillis());
    }

    // --- Getters and setters ---

    public UUID getVaultUuid() { return vaultUuid; }

    public VaultTier getTier() { return tier; }
    public void setTier(VaultTier tier) { this.tier = tier; }

    public String getWorld() { return world; }
    public void setWorld(String world) { this.world = world; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public long getCapacity() { return capacity; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }

    public boolean isLockedDown() { return lockedDown; }
    public void setLockedDown(boolean lockedDown) { this.lockedDown = lockedDown; }

    public Timestamp getLockoutUntil() { return lockoutUntil; }
    public void setLockoutUntil(Timestamp lockoutUntil) { this.lockoutUntil = lockoutUntil; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    /**
     * Calculate protected amount: at least 50% of balance is always safe.
     */
    public long getProtectedAmount() {
        return (long) Math.floor(balance * 0.50);
    }

    /**
     * Calculate stealable amount: maximum that can be taken in a breach.
     */
    public long getStealableAmount() {
        return balance - getProtectedAmount();
    }

    /**
     * Check if this vault can be breached right now.
     */
    public boolean isBreachable() {
        if (lockedDown) return false;
        if (lockoutUntil != null && lockoutUntil.after(new Timestamp(System.currentTimeMillis()))) {
            return false;
        }
        return getStealableAmount() > 0;
    }
}
