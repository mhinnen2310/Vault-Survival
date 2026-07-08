package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class GroupServiceImpl implements GroupService {
    private final VaultSurvivalPlugin plugin;
    private final Logger logger;
    private final Map<Integer, GroupData.PlayerGroup> groups = new ConcurrentHashMap<>();

    public GroupServiceImpl(VaultSurvivalPlugin plugin) { this.plugin = plugin; this.logger = plugin.getLogger(); }

    @Override
    public GroupData.PlayerGroup createGroup(UUID owner, String name) {
        String sql = "INSERT INTO player_groups (name, owner_uuid) VALUES (?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name); ps.setString(2, owner.toString());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var group = new GroupData.PlayerGroup(id, name, owner);
                plugin.getDatabase().executeUpdate("INSERT INTO group_members (group_id, player_uuid) VALUES (?, ?)", id, owner.toString());
                groups.put(id, group);
                return group;
            }
        } catch (SQLException e) { logger.log(Level.WARNING, "Failed to create group", e); }
        return null;
    }

    @Override public boolean disbandGroup(int groupId, UUID actor) {
        var g = groups.get(groupId); if (g == null || !g.getOwnerUuid().equals(actor)) return false;
        groups.remove(groupId);
        try { plugin.getDatabase().executeUpdate("DELETE FROM group_members WHERE group_id = ?", groupId);
              plugin.getDatabase().executeUpdate("DELETE FROM player_groups WHERE id = ?", groupId); }
        catch (SQLException e) { logger.log(Level.WARNING, "Failed to disband group", e); }
        return true;
    }

    @Override public boolean inviteMember(int groupId, UUID inviter, UUID target) {
        var g = groups.get(groupId); if (g == null || !g.isMember(inviter)) return false;
        g.invite(target);
        Player p = Bukkit.getPlayer(target);
        if (p != null) p.sendMessage(plugin.getMessageFormatter().info("You've been invited to group &e" + g.getName()));
        return true;
    }

    @Override public boolean acceptInvite(int groupId, UUID player) {
        var g = groups.get(groupId); if (g == null || !g.isInvited(player)) return false;
        g.addMember(player);
        try { plugin.getDatabase().executeUpdate("INSERT OR IGNORE INTO group_members (group_id, player_uuid) VALUES (?, ?)", groupId, player.toString()); }
        catch (SQLException e) { logger.log(Level.WARNING, "Failed to accept invite", e); }
        return true;
    }

    @Override public boolean kickMember(int groupId, UUID actor, UUID target) {
        var g = groups.get(groupId); if (g == null || !g.getOwnerUuid().equals(actor) || target.equals(g.getOwnerUuid())) return false;
        g.removeMember(target);
        try { plugin.getDatabase().executeUpdate("DELETE FROM group_members WHERE group_id = ? AND player_uuid = ?", groupId, target.toString()); }
        catch (SQLException e) { logger.log(Level.WARNING, "Failed to kick member", e); }
        return true;
    }

    @Override public boolean leaveGroup(int groupId, UUID player) {
        var g = groups.get(groupId); if (g == null || !g.isMember(player) || g.getOwnerUuid().equals(player)) return false;
        g.removeMember(player);
        try { plugin.getDatabase().executeUpdate("DELETE FROM group_members WHERE group_id = ? AND player_uuid = ?", groupId, player.toString()); }
        catch (SQLException e) { logger.log(Level.WARNING, "Failed to leave group", e); }
        return true;
    }

    @Override public GroupData.PlayerGroup getGroup(int groupId) { return groups.get(groupId); }
    @Override public GroupData.PlayerGroup getPlayerGroup(UUID player) {
        return groups.values().stream().filter(g -> g.isMember(player)).findFirst().orElse(null);
    }
    @Override public List<GroupData.PlayerGroup> getAllGroups() { return new ArrayList<>(groups.values()); }

    @Override public void loadAll() {
        groups.clear();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM player_groups");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                var g = new GroupData.PlayerGroup(id, rs.getString("name"), UUID.fromString(rs.getString("owner_uuid")));
                try (PreparedStatement ms = conn.prepareStatement("SELECT player_uuid FROM group_members WHERE group_id = ?")) {
                    ms.setInt(1, id); ResultSet mr = ms.executeQuery();
                    while (mr.next()) g.addMember(UUID.fromString(mr.getString("player_uuid")));
                }
                groups.put(id, g);
            }
        } catch (SQLException e) { logger.log(Level.SEVERE, "Failed to load groups", e); }
        logger.info("Loaded " + groups.size() + " player groups");
    }
}
