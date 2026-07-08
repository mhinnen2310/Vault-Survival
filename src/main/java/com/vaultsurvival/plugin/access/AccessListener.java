package com.vaultsurvival.plugin.access;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Handles player join to ensure profile and default group assignment.
 */
public class AccessListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final AccessServiceImpl accessService;

    public AccessListener(VaultSurvivalPlugin plugin, AccessServiceImpl accessService) {
        this.plugin = plugin;
        this.accessService = accessService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        var name = player.getName();

        // Ensure player record exists
        ensurePlayerRecord(uuid, name);

        // Create a session record
        createSession(uuid);

        // Welcome message with city name
        try {
            var spawnCity = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.spawncity.SpawnCityService.class);
            String cityName = spawnCity.getCityName();
            event.joinMessage(plugin.getMessageFormatter().deserialize(
                "&e" + name + " &7arrived in &6" + cityName + "&7."
            ));
        } catch (IllegalStateException ignored) {
            // SpawnCityService not yet available
        }

        // If player has no groups, assign default
        String[] groups = accessService.getPlayerGroups(uuid);
        if (groups.length == 0) {
            accessService.addToGroup(uuid, "default", null);
        }
    }

    private void ensurePlayerRecord(java.util.UUID uuid, String name) {
        // SQLite: use SELECT-then-UPDATE-or-INSERT pattern to preserve first_seen
        String checkSql = "SELECT uuid FROM players WHERE uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // Player exists — just update last_seen and username
                    String updateSql = "UPDATE players SET username = ?, last_seen = datetime('now') WHERE uuid = ?";
                    try (PreparedStatement up = conn.prepareStatement(updateSql)) {
                        up.setString(1, name);
                        up.setString(2, uuid.toString());
                        up.executeUpdate();
                    }
                } else {
                    // New player — insert with fresh values
                    String insertSql = "INSERT INTO players (uuid, username, first_seen, last_seen) " +
                                       "VALUES (?, ?, datetime('now'), datetime('now'))";
                    try (PreparedStatement in = conn.prepareStatement(insertSql)) {
                        in.setString(1, uuid.toString());
                        in.setString(2, name);
                        in.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ensure player record for " + name, e);
        }
    }

    private void createSession(java.util.UUID uuid) {
        String sql = "INSERT INTO player_sessions (player_uuid, login_time, ip_address) VALUES (?, datetime('now'), ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            var player = plugin.getServer().getPlayer(uuid);
            String ip = player != null && player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "unknown";
            ps.setString(2, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create session for " + uuid, e);
        }
    }
}
