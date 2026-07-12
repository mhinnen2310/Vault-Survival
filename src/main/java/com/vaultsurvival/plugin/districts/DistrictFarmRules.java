package com.vaultsurvival.plugin.districts;

import java.util.List;

final class DistrictFarmRules {
    private DistrictFarmRules() {}

    static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < 3 || name.length() > 32) {
            throw new IllegalArgumentException("Farm name must contain 3 to 32 characters");
        }
        if (!name.matches("[A-Za-z0-9 _-]+")) {
            throw new IllegalArgumentException("Farm name may only contain letters, numbers, spaces, _ and -");
        }
        return name;
    }

    static long footprint(DistrictFarmService.FarmZone zone) {
        return (long) (zone.maxX() - zone.minX() + 1) * (zone.maxZ() - zone.minZ() + 1);
    }

    static void requireAllowedFootprint(DistrictFarmService.FarmZone zone, DistrictData.BlockClaim claim,
                                        double configuredFraction) {
        long claimBlocks = claim.areaBlocks();
        double fraction = Math.max(0.01D, Math.min(1.0D, configuredFraction));
        long maximum = Math.max(1L, (long) Math.floor(claimBlocks * fraction));
        if (footprint(zone) > maximum) {
            throw new IllegalArgumentException("Farm footprint is " + footprint(zone)
                + " blocks; this district allows at most " + maximum + " (" + Math.round(fraction * 100) + "%)");
        }
    }

    static int levelValue(List<Integer> configured, int level, int fallbackPerLevel) {
        int safeLevel = Math.max(1, Math.min(5, level));
        if (configured != null && configured.size() >= 5) return Math.max(0, configured.get(safeLevel - 1));
        return fallbackPerLevel * safeLevel;
    }
}
