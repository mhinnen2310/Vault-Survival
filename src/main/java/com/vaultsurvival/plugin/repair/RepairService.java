package com.vaultsurvival.plugin.repair;

import java.util.List;
import java.util.UUID;

/**
 * Service for the Repairmen system.
 *
 * Each district has a pool of daily repair points (default 500).
 * When temporary damage is recorded, points are consumed.
 * When points run out, restores slow to the exhaustion delay (30 min vs 10 min).
 * Repairmen must be paid a daily wage from the district treasury, or points won't reset.
 */
public interface RepairService {

    /**
     * Get the repair state for a district, creating it if needed.
     */
    RepairData.DistrictRepairState getState(int districtId);

    /**
     * Get the restore delay to use for a new damage record.
     * Returns normal delay minutes if points remain, exhaustion delay if depleted.
     */
    int getRestoreDelayMinutes(int districtId);

    /**
     * Consume a repair point for a district. Called when damage is recorded.
     */
    void consumePoint(int districtId);

    /**
     * Process the daily reset for all districts.
     * Resets points and attempts to pay wages from treasuries.
     */
    int processDailyReset();

    /**
     * Pay the daily wage for a specific district.
     * Withdraws from treasury. Returns true if successful.
     */
    boolean payWage(int districtId, UUID actorUuid);

    /**
     * Force an emergency repair of a specific damage record.
     * Costs 5 repair points. Council+ only.
     */
    boolean emergencyRepair(int districtId, int damageRecordId, UUID actorUuid);

    /**
     * Get all repair states.
     */
    List<RepairData.DistrictRepairState> getAllStates();

    /**
     * Create or replace a repairman NPC for a district at the given location.
     * The NPC uses the COMMAND action to run /repair status when clicked.
     */
    void createRepairmanNpc(int districtId, org.bukkit.Location location);

    /**
     * Load all repair states from the database.
     */
    void loadAll();

    /**
     * Save all repair states to the database.
     */
    void saveAll();
}
