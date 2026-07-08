package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class StationServiceImpl implements StationService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final Logger logger;
    private final MessageFormatter fmt;
    private final Map<Integer, StationData.Station> stations = new ConcurrentHashMap<>();

    public StationServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.logger = plugin.getLogger(); this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public StationData.Station createStation(String name, Location loc, StationData.RouteType type, long cost, UUID owner) {
        String sql = "INSERT INTO stations (name, world, x, y, z, route_type, cost, owner_uuid) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name); ps.setString(2, loc.getWorld().getName());
            ps.setInt(3, loc.getBlockX()); ps.setInt(4, loc.getBlockY()); ps.setInt(5, loc.getBlockZ());
            ps.setString(6, type.name()); ps.setLong(7, cost); ps.setString(8, owner.toString());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var s = new StationData.Station(id, name, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), type, cost, owner);
                stations.put(id, s); return s;
            }
        } catch (SQLException e) { logger.log(Level.WARNING, "Failed to create station", e); }
        return null;
    }

    @Override public boolean removeStation(int id) { stations.remove(id); try { plugin.getDatabase().executeUpdate("DELETE FROM stations WHERE id = ?", id); } catch (SQLException ignored) {} return true; }

    @Override
    public boolean travel(int fromStationId, int toStationId, UUID playerUuid) {
        var from = stations.get(fromStationId); var to = stations.get(toStationId);
        if (from == null || to == null) return false;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return false;

        long cost = to.getCost();
        if (cost > 0 && currency != null) {
            long balance = currency.getPlayerCashTotal(playerUuid);
            if (balance < cost) {
                player.sendMessage(fmt.error("You need " + fmt.formatMoney(cost, plugin.getConfigManager().getCurrencyName(), plugin.getConfigManager().getCurrencyNamePlural()) + " to travel."));
                return false;
            }
            // Deduct cash - simple approach: use mint/invalidate via DB
            try (Connection conn = plugin.getDatabase().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    long remaining = cost;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT cash_uuid, amount FROM cash_items WHERE state='ACTIVE' AND owner_uuid=? ORDER BY amount ASC")) {
                        ps.setString(1, playerUuid.toString());
                        ResultSet rs = ps.executeQuery();
                        while (rs.next() && remaining > 0) {
                            UUID cuuid = UUID.fromString(rs.getString("cash_uuid"));
                            long amt = rs.getLong("amount");
                            if (amt <= remaining) {
                                try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET state='SPENT' WHERE cash_uuid=?")) { up.setString(1, cuuid.toString()); up.executeUpdate(); }
                                remaining -= amt;
                            } else {
                                try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET amount=? WHERE cash_uuid=?")) { up.setLong(1, amt-remaining); up.setString(2, cuuid.toString()); up.executeUpdate(); }
                                remaining = 0;
                            }
                        }
                    }
                    if (remaining > 0) { conn.rollback(); player.sendMessage(fmt.error("Not enough cash.")); return false; }
                    conn.commit();
                } catch (SQLException e) { conn.rollback(); throw e; }
                finally { conn.setAutoCommit(true); }
            } catch (SQLException e) { logger.log(Level.WARNING, "Failed to deduct travel cost", e); return false; }
            removeCashFromInventory(player, cost);
        }

        Location dest = to.getLocation();
        if (dest != null) {
            player.teleport(dest);
            player.sendMessage(fmt.success("Travelled to &e" + to.getName() + (cost > 0 ? " &7(cost: &6" + cost + "&7)" : "")));
        }
        return true;
    }

    private void removeCashFromInventory(Player player, long amount) {
        long remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && currency != null && currency.isCashItem(item)) {
                long amt = currency.getCashAmount(item);
                if (amt <= remaining) {
                    remaining -= amt;
                    player.getInventory().setItem(i, null);
                } else {
                    // Remove the whole item — DB already reduced the amount
                    player.getInventory().setItem(i, null);
                    remaining = 0;
                }
            }
        }
    }

    @Override public List<StationData.Station> getAllStations() { return new ArrayList<>(stations.values()); }
    @Override public StationData.Station getStation(int id) { return stations.get(id); }

    @Override public void loadAll() {
        stations.clear();
        String sql = "SELECT * FROM stations";
        try (Connection conn = plugin.getDatabase().getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                var s = new StationData.Station(rs.getInt("id"), rs.getString("name"), rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    StationData.RouteType.valueOf(rs.getString("route_type")), rs.getLong("cost"),
                    UUID.fromString(rs.getString("owner_uuid")));
                stations.put(s.getId(), s);
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Failed to load stations", e); }
        logger.info("Loaded " + stations.size() + " stations");
    }
}
