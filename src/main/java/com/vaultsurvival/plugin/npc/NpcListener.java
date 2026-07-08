package com.vaultsurvival.plugin.npc;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/**
 * Handles NPC interactions and lifecycle events.
 *
 * - PlayerInteractEntityEvent: Detect right-clicks on Interaction entities (NPCs)
 * - PlayerJoinEvent: Spawn all NPCs for the joining player
 * - PlayerQuitEvent: Clean up NPC entities for the quitting player
 * - PlayerChangedWorldEvent: Respawn NPCs when player changes worlds
 */
public class NpcListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final NpcService npcService;

    public NpcListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.npcService = plugin.getServiceRegistry().get(NpcService.class);
    }

    /**
     * Right-click on an NPC (Interaction entity) triggers the NPC action.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;

        NpcData.Npc npc = npcService.getNpcByInteractionUuid(interaction.getUniqueId());
        if (npc == null) return;

        // Cancel the default interaction (prevent arm swing animation issues)
        event.setCancelled(true);

        // Handle NPC interaction
        npcService.handleInteraction(event.getPlayer(), npc);
    }

    /**
     * Spawn all NPCs for newly joined players.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Spawn NPCs a tick later to ensure the player is fully loaded
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            npcService.spawnAllToPlayer(player);
        }, 20L);
    }

    /**
     * Clean up NPC entities when a player quits.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        npcService.despawnAllFromPlayer(event.getPlayer());
    }

    /**
     * Respawn NPCs when player changes worlds.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // Despawn NPCs from old world, spawn in new world
        npcService.despawnAllFromPlayer(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            npcService.spawnAllToPlayer(player);
        }, 10L);
    }
}
