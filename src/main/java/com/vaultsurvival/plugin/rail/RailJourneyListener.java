package com.vaultsurvival.plugin.rail;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Restricts player actions during train transit.
 *
 * While IN_TRANSIT (on the train), players cannot:
 * - PvP or take damage
 * - Drop items
 * - Break or place blocks
 * - Teleport away
 * - Move (frozen in the train car)
 *
 * Logout/rejoin is handled safely: journey is cancelled on quit.
 */
public class RailJourneyListener implements Listener {

    private final RailService railService;
    private final VaultSurvivalPlugin plugin;

    public RailJourneyListener(VaultSurvivalPlugin plugin) {
        this.railService = plugin.getServiceRegistry().get(RailService.class);
        this.plugin = plugin;
    }

    private boolean isInTransit(Player player) {
        return railService.isPlayerInTransit(player.getUniqueId());
    }

    // === Block movement during transit ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isInTransit(event.getPlayer())) return;

        // Only block if position actually changed (not just head rotation)
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockY() != event.getTo().getBlockY() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    // === Prevent teleport away ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!isInTransit(event.getPlayer())) return;

        // Allow only plugin teleports (train car → arrival)
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getMessageFormatter().error("You cannot teleport while on a train."));
    }

    // === Prevent PvP ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Attacker in transit
        if (event.getDamager() instanceof Player attacker && isInTransit(attacker)) {
            event.setCancelled(true);
            return;
        }
        // Victim in transit
        if (event.getEntity() instanceof Player victim && isInTransit(victim)) {
            event.setCancelled(true);
        }
    }

    // === Prevent damage to player in transit ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isInTransit(player)) {
            // Allow only void damage (emergency fallback)
            if (event.getCause() != EntityDamageEvent.DamageCause.VOID) {
                event.setCancelled(true);
            }
        }
    }

    // === Prevent item dropping ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isInTransit(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessageFormatter().error("You cannot drop items while on a train."));
        }
    }

    // === Prevent block break/place ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isInTransit(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isInTransit(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // === Logout/rejoin handling ===

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        railService.handlePlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay slightly to allow other systems to initialize
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            plugin,
            () -> railService.handlePlayerRejoin(event.getPlayer()),
            20L);
    }
}
