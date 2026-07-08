package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for VWE wand interactions.
 * Left-click (break block) → set pos1
 * Right-click (interact block) → set pos2
 */
public class VSWorldEditListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final VSWorldEditService service;

    public VSWorldEditListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(VSWorldEditService.class);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("vaultsurvival.vwe.use")) return;

        ItemStack item = event.getItem();
        if (item == null || !service.isWand(item)) return;

        // Prevent normal wand usage (block breaking/placing)
        event.setCancelled(true);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            service.setPos1(player, event.getClickedBlock().getLocation());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            service.setPos2(player, event.getClickedBlock().getLocation());
        } else if (event.getAction() == Action.LEFT_CLICK_AIR) {
            // Left-click air: set pos1 at player's target block (up to 100 blocks)
            var target = player.getTargetBlockExact(100);
            if (target != null) {
                service.setPos1(player, target.getLocation());
            }
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            // Right-click air: set pos2 at player's target block
            var target = player.getTargetBlockExact(100);
            if (target != null) {
                service.setPos2(player, target.getLocation());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (service instanceof VSWorldEditServiceImpl impl) {
            impl.cleanup(event.getPlayer());
        }
    }
}
