package com.vaultsurvival.plugin.access;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of AccessService using SQLite.
 * Handles group-based permissions with inheritance, temporary grants, and player-specific overrides.
 */
public class AccessServiceImpl implements AccessService {

    private final Logger logger;
    private final DatabaseManager db;
    private final VaultSurvivalPlugin plugin;

    // Cache of group data for fast permission checks
    private final Map<String, GroupData> groupCache = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> playerAttachments = new ConcurrentHashMap<>();
    private boolean cacheLoaded = false;

    public AccessServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.db = plugin.getDatabase();
    }

    /**
     * Load all groups and their permissions into memory cache.
     */
    public void loadGroups() {
        groupCache.clear();
        try (Connection conn = db.getConnection()) {
            // Load groups
            String groupSql = "SELECT id, name, weight, prefix, color, is_staff FROM access_groups";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(groupSql)) {
                while (rs.next()) {
                    GroupData group = new GroupData();
                    group.id = rs.getInt("id");
                    group.name = rs.getString("name");
                    group.weight = rs.getInt("weight");
                    group.prefix = rs.getString("prefix");
                    group.color = rs.getString("color");
                    group.isStaff = rs.getInt("is_staff") == 1;
                    groupCache.put(group.name.toLowerCase(), group);
                }
            }

            // Load permissions per group
            String permSql = "SELECT g.name, p.permission_node FROM access_group_permissions p " +
                             "JOIN access_groups g ON p.group_id = g.id";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(permSql)) {
                while (rs.next()) {
                    String groupName = rs.getString("name").toLowerCase();
                    GroupData group = groupCache.get(groupName);
                    if (group != null) {
                        group.permissions.add(rs.getString("permission_node"));
                    }
                }
            }

            // Load inheritance
            String inheritSql = "SELECT g.name AS child, pg.name AS parent FROM access_group_inheritance i " +
                                "JOIN access_groups g ON i.group_id = g.id " +
                                "JOIN access_groups pg ON i.parent_group_id = pg.id";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(inheritSql)) {
                while (rs.next()) {
                    String childName = rs.getString("child").toLowerCase();
                    GroupData child = groupCache.get(childName);
                    if (child != null) {
                        child.parentGroups.add(rs.getString("parent"));
                    }
                }
            }

            cacheLoaded = true;
            logger.info("Loaded " + groupCache.size() + " access groups");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load access groups", e);
        }
    }

    @Override
    public boolean hasPermission(UUID playerUuid, String permission) {
        if (!cacheLoaded) return false;

        try (Connection conn = db.getConnection()) {
            // Check player-specific overrides first
            String permCheck = "SELECT value FROM access_player_permissions " +
                               "WHERE player_uuid = ? AND permission_node = ? " +
                               "AND (expires_at IS NULL OR expires_at > datetime('now')) " +
                               "ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(permCheck)) {
                ps.setObject(1, playerUuid);
                ps.setString(2, permission);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("value") == 1;
                    }
                }
            }

            // Check group permissions via player's groups
            String groupSql = "SELECT g.name FROM access_player_groups pg " +
                              "JOIN access_groups g ON pg.group_id = g.id " +
                              "WHERE pg.player_uuid = ? " +
                              "AND (pg.expires_at IS NULL OR pg.expires_at > datetime('now'))";
            List<String> playerGroups = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setObject(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        playerGroups.add(rs.getString("name"));
                    }
                }
            }

            // Check all group permissions including inherited
            Set<String> checkedGroups = new HashSet<>();
            for (String groupName : playerGroups) {
                if (checkGroupPermission(groupName, permission, checkedGroups)) {
                    return true;
                }
            }

        } catch (SQLException e) {
            logger.log(Level.WARNING, "Permission check failed for " + playerUuid, e);
        }
        return false;
    }

    private boolean checkGroupPermission(String groupName, String permission, Set<String> checked) {
        if (checked.contains(groupName.toLowerCase())) return false;
        checked.add(groupName.toLowerCase());

        GroupData group = groupCache.get(groupName.toLowerCase());
        if (group == null) return false;

        // Check for wildcard
        if (group.permissions.contains("*")) return true;

        // Check exact match
        if (group.permissions.contains(permission)) return true;

        // Check parent groups
        for (String parent : group.parentGroups) {
            if (checkGroupPermission(parent, permission, checked)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void addToGroup(UUID playerUuid, String groupName, UUID granterUuid) {
        String sql = "INSERT OR IGNORE INTO access_player_groups (player_uuid, group_id, granted_by) " +
                     "SELECT ?, id, ? FROM access_groups WHERE LOWER(name) = LOWER(?)";
        try {
            db.executeUpdate(sql, playerUuid.toString(), granterUuid != null ? granterUuid.toString() : null, groupName);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add player to group", e);
        }
    }

    @Override
    public void removeFromGroup(UUID playerUuid, String groupName) {
        String sql = "DELETE FROM access_player_groups " +
                     "WHERE player_uuid = ? " +
                     "AND group_id IN (SELECT id FROM access_groups WHERE LOWER(name) = LOWER(?))";
        try {
            db.executeUpdate(sql, playerUuid.toString(), groupName);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remove player from group", e);
        }
    }

    @Override
    public String getPrimaryGroup(UUID playerUuid) {
        String sql = "SELECT g.name FROM access_player_groups pg " +
                     "JOIN access_groups g ON pg.group_id = g.id " +
                     "WHERE pg.player_uuid = ? " +
                     "AND (pg.expires_at IS NULL OR pg.expires_at > datetime('now')) " +
                     "ORDER BY g.weight DESC LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("name");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get primary group", e);
        }
        return "default";
    }

    @Override
    public String getPrefix(UUID playerUuid) {
        String groupName = getPrimaryGroup(playerUuid);
        GroupData group = groupCache.get(groupName.toLowerCase());
        if (group != null && !group.prefix.isEmpty()) {
            return group.prefix;
        }
        return "";
    }

    @Override
    public int getWeight(UUID playerUuid) {
        String groupName = getPrimaryGroup(playerUuid);
        GroupData group = groupCache.get(groupName.toLowerCase());
        return group != null ? group.weight : 0;
    }

    @Override
    public boolean isStaff(UUID playerUuid) {
        String groupName = getPrimaryGroup(playerUuid);
        GroupData group = groupCache.get(groupName.toLowerCase());
        return group != null && group.isStaff;
    }

    @Override
    public void addTemporaryPermission(UUID playerUuid, String permission, long durationSeconds, UUID granterUuid) {
        String sql = "INSERT OR IGNORE INTO access_player_permissions (player_uuid, permission_node, value, " +
                     "granted_by, expires_at) VALUES (?, ?, 1, ?, ?)";
        String expiry = new java.sql.Timestamp(System.currentTimeMillis() + durationSeconds * 1000L).toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, permission);
            ps.setString(3, granterUuid != null ? granterUuid.toString() : null);
            ps.setString(4, expiry);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add temporary permission", e);
        }
    }

    @Override
    public void addTemporaryGroup(UUID playerUuid, String groupName, long durationSeconds, UUID granterUuid) {
        // Only overwrite if the existing grant is already temporary (has an expiry).
        // Permanent grants (expires_at IS NULL) are left untouched.
        String expiry = new java.sql.Timestamp(System.currentTimeMillis() + durationSeconds * 1000L).toString();
        // First try UPDATE on existing temporary grants
        String updateSql = "UPDATE access_player_groups SET expires_at = ?, granted_by = ? " +
                          "WHERE player_uuid = ? AND group_id = (SELECT id FROM access_groups WHERE LOWER(name) = LOWER(?)) " +
                          "AND expires_at IS NOT NULL";
        // Then INSERT OR IGNORE if no row was updated (either no existing row, or permanent grant)
        String insertSql = "INSERT OR IGNORE INTO access_player_groups (player_uuid, group_id, granted_by, expires_at) " +
                          "SELECT ?, id, ?, ? FROM access_groups WHERE LOWER(name) = LOWER(?)";
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, expiry);
                ps.setString(2, granterUuid != null ? granterUuid.toString() : null);
                ps.setString(3, playerUuid.toString());
                ps.setString(4, groupName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, granterUuid != null ? granterUuid.toString() : null);
                ps.setString(3, expiry);
                ps.setString(4, groupName);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add temporary group", e);
        }
    }

    @Override
    public void createGroup(String name, int weight, String prefix, String color, boolean isStaff) {
        String sql = "INSERT OR IGNORE INTO access_groups (name, weight, prefix, color, is_staff) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try {
            db.executeUpdate(sql, name, weight, prefix, color, isStaff ? 1 : 0);
            loadGroups(); // Reload cache
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to create group", e);
        }
    }

    @Override
    public void deleteGroup(String name) {
        String sql = "DELETE FROM access_groups WHERE LOWER(name) = LOWER(?)";
        try {
            db.executeUpdate(sql, name);
            loadGroups();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to delete group", e);
        }
    }

    @Override
    public void addGroupPermission(String groupName, String permission) {
        String sql = "INSERT OR IGNORE INTO access_group_permissions (group_id, permission_node) " +
                     "SELECT id, ? FROM access_groups WHERE LOWER(name) = LOWER(?)";
        try {
            db.executeUpdate(sql, permission, groupName);
            loadGroups();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add group permission", e);
        }
    }

    @Override
    public void removeGroupPermission(String groupName, String permission) {
        String sql = "DELETE FROM access_group_permissions " +
                     "WHERE permission_node = ? " +
                     "AND group_id IN (SELECT id FROM access_groups WHERE LOWER(name) = LOWER(?))";
        try {
            db.executeUpdate(sql, permission, groupName);
            loadGroups();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remove group permission", e);
        }
    }

    @Override
    public void setGroupParent(String groupName, String parentGroupName) {
        String sql = "INSERT OR IGNORE INTO access_group_inheritance (group_id, parent_group_id) " +
                     "SELECT c.id, p.id FROM access_groups c, access_groups p " +
                     "WHERE LOWER(c.name) = LOWER(?) AND LOWER(p.name) = LOWER(?)";
        try {
            db.executeUpdate(sql, groupName, parentGroupName);
            loadGroups();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set group parent", e);
        }
    }

    @Override
    public String[] getPlayerGroups(UUID playerUuid) {
        String sql = "SELECT g.name FROM access_player_groups pg " +
                     "JOIN access_groups g ON pg.group_id = g.id " +
                     "WHERE pg.player_uuid = ? " +
                     "AND (pg.expires_at IS NULL OR pg.expires_at > datetime('now'))";
        List<String> groups = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get player groups", e);
        }
        return groups.toArray(new String[0]);
    }

    @Override
    public String[] getEffectivePermissions(UUID playerUuid) {
        Set<String> allPerms = new HashSet<>();
        try (Connection conn = db.getConnection()) {
            // Player-specific permissions
            String sql = "SELECT permission_node FROM access_player_permissions " +
                         "WHERE player_uuid = ? AND value = TRUE " +
                "AND (expires_at IS NULL OR expires_at > datetime('now'))";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) allPerms.add(rs.getString("permission_node"));
                }
            }

            // Group permissions (including inherited)
            String[] groups = getPlayerGroups(playerUuid);
            Set<String> checkedGroups = new HashSet<>();
            for (String group : groups) {
                collectGroupPermissions(group, allPerms, checkedGroups);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get permissions", e);
        }
        return allPerms.toArray(new String[0]);
    }

    @Override
    public void refreshPlayerPermissions(Player player) {
        clearPlayerPermissions(player.getUniqueId());
        PermissionAttachment attachment = player.addAttachment(plugin);
        Set<String> permissions = new HashSet<>(Arrays.asList(getEffectivePermissions(player.getUniqueId())));
        if (permissions.contains("*")) {
            plugin.getDescription().getPermissions().forEach(permission -> attachment.setPermission(permission.getName(), true));
        } else {
            permissions.forEach(permission -> attachment.setPermission(permission, true));
        }
        playerAttachments.put(player.getUniqueId(), attachment);
    }

    @Override
    public void clearPlayerPermissions(UUID playerUuid) {
        PermissionAttachment attachment = playerAttachments.remove(playerUuid);
        if (attachment != null) attachment.remove();
    }

    private void collectGroupPermissions(String groupName, Set<String> permissions, Set<String> checked) {
        if (checked.contains(groupName.toLowerCase())) return;
        checked.add(groupName.toLowerCase());

        GroupData group = groupCache.get(groupName.toLowerCase());
        if (group == null) return;

        permissions.addAll(group.permissions);

        for (String parent : group.parentGroups) {
            collectGroupPermissions(parent, permissions, checked);
        }
    }

    /**
     * Initialize default groups on first run.
     */
    public void initializeDefaultGroups() {
        if (!groupCache.isEmpty()) return;

        createGroup("owner", 1000, "&4&lOwner &4", "&4", true);
        createGroup("developer", 900, "&b&lDev &b", "&b", true);
        createGroup("admin", 800, "&c&lAdmin &c", "&c", true);
        createGroup("mod", 700, "&5&lMod &5", "&5", true);
        createGroup("helper", 600, "&a&lHelper &a", "&a", true);
        createGroup("vip", 200, "&eVIP &e", "&e", false);
        createGroup("supporter", 100, "&dSupporter &d", "&d", false);
        createGroup("default", 0, "", "&7", false);

        loadGroups();

        // Higher ranks inherit lower ranks, never the reverse.
        setGroupParent("helper", "default");
        setGroupParent("mod", "helper");
        setGroupParent("admin", "mod");
        setGroupParent("developer", "admin");
        setGroupParent("owner", "developer");
        setGroupParent("vip", "supporter");
        setGroupParent("supporter", "default");

        // Default group permissions
        addGroupPermission("default", "vs.chat");
        addGroupPermission("default", "vs.trade");
        addGroupPermission("default", "vs.market.buy");
        addGroupPermission("supporter", "vs.market.sell");
        addGroupPermission("supporter", "vs.vault.place");
        addGroupPermission("vip", "vs.friends");

        // Staff permissions
        addGroupPermission("helper", "vs.staff.chat");
        addGroupPermission("mod", "vs.staff.chat");
        addGroupPermission("mod", "vs.police.use");
        addGroupPermission("mod", "vs.district.admin.approve");
        addGroupPermission("admin", "*");
        addGroupPermission("developer", "*");
        addGroupPermission("owner", "*");

        loadGroups();
        logger.info("Default access groups initialized");
    }

    /** Repair the historical inverted staff hierarchy on both new and existing databases. */
    public void normalizeDefaultGroupHierarchy() {
        try {
            db.executeUpdate("DELETE FROM access_group_inheritance WHERE group_id IN (SELECT id FROM access_groups WHERE LOWER(name) IN ('owner','developer','admin','mod','helper','vip','supporter','default')) "
                + "AND parent_group_id IN (SELECT id FROM access_groups WHERE LOWER(name) IN ('owner','developer','admin','mod','helper','vip','supporter','default'))");
            setGroupParent("supporter", "default");
            setGroupParent("vip", "supporter");
            setGroupParent("helper", "default");
            setGroupParent("mod", "helper");
            setGroupParent("admin", "mod");
            setGroupParent("developer", "admin");
            setGroupParent("owner", "developer");
            addGroupPermission("default", "vs.menu");
            addGroupPermission("helper", "vs.staffinspect");
            addGroupPermission("helper", "vs.staff.alerts");
            addGroupPermission("mod", "vs.staffinspect.freeze");
            loadGroups();
            logger.info("Normalized strict default access hierarchy");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to normalize access hierarchy", e);
        }
    }

    /**
     * Inner class for cached group data.
     */
    private static class GroupData {
        int id;
        String name;
        int weight;
        String prefix;
        String color;
        boolean isStaff;
        Set<String> permissions = new HashSet<>();
        Set<String> parentGroups = new HashSet<>();
    }
}
