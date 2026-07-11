package com.vaultsurvival.plugin.regions;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Service for the custom regions/rules system.
 *
 * Regions are cuboid areas with configurable rule flags.
 * Overlapping regions resolve by priority — highest wins.
 * Other modules query this service instead of hardcoding worlds.
 */
public interface RegionService {

    /**
     * Create a new cuboid region.
     */
    RegionData.Region createRegion(String name, RegionData.RegionType type, String worldName,
                                   int x1, int y1, int z1, int x2, int y2, int z2, int priority);

    /**
     * Delete a region by ID.
     */
    boolean deleteRegion(int regionId);

    /**
     * Set a rule flag on a region.
     */
    boolean setFlag(int regionId, RegionData.RuleFlag flag, boolean value);

    /**
     * Get a region by ID.
     */
    RegionData.Region getRegion(int regionId);

    /**
     * Get all regions.
     */
    Collection<RegionData.Region> getAllRegions();

    /**
     * Get all regions that contain a location, sorted by priority (highest first).
     */
    Collection<RegionData.Region> getRegionsAt(Location location);

    /**
     * Resolve all applicable rules for a location.
     * Overlapping regions are merged by priority.
     * If no region covers the location, default rules apply (everything allowed).
     */
    RegionData.ResolvedRules getRules(Location location);

    /**
     * Check a specific rule flag at a location.
     * Convenience method for quick checks.
     */
    boolean isAllowed(Location location, RegionData.RuleFlag flag);

    /**
     * Resolve a build rule for a specific actor. This keeps narrow role-based
     * exceptions in the region policy instead of teaching unrelated listeners
     * about region naming or district ownership.
     */
    boolean isBuildAllowed(Player player, Location location, RegionData.RuleFlag flag);

    /**
     * Load all regions from the database.
     */
    void loadAll();
}
