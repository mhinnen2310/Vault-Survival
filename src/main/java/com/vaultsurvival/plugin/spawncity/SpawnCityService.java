package com.vaultsurvival.plugin.spawncity;

import com.vaultsurvival.plugin.vsworldedit.VSWorldEditData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Service for managing Spawn City — the Kingdom capital.
 */
public interface SpawnCityService {

    /** Get the configured city name. */
    String getCityName();

    /** Set the city name (persists to config). */
    void setCityName(String name);

    /** Get the saved spawn location, or null if not set. */
    Location getSpawnLocation();

    /** Save the current location as the spawn point. */
    void setSpawnLocation(Location loc);

    /** Teleport a player to the spawn city. */
    void teleportToSpawn(Player player);

    // --- Regions (saved to config, uses VWE selections) ---

    /** Save VWE selection as the capital region. */
    void setCapitalRegion(VSWorldEditData.Selection sel);

    /** Save VWE selection as the Auction Hall region. */
    void setAuctionHallRegion(VSWorldEditData.Selection sel);

    /** Save VWE selection as the Mint region. */
    void setMintRegion(VSWorldEditData.Selection sel);

    /** Get a saved region by type, or null. */
    VSWorldEditData.Selection getRegion(String type);

    /** Get all saved region types. */
    java.util.List<String> getRegionTypes();

    /** Check if a region type is saved. */
    boolean hasRegion(String type);
}
