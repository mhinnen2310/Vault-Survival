package com.vaultsurvival.plugin.staffmode;

import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Records a single block change (break or place) for later revert.
 */
public class BlockChange {
    public final String worldName;
    public final int x, y, z;
    public final Material previousType;
    public final Material newType;
    public final boolean wasBreak;

    public BlockChange(Location loc, Material prev, Material newType, boolean wasBreak) {
        this.worldName = loc.getWorld().getName();
        this.x = loc.getBlockX();
        this.y = loc.getBlockY();
        this.z = loc.getBlockZ();
        this.previousType = prev;
        this.newType = newType;
        this.wasBreak = wasBreak;
    }
}
