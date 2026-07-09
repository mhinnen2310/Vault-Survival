package com.vaultsurvival.plugin.access;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Service interface for the permission/access system.
 * Replaces LuckPerms with a simpler custom system.
 */
public interface AccessService {

    /**
     * Check if a player has a specific permission.
     */
    boolean hasPermission(UUID playerUuid, String permission);

    /**
     * Add a player to a group.
     */
    void addToGroup(UUID playerUuid, String groupName, UUID granterUuid);

    /**
     * Remove a player from a group.
     */
    void removeFromGroup(UUID playerUuid, String groupName);

    /**
     * Get the primary group of a player.
     */
    String getPrimaryGroup(UUID playerUuid);

    /**
     * Get the display prefix for a player.
     */
    String getPrefix(UUID playerUuid);

    /**
     * Get the rank weight for a player (for sorting).
     */
    int getWeight(UUID playerUuid);

    /**
     * Check if a player is staff.
     */
    boolean isStaff(UUID playerUuid);

    /**
     * Add a permission to a player temporarily.
     */
    void addTemporaryPermission(UUID playerUuid, String permission, long durationSeconds, UUID granterUuid);

    /**
     * Add a player to a group temporarily.
     */
    void addTemporaryGroup(UUID playerUuid, String groupName, long durationSeconds, UUID granterUuid);

    /**
     * Create a new group.
     */
    void createGroup(String name, int weight, String prefix, String color, boolean isStaff);

    /**
     * Delete a group.
     */
    void deleteGroup(String name);

    /**
     * Add a permission to a group.
     */
    void addGroupPermission(String groupName, String permission);

    /**
     * Remove a permission from a group.
     */
    void removeGroupPermission(String groupName, String permission);

    /**
     * Set parent group (inheritance).
     */
    void setGroupParent(String groupName, String parentGroupName);

    /**
     * Get all groups a player belongs to.
     */
    String[] getPlayerGroups(UUID playerUuid);

    /**
     * Get all effective permissions for a player.
     */
    String[] getEffectivePermissions(UUID playerUuid);

    /** Apply the database-backed rank permissions to an online Bukkit player. */
    void refreshPlayerPermissions(Player player);

    /** Remove the plugin-owned permission attachment when a player leaves. */
    void clearPlayerPermissions(UUID playerUuid);
}
