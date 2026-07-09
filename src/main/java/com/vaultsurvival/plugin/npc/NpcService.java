package com.vaultsurvival.plugin.npc;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing NPCs — fake player entities with custom skins,
 * shops, commands, and integration with other modules.
 */
public interface NpcService {

    /**
     * Create a new NPC at the given location with a Mojang skin.
     *
     * @param name         Display name (shown in nametag)
     * @param skinUsername Mojang username whose skin to copy
     * @param location     Spawn location
     * @param actionType   What happens on right-click
     * @param actionData   Command string or JSON data for the action
     */
    NpcData.Npc createNpc(String name, String skinUsername, Location location,
                           NpcData.ActionType actionType, String actionData);

    /**
     * Remove and despawn an NPC permanently.
     */
    boolean removeNpc(int npcId);

    /**
     * Move an NPC to a new location.
     */
    boolean moveNpc(int npcId, Location newLocation);

    /**
     * Change an NPC's skin.
     */
    boolean setNpcSkin(int npcId, String skinUsername);

    /**
     * Set the action for an NPC.
     */
    boolean setNpcAction(int npcId, NpcData.ActionType actionType, String actionData);

    /**
     * Add a shop item to an NPC.
     */
    boolean addShopItem(int npcId, int slot, ItemStack item, long price, String command);

    /**
     * Clear all shop items from an NPC.
     */
    boolean clearShopItems(int npcId);

    /**
     * Get an NPC by its database ID.
     */
    NpcData.Npc getNpc(int npcId);

    /**
     * Find an NPC by its Interaction entity UUID.
     */
    NpcData.Npc getNpcByInteractionUuid(UUID interactionUuid);

    /**
     * Find an NPC by its visible Bukkit entity UUID.
     */
    NpcData.Npc getNpcByVisualUuid(UUID visualUuid);

    /**
     * Get all NPCs.
     */
    List<NpcData.Npc> getAllNpcs();

    /**
     * Spawn all NPCs to a specific player (called on join).
     */
    void spawnAllToPlayer(Player player);

    /**
     * Despawn all NPCs from a specific player (called on quit).
     */
    void despawnAllFromPlayer(Player player);

    /**
     * Handle player interaction with an NPC.
     * Executes commands, opens shops, fires NpcInteractEvent, etc.
     */
    void handleInteraction(Player player, NpcData.Npc npc);

    /**
     * Load all NPCs from the database and spawn them for online players.
     */
    void loadAndSpawnAll();

    /** Remove the visual and click hitbox without deleting the persisted NPC. */
    void despawnNpcVisual(int npcId);

    /** Respawn a persisted NPC after a temporary lifecycle event. */
    void respawnNpc(int npcId);
}
