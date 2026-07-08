package com.vaultsurvival.plugin.repair;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.SchedulerHelper;
import com.vaultsurvival.plugin.damage.DamageService;
import com.vaultsurvival.plugin.damage.DamageData;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.npc.NpcService;
import com.vaultsurvival.plugin.npc.NpcData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of RepairService.
 *
 * Manages repair points per district, processes daily resets,
 * pays wages from district treasuries, and determines restore delays.
 */
public class RepairServiceImpl implements RepairService {

    private final VaultSurvivalPlugin plugin;
    private final DamageService damage;
    private final DistrictService districts;
    private final SchedulerHelper scheduler;
    private final Logger logger;
    private final Map<Integer, RepairData.DistrictRepairState> states = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> districtNpcIds = new ConcurrentHashMap<>(); // districtId -> npcId
    private BukkitTask dailyTask = null;

    public RepairServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.damage = plugin.getServiceRegistry().get(DamageService.class);
        this.districts = plugin.getServiceRegistry().get(DistrictService.class);
        this.scheduler = plugin.getScheduler();
        this.logger = plugin.getLogger();
    }

    @Override
    public RepairData.DistrictRepairState getState(int districtId) {
        return states.computeIfAbsent(districtId, id -> {
            int dailyPoints = plugin.getConfigManager().getRestoreDailyPoints();
            var state = new RepairData.DistrictRepairState(id, dailyPoints, 0, System.currentTimeMillis(), false);
            saveState(state);
            return state;
        });
    }

    @Override
    public int getRestoreDelayMinutes(int districtId) {
        var state = getState(districtId);
        if (state.isExhausted()) {
            return plugin.getConfigManager().getRestoreExhaustedDelayMinutes();
        }
        return plugin.getConfigManager().getRestoreNormalDelayMinutes();
    }

    @Override
    public void consumePoint(int districtId) {
        var state = getState(districtId);
        state.consumePoint();
        saveState(state);
    }

    @Override
    public int processDailyReset() {
        int reset = 0;
        for (var state : states.values()) {
            if (state.isNewDay()) {
                int dailyPoints = plugin.getConfigManager().getRestoreDailyPoints();
                int dailyWage = plugin.getConfigManager().getRestoreDailyWage();

                // Attempt to pay wage
                boolean wagePaid = tryPayWage(state.getDistrictId(), dailyWage);

                if (wagePaid) {
                    state.resetPoints(dailyPoints);
                    state.markWagePaid();
                    reset++;
                    logger.info("District #" + state.getDistrictId() + " repair points reset to " + dailyPoints);
                } else {
                    // Wage couldn't be paid — points stay at 0 and remain exhausted
                    state.setExhausted(true);
                    logger.info("District #" + state.getDistrictId() + " wage unpaid — repair points remain exhausted");
                }
                saveState(state);
            }
        }
        return reset;
    }

    private boolean tryPayWage(int districtId, int amount) {
        if (districts == null) return false; // can't pay without district service

        DistrictData.District district = districts.getDistrict(districtId);
        if (district == null || district.getStatus() != DistrictData.DistrictStatus.ACTIVE) return false;

        long treasury = getTreasuryBalance(districtId);
        if (treasury < amount) return false;

        // Spend treasury cash for wage (mark as SPENT)
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                String sql = "SELECT cash_uuid, amount FROM cash_items " +
                             "WHERE state = 'IN_DISTRICT_TREASURY' AND location_id = ? ORDER BY amount ASC";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, String.valueOf(districtId));
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmt = rs.getLong("amount");
                        if (cashAmt <= remaining) {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET state = 'SPENT' WHERE cash_uuid = ?")) {
                                up.setString(1, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining -= cashAmt;
                        } else {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET amount = ? WHERE cash_uuid = ?")) {
                                up.setLong(1, cashAmt - remaining);
                                up.setString(2, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining = 0;
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to pay wage for district #" + districtId, e);
            return false;
        }

        logger.info("Paid daily wage of " + amount + " for district #" + districtId);
        return true;
    }

    private long getTreasuryBalance(int districtId) {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items " +
                     "WHERE state = 'IN_DISTRICT_TREASURY' AND location_id = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(districtId));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get treasury balance", e);
        }
        return 0;
    }

    @Override
    public boolean payWage(int districtId, UUID actorUuid) {
        int wage = plugin.getConfigManager().getRestoreDailyWage();
        DistrictData.District district = districts != null ? districts.getDistrict(districtId) : null;

        if (district != null && !district.isTreasurer(actorUuid) && !district.isMayor(actorUuid)) {
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().error("Only the mayor or treasurer can pay wages."));
            }
            return false;
        }

        boolean paid = tryPayWage(districtId, wage);
        if (paid) {
            var state = getState(districtId);
            state.markWagePaid();
            int dailyPoints = plugin.getConfigManager().getRestoreDailyPoints();
            state.resetPoints(dailyPoints);
            saveState(state);

            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().success("Wage paid! Repair points reset to " + dailyPoints + "."));
            }
        }
        return paid;
    }

    @Override
    public boolean emergencyRepair(int districtId, int damageRecordId, UUID actorUuid) {
        DistrictData.District district = districts != null ? districts.getDistrict(districtId) : null;
        if (district != null && !district.isCouncil(actorUuid)) {
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().error("Only council+ can use emergency repair."));
            }
            return false;
        }

        var state = getState(districtId);

        // Cost 5 repair points for emergency repair
        if (state.getRepairPoints() < 5) {
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().error("Not enough repair points. Need 5, have " + state.getRepairPoints() + "."));
            }
            return false;
        }

        // Verify the damage record belongs to this district
        List<DamageData.DamageRecord> pending = damage.getPendingDamage(districtId);
        boolean belongsToDistrict = pending.stream().anyMatch(r -> r.getId() == damageRecordId);
        if (!belongsToDistrict) {
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().error("That damage record does not belong to your district."));
            }
            return false;
        }

        boolean restored = damage.forceRestore(damageRecordId);
        if (restored) {
            for (int i = 0; i < 5; i++) state.consumePoint();
            saveState(state);
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().success("Emergency repair complete! " + state.getRepairPoints() + " points remaining."));
            }
        } else {
            Player p = Bukkit.getPlayer(actorUuid);
            if (p != null) {
                p.sendMessage(plugin.getMessageFormatter().error("Damage record not found or already restored."));
            }
        }
        return restored;
    }

    @Override
    public void createRepairmanNpc(int districtId, Location location) {
        NpcService npcService = plugin.getServiceRegistry().get(NpcService.class);
        if (npcService == null) {
            logger.warning("Cannot create repairman NPC — NPC system not loaded");
            return;
        }

        DistrictData.District district = districts != null ? districts.getDistrict(districtId) : null;
        String districtName = district != null ? district.getName() : ("District #" + districtId);

        // Remove old repairman NPC if exists
        Integer oldNpcId = districtNpcIds.get(districtId);
        if (oldNpcId != null) {
            npcService.removeNpc(oldNpcId);
        }

        // Create repairman NPC with Notch skin
        var npc = npcService.createNpc("Repairman", "Notch", location,
            NpcData.ActionType.COMMAND, "repair status");

        if (npc != null) {
            districtNpcIds.put(districtId, npc.getId());
            logger.info("Created repairman NPC #" + npc.getId() + " for " + districtName);
        }
    }

    @Override
    public List<RepairData.DistrictRepairState> getAllStates() {
        return new ArrayList<>(states.values());
    }

    @Override
    public void loadAll() {
        states.clear();
        String sql = "SELECT * FROM repair_states";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int districtId = rs.getInt("district_id");
                var state = new RepairData.DistrictRepairState(
                    districtId,
                    rs.getInt("repair_points"),
                    rs.getLong("last_wage_paid"),
                    rs.getLong("last_points_reset"),
                    rs.getInt("is_exhausted") == 1
                );
                states.put(districtId, state);
            }
            logger.info("Loaded " + states.size() + " district repair states");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load repair states", e);
        }
    }

    @Override
    public void saveAll() {
        for (var state : states.values()) {
            saveState(state);
        }
    }

    private void saveState(RepairData.DistrictRepairState state) {
        String sql = "INSERT OR REPLACE INTO repair_states (district_id, repair_points, last_wage_paid, last_points_reset, is_exhausted) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try {
            plugin.getDatabase().executeUpdate(sql,
                state.getDistrictId(),
                state.getRepairPoints(),
                state.getLastWagePaid(),
                state.getLastPointsReset(),
                state.isExhausted() ? 1 : 0);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to save repair state for district #" + state.getDistrictId(), e);
        }
    }

    /** Start the daily reset scheduler (checks every 5 minutes). */
    public void startDailyScheduler() {
        dailyTask = scheduler.runRepeating(() -> {
            int reset = processDailyReset();
            if (reset > 0) {
                logger.info("Daily reset processed " + reset + " districts");
            }
        }, 6000L, 6000L); // Every 5 min = 6000 ticks
    }

    /** Stop the daily scheduler. */
    public void stopDailyScheduler() {
        if (dailyTask != null) {
            scheduler.cancel(dailyTask);
            dailyTask = null;
        }
    }
}
