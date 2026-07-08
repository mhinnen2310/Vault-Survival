package com.vaultsurvival.plugin.vaults;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for vault operations.
 * Vaults are physical storage objects for cash. They cannot be broken normally,
 * only breached through the VS-Breach system. A vault always protects at least 50%
 * of its value.
 */
public interface VaultService {

    /**
     * Place a vault at a location owned by a player.
     * @param player The owner.
     * @param location Where to place the vault.
     * @param tier The vault tier (SMALL, IRON, REINFORCED, TREASURY, DECOY).
     * @return The vault's UUID, or null if placement fails.
     */
    UUID placeVault(Player player, Location location, VaultData.VaultTier tier);

    /**
     * Remove a vault. Only the owner or admin can remove it.
     * The vault must be empty of cash.
     */
    boolean removeVault(Player player, UUID vaultUuid);

    /**
     * Deposit cash into a vault.
     */
    boolean depositCash(Player player, UUID vaultUuid, List<ItemStack> cashItems);

    /**
     * Withdraw cash from a vault.
     */
    List<ItemStack> withdrawCash(Player player, UUID vaultUuid, long amount);

    /**
     * Get the total cash balance stored in a vault.
     */
    long getBalance(UUID vaultUuid);

    /**
     * Calculate how much is protected (can never be stolen).
     * Always >= floor(balance * 0.50).
     */
    long getProtectedAmount(UUID vaultUuid);

    /**
     * Calculate the stealable amount (what a breach could take).
     * Always <= floor(balance * 0.50).
     */
    long getStealableAmount(UUID vaultUuid);

    /**
     * Check if a vault is in lockdown (cannot be breached).
     */
    boolean isLockedDown(UUID vaultUuid);

    /**
     * Get the remaining lockdown time in seconds, or 0 if not locked down.
     */
    long getLockdownRemaining(UUID vaultUuid);

    /**
     * Attempt to repair/reseal a vault from lockdown.
     * Costs money and/or requires a repair kit.
     */
    boolean repairVault(Player player, UUID vaultUuid);

    /**
     * Grant access to a player for this vault.
     */
    boolean grantAccess(UUID vaultUuid, UUID playerUuid, String accessLevel, UUID grantedBy);

    /**
     * Revoke access from a player.
     */
    boolean revokeAccess(UUID vaultUuid, UUID playerUuid);

    /**
     * Check if a player can access this vault.
     */
    boolean canAccess(UUID vaultUuid, UUID playerUuid);

    /**
     * Get vault data by UUID.
     */
    VaultData getVault(UUID vaultUuid);

    /**
     * Get all vaults owned by a player.
     */
    List<VaultData> getPlayerVaults(UUID ownerUuid);

    /**
     * Find a vault by location (for click/break detection).
     */
    VaultData getVaultAt(Location location);

    /**
     * Update the cash balance of a vault after breach.
     * Only callable by VS-Breach module.
     */
    boolean updateBalanceAfterBreach(UUID vaultUuid, long stolenAmount);

    /**
     * Check if this block location has a vault.
     */
    boolean isVaultBlock(Location location);

    /**
     * Get the total capacity of a vault tier.
     */
    long getCapacity(VaultData.VaultTier tier);
}
