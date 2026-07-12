package com.vaultsurvival.plugin.districts;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
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

    /** Submit an application using a validated exact-block claim. */
    DistrictData.District apply(Player founder, String name, DistrictData.BlockClaim claim);

    /** Return the persisted exact-block claim for a district, or null for unmigrated legacy districts. */
    DistrictData.BlockClaim getClaim(int districtId);

    /** Update an active district claim after a level-gated expansion. */
    boolean updateClaim(DistrictData.District district, UUID actorUuid, DistrictData.BlockClaim claim);

    /** The configured block-area cap for the district's current development level. */
    long getClaimBlockLimit(DistrictData.District district);

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

    boolean removeRole(int districtId, UUID actorUuid, UUID targetUuid, DistrictData.DistrictRole role);

    boolean hasDistrictRole(UUID playerUuid, DistrictData.District district, DistrictData.DistrictRole role);

    Set<DistrictData.DistrictRole> getDistrictRoles(UUID playerUuid, DistrictData.District district);

    DistrictData.DistrictRole getHighestDistrictRole(UUID playerUuid, DistrictData.District district);

    boolean canManageRoles(UUID playerUuid, DistrictData.District district);

    boolean canManageLaws(UUID playerUuid, DistrictData.District district);

    boolean canManageTreasury(UUID playerUuid, DistrictData.District district);

    boolean canCreateMerchantNpc(UUID playerUuid, DistrictData.District district);

    boolean canCreateDistrictJob(UUID playerUuid, DistrictData.District district);

    boolean canApproveDistrictJob(UUID playerUuid, DistrictData.District district);

    boolean canPolice(UUID playerUuid, DistrictData.District district);

    boolean canRequestStation(UUID playerUuid, DistrictData.District district);

    boolean canManageDevelopment(UUID playerUuid, DistrictData.District district);

    /** MAYOR-only district entry/exit messages. */
    boolean setDistrictMessage(DistrictData.District district, UUID actorUuid, boolean welcome, String message);
    String getDistrictMessage(DistrictData.District district, boolean welcome);

    /** MAYOR-only district chat style. */
    boolean setDistrictChatPrefix(DistrictData.District district, UUID actorUuid, String prefix);
    String getDistrictChatPrefix(DistrictData.District district);
    boolean setDistrictChatPrefixColor(DistrictData.District district, UUID actorUuid, String color);
    String getDistrictChatPrefixColor(DistrictData.District district);
    boolean setDistrictRoleColor(DistrictData.District district, UUID actorUuid, DistrictData.DistrictRole role, String color);
    String getDistrictRoleColor(DistrictData.District district, DistrictData.DistrictRole role);

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

    boolean proposeLaw(int districtId, UUID actorUuid, DistrictData.LawKey lawKey, boolean enabled);

    int applyPendingLaws();

    boolean isLawActive(DistrictData.District district, DistrictData.LawKey lawKey);

    boolean isLawPending(DistrictData.District district, DistrictData.LawKey lawKey);

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
