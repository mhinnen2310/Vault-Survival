package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of RegionService.
 *
 * Regions are stored in SQLite and loaded into memory on startup.
 * Rule resolution: find all regions at a location, sort by priority (highest first),
 * then merge flags — higher priority regions override lower ones.
 */
public class RegionServiceImpl implements RegionService {

    private final VaultSurvivalPlugin plugin;
    private final Logger logger;
    private final Map<Integer, RegionData.Region> regions = new ConcurrentHashMap<>();

    public RegionServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    @Override
    public RegionData.Region createRegion(String name, RegionData.RegionType type, String worldName,
                                           int x1, int y1, int z1, int x2, int y2, int z2, int priority) {
        String sql = "INSERT INTO regions (name, type, world, x1, y1, z1, x2, y2, z2, priority) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, type.name());
            ps.setString(3, worldName);
            ps.setInt(4, x1); ps.setInt(5, y1); ps.setInt(6, z1);
            ps.setInt(7, x2); ps.setInt(8, y2); ps.setInt(9, z2);
            ps.setInt(10, priority);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                RegionData.Region region = new RegionData.Region(id, name, type, worldName,
                    x1, y1, z1, x2, y2, z2, priority);
                regions.put(id, region);
                return region;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create region", e);
        }
        return null;
    }

    @Override
    public boolean deleteRegion(int regionId) {
        regions.remove(regionId);
        try {
            plugin.getDatabase().executeUpdate("DELETE FROM regions WHERE id = ?", regionId);
            plugin.getDatabase().executeUpdate("DELETE FROM region_flags WHERE region_id = ?", regionId);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to delete region", e);
            return false;
        }
    }

    @Override
    public boolean setFlag(int regionId, RegionData.RuleFlag flag, boolean value) {
        RegionData.Region region = regions.get(regionId);
        if (region == null) return false;

        region.setFlag(flag, value);

        // Persist
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO region_flags (region_id, flag_name, flag_value) VALUES (?, ?, ?)",
                regionId, flag.name(), value ? 1 : 0);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set region flag", e);
            return false;
        }
        return true;
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public RegionData.Region getRegion(int regionId) {
        return regions.get(regionId);
    }

    @Override
    public Collection<RegionData.Region> getAllRegions() {
        return new ArrayList<>(regions.values());
    }

    @Override
    public Collection<RegionData.Region> getRegionsAt(Location location) {
        return regions.values().stream()
            .filter(r -> r.contains(location))
            .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
            .toList();
    }

    @Override
    public RegionData.ResolvedRules getRules(Location location) {
        RegionData.ResolvedRules resolved = new RegionData.ResolvedRules();
        // Get all regions at location, highest priority first
        var applicableRegions = getRegionsAt(location);
        // Merge: highest priority sets the rule, lower priorities only fill gaps
        for (RegionData.Region region : applicableRegions) {
            for (var entry : region.getFlags().entrySet()) {
                if (!resolved.getAll().containsKey(entry.getKey())) {
                    resolved.set(entry.getKey(), entry.getValue());
                }
            }
        }
        return resolved;
    }

    @Override
    public boolean isAllowed(Location location, RegionData.RuleFlag flag) {
        return getRules(location).isAllowed(flag);
    }

    @Override
    public boolean isBuildAllowed(Player player, Location location, RegionData.RuleFlag flag) {
        if (flag != RegionData.RuleFlag.BLOCK_PLACE && flag != RegionData.RuleFlag.BLOCK_BREAK) {
            return isAllowed(location, flag);
        }
        // Owner-granted staff build access is session-only, staffmode-bound and
        // audited by Staffmode. It deliberately bypasses static spawn/region
        // build flags, but does not bypass dedicated vault/treasury listeners.
        if (player != null && plugin.hasStaffBuildPermission(player.getUniqueId())) return true;
        if (isAllowed(location, flag)) return true;
        if (player == null) return false;

        // DISTRICT_MARKET intentionally denies visitors through its ordinary
        // BLOCK_* flags. A merchant exception is only valid for the market zone
        // belonging to that player's own district; it grants nothing elsewhere.
        RegionData.Region marketZone = getRegionsAt(location).stream()
            .filter(region -> region.getType() == RegionData.RegionType.DISTRICT_MARKET)
            .findFirst().orElse(null);
        if (marketZone == null) return false;
        Integer districtId = marketDistrictId(marketZone.getName());
        if (districtId == null) return false;

        try {
            DistrictService districts = plugin.getServiceRegistry().get(DistrictService.class);
            DistrictData.District district = districts.getDistrict(districtId);
            if (district == null || district.getStatus() != DistrictData.DistrictStatus.ACTIVE
                || !district.getWorldName().equals(location.getWorld().getName())) return false;
            DistrictData.District ownDistrict = districts.getPlayerDistrict(player.getUniqueId());
            if (ownDistrict == null || ownDistrict.getId() != districtId) return false;
            return districts.canCreateMerchantNpc(player.getUniqueId(), district);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Integer marketDistrictId(String regionName) {
        final String prefix = "district_market_";
        if (regionName == null || !regionName.toLowerCase(Locale.ROOT).startsWith(prefix)) return null;
        try {
            return Integer.parseInt(regionName.substring(prefix.length()));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    // ========================================================================
    // Load
    // ========================================================================

    @Override
    public void loadAll() {
        regions.clear();
        String sql = "SELECT * FROM regions";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                RegionData.RegionType type = RegionData.RegionType.valueOf(rs.getString("type"));
                String world = rs.getString("world");
                int x1 = rs.getInt("x1"), y1 = rs.getInt("y1"), z1 = rs.getInt("z1");
                int x2 = rs.getInt("x2"), y2 = rs.getInt("y2"), z2 = rs.getInt("z2");
                int priority = rs.getInt("priority");

                RegionData.Region region = new RegionData.Region(id, name, type, world,
                    x1, y1, z1, x2, y2, z2, priority);
                regions.put(id, region);
            }

            // Load custom flags
            String flagSql = "SELECT * FROM region_flags";
            try (PreparedStatement fps = conn.prepareStatement(flagSql);
                 ResultSet frs = fps.executeQuery()) {
                while (frs.next()) {
                    int regionId = frs.getInt("region_id");
                    RegionData.RuleFlag flag = RegionData.RuleFlag.valueOf(frs.getString("flag_name"));
                    boolean value = frs.getInt("flag_value") == 1;
                    RegionData.Region region = regions.get(regionId);
                    if (region != null) {
                        region.setFlag(flag, value);
                    }
                }
            }

            logger.info("Loaded " + regions.size() + " regions from database");

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load regions", e);
        }
    }
}
