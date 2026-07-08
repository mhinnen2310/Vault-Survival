package com.vaultsurvival.plugin.districts;

import org.bukkit.Location;

import java.util.*;

/**
 * Data models for the District system.
 *
 * Districts are official player-built towns recognized by the Kingdom.
 * They protect buildings from permanent grief and have governance structures.
 */
public class DistrictData {

    public enum DistrictStatus {
        APPLICATION,   // Application submitted, pending review
        ACTIVE,        // Approved and active
        SUSPENDED,     // Temporarily suspended (unpaid taxes, etc.)
        DISBANDED      // Permanently disbanded
    }

    public enum DistrictRole {
        MAYOR,      // Full control — manage members, roles, treasury, laws
        COUNCIL,    // Manage members (except mayor), set laws
        TREASURER,  // Access treasury (deposit/withdraw)
        POLICE,     // Arrest wanted players, manage jail
        BUILDER,    // Build within district boundaries
        MERCHANT,   // Set up shops, trade
        CITIZEN     // Basic membership — live and build
    }

    /**
     * A recognized district with members, treasury, and local governance.
     */
    public static class District {
        private final int id;
        private final String name;
        private final UUID founderUuid;
        private final String worldName;
        private final int centerX, centerZ;
        private DistrictStatus status;
        private String createdAt;
        private final Set<UUID> members = new HashSet<>();
        private final Map<UUID, DistrictRole> roles = new HashMap<>();
        private final Map<String, Boolean> laws = new HashMap<>();
        private long treasuryBalance; // derived from cash_items

        public District(int id, String name, UUID founderUuid, String worldName,
                        int centerX, int centerZ) {
            this.id = id;
            this.name = name;
            this.founderUuid = founderUuid;
            this.worldName = worldName;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.status = DistrictStatus.APPLICATION;
        }

        // Role checks
        public boolean isMayor(UUID uuid) { return roles.getOrDefault(uuid, DistrictRole.CITIZEN) == DistrictRole.MAYOR; }
        public boolean isCouncil(UUID uuid) {
            DistrictRole r = roles.getOrDefault(uuid, DistrictRole.CITIZEN);
            return r == DistrictRole.MAYOR || r == DistrictRole.COUNCIL;
        }
        public boolean isTreasurer(UUID uuid) {
            DistrictRole r = roles.getOrDefault(uuid, DistrictRole.CITIZEN);
            return r == DistrictRole.MAYOR || r == DistrictRole.TREASURER;
        }
        public boolean isPolice(UUID uuid) {
            DistrictRole r = roles.getOrDefault(uuid, DistrictRole.CITIZEN);
            return r == DistrictRole.MAYOR || r == DistrictRole.POLICE;
        }
        public boolean isMember(UUID uuid) { return members.contains(uuid); }

        /** Get the minimum role that can perform an action. */
        public DistrictRole getRole(UUID uuid) {
            return roles.getOrDefault(uuid, DistrictRole.CITIZEN);
        }

        // Getters/Setters
        public int getId() { return id; }
        public String getName() { return name; }
        public UUID getFounderUuid() { return founderUuid; }
        public String getWorldName() { return worldName; }
        public int getCenterX() { return centerX; }
        public int getCenterZ() { return centerZ; }
        public DistrictStatus getStatus() { return status; }
        public void setStatus(DistrictStatus status) { this.status = status; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }
        public Map<UUID, DistrictRole> getRoles() { return Collections.unmodifiableMap(roles); }
        public Map<String, Boolean> getLaws() { return Collections.unmodifiableMap(laws); }
        public long getTreasuryBalance() { return treasuryBalance; }
        public void setTreasuryBalance(long balance) { this.treasuryBalance = balance; }

        public void addMember(UUID uuid, DistrictRole role) { members.add(uuid); roles.put(uuid, role); }
        public void removeMember(UUID uuid) { members.remove(uuid); roles.remove(uuid); }
        public void setRole(UUID uuid, DistrictRole role) { roles.put(uuid, role); }
        public void setLaw(String law, boolean enabled) { laws.put(law, enabled); }
        public int getMemberCount() { return members.size(); }
    }
}
