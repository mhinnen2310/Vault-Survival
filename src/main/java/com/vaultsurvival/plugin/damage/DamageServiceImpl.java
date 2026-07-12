package com.vaultsurvival.plugin.damage;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.SchedulerHelper;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.repair.RepairService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of DamageService.
 *
 * Records block breaks/places by non-district-members in district regions,
 * and automatically restores them after a configurable delay.
 */
public class DamageServiceImpl implements DamageService {

    private final VaultSurvivalPlugin plugin;
    private final RegionService regions;
    private final DistrictService districts;
    private final SchedulerHelper scheduler;
    private final Logger logger;
    private final Map<Integer, DamageData.DamageRecord> pendingDamage = new ConcurrentHashMap<>();
    private final AtomicInteger temporaryIds = new AtomicInteger(-1);
    private BukkitTask restoreTask = null;
    private RepairService repairService = null;

    public DamageServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.districts = plugin.getServiceRegistry().get(DistrictService.class);
        this.scheduler = plugin.getScheduler();
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean shouldTrackDamage(Location location, UUID playerUuid) {
        if (regions == null || districts == null) return false;

        var rules = regions.getRules(location);
        if (!rules.isAllowed(RegionData.RuleFlag.TEMPORARY_DAMAGE_ENABLED)) return false;

        // Find which district owns this location
        var applicableRegions = regions.getRegionsAt(location);
        for (var region : applicableRegions) {
            if (region.getType() == RegionData.RegionType.DISTRICT) {
                DistrictData.District district = findDistrictByRegion(region);
                if (district != null) {
                    // If player is a member, don't track
                    if (playerUuid != null && district.isMember(playerUuid)) return false;
                    return true; // Non-member in a district region
                }
            }
        }
        return false;
    }

    private DistrictData.District findDistrictByRegion(RegionData.Region region) {
        if (districts == null) return null;
        // Match by name pattern: region name is "district_" + district name
        String regionName = region.getName();
        if (regionName.startsWith("district_")) {
            String districtName = regionName.substring("district_".length());
            for (var d : districts.getAllDistricts()) {
                if (d.getName().equalsIgnoreCase(districtName)) return d;
            }
        }
        // Fallback: find district whose center is within this region
        for (var d : districts.getAllDistricts()) {
            if (d.getWorldName().equals(region.getWorldName())
                && region.contains(new Location(Bukkit.getWorld(region.getWorldName()),
                    d.getCenterX(), 64, d.getCenterZ()))) {
                return d;
            }
        }
        return null;
    }

    /** Set the repair service (called by RepairModule after loading). */
    public void setRepairService(RepairService repairService) {
        this.repairService = repairService;
    }

    /** Get the restore delay for a district, considering repair points. */
    private long getRestoreDelayMs(int districtId) {
        if (repairService != null) {
            repairService.consumePoint(districtId);
            return repairService.getRestoreDelayMinutes(districtId) * 60 * 1000L;
        }
        return plugin.getConfigManager().getRestoreNormalDelayMinutes() * 60 * 1000L;
    }

    @Override
    public DamageData.DamageRecord recordBreak(Block block, UUID actorUuid, int districtId) {
        Material original = block.getType();
        if (original == Material.AIR || original == Material.CAVE_AIR || original == Material.VOID_AIR) return null;

        if (original == Material.BEDROCK || original == Material.BARRIER
            || original == Material.COMMAND_BLOCK || original == Material.CHAIN_COMMAND_BLOCK
            || original == Material.REPEATING_COMMAND_BLOCK) return null;

        String blockDataStr = block.getBlockData().getAsString();
        long now = System.currentTimeMillis();
        long restoreTime = now + getRestoreDelayMs(districtId);

        return queueDamage(districtId,block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),original,blockDataStr,DamageData.DamageType.BREAK,actorUuid,now,restoreTime);
    }

    @Override
    public DamageData.DamageRecord recordPlace(Block block, UUID actorUuid, int districtId) {
        // Check what's at this location — if it was already damaged (broken), don't track the place
        // as it would conflict with the break's restore
        for (var record : pendingDamage.values()) {
            if (record.getWorldName().equals(block.getWorld().getName())
                && record.getX() == block.getX() && record.getY() == block.getY() && record.getZ() == block.getZ()
                && !record.isRestored()) {
                return null; // Location already has pending damage, skip
            }
        }

        // Don't track common temporary blocks or natural blocks
        Material mat = block.getType();
        if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR
            || mat == Material.WATER || mat == Material.LAVA || mat == Material.FIRE
            || mat == Material.TNT || mat == Material.SNOW || mat == Material.TALL_GRASS
            || mat == Material.SHORT_GRASS) return null;

        long now = System.currentTimeMillis();
        long restoreTime = now + getRestoreDelayMs(districtId);

        return queueDamage(districtId,block.getWorld().getName(),block.getX(),block.getY(),block.getZ(),Material.AIR,"",DamageData.DamageType.PLACE,actorUuid,now,restoreTime);
    }

    private DamageData.DamageRecord queueDamage(int districtId,String world,int x,int y,int z,Material original,String blockData,DamageData.DamageType type,UUID actor,long timestamp,long restoreAt){
        int temporaryId=temporaryIds.getAndDecrement();var pending=new DamageData.DamageRecord(temporaryId,districtId,world,x,y,z,original,blockData,type,actor,timestamp,restoreAt);pendingDamage.put(temporaryId,pending);
        plugin.getDatabase().write(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO temporary_damage(district_id,world,x,y,z,original_block,original_block_data,damage_type,actor_uuid,timestamp,scheduled_restore) VALUES(?,?,?,?,?,?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS)){statement.setInt(1,districtId);statement.setString(2,world);statement.setInt(3,x);statement.setInt(4,y);statement.setInt(5,z);statement.setString(6,original.name());statement.setString(7,blockData);statement.setString(8,type.name());statement.setString(9,actor.toString());statement.setLong(10,timestamp);statement.setLong(11,restoreAt);statement.executeUpdate();try(ResultSet keys=statement.getGeneratedKeys()){if(!keys.next())throw new SQLException("Damage insert returned no id");return keys.getInt(1);}}}).whenComplete((id,failure)->{pendingDamage.remove(temporaryId);if(failure!=null)logger.log(Level.WARNING,"Failed to persist temporary damage",failure);else pendingDamage.put(id,new DamageData.DamageRecord(id,districtId,world,x,y,z,original,blockData,type,actor,timestamp,restoreAt));});
        return pending;
    }

    @Override
    public int processRestores() {
        long now = System.currentTimeMillis();
        int restored = 0;
        List<DamageData.DamageRecord> toRestore = new ArrayList<>();

        for (var record : pendingDamage.values()) {
            if (!record.isRestored() && record.getScheduledRestoreTime() <= now) {
                toRestore.add(record);
            }
        }

        for (var record : toRestore) {
            if (restoreBlock(record)) {
                restored++;
            }
        }

        if (restored > 0) {
            logger.info("Restored " + restored + " temporarily damaged blocks");
        }
        return restored;
    }

    private boolean restoreBlock(DamageData.DamageRecord record) {
        World world = Bukkit.getWorld(record.getWorldName());
        if (world == null) return false;

        Block block = world.getBlockAt(record.getX(), record.getY(), record.getZ());

        try {
            if (record.isBreak()) {
                // Restore the original block type
                Material restoreMat = record.getOriginalBlock();
                if (restoreMat == Material.AIR || restoreMat == Material.CAVE_AIR) return markRestored(record);

                // For containers, restore as empty (don't restore contents)
                if (record.getBlockClass() == DamageData.BlockClass.CONTAINER) {
                    block.setType(restoreMat);
                    // Container is empty by default — no need to clear inventory
                } else if (record.getOriginalBlockData() != null && !record.getOriginalBlockData().isEmpty()) {
                    try {
                        BlockData data = Bukkit.createBlockData(record.getOriginalBlockData());
                        block.setBlockData(data);
                    } catch (IllegalArgumentException e) {
                        block.setType(restoreMat);
                    }
                } else {
                    block.setType(restoreMat);
                }
            } else {
                // PLACE: restore to air (remove the placed block)
                // Short-circuit if block was already removed by a member before restore fired
                Material currentType = block.getType();
                if (currentType == Material.AIR || currentType == Material.CAVE_AIR || currentType == Material.VOID_AIR) {
                    return markRestored(record);
                }
                // Anti-dupe: don't drop the block being removed
                block.setType(Material.AIR, false); // false = don't apply physics
            }

            // Mark as restored in DB
            try {
                plugin.getDatabase().executeUpdate(
                    "UPDATE temporary_damage SET restored = 1 WHERE id = ?", record.getId());
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to mark damage record as restored", e);
            }

            record.setRestored(true);
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to restore block at " +
                record.getWorldName() + " " + record.getX() + "," + record.getY() + "," + record.getZ(), e);
            return false;
        }
    }

    private boolean markRestored(DamageData.DamageRecord record) {
        record.setRestored(true);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE temporary_damage SET restored = 1 WHERE id = ?", record.getId());
        } catch (SQLException ignored) {}
        return true;
    }

    @Override
    public boolean forceRestore(int recordId) {
        var record = pendingDamage.get(recordId);
        if (record == null || record.isRestored()) return false;
        return restoreBlock(record);
    }

    @Override
    public List<DamageData.DamageRecord> getPendingDamage(int districtId) {
        return pendingDamage.values().stream()
            .filter(r -> r.getDistrictId() == districtId && !r.isRestored())
            .sorted(Comparator.comparingLong(DamageData.DamageRecord::getScheduledRestoreTime))
            .toList();
    }

    @Override
    public List<DamageData.DamageRecord> getDamageAt(Location location) {
        String world = location.getWorld().getName();
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        return pendingDamage.values().stream()
            .filter(r -> r.getWorldName().equals(world) && r.getX() == x && r.getY() == y && r.getZ() == z)
            .toList();
    }

    @Override
    public void loadAll() {
        pendingDamage.clear();
        String sql = "SELECT * FROM temporary_damage WHERE restored = 0";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                loadRecord(rs);
            }
            logger.info("Loaded " + pendingDamage.size() + " pending temporary damage records");

            // Process any that are due
            int restored = processRestores();
            if (restored > 0) {
                logger.info("Restored " + restored + " overdue blocks on startup");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load temporary damage records", e);
        }
    }

    @Override
    public List<DamageData.DamageRecord> getAllDamage() {
        return new ArrayList<>(pendingDamage.values());
    }

    private void loadRecord(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        Material mat = Material.getMaterial(rs.getString("original_block"));
        if (mat == null) mat = Material.AIR;

        var record = new DamageData.DamageRecord(
            id,
            rs.getInt("district_id"),
            rs.getString("world"),
            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
            mat,
            rs.getString("original_block_data"),
            DamageData.DamageType.valueOf(rs.getString("damage_type")),
            UUID.fromString(rs.getString("actor_uuid")),
            rs.getLong("timestamp"),
            rs.getLong("scheduled_restore")
        );
        pendingDamage.put(id, record);
    }

    /** Start the periodic restore checker. */
    public void startRestoreScheduler() {
        // Process restores every 30 seconds
        restoreTask = scheduler.runRepeating(this::processRestores, 600L, 600L); // 30s = 600 ticks
    }

    /** Stop the periodic restore checker. */
    public void stopRestoreScheduler() {
        if (restoreTask != null) {
            scheduler.cancel(restoreTask);
            restoreTask = null;
        }
    }

    /** Get count of pending restores. */
    public int getPendingCount() {
        return (int) pendingDamage.values().stream().filter(r -> !r.isRestored()).count();
    }

    /** Get the district ID from a region. */
    public int getDistrictIdForLocation(Location location) {
        if (regions == null) return -1;
        var applicableRegions = regions.getRegionsAt(location);
        for (var region : applicableRegions) {
            if (region.getType() == RegionData.RegionType.DISTRICT) {
                var district = findDistrictByRegion(region);
                if (district != null) return district.getId();
            }
        }
        return -1;
    }
}
