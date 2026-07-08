package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of CrimeService.
 */
public class CrimeServiceImpl implements CrimeService {

    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final CurrencyService currency;
    private final Logger logger;
    private final MessageFormatter fmt;
    private final Map<Integer, CrimeData.CrimeRecord> crimes = new ConcurrentHashMap<>();
    private final Map<String, CrimeData.WantedStatus> wanted = new ConcurrentHashMap<>(); // key: "districtId:criminalUuid"
    private final Map<Integer, CrimeData.JailInfo> jails = new ConcurrentHashMap<>();
    private BukkitTask jailTask = null;

    public CrimeServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.districts = plugin.getServiceRegistry().get(DistrictService.class);
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.logger = plugin.getLogger();
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public CrimeData.CrimeRecord logCrime(UUID criminalUuid, int districtId, CrimeData.CrimeType type,
                                           CrimeData.CrimeSeverity severity, String blockType, String location) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO crime_log (district_id, criminal_uuid, type, severity, block_type, location, timestamp) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, districtId);
            ps.setString(2, criminalUuid.toString());
            ps.setString(3, type.name());
            ps.setString(4, severity.name());
            ps.setString(5, blockType);
            ps.setString(6, location);
            ps.setLong(7, now);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var record = new CrimeData.CrimeRecord(id, districtId, criminalUuid, type, severity,
                    blockType, location, now);
                crimes.put(id, record);

                // Update wanted status
                upsertWanted(criminalUuid, districtId);

                // Alert online police in that district
                alertPolice(districtId, record);

                return record;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to log crime", e);
        }
        return null;
    }

    private void upsertWanted(UUID criminalUuid, int districtId) {
        String key = districtId + ":" + criminalUuid;
        var ws = wanted.get(key);
        if (ws == null) {
            ws = new CrimeData.WantedStatus(criminalUuid, districtId, 0, 0, System.currentTimeMillis(), false);
            wanted.put(key, ws);
        }
        ws.incrementCrimeCount();
        ws.setLastCrimeTime(System.currentTimeMillis());
        ws.setArrested(false); // new crime reactivates wanted status

        // Persist
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO wanted_players (criminal_uuid, district_id, bounty, crime_count, last_crime_time, arrested) " +
                "VALUES (?, ?, ?, ?, ?, 0)",
                criminalUuid.toString(), districtId, ws.getBounty(), ws.getCrimeCount(), ws.getLastCrimeTime());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to persist wanted status", e);
        }
    }

    private void alertPolice(int districtId, CrimeData.CrimeRecord record) {
        if (districts == null) return;
        var district = districts.getDistrict(districtId);
        if (district == null) return;

        String criminalName = "Unknown";
        var offline = Bukkit.getOfflinePlayer(record.getCriminalUuid());
        if (offline.getName() != null) criminalName = offline.getName();

        String alert = fmt.info("&c\u26A0 &e" + criminalName + " &ccommitted &e" + record.getType() +
            " &c(severity: " + record.getSeverity() + ") &8| &7" + record.getBlockType());

        for (UUID memberUuid : district.getMembers()) {
            if (district.isPolice(memberUuid)) {
                Player police = Bukkit.getPlayer(memberUuid);
                if (police != null && police.isOnline()) {
                    police.sendMessage(alert);
                }
            }
        }
    }

    @Override
    public List<CrimeData.WantedStatus> getWantedPlayers(int districtId) {
        return wanted.values().stream()
            .filter(w -> w.getDistrictId() == districtId && !w.isArrested())
            .sorted((a, b) -> Long.compare(b.getLastCrimeTime(), a.getLastCrimeTime()))
            .toList();
    }

    @Override
    public boolean isWanted(UUID playerUuid, int districtId) {
        var ws = wanted.get(districtId + ":" + playerUuid);
        return ws != null && !ws.isArrested() && ws.getCrimeCount() > 0;
    }

    @Override
    public boolean setBounty(int districtId, UUID criminalUuid, long amount, UUID setterUuid) {
        if (districts == null) return false;
        var district = districts.getDistrict(districtId);
        if (district == null || !district.isPolice(setterUuid)) return false;

        var ws = wanted.get(districtId + ":" + criminalUuid);
        if (ws == null || ws.isArrested()) return false;

        ws.setBounty(ws.getBounty() + amount);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE wanted_players SET bounty = ? WHERE criminal_uuid = ? AND district_id = ?",
                ws.getBounty(), criminalUuid.toString(), districtId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update bounty", e);
        }
        return true;
    }

    @Override
    public boolean arrest(UUID policeUuid, UUID criminalUuid) {
        Player police = Bukkit.getPlayer(policeUuid);
        Player criminal = Bukkit.getPlayer(criminalUuid);
        if (police == null || criminal == null) return false;
        if (districts == null) return false;

        // Find which district the police belongs to
        var district = districts.getPlayerDistrict(policeUuid);
        if (district == null || !district.isPolice(policeUuid)) {
            police.sendMessage(fmt.error("You must be a police officer in a district to arrest players."));
            return false;
        }

        var ws = wanted.get(district.getId() + ":" + criminalUuid);
        if (ws == null || ws.isArrested()) {
            police.sendMessage(fmt.error("That player is not wanted in your district."));
            return false;
        }

        // Must be within arrest range (10 blocks)
        if (police.getLocation().distance(criminal.getLocation()) > 10) {
            police.sendMessage(fmt.error("You must be within 10 blocks of the criminal to arrest them."));
            return false;
        }

        ws.setArrested(true);

        // Calculate jail time: 5 minutes per crime
        long jailDuration = ws.getCrimeCount() * 5L * 60 * 1000;
        long jailUntil = System.currentTimeMillis() + jailDuration;
        ws.setJailUntil(jailUntil);

        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE wanted_players SET arrested = 1, jail_until = ? WHERE criminal_uuid = ? AND district_id = ?",
                jailUntil, criminalUuid.toString(), district.getId());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to mark arrest", e);
        }

        // Teleport criminal to jail
        var jailInfo = jails.get(district.getId());
        if (jailInfo != null) {
            Location jailLoc = jailInfo.getLocation();
            if (jailLoc != null) {
                criminal.teleport(jailLoc);
            }
        }

        long jailMinutes = jailDuration / 60000;
        police.sendMessage(fmt.success("Arrested &e" + criminal.getName() + " &a(" + jailMinutes + " min jail)"));
        criminal.sendMessage(fmt.error("You have been arrested by &e" + police.getName() +
            " &cin district &e" + district.getName() + " &cfor &e" + jailMinutes + " minutes&c."));

        // Broadcast to district
        for (UUID memberUuid : district.getMembers()) {
            if (district.isPolice(memberUuid)) {
                Player p = Bukkit.getPlayer(memberUuid);
                if (p != null && p.isOnline() && !p.equals(police)) {
                    p.sendMessage(fmt.info("&e" + police.getName() + " &7arrested &e" + criminal.getName()));
                }
            }
        }

        return true;
    }

    @Override
    public boolean fine(UUID policeUuid, UUID criminalUuid, long amount) {
        Player police = Bukkit.getPlayer(policeUuid);
        Player criminal = Bukkit.getPlayer(criminalUuid);
        if (police == null || criminal == null) return false;
        if (districts == null || currency == null) return false;

        var district = districts.getPlayerDistrict(policeUuid);
        if (district == null || !district.isPolice(policeUuid)) {
            police.sendMessage(fmt.error("You must be a police officer to issue fines."));
            return false;
        }

        if (!isWanted(criminalUuid, district.getId())) {
            police.sendMessage(fmt.error("That player is not wanted in your district."));
            return false;
        }

        // Directly transfer cash from criminal to district treasury (criminal is NOT a member)
        boolean transferred = transferCashToTreasury(criminalUuid, district.getId(), amount);
        if (!transferred) {
            police.sendMessage(fmt.error(criminal.getName() + " doesn't have enough cash. They have " +
                fmt.formatMoney(currency.getPlayerCashTotal(criminalUuid),
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
            return false;
        }

        // Remove physical cash items from criminal inventory
        if (criminal.isOnline()) {
            removeInventoryCash(criminal, amount);
        }

        police.sendMessage(fmt.success("Fined &e" + criminal.getName() + " &6" + fmt.formatMoney(amount,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        criminal.sendMessage(fmt.error("You have been fined &6" + fmt.formatMoney(amount,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural()) + " &cby &e" + police.getName()));

        return true;
    }

    /** Transfer cash from a player to the district treasury (atomic, works for non-members). */
    private boolean transferCashToTreasury(UUID playerUuid, int districtId, long amount) {
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                                 "WHERE state = 'ACTIVE' AND owner_uuid = ? ORDER BY amount ASC";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, playerUuid.toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmt = rs.getLong("amount");
                        if (cashAmt <= remaining) {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET state = 'IN_DISTRICT_TREASURY', location_type = 'TREASURY', " +
                                    "location_id = ?, owner_uuid = NULL, last_seen_at = datetime('now') WHERE cash_uuid = ?")) {
                                up.setString(1, String.valueOf(districtId));
                                up.setString(2, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining -= cashAmt;
                        } else {
                            try (PreparedStatement up = conn.prepareStatement(
                                    "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?")) {
                                up.setLong(1, cashAmt - remaining);
                                up.setString(2, cashUuid.toString());
                                up.executeUpdate();
                            }
                            remaining = 0;
                        }
                    }
                }
                if (remaining > 0) {
                    conn.rollback();
                    return false; // Not enough cash
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to transfer cash to treasury for fine", e);
            return false;
        }
    }

    /** Remove physical cash items from a player's inventory to match DB state. */
    private void removeInventoryCash(Player player, long amount) {
        long remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && currency.isCashItem(item)) {
                long itemAmt = currency.getCashAmount(item);
                if (itemAmt <= remaining) {
                    remaining -= itemAmt;
                    player.getInventory().setItem(i, null);
                }
            }
        }
    }

    @Override
    public List<CrimeData.CrimeRecord> getCrimeRecord(UUID playerUuid) {
        return crimes.values().stream()
            .filter(c -> c.getCriminalUuid().equals(playerUuid))
            .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
            .toList();
    }

    @Override
    public CrimeData.WantedStatus getWantedStatus(UUID playerUuid, int districtId) {
        return wanted.get(districtId + ":" + playerUuid);
    }

    @Override
    public boolean setJailLocation(int districtId, String worldName, int x, int y, int z, UUID setterUuid) {
        if (districts == null) return false;
        var district = districts.getDistrict(districtId);
        if (district == null || !district.isMayor(setterUuid)) return false;

        var jail = new CrimeData.JailInfo(districtId, worldName, x, y, z);
        jails.put(districtId, jail);

        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO jail_locations (district_id, world, x, y, z) VALUES (?, ?, ?, ?, ?)",
                districtId, worldName, x, y, z);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to save jail location", e);
        }
        return true;
    }

    @Override
    public CrimeData.JailInfo getJailInfo(int districtId) {
        return jails.get(districtId);
    }

    @Override
    public boolean release(UUID policeUuid, UUID criminalUuid) {
        Player police = Bukkit.getPlayer(policeUuid);
        if (police == null || districts == null) return false;

        var district = districts.getPlayerDistrict(policeUuid);
        if (district == null || !district.isPolice(policeUuid)) {
            police.sendMessage(fmt.error("You must be a police officer to release prisoners."));
            return false;
        }

        var ws = wanted.get(district.getId() + ":" + criminalUuid);
        if (ws == null || !isJailed(criminalUuid)) {
            police.sendMessage(fmt.error("That player is not currently jailed in your district."));
            return false;
        }

        ws.setJailUntil(0);
        ws.setArrested(false);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE wanted_players SET arrested = 0, jail_until = 0 WHERE criminal_uuid = ? AND district_id = ?",
                criminalUuid.toString(), district.getId());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to release prisoner", e);
        }

        // Teleport to spawn
        Player criminal = Bukkit.getPlayer(criminalUuid);
        if (criminal != null) {
            var spawnCity = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.spawncity.SpawnCityService.class);
            Location spawnLoc = spawnCity.getSpawnLocation();
            criminal.teleport(spawnLoc != null ? spawnLoc : criminal.getWorld().getSpawnLocation());
            criminal.sendMessage(fmt.success("You have been released from jail by &e" + police.getName() + "&a. Welcome back to " + spawnCity.getCityName() + "."));
        }

        police.sendMessage(fmt.success("Released &e" + (criminal != null ? criminal.getName() : "player")));
        return true;
    }

    @Override
    public boolean isJailed(UUID playerUuid) {
        long now = System.currentTimeMillis();
        for (var ws : wanted.values()) {
            if (ws.getCriminalUuid().equals(playerUuid) && ws.getJailUntil() > now) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<CrimeData.WantedStatus> getJailedPlayers(int districtId) {
        long now = System.currentTimeMillis();
        return wanted.values().stream()
            .filter(w -> w.getDistrictId() == districtId && w.getJailUntil() > now)
            .sorted((a, b) -> Long.compare(b.getJailUntil(), a.getJailUntil()))
            .toList();
    }

    @Override
    public int processJailReleases() {
        int released = 0;
        long now = System.currentTimeMillis();
        for (var ws : wanted.values()) {
            if (ws.getJailUntil() > 0 && ws.getJailUntil() <= now) {
                // Release expired sentence
                ws.setJailUntil(0);
                ws.setArrested(false);
                try {
                    plugin.getDatabase().executeUpdate(
                        "UPDATE wanted_players SET arrested = 0, jail_until = 0 WHERE criminal_uuid = ? AND district_id = ?",
                        ws.getCriminalUuid().toString(), ws.getDistrictId());
                } catch (SQLException ignored) {}

                Player player = Bukkit.getPlayer(ws.getCriminalUuid());
                if (player != null) {
                    var spawnCity2 = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.spawncity.SpawnCityService.class);
                    Location spawnLoc2 = spawnCity2.getSpawnLocation();
                    player.teleport(spawnLoc2 != null ? spawnLoc2 : player.getWorld().getSpawnLocation());
                    player.sendMessage(fmt.success("Your jail sentence has expired. Welcome back to " + spawnCity2.getCityName() + "!"));
                }
                released++;
            }
        }
        return released;
    }

    /** Start the jail release checker (every 30 seconds). */
    public void startJailScheduler() {
        if (jailTask != null) return;
        jailTask = plugin.getScheduler().runRepeating(this::processJailReleases, 600L, 600L);
    }

    /** Stop the jail release checker. */
    public void stopJailScheduler() {
        if (jailTask != null) {
            plugin.getScheduler().cancel(jailTask);
            jailTask = null;
        }
    }

    @Override
    public void loadAll() {
        crimes.clear();
        wanted.clear();

        // Load crimes
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM crime_log ORDER BY timestamp DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                var record = new CrimeData.CrimeRecord(id,
                    rs.getInt("district_id"),
                    UUID.fromString(rs.getString("criminal_uuid")),
                    CrimeData.CrimeType.valueOf(rs.getString("type")),
                    CrimeData.CrimeSeverity.valueOf(rs.getString("severity")),
                    rs.getString("block_type"),
                    rs.getString("location"),
                    rs.getLong("timestamp"));
                crimes.put(id, record);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load crimes", e);
        }

        // Load wanted (including jailed)
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM wanted_players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                var ws = new CrimeData.WantedStatus(
                    UUID.fromString(rs.getString("criminal_uuid")),
                    rs.getInt("district_id"),
                    rs.getLong("bounty"),
                    rs.getInt("crime_count"),
                    rs.getLong("last_crime_time"),
                    rs.getInt("arrested") == 1);
                ws.setJailUntil(rs.getLong("jail_until"));
                wanted.put(ws.getDistrictId() + ":" + ws.getCriminalUuid(), ws);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load wanted players", e);
        }

        // Load jail locations
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM jail_locations");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                var jail = new CrimeData.JailInfo(
                    rs.getInt("district_id"),
                    rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
                jails.put(jail.getDistrictId(), jail);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load jail locations", e);
        }

        // Release any expired jail sentences immediately
        int released = processJailReleases();

        logger.info("Loaded " + crimes.size() + " crimes, " + wanted.size() + " wanted, " +
            jails.size() + " jails" + (released > 0 ? ", released " + released + " expired sentences" : ""));
    }
}
