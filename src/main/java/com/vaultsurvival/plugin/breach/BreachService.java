package com.vaultsurvival.plugin.breach;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for the breach system.
 *
 * A breach is the only way to steal cash from vaults.
 * Players use a breach kit to initiate a multi-stage minigame.
 * Success steals a percentage (max 50%) of the vault's balance.
 */
public interface BreachService {

    /**
     * Create a breach kit item. Admin-only spawnable.
     * The kit is a consumable physical item required to start a breach.
     */
    ItemStack createBreachKit();

    /**
     * Check if an ItemStack is a valid breach kit.
     */
    boolean isBreachKit(ItemStack item);

    /**
     * Start a breach attempt on a vault.
     * Validates all start conditions before beginning the minigame.
     *
     * @param thief   The player attempting the breach.
     * @param vaultUuid The target vault UUID.
     * @return true if the breach started, false otherwise.
     */
    boolean startBreach(Player thief, UUID vaultUuid);

    /**
     * Cancel an active breach for a player.
     * Called when they close the GUI, move too far, log out, or die.
     * The breach is marked as FAILED and the kit is lost.
     */
    void cancelBreach(UUID playerUuid, String reason);

    /**
     * Handle a GUI click during the breach minigame.
     * Returns true if the click was handled (consumed).
     */
    boolean handleMinigameClick(Player player, int slot);

    /**
     * Get the active breach for a player, or null.
     */
    BreachData.ActiveBreach getActiveBreach(UUID playerUuid);

    /**
     * Check if a player is currently breaching.
     */
    boolean isBreaching(UUID playerUuid);

    /**
     * Check if a vault is currently being breached.
     */
    boolean isVaultBeingBreached(UUID vaultUuid);

    /**
     * Apply the no-teleport cooldown after a breach.
     */
    void applyTeleportCooldown(UUID playerUuid, int seconds);

    /**
     * Check if a player is on breach teleport cooldown.
     */
    boolean isTeleportBlocked(UUID playerUuid);

    /**
     * Get breach logs for a vault (admin).
     */
    List<BreachData.BreachLogEntry> getBreachLogs(UUID vaultUuid);

    /**
     * Get breach logs for a thief (admin).
     */
    List<BreachData.BreachLogEntry> getBreachLogsByThief(UUID thiefUuid);
}
