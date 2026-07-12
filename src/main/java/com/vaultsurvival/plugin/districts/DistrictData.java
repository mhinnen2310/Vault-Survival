package com.vaultsurvival.plugin.districts;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DistrictData {

    /** A rectangular district claim whose persisted source of truth is exact block coordinates. */
    public record BlockClaim(String worldName, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        public BlockClaim {
            if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("worldName");
            int lowX = Math.min(minBlockX, maxBlockX);
            int highX = Math.max(minBlockX, maxBlockX);
            int lowZ = Math.min(minBlockZ, maxBlockZ);
            int highZ = Math.max(minBlockZ, maxBlockZ);
            minBlockX = lowX;
            maxBlockX = highX;
            minBlockZ = lowZ;
            maxBlockZ = highZ;
        }

        public int widthBlocks() { return maxBlockX - minBlockX + 1; }
        public int depthBlocks() { return maxBlockZ - minBlockZ + 1; }
        public long areaBlocks() { return (long) widthBlocks() * depthBlocks(); }
        public int centerBlockX() { return minBlockX + (maxBlockX - minBlockX) / 2; }
        public int centerBlockZ() { return minBlockZ + (maxBlockZ - minBlockZ) / 2; }
        public boolean contains(int blockX, int blockZ) {
            return blockX >= minBlockX && blockX <= maxBlockX && blockZ >= minBlockZ && blockZ <= maxBlockZ;
        }
        public boolean contains(BlockClaim other) {
            return other != null && worldName.equals(other.worldName)
                && contains(other.minBlockX, other.minBlockZ) && contains(other.maxBlockX, other.maxBlockZ);
        }
    }

    /** Compatibility adapter for integrations compiled against the former whole-chunk model. */
    @Deprecated
    public record ChunkClaim(String worldName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        public BlockClaim toBlockClaim() {
            return new BlockClaim(worldName, minChunkX << 4, minChunkZ << 4,
                (maxChunkX << 4) + 15, (maxChunkZ << 4) + 15);
        }
    }

    public enum DistrictStatus {
        APPLICATION,
        ACTIVE,
        SUSPENDED,
        DISBANDED
    }

    public enum DistrictRole {
        MAYOR,
        CO_MAYOR,
        TREASURER,
        MERCHANT,
        FARMER,
        POLICE,
        BUILDER,
        DIPLOMAT,
        WARDEN,
        MEMBER,
        GUEST,
        VISITOR
    }

    public enum LawKey {
        TRESPASSING_ILLEGAL,
        VISITOR_PVP_ILLEGAL,
        MARKET_PVP_ILLEGAL,
        TOWN_HALL_PVP_ILLEGAL,
        CHEST_THEFT_ILLEGAL,
        VISITOR_BLOCK_DAMAGE_ILLEGAL,
        VISITOR_BLOCK_PLACEMENT_ILLEGAL,
        VAULT_BREACH_ILLEGAL,
        ARMED_VISITORS_ILLEGAL,
        TREASURY_LOITERING_ILLEGAL,
        UNLICENSED_MERCHANT_ILLEGAL,
        ASSAULT_POLICE_ILLEGAL,
        RESISTING_ARREST_ILLEGAL,
        JAIL_ESCAPE_ILLEGAL,
        MARKET_OBSTRUCTION_ILLEGAL,
        ROAD_BLOCKING_ILLEGAL,
        FIRE_LAVA_PLACEMENT_ILLEGAL,
        EXPLOSION_USE_ILLEGAL,
        ENEMY_TRESPASSING_ILLEGAL,
        BOUNTY_HUNTERS_ALLOWED,
        MERCENARIES_ALLOWED
    }

    public static class District {
        private final int id;
        private final String name;
        private final UUID founderUuid;
        private final String worldName;
        private final int centerX;
        private final int centerZ;
        private DistrictStatus status;
        private String createdAt;
        private final Set<UUID> members = new HashSet<>();
        private final Map<UUID, EnumSet<DistrictRole>> roles = new HashMap<>();
        private final Map<String, Boolean> laws = new HashMap<>();
        private final Map<String, Boolean> pendingLaws = new HashMap<>();
        private long treasuryBalance;

        public District(int id, String name, UUID founderUuid, String worldName, int centerX, int centerZ) {
            this.id = id;
            this.name = name;
            this.founderUuid = founderUuid;
            this.worldName = worldName;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.status = DistrictStatus.APPLICATION;
        }

        public boolean isMayor(UUID uuid) {
            return hasRole(uuid, DistrictRole.MAYOR);
        }

        public boolean isCouncil(UUID uuid) {
            return hasRole(uuid, DistrictRole.MAYOR) || hasRole(uuid, DistrictRole.CO_MAYOR);
        }

        public boolean isTreasurer(UUID uuid) {
            return isCouncil(uuid) || hasRole(uuid, DistrictRole.TREASURER);
        }

        public boolean isPolice(UUID uuid) {
            return isCouncil(uuid) || hasRole(uuid, DistrictRole.POLICE) || hasRole(uuid, DistrictRole.WARDEN);
        }

        public boolean isMember(UUID uuid) {
            return members.contains(uuid);
        }

        public boolean hasRole(UUID uuid, DistrictRole role) {
            return getRoles(uuid).contains(role);
        }

        public DistrictRole getRole(UUID uuid) {
            return getHighestRole(uuid);
        }

        public Set<DistrictRole> getRoles(UUID uuid) {
            if (!members.contains(uuid)) {
                return EnumSet.of(DistrictRole.VISITOR);
            }
            EnumSet<DistrictRole> playerRoles = roles.get(uuid);
            if (playerRoles == null || playerRoles.isEmpty()) {
                return EnumSet.of(DistrictRole.MEMBER);
            }
            return EnumSet.copyOf(playerRoles);
        }

        public DistrictRole getHighestRole(UUID uuid) {
            return getRoles(uuid).stream()
                .max(Comparator.comparingInt(District::roleWeight))
                .orElse(DistrictRole.VISITOR);
        }

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

        public Map<UUID, DistrictRole> getRoles() {
            Map<UUID, DistrictRole> highest = new HashMap<>();
            for (UUID uuid : members) {
                highest.put(uuid, getHighestRole(uuid));
            }
            return Collections.unmodifiableMap(highest);
        }

        public Map<UUID, Set<DistrictRole>> getRoleSets() {
            Map<UUID, Set<DistrictRole>> copy = new HashMap<>();
            for (UUID uuid : members) {
                copy.put(uuid, Collections.unmodifiableSet(getRoles(uuid)));
            }
            return Collections.unmodifiableMap(copy);
        }

        public Map<String, Boolean> getLaws() { return Collections.unmodifiableMap(laws); }
        public Map<String, Boolean> getPendingLaws() { return Collections.unmodifiableMap(pendingLaws); }
        public long getTreasuryBalance() { return treasuryBalance; }
        public void setTreasuryBalance(long balance) { this.treasuryBalance = balance; }

        public void addMember(UUID uuid, DistrictRole role) {
            members.add(uuid);
            roles.computeIfAbsent(uuid, ignored -> EnumSet.noneOf(DistrictRole.class)).add(normalizeMemberRole(role));
        }

        public void removeMember(UUID uuid) {
            members.remove(uuid);
            roles.remove(uuid);
        }

        public void setRole(UUID uuid, DistrictRole role) {
            members.add(uuid);
            roles.computeIfAbsent(uuid, ignored -> EnumSet.noneOf(DistrictRole.class)).add(normalizeMemberRole(role));
        }

        public void removeRole(UUID uuid, DistrictRole role) {
            EnumSet<DistrictRole> playerRoles = roles.get(uuid);
            if (playerRoles == null) return;
            playerRoles.remove(role);
            if (playerRoles.isEmpty() && members.contains(uuid)) {
                playerRoles.add(DistrictRole.MEMBER);
            }
        }

        public void setLaw(String law, boolean enabled) { laws.put(law, enabled); }
        public void setPendingLaw(String law, boolean enabled) { pendingLaws.put(law, enabled); }
        public void clearPendingLaw(String law) { pendingLaws.remove(law); }
        public int getMemberCount() { return members.size(); }
        public int getDistrictRoleCount(UUID uuid) { return getRoles(uuid).contains(DistrictRole.VISITOR) ? 0 : getRoles(uuid).size(); }

        public static int roleWeight(DistrictRole role) {
            return switch (role) {
                case MAYOR -> 100;
                case CO_MAYOR -> 90;
                case TREASURER -> 70;
                case POLICE -> 65;
                case WARDEN -> 60;
                case DIPLOMAT -> 55;
                case MERCHANT -> 50;
                case FARMER -> 48;
                case BUILDER -> 45;
                case MEMBER -> 20;
                case GUEST -> 10;
                case VISITOR -> 0;
            };
        }

        private DistrictRole normalizeMemberRole(DistrictRole role) {
            return role == DistrictRole.VISITOR ? DistrictRole.GUEST : role;
        }
    }
}
