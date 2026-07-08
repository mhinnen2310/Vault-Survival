package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Lightweight internal build/edit toolkit for Vault Survival.
 */
public interface VSWorldEditService {

    // --- Wand ---
    void giveWand(Player player);
    boolean isWand(ItemStack item);

    // --- Selection ---
    void setPos1(Player player, Location loc);
    void setPos2(Player player, Location loc);
    Location getPos1(Player player);
    Location getPos2(Player player);
    VSWorldEditData.Selection getSelection(Player player);
    void clearSelection(Player player);

    // --- Operations (batched) ---
    /** Start a batched fill operation. Returns true if queued/started. */
    boolean fill(Player player, Material material);
    /** Start a batched replace operation. */
    boolean replace(Player player, Material from, Material to);
    /** Start a batched walls operation. */
    boolean walls(Player player, Material material);
    /** Start a batched outline operation. */
    boolean outline(Player player, Material material);
    /** Place blocks on the bottom layer of the selection. */
    boolean floor(Player player, Material material);
    /** Place blocks on the top layer of the selection. */
    boolean ceiling(Player player, Material material);
    /** Build walls with wallBlock and hollow out interior with airBlock. */
    boolean hollow(Player player, Material wallBlock, Material airBlock);
    /** Create a vertical cylinder centered on the player. */
    boolean cylinder(Player player, int radius, int height, Material material);
    /** Create a flat circle centered on the player. */
    boolean circle(Player player, int radius, Material material);
    /** Create a solid sphere centered on the player. */
    boolean sphere(Player player, int radius, Material material);
    /** Create a hollow sphere centered on the player. */
    boolean hollowSphere(Player player, int radius, Material material);
    /** Create a line from pos1 to pos2. */
    boolean line(Player player, Material material);

    // --- Confirm / Cancel ---
    /** Confirm a pending operation (requires confirmation). */
    boolean confirm(Player player);
    /** Cancel a pending or active operation. */
    boolean cancelOperation(Player player);
    /** Check if a player has a pending operation awaiting confirmation. */
    boolean hasPendingConfirmation(Player player);
    /** Check if a player has an active operation running. */
    boolean hasActiveOperation(Player player);
    /** Get the pending operation description for the player. */
    String getPendingDescription(Player player);

    // --- Undo ---
    void pushUndo(Player player, VSWorldEditData.UndoEntry entry);
    int undo(Player player);

    // --- Limits ---
    int getMaxBlocksPerOperation();
    int getBlocksPerTick();
    int getMaxUndoOperations();
    int getRequireConfirmationAbove();
}
