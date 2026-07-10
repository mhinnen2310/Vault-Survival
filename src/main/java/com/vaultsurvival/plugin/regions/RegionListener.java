package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Handles region wand selection tool interactions.
 *
 * Left-click with wand = set position 1
 * Right-click with wand = set position 2
 * Each click plays a sound and shows particles for visual feedback.
 */
public class RegionListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final RegionCommand regionCmd;
    private final RegionSelectionService selections;

    public RegionListener(VaultSurvivalPlugin plugin, RegionCommand regionCmd, RegionSelectionService selections) {
        this.plugin = plugin;
        this.regionCmd = regionCmd;
        this.selections = selections;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        if (!regionCmd.isWand(player.getInventory().getItemInMainHand())) return;
        if (!player.hasPermission("vs.region.admin")) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            var loc = event.getClickedBlock().getLocation();
            selections.setPoint(player, loc, true);
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(), "REGION_SELECTION_POS1",
                player.getWorld().getName(), "x=" + loc.getBlockX() + " y=" + loc.getBlockY() + " z=" + loc.getBlockZ());
            player.sendMessage(plugin.getMessageFormatter().success(
                "Position 1 set: &e" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            var loc = event.getClickedBlock().getLocation();
            selections.setPoint(player, loc, false);
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(), "REGION_SELECTION_POS2",
                player.getWorld().getName(), "x=" + loc.getBlockX() + " y=" + loc.getBlockY() + " z=" + loc.getBlockZ());
            player.sendMessage(plugin.getMessageFormatter().success(
                "Position 2 set: &e" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.5f);
        }
    }
}
