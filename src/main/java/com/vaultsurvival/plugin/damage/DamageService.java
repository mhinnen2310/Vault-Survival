package com.vaultsurvival.plugin.damage;

import com.vaultsurvival.plugin.repair.RepairService;
import org.bukkit.block.Block;

import java.util.List;
import java.util.UUID;

/**
 * Service for the Temporary District Damage system.
 *
 * When non-district-members break or place blocks inside district regions,
 * the damage is recorded and automatically restored after a configurable delay.
 * Drops are cancelled for structure blocks to prevent permanent grief.
 */
public interface DamageService {

    /**
     * Record a block break by a non-member visitor.
     * Saves the original block type/data and schedules restoration.
     *
     * @return the damage record, or null if the block shouldn't be tracked
     */
    DamageData.DamageRecord recordBreak(Block block, UUID actorUuid, int districtId);

    /**
     * Record a block place by a non-member visitor.
     * Saves the original state (usually AIR) and schedules removal.
     *
     * @return the damage record, or null if the block shouldn't be tracked
     */
    DamageData.DamageRecord recordPlace(Block block, UUID actorUuid, int districtId);

    /**
     * Process all pending restores that are due.
     * Called periodically by the scheduler and on server startup.
     *
     * @return number of blocks restored
     */
    int processRestores();

    /**
     * Force immediate restoration of a specific damage record.
     */
    boolean forceRestore(int recordId);

    /**
     * Get all pending (unrestored) damage records for a district.
     */
    List<DamageData.DamageRecord> getPendingDamage(int districtId);

    /**
     * Get pending damage at a specific location.
     */
    List<DamageData.DamageRecord> getDamageAt(org.bukkit.Location location);

    /**
     * Load all pending damage records from the database.
     */
    void loadAll();

    /**
     * Check if temporary damage is enabled at a location for a specific player.
     * Returns true if: location is in a region with TEMPORARY_DAMAGE_ENABLED
     * AND (player is null OR player is not a member of the owning district).
     */
    boolean shouldTrackDamage(org.bukkit.Location location, UUID playerUuid);

    /**
     * Get the district ID that owns the location (for damage tracking).
     * Returns -1 if location is not in a district region.
     */
    int getDistrictIdForLocation(org.bukkit.Location location);

    /**
     * Get all damage records (unrestored, for admin inspection).
     */
    List<DamageData.DamageRecord> getAllDamage();

    /**
     * Set the repair service for repair-aware restore delays.
     * Called by RepairModule after both modules are loaded.
     */
    void setRepairService(RepairService repairService);
}
