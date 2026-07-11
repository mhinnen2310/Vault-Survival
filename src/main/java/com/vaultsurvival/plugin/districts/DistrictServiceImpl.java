package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.regions.RegionData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of DistrictService.
 */
public class DistrictServiceImpl implements DistrictService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final RegionService regions;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;
    private final Map<Integer, DistrictData.District> districts = new ConcurrentHashMap<>();
    private final Map<Integer, DistrictData.BlockClaim> claims = new ConcurrentHashMap<>();
    private BukkitTask lawReloadTask;
    private LocalDate lastLawReload = LocalDate.now();

    public DistrictServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
    }

    @Override
    public DistrictData.District apply(Player founder, String name) {
        founder.sendMessage(fmt.error("Select your district's block corners first with /district apply <name>, then /district confirm."));
        return null;
    }

    @Override
    public DistrictData.District apply(Player founder, String name, DistrictData.BlockClaim claim) {
        if (claim == null || !claim.worldName().equals(founder.getWorld().getName())
            || claim.areaBlocks() != plugin.getConfigManager().getDistrictInitialClaimBlocks()) {
            founder.sendMessage(fmt.error("District applications require exactly "
                + plugin.getConfigManager().getDistrictInitialClaimBlocks() + " selected blocks of horizontal area."));
            return null;
        }
        if (districts.values().stream().anyMatch(d -> d.getFounderUuid().equals(founder.getUniqueId())
            && d.getStatus() != DistrictData.DistrictStatus.DISBANDED)) {
            founder.sendMessage(fmt.error("You already have a district application or district."));
            return null;
        }

        // Check min distance from Spawn City against the claim edge, not just the founder's feet.
        var spawnCity = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.spawncity.SpawnCityService.class);
        Location spawn = spawnCity.getSpawnLocation();
        if (spawn == null) spawn = founder.getWorld().getSpawnLocation();
        String cityName = spawnCity.getCityName();
        if (spawn.getWorld().getName().equals(claim.worldName())
            && distanceToClaim(spawn.getBlockX(), spawn.getBlockZ(), claim) < plugin.getConfigManager().getDistrictMinDistanceFromSpawn()) {
            founder.sendMessage(fmt.error("You must be at least " +
                plugin.getConfigManager().getDistrictMinDistanceFromSpawn() + " blocks from " + cityName + " to found a district."));
            return null;
        }

        // Check distance from other districts
        int minDist = plugin.getConfigManager().getDistrictMinDistanceBetween();
        for (var d : districts.values()) {
            if (d.getStatus() != DistrictData.DistrictStatus.DISBANDED && d.getWorldName().equals(claim.worldName())) {
                DistrictData.BlockClaim existing = claims.get(d.getId());
                double dist = existing == null
                    ? Math.hypot(claim.centerBlockX() - d.getCenterX(), claim.centerBlockZ() - d.getCenterZ())
                    : distanceBetweenClaims(claim, existing);
                if (dist < minDist) {
                    founder.sendMessage(fmt.error("Too close to district '" + d.getName() +
                        "'. Minimum distance: " + minDist + " blocks."));
                    return null;
                }
            }
        }

        String sql = "INSERT INTO districts (name, founder_uuid, world, center_x, center_z, status) " +
                     "VALUES (?, ?, ?, ?, ?, 'APPLICATION')";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, founder.getUniqueId().toString());
            ps.setString(3, claim.worldName());
            ps.setInt(4, claim.centerBlockX());
            ps.setInt(5, claim.centerBlockZ());
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                DistrictData.District district = new DistrictData.District(id, name,
                    founder.getUniqueId(), claim.worldName(), claim.centerBlockX(), claim.centerBlockZ());
                district.addMember(founder.getUniqueId(), DistrictData.DistrictRole.MAYOR);
                districts.put(id, district);

                // Persist the founder as first member
                plugin.getDatabase().executeUpdate(
                    "INSERT INTO district_members (district_id, player_uuid, role) VALUES (?, ?, 'MAYOR')",
                    id, founder.getUniqueId().toString());
                plugin.getDatabase().executeUpdate(
                    "INSERT OR IGNORE INTO district_member_roles (district_id, player_uuid, role) VALUES (?, ?, 'MAYOR')",
                    id, founder.getUniqueId().toString());
                plugin.getDatabase().executeUpdate(
                    "INSERT INTO district_claims (district_id, world, min_chunk_x, min_chunk_z, max_chunk_x, max_chunk_z, min_block_x, min_block_z, max_block_x, max_block_z) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, claim.worldName(), Math.floorDiv(claim.minBlockX(), 16), Math.floorDiv(claim.minBlockZ(), 16),
                    Math.floorDiv(claim.maxBlockX(), 16), Math.floorDiv(claim.maxBlockZ(), 16),
                    claim.minBlockX(), claim.minBlockZ(), claim.maxBlockX(), claim.maxBlockZ());
                claims.put(id, claim);

                audit.log(founder.getUniqueId(), founder.getName(), "DISTRICT_APPLY", "DISTRICT",
                    String.valueOf(id), "name=" + name);

                founder.sendMessage(fmt.success("District application submitted: &e" + name));
                founder.sendMessage(fmt.info("An admin will review your application. Use &e/district info " + id));
                return district;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create district application", e);
        }
        return null;
    }

    @Override
    public DistrictData.BlockClaim getClaim(int districtId) {
        return claims.get(districtId);
    }

    @Override
    public long getClaimBlockLimit(DistrictData.District district) {
        if (district == null) return plugin.getConfigManager().getDistrictInitialClaimBlocks();
        int level = 0;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT level FROM district_development WHERE district_id = ?")) {
            ps.setInt(1, district.getId());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) level = rs.getInt(1);
        } catch (SQLException ignored) {
            // A fresh district has no development row yet, so level zero is correct.
        }
        return plugin.getConfigManager().getDistrictClaimBlocksAtLevel(level);
    }

    @Override
    public boolean updateClaim(DistrictData.District district, UUID actorUuid, DistrictData.BlockClaim claim) {
        if (district == null || claim == null || district.getStatus() != DistrictData.DistrictStatus.ACTIVE
            || !district.getWorldName().equals(claim.worldName()) || !canManageDevelopment(actorUuid, district)) return false;
        DistrictData.BlockClaim previous = claims.get(district.getId());
        if (previous == null || !claim.contains(previous) || claim.areaBlocks() <= previous.areaBlocks()
            || claim.areaBlocks() > getClaimBlockLimit(district)) return false;
        for (DistrictData.District other : districts.values()) {
            if (other.getId() == district.getId() || other.getStatus() == DistrictData.DistrictStatus.DISBANDED
                || !other.getWorldName().equals(claim.worldName())) continue;
            DistrictData.BlockClaim otherClaim = claims.get(other.getId());
            if (otherClaim != null && distanceBetweenClaims(claim, otherClaim) < plugin.getConfigManager().getDistrictMinDistanceBetween()) return false;
        }
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE district_claims SET min_chunk_x=?,min_chunk_z=?,max_chunk_x=?,max_chunk_z=?,min_block_x=?,min_block_z=?,max_block_x=?,max_block_z=?,updated_at=datetime('now') WHERE district_id=?",
                Math.floorDiv(claim.minBlockX(), 16), Math.floorDiv(claim.minBlockZ(), 16),
                Math.floorDiv(claim.maxBlockX(), 16), Math.floorDiv(claim.maxBlockZ(), 16),
                claim.minBlockX(), claim.minBlockZ(), claim.maxBlockX(), claim.maxBlockZ(), district.getId());
            claims.put(district.getId(), claim);
            replaceDistrictRegion(district, claim);
            audit.log(actorUuid, "DISTRICT", "DISTRICT_CLAIM_EXPAND", "DISTRICT", String.valueOf(district.getId()),
                "areaBlocks=" + previous.areaBlocks() + "->" + claim.areaBlocks());
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update district claim", e);
            return false;
        }
    }

    @Override
    public boolean approve(int districtId, UUID adminUuid) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || d.getStatus() != DistrictData.DistrictStatus.APPLICATION) return false;

        d.setStatus(DistrictData.DistrictStatus.ACTIVE);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE districts SET status = 'ACTIVE' WHERE id = ?", districtId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to approve district", e);
        }

        DistrictData.BlockClaim claim = claims.get(districtId);
        if (claim != null) replaceDistrictRegion(d, claim);
        else createLegacyDistrictRegion(d);

        audit.log(adminUuid, "ADMIN", "DISTRICT_APPROVE", "DISTRICT",
            String.valueOf(districtId), "name=" + d.getName());

        var founder = Bukkit.getPlayer(d.getFounderUuid());
        if (founder != null) {
            founder.sendMessage(fmt.success("Your district &e" + d.getName() + " &ahas been approved!"));
        }
        return true;
    }

    @Override
    public boolean reject(int districtId, UUID adminUuid, String reason) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || d.getStatus() != DistrictData.DistrictStatus.APPLICATION) return false;

        d.setStatus(DistrictData.DistrictStatus.DISBANDED);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE districts SET status = 'DISBANDED' WHERE id = ?", districtId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to reject district", e);
        }

        audit.log(adminUuid, "ADMIN", "DISTRICT_REJECT", "DISTRICT",
            String.valueOf(districtId), "reason=" + reason);

        var founder = Bukkit.getPlayer(d.getFounderUuid());
        if (founder != null) {
            founder.sendMessage(fmt.error("Your district application for &e" + d.getName() +
                " &cwas rejected: " + reason));
        }
        return true;
    }

    @Override
    public boolean disband(int districtId, UUID actorUuid) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || d.getStatus() != DistrictData.DistrictStatus.ACTIVE) return false;

        // Only mayor or admin can disband
        if (!canManageRoles(actorUuid, d)) {
            var player = Bukkit.getPlayer(actorUuid);
            if (player != null && !player.hasPermission("vs.district.admin")) return false;
        }

        d.setStatus(DistrictData.DistrictStatus.DISBANDED);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE districts SET status = 'DISBANDED' WHERE id = ?", districtId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to disband district", e);
        }

        audit.log(actorUuid, Bukkit.getOfflinePlayer(actorUuid).getName(), "DISTRICT_DISBAND",
            "DISTRICT", String.valueOf(districtId), "");
        return true;
    }

    @Override
    public boolean inviteMember(int districtId, UUID actorUuid, UUID targetUuid) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || d.getStatus() != DistrictData.DistrictStatus.ACTIVE) return false;
        if (!canManageRoles(actorUuid, d)) return false;
        if (d.isMember(targetUuid)) return false;

        d.addMember(targetUuid, DistrictData.DistrictRole.MEMBER);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR IGNORE INTO district_members (district_id, player_uuid, role) VALUES (?, ?, 'MEMBER')",
                districtId, targetUuid.toString());
            plugin.getDatabase().executeUpdate(
                "INSERT OR IGNORE INTO district_member_roles (district_id, player_uuid, role) VALUES (?, ?, 'MEMBER')",
                districtId, targetUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to invite member", e);
        }
        return true;
    }

    @Override
    public boolean kickMember(int districtId, UUID actorUuid, UUID targetUuid) {
        DistrictData.District d = districts.get(districtId);
        if (d == null) return false;
        if (!canManageRoles(actorUuid, d)) return false;
        if (d.isMayor(targetUuid)) return false; // can't kick mayor
        if (!d.isMember(targetUuid)) return false;

        d.removeMember(targetUuid);
        try {
            plugin.getDatabase().executeUpdate(
                "DELETE FROM district_members WHERE district_id = ? AND player_uuid = ?",
                districtId, targetUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to kick member", e);
        }
        return true;
    }

    @Override
    public boolean setRole(int districtId, UUID actorUuid, UUID targetUuid, DistrictData.DistrictRole role) {
        DistrictData.District d = districts.get(districtId);
        if (d == null) return false;
        if (!canManageRoles(actorUuid, d)) return false;
        if (!d.isMember(targetUuid)) return false;
        if (role == DistrictData.DistrictRole.VISITOR) return false;
        if (role == DistrictData.DistrictRole.MAYOR && !d.isMayor(actorUuid)) return false;

        d.setRole(targetUuid, role);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR IGNORE INTO district_member_roles (district_id, player_uuid, role) VALUES (?, ?, ?)",
                districtId, targetUuid.toString(), role.name());
            plugin.getDatabase().executeUpdate(
                "UPDATE district_members SET role = ? WHERE district_id = ? AND player_uuid = ?",
                d.getHighestRole(targetUuid).name(), districtId, targetUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set role", e);
            return false;
        }
        return true;
    }

    @Override
    public boolean removeRole(int districtId, UUID actorUuid, UUID targetUuid, DistrictData.DistrictRole role) {
        DistrictData.District d = districts.get(districtId);
        if (d == null) return false;
        if (!canManageRoles(actorUuid, d)) return false;
        if (!d.isMember(targetUuid)) return false;
        if (role == DistrictData.DistrictRole.MAYOR) return false;

        d.removeRole(targetUuid, role);
        try {
            plugin.getDatabase().executeUpdate(
                "DELETE FROM district_member_roles WHERE district_id = ? AND player_uuid = ? AND role = ?",
                districtId, targetUuid.toString(), role.name());
            if (d.getDistrictRoleCount(targetUuid) == 0) {
                plugin.getDatabase().executeUpdate(
                    "INSERT OR IGNORE INTO district_member_roles (district_id, player_uuid, role) VALUES (?, ?, 'MEMBER')",
                    districtId, targetUuid.toString());
            }
            plugin.getDatabase().executeUpdate(
                "UPDATE district_members SET role = ? WHERE district_id = ? AND player_uuid = ?",
                d.getHighestRole(targetUuid).name(), districtId, targetUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remove role", e);
            return false;
        }
        return true;
    }

    @Override
    public boolean depositTreasury(Player player, int districtId, long amount) {
        if (plugin.isEnabled()) {
            player.sendMessage(fmt.error("Remote treasury banking is disabled. Interact with a registered physical treasury vault."));
            return false;
        }
        DistrictData.District d = districts.get(districtId);
        if (d == null || !d.isMember(player.getUniqueId())) return false;

        long playerCash = currency.getPlayerCashTotal(player.getUniqueId());
        if (playerCash < amount) {
            player.sendMessage(fmt.error("You don't have enough cash."));
            return false;
        }

        // Directly transfer player's cash to treasury in one DB operation
        // (avoids the withdrawCash SPENT → updateCashLocation override pattern)
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                                 "WHERE state = 'ACTIVE' AND owner_uuid = ? ORDER BY amount ASC";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, player.getUniqueId().toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmount = rs.getLong("amount");
                        if (cashAmount <= remaining) {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET state = 'IN_DISTRICT_TREASURY', location_type = 'TREASURY', " +
                                    "location_id = ?, owner_uuid = NULL, last_seen_at = datetime('now') WHERE cash_uuid = ?")) {
                                up.setString(1, String.valueOf(districtId));
                                up.setString(2, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining -= cashAmount;
                        } else {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?")) {
                                up.setLong(1, cashAmount - remaining);
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
            logger.log(Level.WARNING, "Failed to deposit treasury", e);
            player.sendMessage(fmt.error("Deposit failed. Your cash is safe."));
            return false;
        }

        // Remove cash items from player inventory (DB already updated)
        removeCashFromInventory(player, amount);

        player.sendMessage(fmt.success("Deposited &6" + fmt.formatMoney(amount,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural()) + " &ainto the district treasury."));
        return true;
    }

    private void removeCashFromInventory(Player player, long amount) {
        long remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && currency.isCashItem(item)) {
                long itemAmount = currency.getCashAmount(item);
                if (itemAmount <= remaining) {
                    remaining -= itemAmount;
                    player.getInventory().setItem(i, null);
                }
            }
        }
    }

    @Override
    public boolean withdrawTreasury(Player player, int districtId, long amount) {
        if (plugin.isEnabled()) {
            player.sendMessage(fmt.error("Remote treasury banking is disabled. Interact with a registered physical treasury vault."));
            return false;
        }
        DistrictData.District d = districts.get(districtId);
        if (d == null || !canManageTreasury(player.getUniqueId(), d)) {
            player.sendMessage(fmt.error("Only the mayor or treasurer can withdraw from the treasury."));
            return false;
        }

        long balance = getTreasuryBalance(districtId);
        if (amount > balance) {
            player.sendMessage(fmt.error("Treasury only has &6" + fmt.formatMoney(balance,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            return false;
        }

        // Move cash from treasury to player (atomic transaction on single connection)
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                                 "WHERE state = 'IN_DISTRICT_TREASURY' AND location_id = ? ORDER BY amount ASC";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, String.valueOf(districtId));
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmount = rs.getLong("amount");
                        if (cashAmount <= remaining) {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET state = 'SPENT' WHERE cash_uuid = ?")) {
                                up.setString(1, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining -= cashAmount;
                        } else {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET amount = ? WHERE cash_uuid = ?")) {
                                up.setLong(1, cashAmount - remaining);
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
            logger.log(Level.WARNING, "Failed to withdraw treasury", e);
            player.sendMessage(fmt.error("Withdrawal failed. Treasury is safe — nothing was moved."));
            return false;
        }

        // Give minted cash to player
        var cash = currency.mintCash(amount, player.getUniqueId(), player.getUniqueId());
        if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(cash);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), cash);
        }

        player.sendMessage(fmt.success("Withdrew &6" + fmt.formatMoney(amount,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural()) + " &afrom the district treasury."));
        return true;
    }

    @Override
    public boolean setLaw(int districtId, UUID actorUuid, String lawName, boolean enabled) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || !canManageLaws(actorUuid, d)) return false;

        d.setLaw(lawName, enabled);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO district_laws (district_id, law_name, enabled) VALUES (?, ?, ?)",
                districtId, lawName, enabled ? 1 : 0);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set law", e);
        }
        return true;
    }

    @Override
    public boolean proposeLaw(int districtId, UUID actorUuid, DistrictData.LawKey lawKey, boolean enabled) {
        DistrictData.District d = districts.get(districtId);
        if (d == null || !canManageLaws(actorUuid, d)) return false;
        int maxChanges = plugin.getConfigManager().getDistrictMaxLawChangesPerDay();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement count = conn.prepareStatement(
                 "SELECT COUNT(*) FROM district_pending_laws WHERE district_id = ? AND applied = 0")) {
            count.setInt(1, districtId);
            ResultSet rs = count.executeQuery();
            if (rs.next() && rs.getInt(1) >= maxChanges && !d.getPendingLaws().containsKey(lawKey.name())) {
                return false;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to count pending laws", e);
            return false;
        }

        d.setPendingLaw(lawKey.name(), enabled);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO district_pending_laws (district_id, law_name, enabled, proposed_by, proposed_at, applied) " +
                "VALUES (?, ?, ?, ?, datetime('now'), 0)",
                districtId, lawKey.name(), enabled ? 1 : 0, actorUuid.toString());
            audit.log(actorUuid, Bukkit.getOfflinePlayer(actorUuid).getName(), "DISTRICT_LAW_PROPOSE",
                "DISTRICT", String.valueOf(districtId), "law=" + lawKey.name() + " enabled=" + enabled);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to propose law", e);
            return false;
        }
        return true;
    }

    @Override
    public int applyPendingLaws() {
        int applied = 0;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT district_id, law_name, enabled FROM district_pending_laws WHERE applied = 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int districtId = rs.getInt("district_id");
                String lawName = rs.getString("law_name");
                boolean enabled = rs.getInt("enabled") == 1;
                DistrictData.District d = districts.get(districtId);
                if (d == null) continue;
                d.setLaw(lawName, enabled);
                d.clearPendingLaw(lawName);
                plugin.getDatabase().executeUpdate(
                    "INSERT OR REPLACE INTO district_laws (district_id, law_name, enabled) VALUES (?, ?, ?)",
                    districtId, lawName, enabled ? 1 : 0);
                plugin.getDatabase().executeUpdate(
                    "UPDATE district_pending_laws SET applied = 1, applies_at = datetime('now') WHERE district_id = ? AND law_name = ?",
                    districtId, lawName);
                audit.logSystem("DISTRICT_LAW_APPLY", "DISTRICT", String.valueOf(districtId),
                    "law=" + lawName + " enabled=" + enabled);
                applied++;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to apply pending laws", e);
        }
        return applied;
    }

    @Override
    public boolean isLawActive(DistrictData.District district, DistrictData.LawKey lawKey) {
        return district != null && Boolean.TRUE.equals(district.getLaws().get(lawKey.name()));
    }

    @Override
    public boolean isLawPending(DistrictData.District district, DistrictData.LawKey lawKey) {
        return district != null && district.getPendingLaws().containsKey(lawKey.name());
    }

    public void startLawReloadScheduler() {
        if (lawReloadTask != null) return;
        lawReloadTask = plugin.getScheduler().runRepeating(() -> {
            LocalDate today = LocalDate.now();
            if (!today.equals(lastLawReload)) {
                lastLawReload = today;
                int count = applyPendingLaws();
                if (count > 0) {
                    logger.info("Applied " + count + " pending district law changes");
                }
            }
        }, 1200L, 1200L);
    }

    public void stopLawReloadScheduler() {
        if (lawReloadTask != null) {
            plugin.getScheduler().cancel(lawReloadTask);
            lawReloadTask = null;
        }
    }

    @Override
    public boolean hasDistrictRole(UUID playerUuid, DistrictData.District district, DistrictData.DistrictRole role) {
        return district != null && district.hasRole(playerUuid, role);
    }

    @Override
    public Set<DistrictData.DistrictRole> getDistrictRoles(UUID playerUuid, DistrictData.District district) {
        return district == null ? EnumSet.of(DistrictData.DistrictRole.VISITOR) : district.getRoles(playerUuid);
    }

    @Override
    public DistrictData.DistrictRole getHighestDistrictRole(UUID playerUuid, DistrictData.District district) {
        return district == null ? DistrictData.DistrictRole.VISITOR : district.getHighestRole(playerUuid);
    }

    @Override
    public boolean canManageRoles(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR, DistrictData.DistrictRole.CO_MAYOR);
    }

    @Override
    public boolean canManageLaws(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR, DistrictData.DistrictRole.CO_MAYOR);
    }

    @Override
    public boolean canManageTreasury(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.TREASURER);
    }

    @Override
    public boolean canCreateMerchantNpc(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.MERCHANT);
    }

    @Override
    public boolean canCreateDistrictJob(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.MERCHANT,
            DistrictData.DistrictRole.TREASURER);
    }

    @Override
    public boolean canApproveDistrictJob(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.TREASURER);
    }

    @Override
    public boolean canPolice(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.POLICE,
            DistrictData.DistrictRole.WARDEN);
    }

    @Override
    public boolean canRequestStation(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.DIPLOMAT);
    }

    @Override
    public boolean canManageDevelopment(UUID playerUuid, DistrictData.District district) {
        return hasAnyRole(playerUuid, district, DistrictData.DistrictRole.MAYOR,
            DistrictData.DistrictRole.CO_MAYOR, DistrictData.DistrictRole.BUILDER);
    }

    @Override
    public boolean setDistrictMessage(DistrictData.District district, UUID actorUuid, boolean welcome, String message) {
        return setMayorSetting(district, actorUuid, welcome ? "message.welcome" : "message.leave", message);
    }

    @Override
    public String getDistrictMessage(DistrictData.District district, boolean welcome) {
        return getSetting(district, welcome ? "message.welcome" : "message.leave", "");
    }

    @Override
    public boolean setDistrictChatPrefix(DistrictData.District district, UUID actorUuid, String prefix) {
        return setMayorSetting(district, actorUuid, "chat.prefix", prefix);
    }

    @Override
    public String getDistrictChatPrefix(DistrictData.District district) {
        return getSetting(district, "chat.prefix", district == null ? "" : district.getName());
    }

    @Override
    public boolean setDistrictRoleColor(DistrictData.District district, UUID actorUuid, DistrictData.DistrictRole role, String color) {
        return role != null && setMayorSetting(district, actorUuid, "chat.role." + role.name(), color);
    }

    @Override
    public String getDistrictRoleColor(DistrictData.District district, DistrictData.DistrictRole role) {
        String fallback = role == null ? "&7" : switch (role) {
            case MAYOR -> "&6"; case CO_MAYOR -> "&e"; case TREASURER -> "&a"; case MERCHANT -> "&2";
            case POLICE, WARDEN -> "&9"; case BUILDER -> "&b"; case DIPLOMAT -> "&d"; case GUEST -> "&7";
            case MEMBER -> "&f"; case VISITOR -> "&7";
        };
        return getSetting(district, "chat.role." + (role == null ? "VISITOR" : role.name()), fallback);
    }

    private boolean setMayorSetting(DistrictData.District district, UUID actorUuid, String key, String value) {
        if (district == null || actorUuid == null || !district.isMayor(actorUuid) || value == null || value.length() > 96) return false;
        try {
            plugin.getDatabase().executeUpdate("INSERT INTO district_settings (district_id,setting_key,setting_value,updated_at) VALUES (?,?,?,datetime('now')) ON CONFLICT(district_id,setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=datetime('now')",
                district.getId(), key, value);
            audit.log(actorUuid, Bukkit.getOfflinePlayer(actorUuid).getName(), "DISTRICT_SETTING_UPDATE", "DISTRICT", String.valueOf(district.getId()), key);
            return true;
        } catch (SQLException error) { logger.log(Level.WARNING, "Failed to save district setting", error); return false; }
    }

    private String getSetting(DistrictData.District district, String key, String fallback) {
        if (district == null) return fallback;
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT setting_value FROM district_settings WHERE district_id=? AND setting_key=?")) {
            statement.setInt(1, district.getId()); statement.setString(2, key); ResultSet result = statement.executeQuery();
            return result.next() ? result.getString(1) : fallback;
        } catch (SQLException ignored) { return fallback; }
    }

    private boolean hasAnyRole(UUID playerUuid, DistrictData.District district, DistrictData.DistrictRole... roles) {
        if (district == null) return false;
        Set<DistrictData.DistrictRole> playerRoles = district.getRoles(playerUuid);
        for (DistrictData.DistrictRole role : roles) {
            if (playerRoles.contains(role)) return true;
        }
        return false;
    }

    @Override
    public DistrictData.District getDistrict(int districtId) {
        return districts.get(districtId);
    }

    @Override
    public DistrictData.District getPlayerDistrict(UUID playerUuid) {
        return districts.values().stream()
            .filter(d -> d.isMember(playerUuid) && d.getStatus() == DistrictData.DistrictStatus.ACTIVE)
            .findFirst().orElse(null);
    }

    @Override
    public List<DistrictData.District> getAllDistricts() {
        return new ArrayList<>(districts.values());
    }

    @Override
    public List<DistrictData.District> getApplications() {
        return districts.values().stream()
            .filter(d -> d.getStatus() == DistrictData.DistrictStatus.APPLICATION)
            .toList();
    }

    private long getTreasuryBalance(int districtId) {
        String sql = "SELECT IFNULL(SUM(c.amount), 0) FROM cash_items c " +
                     "JOIN district_treasury_vaults v ON v.vault_uuid = c.location_id " +
                     "WHERE c.state = 'IN_DISTRICT_TREASURY' AND c.location_type = 'DISTRICT_TREASURY_VAULT' AND v.district_id = ?";
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

    private void createLegacyDistrictRegion(DistrictData.District district) {
        if (regions == null) return;
        var world = Bukkit.getWorld(district.getWorldName());
        if (world == null) return;
        var region = regions.createRegion("district_" + district.getName(), RegionData.RegionType.DISTRICT,
            district.getWorldName(), district.getCenterX() - 250, world.getMinHeight(), district.getCenterZ() - 250,
            district.getCenterX() + 250, world.getMaxHeight(), district.getCenterZ() + 250, 10);
        if (region == null) logger.warning("Failed to create legacy region for district " + district.getName());
    }

    private void replaceDistrictRegion(DistrictData.District district, DistrictData.BlockClaim claim) {
        if (regions == null) return;
        var world = Bukkit.getWorld(claim.worldName());
        if (world == null) {
            logger.warning("Cannot create district region; world is unavailable: " + claim.worldName());
            return;
        }
        String regionName = "district_" + district.getName();
        regions.getAllRegions().stream()
            .filter(region -> region.getType() == RegionData.RegionType.DISTRICT && region.getName().equalsIgnoreCase(regionName))
            .map(RegionData.Region::getId)
            .toList()
            .forEach(regions::deleteRegion);
        var region = regions.createRegion(regionName, RegionData.RegionType.DISTRICT, claim.worldName(),
            claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(),
            claim.maxBlockX(), world.getMaxHeight(), claim.maxBlockZ(), 10);
        if (region == null) logger.warning("Failed to create region for district " + district.getName());
    }

    private double distanceToClaim(int x, int z, DistrictData.BlockClaim claim) {
        int nearestX = Math.max(claim.minBlockX(), Math.min(x, claim.maxBlockX()));
        int nearestZ = Math.max(claim.minBlockZ(), Math.min(z, claim.maxBlockZ()));
        return Math.hypot(x - nearestX, z - nearestZ);
    }

    private double distanceBetweenClaims(DistrictData.BlockClaim first, DistrictData.BlockClaim second) {
        int xGap = Math.max(0, Math.max(first.minBlockX() - second.maxBlockX(), second.minBlockX() - first.maxBlockX()));
        int zGap = Math.max(0, Math.max(first.minBlockZ() - second.maxBlockZ(), second.minBlockZ() - first.maxBlockZ()));
        return Math.hypot(xGap, zGap);
    }

    @Override
    public void loadAll() {
        districts.clear();
        claims.clear();
        try (Connection conn = plugin.getDatabase().getConnection()) {
            // Load districts
            String sql = "SELECT * FROM districts WHERE status != 'DISBANDED'";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    DistrictData.District d = new DistrictData.District(id,
                        rs.getString("name"),
                        UUID.fromString(rs.getString("founder_uuid")),
                        rs.getString("world"),
                        rs.getInt("center_x"), rs.getInt("center_z"));
                    d.setStatus(DistrictData.DistrictStatus.valueOf(rs.getString("status")));
                    d.setCreatedAt(rs.getString("created_at"));
                    d.setTreasuryBalance(getTreasuryBalance(id));
                    districts.put(id, d);
                }
            }

            // Load legacy/current members and their highest role column.
            String memSql = "SELECT * FROM district_members";
            try (PreparedStatement ps = conn.prepareStatement(memSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int districtId = rs.getInt("district_id");
                    DistrictData.District d = districts.get(districtId);
                    if (d != null) {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        DistrictData.DistrictRole role = parseRole(rs.getString("role"));
                        d.addMember(uuid, role);
                        try (PreparedStatement migrate = conn.prepareStatement(
                                "INSERT OR IGNORE INTO district_member_roles (district_id, player_uuid, role) VALUES (?, ?, ?)")) {
                            migrate.setInt(1, districtId);
                            migrate.setString(2, uuid.toString());
                            migrate.setString(3, role.name());
                            migrate.executeUpdate();
                        }
                    }
                }
            }

            String claimSql = "SELECT * FROM district_claims";
            try (PreparedStatement ps = conn.prepareStatement(claimSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claims.put(rs.getInt("district_id"), new DistrictData.BlockClaim(
                        rs.getString("world"), rs.getInt("min_block_x"), rs.getInt("min_block_z"),
                        rs.getInt("max_block_x"), rs.getInt("max_block_z")));
                }
            }

            // Load full multi-role assignments.
            String roleSql = "SELECT * FROM district_member_roles";
            try (PreparedStatement ps = conn.prepareStatement(roleSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int districtId = rs.getInt("district_id");
                    DistrictData.District d = districts.get(districtId);
                    if (d != null) {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        d.setRole(uuid, parseRole(rs.getString("role")));
                    }
                }
            }

            // Load laws
            String lawSql = "SELECT * FROM district_laws";
            try (PreparedStatement ps = conn.prepareStatement(lawSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int districtId = rs.getInt("district_id");
                    DistrictData.District d = districts.get(districtId);
                    if (d != null) {
                        d.setLaw(rs.getString("law_name"), rs.getInt("enabled") == 1);
                    }
                }
            }

            String pendingLawSql = "SELECT * FROM district_pending_laws WHERE applied = 0";
            try (PreparedStatement ps = conn.prepareStatement(pendingLawSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int districtId = rs.getInt("district_id");
                    DistrictData.District d = districts.get(districtId);
                    if (d != null) {
                        d.setPendingLaw(rs.getString("law_name"), rs.getInt("enabled") == 1);
                    }
                }
            }

            logger.info("Loaded " + districts.size() + " districts from database");

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load districts", e);
        }
    }

    private DistrictData.DistrictRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return DistrictData.DistrictRole.MEMBER;
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (normalized.equals("CITIZEN")) {
            return DistrictData.DistrictRole.MEMBER;
        }
        if (normalized.equals("COUNCIL")) {
            return DistrictData.DistrictRole.CO_MAYOR;
        }
        try {
            return DistrictData.DistrictRole.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return DistrictData.DistrictRole.MEMBER;
        }
    }
}
