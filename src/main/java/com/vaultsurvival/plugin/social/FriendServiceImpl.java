package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class FriendServiceImpl implements FriendService {
    private final VaultSurvivalPlugin plugin;
    private final Logger logger;
    private final Map<UUID, Set<UUID>> friends = new ConcurrentHashMap<>();

    public FriendServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.logger = plugin.getLogger();
    }

    @Override
    public boolean addFriend(UUID player, UUID friend) {
        if (player.equals(friend)) return false;
        friends.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(friend);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR IGNORE INTO friends (player_uuid, friend_uuid, since) VALUES (?, ?, ?)",
                player.toString(), friend.toString(), System.currentTimeMillis());
        } catch (SQLException e) { logger.log(Level.WARNING, "Failed to add friend", e); }
        return true;
    }

    @Override
    public boolean removeFriend(UUID player, UUID friend) {
        var set = friends.get(player);
        if (set != null) set.remove(friend);
        try {
            plugin.getDatabase().executeUpdate(
                "DELETE FROM friends WHERE player_uuid = ? AND friend_uuid = ?",
                player.toString(), friend.toString());
        } catch (SQLException e) { logger.log(Level.WARNING, "Failed to remove friend", e); }
        return true;
    }

    @Override
    public List<FriendData.FriendEntry> getFriends(UUID player) {
        var set = friends.getOrDefault(player, Set.of());
        return set.stream().map(uuid -> {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return new FriendData.FriendEntry(uuid, name != null ? name : "Unknown", 0);
        }).toList();
    }

    @Override
    public boolean areFriends(UUID a, UUID b) {
        var set = friends.get(a);
        return set != null && set.contains(b);
    }

    @Override
    public void loadAll() {
        friends.clear();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM friends");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID player = UUID.fromString(rs.getString("player_uuid"));
                UUID friend = UUID.fromString(rs.getString("friend_uuid"));
                friends.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(friend);
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Failed to load friends", e); }
        logger.info("Loaded friends for " + friends.size() + " players");
    }
}
