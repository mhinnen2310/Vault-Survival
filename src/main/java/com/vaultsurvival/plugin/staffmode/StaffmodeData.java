package com.vaultsurvival.plugin.staffmode;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;

/**
 * Tracks all state for a player in staff mode.
 * Stores gameplay inventory separately, tracks block changes for revert,
 * and manages bypass status.
 */
public class StaffmodeData {

    private final UUID playerUuid;
    private boolean staffModeActive;
    private boolean bypassMode; // Owner override - bypasses all restrictions
    private boolean buildPermissionEnabled;
    private int breakerSize;
    private PermissionAttachment buildPermissionAttachment;
    private boolean sandboxTransferPending;
    private ItemStack[] gameplayInventory;
    private ItemStack[] gameplayArmor;
    private Location gameplayLocation;
    private final List<BlockChange> blockChanges = new ArrayList<>();

    public StaffmodeData(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public boolean isStaffModeActive() { return staffModeActive; }
    public void setStaffModeActive(boolean active) { this.staffModeActive = active; }
    public boolean isBypassMode() { return bypassMode; }
    public void setBypassMode(boolean bypass) { this.bypassMode = bypass; }
    public boolean isBuildPermissionEnabled() { return buildPermissionEnabled; }
    public void setBuildPermissionEnabled(boolean enabled) { this.buildPermissionEnabled = enabled; }
    public int getBreakerSize() { return breakerSize; }
    public void setBreakerSize(int breakerSize) { this.breakerSize = breakerSize; }
    public PermissionAttachment getBuildPermissionAttachment() { return buildPermissionAttachment; }
    public void setBuildPermissionAttachment(PermissionAttachment attachment) { this.buildPermissionAttachment = attachment; }
    public boolean isSandboxTransferPending() { return sandboxTransferPending; }
    public void setSandboxTransferPending(boolean pending) { this.sandboxTransferPending = pending; }

    public ItemStack[] getGameplayInventory() { return gameplayInventory; }
    public void setGameplayInventory(ItemStack[] inv) { this.gameplayInventory = inv; }

    public ItemStack[] getGameplayArmor() { return gameplayArmor; }
    public void setGameplayArmor(ItemStack[] armor) { this.gameplayArmor = armor; }
    public Location getGameplayLocation() { return gameplayLocation; }
    public void setGameplayLocation(Location location) { this.gameplayLocation = location; }

    public List<BlockChange> getBlockChanges() { return blockChanges; }
    public void addBlockChange(BlockChange change) { blockChanges.add(change); }
    public void clearBlockChanges() { blockChanges.clear(); }

    public int getBlockChangeCount() { return blockChanges.size(); }
}
