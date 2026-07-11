package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.TileState;

import java.util.*;

/**
 * Data classes for VS-WorldEdit.
 */
public class VSWorldEditData {

    /** A validated weighted material entry used by VWE patterns. */
    public record WeightedMaterial(Material material, int weight) { }

    // Max undo operations per player
    public static final int DEFAULT_MAX_UNDO = 10;
    // Max blocks per operation
    public static final int DEFAULT_MAX_BLOCKS = 50_000;
    // Blocks processed per tick (batched)
    public static final int DEFAULT_BLOCKS_PER_TICK = 500;
    // Require confirmation above this block count
    public static final int DEFAULT_CONFIRM_ABOVE = 10_000;

    /** Wand item material */
    public static final String WAND_MATERIAL = "WOODEN_AXE";
    /** NamespacedKey for wand identification */
    public static final String WAND_KEY = "vwe_wand";

    /** Operation types for audit and undo descriptions */
    public enum OperationType {
        FILL, REPLACE, PATTERN_FILL, PATTERN_REPLACE, WALLS, OUTLINE,
        FLOOR, CEILING, HOLLOW,
        CYLINDER, CIRCLE, SPHERE, HSPHERE, LINE, SCHEMATIC_PASTE
    }

    /**
     * A pre-computed block placement with its target material.
     * Used for operations that need per-block material control (e.g. hollow).
     */
    public static class BlockPlacement {
        public final int x, y, z;
        public final Material material;

        public BlockPlacement(int x, int y, int z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }
    }

    /**
     * One absolute target block copied from a vanilla structure palette.
     * Keeping the detached BlockState preserves signs, containers and other
     * tile-state data instead of reducing a structure to material names.
     */
    public record SchematicPlacement(int x, int y, int z, BlockState templateState, BlockData blockData) {
        public SchematicPlacement(int x, int y, int z, BlockState templateState) {
            this(x, y, z, Objects.requireNonNull(templateState, "templateState"), templateState.getBlockData().clone());
        }
        public SchematicPlacement(int x, int y, int z, BlockData blockData) {
            this(x, y, z, null, Objects.requireNonNull(blockData, "blockData").clone());
        }
        public SchematicPlacement {
            Objects.requireNonNull(blockData, "blockData");
        }
        public Material material() { return blockData.getMaterial(); }
    }

    /**
     * A cuboid region selection.
     */
    public static class Selection {
        private final UUID playerUuid;
        private final String worldName;
        private final int x1, y1, z1;
        private final int x2, y2, z2;

        public Selection(UUID playerUuid, Location pos1, Location pos2) {
            this.playerUuid = playerUuid;
            this.worldName = pos1.getWorld().getName();
            this.x1 = Math.min(pos1.getBlockX(), pos2.getBlockX());
            this.y1 = Math.min(pos1.getBlockY(), pos2.getBlockY());
            this.z1 = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            this.x2 = Math.max(pos1.getBlockX(), pos2.getBlockX());
            this.y2 = Math.max(pos1.getBlockY(), pos2.getBlockY());
            this.z2 = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        }

        public UUID getPlayerUuid() { return playerUuid; }
        public String getWorldName() { return worldName; }
        public int getX1() { return x1; }
        public int getY1() { return y1; }
        public int getZ1() { return z1; }
        public int getX2() { return x2; }
        public int getY2() { return y2; }
        public int getZ2() { return z2; }
        public int getWidth() { return x2 - x1 + 1; }
        public int getHeight() { return y2 - y1 + 1; }
        public int getDepth() { return z2 - z1 + 1; }
        public int getVolume() {
            long volume = (long) getWidth() * getHeight() * getDepth();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, volume));
        }

        public boolean isWallBlock(int x, int y, int z) {
            return x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
        }

        public boolean isOutlineBlock(int x, int y, int z) {
            int edgeCount = 0;
            if (x == x1 || x == x2) edgeCount++;
            if (y == y1 || y == y2) edgeCount++;
            if (z == z1 || z == z2) edgeCount++;
            return edgeCount >= 2;
        }

        @Override
        public String toString() {
            return String.format("(%d,%d,%d) -> (%d,%d,%d) [%d blocks]",
                x1, y1, z1, x2, y2, z2, getVolume());
        }
    }

    /**
     * Snapshot of a block for undo operations.
     */
    public static class BlockSnapshot {
        public final String worldName;
        public final int x, y, z;
        public final String previousType;
        public final String previousBlockData;
        public final BlockState previousTileState;

        public BlockSnapshot(Location loc, String previousType) {
            this.worldName = loc.getWorld().getName();
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.previousType = previousType;
            this.previousBlockData = loc.getBlock().getBlockData().getAsString();
            BlockState state = loc.getBlock().getState();
            this.previousTileState = state instanceof TileState ? state : null;
        }
    }

    /**
     * An undo entry stores snapshots of blocks before an operation.
     */
    public static class UndoEntry {
        private final UUID playerUuid;
        private final String description;
        private final long timestamp;
        private final List<BlockSnapshot> snapshots;

        public UndoEntry(UUID playerUuid, String description) {
            this.playerUuid = playerUuid;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
            this.snapshots = new ArrayList<>();
        }

        public UUID getPlayerUuid() { return playerUuid; }
        public String getDescription() { return description; }
        public long getTimestamp() { return timestamp; }
        public List<BlockSnapshot> getSnapshots() { return snapshots; }
        public void addSnapshot(BlockSnapshot snapshot) { snapshots.add(snapshot); }
        public int getSize() { return snapshots.size(); }
    }

    /**
     * A pending or active batch operation.
     */
    public static class ActiveOperation {
        private final UUID playerUuid;
        private final OperationType type;
        private final Selection selection;
        private final Material primaryMaterial;
        private final Material secondaryMaterial; // for replace: fromBlock
        private final UndoEntry undoEntry;
        private volatile boolean cancelled;
        private int processed;
        private int totalBlocks;

        public ActiveOperation(UUID playerUuid, OperationType type, Selection selection,
                               Material primary, Material secondary) {
            this.playerUuid = playerUuid;
            this.type = type;
            this.selection = selection;
            this.primaryMaterial = primary;
            this.secondaryMaterial = secondary;
            this.undoEntry = new UndoEntry(playerUuid,
                type.name() + " " + selection.getVolume() + " blocks");
            this.cancelled = false;
            this.processed = 0;
            this.totalBlocks = selection.getVolume();
        }

        public UUID getPlayerUuid() { return playerUuid; }
        public OperationType getType() { return type; }
        public Selection getSelection() { return selection; }
        public Material getPrimaryMaterial() { return primaryMaterial; }
        public Material getSecondaryMaterial() { return secondaryMaterial; }
        public UndoEntry getUndoEntry() { return undoEntry; }
        public boolean isCancelled() { return cancelled; }
        public void cancel() { this.cancelled = true; }
        public int getProcessed() { return processed; }
        public void incrementProcessed() { processed++; }
        public int getTotalBlocks() { return totalBlocks; }
        public void setTotalBlocks(int n) { this.totalBlocks = n; }
        public int getPercent() {
            return totalBlocks > 0 ? (processed * 100 / totalBlocks) : 0;
        }
    }
}
