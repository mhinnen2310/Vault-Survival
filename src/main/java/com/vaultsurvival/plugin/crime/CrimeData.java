package com.vaultsurvival.plugin.crime;

import java.util.UUID;

/**
 * Data models for the Crime & Police system.
 *
 * Crimes are automatically logged when valuable blocks are stolen,
 * and police players within a district can arrest, fine, or set bounties.
 */
public class CrimeData {

    public enum CrimeType {
        THEFT,       // Stealing valuable blocks or items
        GRIEF,       // Mass structure damage
        TRESPASS,    // Entering restricted areas
        ASSAULT,     // Attacking district members
        VANDALISM    // Placing junk blocks
    }

    public enum CrimeSeverity {
        MINOR,       // 1 point
        MODERATE,    // 3 points
        MAJOR        // 5 points
    }

    /**
     * A single crime committed by a player in a district.
     */
    public static class CrimeRecord {
        private final int id;
        private final int districtId;
        private final UUID criminalUuid;
        private final CrimeType type;
        private final CrimeSeverity severity;
        private final String blockType;   // what was stolen/griefed
        private final String location;    // "world x,y,z"
        private final long timestamp;

        public CrimeRecord(int id, int districtId, UUID criminalUuid, CrimeType type,
                           CrimeSeverity severity, String blockType, String location, long timestamp) {
            this.id = id;
            this.districtId = districtId;
            this.criminalUuid = criminalUuid;
            this.type = type;
            this.severity = severity;
            this.blockType = blockType;
            this.location = location;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public int getDistrictId() { return districtId; }
        public UUID getCriminalUuid() { return criminalUuid; }
        public CrimeType getType() { return type; }
        public CrimeSeverity getSeverity() { return severity; }
        public String getBlockType() { return blockType; }
        public String getLocation() { return location; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * A player wanted in a specific district. Tracks bounty and crime count.
     */
    public static class WantedStatus {
        private final UUID criminalUuid;
        private final int districtId;
        private long bounty;
        private int crimeCount;
        private long lastCrimeTime;
        private boolean arrested;
        private long jailUntil; // epoch millis, 0 = not jailed

        public WantedStatus(UUID criminalUuid, int districtId, long bounty,
                            int crimeCount, long lastCrimeTime, boolean arrested) {
            this.criminalUuid = criminalUuid;
            this.districtId = districtId;
            this.bounty = bounty;
            this.crimeCount = crimeCount;
            this.lastCrimeTime = lastCrimeTime;
            this.arrested = arrested;
        }

        public UUID getCriminalUuid() { return criminalUuid; }
        public int getDistrictId() { return districtId; }
        public long getBounty() { return bounty; }
        public void setBounty(long bounty) { this.bounty = bounty; }
        public int getCrimeCount() { return crimeCount; }
        public void incrementCrimeCount() { this.crimeCount++; }
        public long getLastCrimeTime() { return lastCrimeTime; }
        public void setLastCrimeTime(long time) { this.lastCrimeTime = time; }
        public boolean isArrested() { return arrested; }
        public void setArrested(boolean arrested) { this.arrested = arrested; }
        public long getJailUntil() { return jailUntil; }
        public void setJailUntil(long jailUntil) { this.jailUntil = jailUntil; }
    }

    /** Jail location for a district, set by the mayor. */
    public static class JailInfo {
        private final int districtId;
        private final String worldName;
        private final int x, y, z;

        public JailInfo(int districtId, String worldName, int x, int y, int z) {
            this.districtId = districtId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getDistrictId() { return districtId; }
        public String getWorldName() { return worldName; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }

        public org.bukkit.Location getLocation() {
            var world = org.bukkit.Bukkit.getWorld(worldName);
            return world != null ? new org.bukkit.Location(world, x + 0.5, y, z + 0.5) : null;
        }
    }
}
