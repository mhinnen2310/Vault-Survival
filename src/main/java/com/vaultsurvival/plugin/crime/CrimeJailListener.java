package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Listener that prevents jailed players from breaking/placing blocks.
 * Jailed players are confined to the jail area (can't move beyond 5 blocks from jail).
 */
public class CrimeJailListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final CrimeService crimeService;
    private final MessageFormatter fmt;

    public CrimeJailListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.crimeService = plugin.getServiceRegistry().get(CrimeService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (crimeService == null) return;
        Player player = event.getPlayer();
        if (crimeService.isJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(fmt.error("You cannot break blocks while jailed."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (crimeService == null) return;
        Player player = event.getPlayer();
        if (crimeService.isJailed(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(fmt.error("You cannot place blocks while jailed."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (crimeService == null) return;
        Player player = event.getPlayer();
        if (!crimeService.isJailed(player.getUniqueId())) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        // Only check if they moved to a different block (not just looking around)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        // Find which jail they're in and constrain to within 5 blocks
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService == null) return;

        for (var d : districtService.getAllDistricts()) {
            var ws = crimeService.getWantedStatus(player.getUniqueId(), d.getId());
            if (ws != null && ws.getJailUntil() > System.currentTimeMillis()) {
                var jail = crimeService.getJailInfo(d.getId());
                if (jail != null && jail.getLocation() != null) {
                    if (event.getTo().distance(jail.getLocation()) > 5) {
                        event.setTo(event.getFrom());
                        player.sendMessage(fmt.error("You are jailed! You cannot leave the jail area."));
                    }
                    return;
                }
            }
        }
    }
}
