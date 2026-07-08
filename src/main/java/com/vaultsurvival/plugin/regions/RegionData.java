package com.vaultsurvival.plugin.regions;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

/**
 * Data models for the custom regions/rules system.
 *
 * Regions are cuboid areas in the world with configurable rule flags.
 * Overlapping regions resolve by priority — highest priority wins.
 * This replaces WorldGuard for core gameplay rules.
 */
public class RegionData {

    /** Region categories that determine default rule sets. */
    public enum RegionType {
        SPAWN_PUBLIC,      // Spawn capital — safe, no PvP, no breach
        AUCTION_HALL,      // Auction Hall interior — market interaction allowed
        MINT,              // Mint building — cash minting allowed
        DISTRICT,          // Player district — full district rules
        DISTRICT_PUBLIC,   // Public building within a district
        DISTRICT_STATION,  // Station within a district
        OUTLANDS,          // Wild areas beyond 1500 blocks
        ROAD,              // Protected road infrastructure
        STATION_ROUTE,     // Paid station travel route
        NO_BREACH_ZONE,    // Breaching disallowed
        NO_CASH_DROP,      // Cash cannot be dropped
        CUSTOM             // Fully custom flags
    }

    /** Individual rule flags that can be toggled per region. */
    public enum RuleFlag {
        PVP_ENABLED,
        CASH_DROP_ENABLED,
        BREACH_ALLOWED,
        TEMPORARY_DAMAGE_ENABLED,
        VISITOR_BUILD_ALLOWED,
        MARKET_INTERACTION_ALLOWED,
        MINT_INTERACTION_ALLOWED,
        TELEPORT_WITH_CASH_ALLOWED,
        STATION_USE_ALLOWED,
        BLOCK_PLACE,
        BLOCK_BREAK,
        ENTITY_DAMAGE,
        EXPLOSION_DAMAGE
    }

    /**
     * A cuboid region in the world with rule flags.
     */
    public static class Region {
        private final int id;
        private final String name;
        private final RegionType type;
        private final String worldName;
        private final int x1, y1, z1; // lower corner
        private final int x2, y2, z2; // upper corner
        private final int priority; // higher = wins in conflicts
        private final Map<RuleFlag, Boolean> flags;

        public Region(int id, String name, RegionType type, String worldName,
                      int x1, int y1, int z1, int x2, int y2, int z2, int priority) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.worldName = worldName;
            // Normalize: ensure x1 <= x2, etc.
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
            this.priority = priority;
            this.flags = new EnumMap<>(RuleFlag.class);
            // Set defaults based on type
            applyTypeDefaults(type);
        }

        private void applyTypeDefaults(RegionType type) {
            switch (type) {
                case SPAWN_PUBLIC -> {
                    flags.put(RuleFlag.PVP_ENABLED, false);
                    flags.put(RuleFlag.CASH_DROP_ENABLED, true);
                    flags.put(RuleFlag.BREACH_ALLOWED, false);
                    flags.put(RuleFlag.BLOCK_PLACE, false);
                    flags.put(RuleFlag.BLOCK_BREAK, false);
                }
                case AUCTION_HALL -> {
                    flags.put(RuleFlag.PVP_ENABLED, false);
                    flags.put(RuleFlag.MARKET_INTERACTION_ALLOWED, true);
                    flags.put(RuleFlag.BLOCK_PLACE, false);
                    flags.put(RuleFlag.BLOCK_BREAK, false);
                }
                case MINT -> {
                    flags.put(RuleFlag.MINT_INTERACTION_ALLOWED, true);
                    flags.put(RuleFlag.PVP_ENABLED, false);
                    flags.put(RuleFlag.BLOCK_PLACE, false);
                    flags.put(RuleFlag.BLOCK_BREAK, false);
                }
                case DISTRICT -> {
                    flags.put(RuleFlag.PVP_ENABLED, true);
                    flags.put(RuleFlag.BREACH_ALLOWED, true);
                    flags.put(RuleFlag.TEMPORARY_DAMAGE_ENABLED, true);
                    flags.put(RuleFlag.VISITOR_BUILD_ALLOWED, false);
                    flags.put(RuleFlag.CASH_DROP_ENABLED, true);
                }
                case OUTLANDS -> {
                    flags.put(RuleFlag.PVP_ENABLED, true);
                    flags.put(RuleFlag.BREACH_ALLOWED, true);
                    flags.put(RuleFlag.BLOCK_PLACE, true);
                    flags.put(RuleFlag.BLOCK_BREAK, true);
                    flags.put(RuleFlag.CASH_DROP_ENABLED, true);
                }
                case ROAD, STATION_ROUTE -> {
                    flags.put(RuleFlag.BLOCK_PLACE, false);
                    flags.put(RuleFlag.BLOCK_BREAK, false);
                    flags.put(RuleFlag.PVP_ENABLED, true);
                }
                case NO_BREACH_ZONE -> {
                    flags.put(RuleFlag.BREACH_ALLOWED, false);
                }
                case NO_CASH_DROP -> {
                    flags.put(RuleFlag.CASH_DROP_ENABLED, false);
                }
                default -> {} // CUSTOM — no defaults
            }
        }

        /** Check if a location is inside this region. */
        public boolean contains(Location loc) {
            if (!loc.getWorld().getName().equals(worldName)) return false;
            int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        /** Get a rule flag value, or null if not set. */
        public Boolean getFlag(RuleFlag flag) {
            return flags.get(flag);
        }

        /** Set a rule flag. */
        public void setFlag(RuleFlag flag, boolean value) {
            flags.put(flag, value);
        }

        // Getters
        public int getId() { return id; }
        public String getName() { return name; }
        public RegionType getType() { return type; }
        public String getWorldName() { return worldName; }
        public int getX1() { return x1; }
        public int getY1() { return y1; }
        public int getZ1() { return z1; }
        public int getX2() { return x2; }
        public int getY2() { return y2; }
        public int getZ2() { return z2; }
        public int getPriority() { return priority; }
        public Map<RuleFlag, Boolean> getFlags() { return Collections.unmodifiableMap(flags); }
    }

    /**
     * Resolved rules for a specific location, combining all applicable regions by priority.
     */
    public static class ResolvedRules {
        private final Map<RuleFlag, Boolean> rules = new EnumMap<>(RuleFlag.class);

        public Boolean get(RuleFlag flag) {
            return rules.get(flag);
        }

        public boolean isAllowed(RuleFlag flag) {
            return rules.getOrDefault(flag, true); // default: allowed if not specified
        }

        public void set(RuleFlag flag, boolean value) {
            rules.put(flag, value);
        }

        public Map<RuleFlag, Boolean> getAll() {
            return Collections.unmodifiableMap(rules);
        }
    }
}
