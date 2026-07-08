package com.vaultsurvival.plugin.districts;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Service for the District governance system.
 *
 * Districts are official player-built towns. Members have roles,
 * the district has a treasury, and local laws can be configured.
 */
public interface DistrictService {

    /**
     * Submit a district application.
     * Requirements: 1500+ blocks from spawn, 500+ blocks from other districts.
     */
    DistrictData.District apply(Player founder, String name);

    /**
     * Admin approves a district application. Auto-creates a DISTRICT region.
     */
    boolean approve(int districtId, UUID adminUuid);

    /**
     * Admin rejects a district application.
     */
    boolean reject(int districtId, UUID adminUuid, String reason);

    /**
     * Disband a district (mayor or admin).
     */
    boolean disband(int districtId, UUID actorUuid);

    /**
     * Invite a player to the district (mayor/council only).
     */
    boolean inviteMember(int districtId, UUID actorUuid, UUID targetUuid);

    /**
     * Kick a member from the district (mayor/council only).
     */
    boolean kickMember(int districtId, UUID actorUuid, UUID targetUuid);

    /**
     * Set a member's role (mayor only, except own role).
     */
    boolean setRole(int districtId, UUID actorUuid, UUID targetUuid, DistrictData.DistrictRole role);

    /**
     * Deposit cash into the district treasury.
     */
    boolean depositTreasury(Player player, int districtId, long amount);

    /**
     * Withdraw cash from the district treasury (mayor/treasurer only).
     */
    boolean withdrawTreasury(Player player, int districtId, long amount);

    /**
     * Toggle a local law for the district (council+ only).
     */
    boolean setLaw(int districtId, UUID actorUuid, String lawName, boolean enabled);

    /**
     * Get a district by ID.
     */
    DistrictData.District getDistrict(int districtId);

    /**
     * Find a district by player membership.
     */
    DistrictData.District getPlayerDistrict(UUID playerUuid);

    /**
     * Get all districts.
     */
    List<DistrictData.District> getAllDistricts();

    /**
     * Get pending applications.
     */
    List<DistrictData.District> getApplications();

    /**
     * Load all districts from database.
     */
    void loadAll();
}
