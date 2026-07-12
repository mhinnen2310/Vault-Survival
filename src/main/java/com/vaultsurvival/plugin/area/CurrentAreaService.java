package com.vaultsurvival.plugin.area;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.spawncity.SpawnCityService;
import com.vaultsurvival.plugin.rail.RailData;
import com.vaultsurvival.plugin.rail.RailService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
            riskSummary(player, areaType, district, flags),
            marketSummary(areaType, district, flags),
            jobSummary(district),
            stationSummary(location, district)
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
                case DISTRICT_STATION, STATION_ROUTE, STATION_PLATFORM, STATION_ARRIVAL, TRAIN_INTERIOR -> {
                    return CurrentAreaContext.AreaType.STATION;
                }
                case DISTRICT_PUBLIC, DISTRICT_MARKET, BLACK_MARKET -> {
                    return CurrentAreaContext.AreaType.MARKET_ZONE;
                }
                case SPAWN_PUBLIC, SPAWN_CITY, TOWN_HALL, JAIL, POLICE_STATION, TREASURY,
                     VAULT_ZONE, REPAIR_ZONE, JOB_BOARD -> {
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
            case FARMER -> "FARMER";
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

    private String riskSummary(Player player, CurrentAreaContext.AreaType areaType, DistrictData.District district,
                               Map<RegionData.RuleFlag, Boolean> flags) {
        int score = areaType == CurrentAreaContext.AreaType.OUTLANDS ? 25 : 5;
        if (Boolean.TRUE.equals(flags.get(RegionData.RuleFlag.PVP_ENABLED))) score += 25;
        if (Boolean.TRUE.equals(flags.get(RegionData.RuleFlag.BREACH_ALLOWED))) score += 20;
        if (Boolean.TRUE.equals(flags.get(RegionData.RuleFlag.TEMPORARY_DAMAGE_ENABLED))) score += 10;
        int activeEvidence = district == null ? 0 : count(
            "SELECT COUNT(*) FROM district_evidence WHERE district_id=? AND status IN ('UNHANDLED','ACTIVE')", district.getId());
        int playerEvidence = district == null ? 0 : count(
            "SELECT COUNT(*) FROM district_evidence WHERE district_id=? AND player_uuid=? AND status IN ('UNHANDLED','ACTIVE')",
            district.getId(), player.getUniqueId().toString());
        boolean wanted = district != null && count(
            "SELECT COUNT(*) FROM wanted_players WHERE district_id=? AND criminal_uuid=? AND arrested=0",
            district.getId(), player.getUniqueId().toString()) > 0;
        score += Math.min(20, activeEvidence * 2);
        if (wanted) score += 25;
        score = Math.min(100, score);
        String level = score >= 75 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 25 ? "ELEVATED" : "LOW";
        return level + " (" + score + "/100) | PvP " + state(flags, RegionData.RuleFlag.PVP_ENABLED)
            + ", breach " + state(flags, RegionData.RuleFlag.BREACH_ALLOWED)
            + ", local evidence " + activeEvidence + ", your evidence " + playerEvidence
            + (wanted ? ", WANTED" : "");
    }

    private String marketSummary(CurrentAreaContext.AreaType areaType, DistrictData.District district,
                                 Map<RegionData.RuleFlag, Boolean> flags) {
        if (areaType == CurrentAreaContext.AreaType.AUCTION_HALL) {
            return "Auction Hall | " + count("SELECT COUNT(*) FROM auction_listings WHERE status='ACTIVE'") + " active listing(s).";
        }
        if (district != null) {
            int shops = count("SELECT COUNT(*) FROM merchant_shops WHERE district_id=?", district.getId());
            int orders = count("SELECT COUNT(*) FROM merchant_orders WHERE status IN ('ACTIVE','PARTIALLY_FILLED')");
            return district.getName() + " market | interaction " + state(flags, RegionData.RuleFlag.MARKET_INTERACTION_ALLOWED)
                + " | " + shops + " shop(s), " + orders + " active network order(s).";
        }
        return "No district market applies here. Global Auction Hall listings: "
            + count("SELECT COUNT(*) FROM auction_listings WHERE status='ACTIVE'") + ".";
    }

    private String jobSummary(DistrictData.District district) {
        int spawnJobs = count("SELECT COUNT(*) FROM spawn_city_jobs WHERE enabled=1");
        if (district == null) return spawnJobs + " Spawn City job(s) are available; no local district job board applies here.";
        int local = count("SELECT COUNT(*) FROM district_jobs WHERE district_id=? AND status='ACTIVE'", district.getId());
        return local + " active " + district.getName() + " job(s) and " + spawnJobs + " enabled Spawn City job(s).";
    }

    private String stationSummary(Location location, DistrictData.District district) {
        try {
            RailService rail = plugin.getServiceRegistry().get(RailService.class);
            RailData.Station station = district == null ? rail.getAllStations().stream()
                .filter(candidate -> candidate.getWorldName().equals(location.getWorld().getName()))
                .filter(candidate -> location.getX() >= candidate.getPlatMinX() && location.getX() <= candidate.getPlatMaxX()
                    && location.getY() >= candidate.getPlatMinY() && location.getY() <= candidate.getPlatMaxY()
                    && location.getZ() >= candidate.getPlatMinZ() && location.getZ() <= candidate.getPlatMaxZ())
                .findFirst().orElse(null) : rail.getStationByDistrict(district.getId());
            if (station == null) return "No station serves this area yet.";
            int routes = rail.getRoutesFrom(station.getId()).size();
            return station.getName() + " | " + station.getStatus() + " | " + routes + " departing route(s) | base ticket " + station.getTicketPrice() + ".";
        } catch (RuntimeException ignored) {
            return "No active rail station context was found for this area.";
        }
    }

    private String state(Map<RegionData.RuleFlag, Boolean> flags, RegionData.RuleFlag flag) {
        return Boolean.TRUE.equals(flags.get(flag)) ? "enabled" : "disabled";
    }

    private int count(String sql, Object... values) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            ResultSet result = statement.executeQuery();
            return result.next() ? result.getInt(1) : 0;
        } catch (Exception ignored) { return 0; }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
