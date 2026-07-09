package com.vaultsurvival.plugin.crime;

import java.util.List;
import java.util.UUID;

/**
 * Service for the Crime & Police system.
 *
 * Tracks crimes committed in districts, maintains wanted status,
 * and provides police tools for arrest, fines, and bounties.
 */
public interface CrimeService {

    /**
     * Log a crime in a district. Also updates/creates wanted status.
     */
    CrimeData.CrimeRecord logCrime(UUID criminalUuid, int districtId, CrimeData.CrimeType type,
                                    CrimeData.CrimeSeverity severity, String blockType, String location);

    CrimeData.EvidenceRecord createEvidence(UUID playerUuid, int districtId, String lawKey, String actionType,
                                            String location, CrimeData.CrimeSeverity severity, String details);

    List<CrimeData.EvidenceRecord> getDistrictEvidence(int districtId);

    List<CrimeData.EvidenceRecord> getEvidenceForPlayer(UUID playerUuid);

    CrimeData.EvidenceRecord getEvidence(int evidenceId);

    boolean fineEvidence(UUID policeUuid, int evidenceId, long amount);

    boolean markWantedFromEvidence(UUID policeUuid, int evidenceId);

    boolean dismissEvidence(UUID policeUuid, int evidenceId, CrimeData.EvidenceStatus status);

    /**
     * Get all wanted players in a district (not arrested).
     */
    List<CrimeData.WantedStatus> getWantedPlayers(int districtId);

    /**
     * Check if a player is wanted in a specific district.
     */
    boolean isWanted(UUID playerUuid, int districtId);

    /**
     * Set a bounty on a wanted player (police only in their own district).
     */
    boolean setBounty(int districtId, UUID criminalUuid, long amount, UUID setterUuid);

    /**
     * Arrest a wanted player. Marks them as arrested.
     * Police must be in the same district.
     */
    boolean arrest(UUID policeUuid, UUID criminalUuid);

    /**
     * Issue a fine to a criminal. Tries to withdraw cash from them.
     * Police must be in the criminal's wanted district.
     */
    boolean fine(UUID policeUuid, UUID criminalUuid, long amount);

    /**
     * Get all crimes for a specific player across all districts.
     */
    List<CrimeData.CrimeRecord> getCrimeRecord(UUID playerUuid);

    /**
     * Get the wanted status for a player in a district.
     */
    CrimeData.WantedStatus getWantedStatus(UUID playerUuid, int districtId);

    /** Set the jail location for a district (mayor only). */
    boolean setJailLocation(int districtId, String worldName, int x, int y, int z, UUID setterUuid);

    /** Get the jail location for a district. */
    CrimeData.JailInfo getJailInfo(int districtId);

    /** Release a jailed player early (police only). */
    boolean release(UUID policeUuid, UUID criminalUuid);

    /** Check if a player is currently jailed in any district. */
    boolean isJailed(UUID playerUuid);

    /** Get all currently jailed players in a district. */
    List<CrimeData.WantedStatus> getJailedPlayers(int districtId);

    /** Process jail releases for expired sentences. Returns count of players released. */
    int processJailReleases();

    /**
     * Load all crime/wanted data from the database.
     */
    void loadAll();
}
