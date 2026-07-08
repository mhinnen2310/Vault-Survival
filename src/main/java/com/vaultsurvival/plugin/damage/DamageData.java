package com.vaultsurvival.plugin.damage;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

/**
 * Data models for the Temporary District Damage system.
 *
 * When non-district-members (visitors) break or place blocks inside district regions
 * with TEMPORARY_DAMAGE_ENABLED, the damage is recorded and scheduled for restoration.
 * Drops are cancelled to prevent permanent grief/stealing of structure blocks.
 */
public class DamageData {

    /** Classification of blocks for damage handling. */
    public enum BlockClass {
        /** Standard building materials — restore unchanged. */
        STRUCTURE,
        /** Valuable blocks (diamonds, gold, etc.) — logged specially for crime tracking. */
        VALUABLE,
        /** Containers (chests, barrels, etc.) — restored empty, contents lost. */
        CONTAINER,
        /** Decorative/functional blocks — restore unchanged. */
        FUNCTIONAL
    }

    /** Whether this was a break or a place. */
    public enum DamageType {
        /** Visitor broke an existing block. Restore the original block. */
        BREAK,
        /** Visitor placed a block. Restore to air. */
        PLACE
    }

    /**
     * A single block damage record — what was changed, by whom, and when to restore.
     */
    public static class DamageRecord {
        private final int id;
        private final int districtId;
        private final String worldName;
        private final int x, y, z;
        private final Material originalBlock; // what was there before the action
        private final String originalBlockData; // BlockData string for complex blocks
        private final DamageType type;
        private final UUID actorUuid;
        private final long timestamp;
        private long scheduledRestoreTime;
        private boolean restored;

        public DamageRecord(int id, int districtId, String worldName, int x, int y, int z,
                            Material originalBlock, String originalBlockData, DamageType type,
                            UUID actorUuid, long timestamp, long scheduledRestoreTime) {
            this.id = id;
            this.districtId = districtId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.originalBlock = originalBlock;
            this.originalBlockData = originalBlockData;
            this.type = type;
            this.actorUuid = actorUuid;
            this.timestamp = timestamp;
            this.scheduledRestoreTime = scheduledRestoreTime;
            this.restored = false;
        }

        public Location getLocation() {
            var world = org.bukkit.Bukkit.getWorld(worldName);
            return world != null ? new Location(world, x, y, z) : null;
        }

        // Getters/Setters
        public int getId() { return id; }
        public int getDistrictId() { return districtId; }
        public String getWorldName() { return worldName; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public Material getOriginalBlock() { return originalBlock; }
        public String getOriginalBlockData() { return originalBlockData; }
        public DamageType getType() { return type; }
        public UUID getActorUuid() { return actorUuid; }
        public long getTimestamp() { return timestamp; }
        public long getScheduledRestoreTime() { return scheduledRestoreTime; }
        public void setScheduledRestoreTime(long time) { this.scheduledRestoreTime = time; }
        public boolean isRestored() { return restored; }
        public void setRestored(boolean restored) { this.restored = restored; }
        public boolean isPlacement() { return type == DamageType.PLACE; }
        public boolean isBreak() { return type == DamageType.BREAK; }

        /** Classify this block for damage handling. */
        public BlockClass getBlockClass() {
            return classify(originalBlock);
        }

        /** Classify a material for damage handling. */
        public static BlockClass classify(Material mat) {
            if (mat == null) return BlockClass.STRUCTURE;
            return switch (mat) {
                // Containers — restored empty
                case CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX,
                     WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                     LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX,
                     PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                     CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX,
                     BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                     BLACK_SHULKER_BOX, FURNACE, BLAST_FURNACE, SMOKER,
                     HOPPER, DISPENSER, DROPPER, BREWING_STAND
                    -> BlockClass.CONTAINER;

                // Valuable — logged for crime tracking
                case DIAMOND_BLOCK, EMERALD_BLOCK, GOLD_BLOCK, IRON_BLOCK,
                     NETHERITE_BLOCK, COPPER_BLOCK, ANCIENT_DEBRIS,
                     BEACON, ENCHANTING_TABLE, ENDER_CHEST, RESPAWN_ANCHOR,
                     DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE,
                     DEEPSLATE_EMERALD_ORE, GOLD_ORE, DEEPSLATE_GOLD_ORE,
                     NETHER_GOLD_ORE, IRON_ORE, DEEPSLATE_IRON_ORE,
                     COPPER_ORE, DEEPSLATE_COPPER_ORE, NETHERITE_SCRAP,
                     LAPIS_ORE, DEEPSLATE_LAPIS_ORE, REDSTONE_ORE,
                     DEEPSLATE_REDSTONE_ORE
                    -> BlockClass.VALUABLE;

                // Functional blocks
                case CRAFTING_TABLE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                     LOOM, CARTOGRAPHY_TABLE, SMITHING_TABLE, FLETCHING_TABLE,
                     GRINDSTONE, STONECUTTER, COMPOSTER, CAULDRON,
                     LAVA_CAULDRON, WATER_CAULDRON, POWDER_SNOW_CAULDRON,
                     BELL, LECTERN, NOTE_BLOCK, JUKEBOX, LODESTONE,
                     CONDUIT, CAMPFIRE, SOUL_CAMPFIRE, LANTERN, SOUL_LANTERN,
                     END_ROD, LIGHTNING_ROD, TARGET, ITEM_FRAME, GLOW_ITEM_FRAME,
                     PAINTING, ARMOR_STAND
                    -> BlockClass.FUNCTIONAL;

                // Everything else is structure
                default -> BlockClass.STRUCTURE;
            };
        }
    }
}
