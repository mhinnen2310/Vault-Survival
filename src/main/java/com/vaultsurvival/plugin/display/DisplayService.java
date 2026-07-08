package com.vaultsurvival.plugin.display;

import com.vaultsurvival.plugin.market.MarketData;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * Service for the Display Auction Hall system.
 *
 * Manages fixed display slots where auction listings are shown
 * as physical ItemDisplays and TextDisplays in the world.
 */
public interface DisplayService {

    /**
     * Add a new display slot at the given block location.
     */
    DisplayData.DisplaySlot addSlot(Location location, MarketData.Category category);

    /**
     * Remove a display slot and despawn its displays.
     */
    boolean removeSlot(int slotId);

    /**
     * Get all display slots.
     */
    List<DisplayData.DisplaySlot> getAllSlots();

    /**
     * Get all display slots for a specific category.
     */
    List<DisplayData.DisplaySlot> getSlotsByCategory(MarketData.Category category);

    /**
     * Refresh all displays — despawn old, spawn new based on active listings.
     * Called periodically and when listings change.
     */
    int refreshAll();

    /**
     * Play a sold animation at a display slot (particles + optional text flash).
     */
    void soldAnimation(int slotId, String buyerName, long price);

    /**
     * Load all display slots from the database.
     */
    void loadAll();
}
