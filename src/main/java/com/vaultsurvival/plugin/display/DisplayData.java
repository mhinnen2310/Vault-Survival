package com.vaultsurvival.plugin.display;

import com.vaultsurvival.plugin.market.MarketData;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Data models for the Display Auction Hall system.
 *
 * ItemDisplays and TextDisplays are spawned at designated slots
 * to physically show auction listings in the world.
 */
public class DisplayData {

    /**
     * A designated display slot at a fixed location.
     * Shows one active auction listing at a time.
     */
    public static class DisplaySlot {
        private final int id;
        private final String worldName;
        private final int x, y, z;
        private final MarketData.Category category;
        private UUID currentItemDisplayId;  // Entity UUID of the ItemDisplay
        private UUID currentTextDisplayId;  // Entity UUID of the TextDisplay
        private UUID currentListingUuid;     // Which listing is currently shown

        public DisplaySlot(int id, String worldName, int x, int y, int z, MarketData.Category category) {
            this.id = id;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.category = category;
        }

        public Location getLocation() {
            var world = org.bukkit.Bukkit.getWorld(worldName);
            // Spawn display slightly above the block
            return world != null ? new Location(world, x + 0.5, y + 0.6, z + 0.5) : null;
        }

        public int getId() { return id; }
        public String getWorldName() { return worldName; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public MarketData.Category getCategory() { return category; }
        public UUID getCurrentItemDisplayId() { return currentItemDisplayId; }
        public void setCurrentItemDisplayId(UUID id) { this.currentItemDisplayId = id; }
        public UUID getCurrentTextDisplayId() { return currentTextDisplayId; }
        public void setCurrentTextDisplayId(UUID id) { this.currentTextDisplayId = id; }
        public UUID getCurrentListingUuid() { return currentListingUuid; }
        public void setCurrentListingUuid(UUID id) { this.currentListingUuid = id; }
    }
}
