package com.vaultsurvival.plugin.area;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.spawncity.SpawnCityService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CurrentAreaService {

    private final VaultSurvivalPlugin plugin;

    public CurrentAreaService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public CurrentAreaContext resolve(Player player) {
        Location location = player.getLocation();
        List<RegionData.Region> regions = getRegions(location);
        Map<RegionData.RuleFlag, Boolean> flags = getFlags(location);
        DistrictData.District district = findDistrict(location, regions);
        CurrentAreaContext.AreaType areaType = determineAreaType(location, regions, district);
        String areaName = determineAreaName(areaType, regions, district);

        return new CurrentAreaContext(
            areaType,
            areaName,
            district,
            districtStatus(player, district),
            regions,
            flags,
            lawSummary(district),
            "Risk model pending. PvP, breach, and law evidence rules are shown by active flags.",
            servicePlaceholder("Market"),
            servicePlaceholder("Jobs"),
            servicePlaceholder("Station")
        );
    }

    private List<RegionData.Region> getRegions(Location location) {
        try {
            RegionService regionService = plugin.getServiceRegistry().get(RegionService.class);
            return regionService.getRegionsAt(location).stream()
                .sorted(Comparator.comparingInt(RegionData.Region::getPriority).reversed())
                .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<RegionData.RuleFlag, Boolean> getFlags(Location location) {
        try {
            RegionService regionService = plugin.getServiceRegistry().get(RegionService.class);
            return new EnumMap<>(regionService.getRules(location).getAll());
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private DistrictData.District findDistrict(Location location, List<RegionData.Region> regions) {
        try {
            DistrictService districtService = plugin.getServiceRegistry().get(DistrictService.class);
            List<DistrictData.District> active = districtService.getAllDistricts().stream()
                .filter(d -> d.getStatus() == DistrictData.DistrictStatus.ACTIVE)
                .filter(d -> d.getWorldName().equals(location.getWorld().getName()))
                .toList();

            for (RegionData.Region region : regions) {
                if (region.getType() != RegionData.RegionType.DISTRICT) continue;
                String regionName = normalize(region.getName().replaceFirst("(?i)^district_", ""));
                for (DistrictData.District district : active) {
                    if (regionName.equals(normalize(district.getName()))) {
                        return district;
                    }
                }
            }

            int radius = 250;
            int x = location.getBlockX();
            int z = location.getBlockZ();
            for (DistrictData.District district : active) {
                if (Math.abs(x - district.getCenterX()) <= radius && Math.abs(z - district.getCenterZ()) <= radius) {
                    return district;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private CurrentAreaContext.AreaType determineAreaType(Location location, List<RegionData.Region> regions,
                                                          DistrictData.District district) {
        for (RegionData.Region region : regions) {
            switch (region.getType()) {
                case AUCTION_HALL -> {
                    return CurrentAreaContext.AreaType.AUCTION_HALL;
                }
                case MINT -> {
                    return CurrentAreaContext.AreaType.MINT;
                }
                case DISTRICT_STATION, STATION_ROUTE -> {
                    return CurrentAreaContext.AreaType.STATION;
                }
                case DISTRICT_PUBLIC -> {
                    return CurrentAreaContext.AreaType.MARKET_ZONE;
                }
                case SPAWN_PUBLIC -> {
                    return CurrentAreaContext.AreaType.SPAWN_CITY;
                }
                default -> {
                }
            }
        }
        if (district != null) {
            return CurrentAreaContext.AreaType.DISTRICT;
        }
        if (isSpawnCity(location)) {
            return CurrentAreaContext.AreaType.SPAWN_CITY;
        }
        if (isOutlands(location)) {
            return CurrentAreaContext.AreaType.OUTLANDS;
        }
        return regions.isEmpty() ? CurrentAreaContext.AreaType.OUTLANDS : CurrentAreaContext.AreaType.OTHER_REGION;
    }

    private String determineAreaName(CurrentAreaContext.AreaType areaType, List<RegionData.Region> regions,
                                     DistrictData.District district) {
        if (district != null && areaType == CurrentAreaContext.AreaType.DISTRICT) {
            return district.getName();
        }
        if (!regions.isEmpty()) {
            return regions.get(0).getName();
        }
        if (areaType == CurrentAreaContext.AreaType.SPAWN_CITY) {
            try {
                return plugin.getServiceRegistry().get(SpawnCityService.class).getCityName();
            } catch (RuntimeException ignored) {
                return "Spawn City";
            }
        }
        return areaType == CurrentAreaContext.AreaType.OUTLANDS ? "Outlands" : "Unknown Area";
    }

    private boolean isSpawnCity(Location location) {
        try {
            SpawnCityService spawnCity = plugin.getServiceRegistry().get(SpawnCityService.class);
            Location spawn = spawnCity.getSpawnLocation();
            if (spawn == null || spawn.getWorld() == null || location.getWorld() == null) return false;
            return spawn.getWorld().equals(location.getWorld()) && spawn.distanceSquared(location) <= 250 * 250;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isOutlands(Location location) {
        try {
            SpawnCityService spawnCity = plugin.getServiceRegistry().get(SpawnCityService.class);
            Location spawn = spawnCity.getSpawnLocation();
            if (spawn == null) spawn = location.getWorld().getSpawnLocation();
            return !spawn.getWorld().equals(location.getWorld())
                || spawn.distance(location) >= plugin.getConfigManager().getDistrictMinDistanceFromSpawn();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private String districtStatus(Player player, DistrictData.District district) {
        if (district == null) {
            return "VISITOR";
        }
        if (!district.isMember(player.getUniqueId())) {
            return "VISITOR";
        }
        DistrictData.DistrictRole role = district.getRole(player.getUniqueId());
        return switch (role) {
            case MAYOR -> "MAYOR";
            case CO_MAYOR -> "CO_MAYOR";
            case TREASURER -> "TREASURER";
            case POLICE -> "POLICE";
            case MERCHANT -> "MERCHANT";
            case BUILDER, DIPLOMAT, WARDEN, MEMBER -> "MEMBER";
            case GUEST -> "GUEST";
            case VISITOR -> "VISITOR";
        };
    }

    private String lawSummary(DistrictData.District district) {
        if (district == null) {
            return "No district laws apply here yet.";
        }
        if (district.getLaws().isEmpty()) {
            return "Law service is not fully active. Existing district law map is empty.";
        }
        List<String> enabled = new ArrayList<>();
        district.getLaws().forEach((law, value) -> {
            if (Boolean.TRUE.equals(value)) enabled.add(law);
        });
        return enabled.isEmpty() ? "No active district laws configured." : "Active laws: " + String.join(", ", enabled);
    }

    private String servicePlaceholder(String serviceName) {
        return serviceName + " context is planned and locked until that service is ready.";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
