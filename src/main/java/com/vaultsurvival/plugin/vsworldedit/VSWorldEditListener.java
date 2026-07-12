package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Listener for VWE wand interactions.
 * Left-click (break block) → set pos1
 * Right-click (interact block) → set pos2
 */
public class VSWorldEditListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final VSWorldEditService service;
    private static final Set<String> DOUBLE_SLASH_COMMANDS = Set.of(
        "wand", "pos1", "pos2", "selection", "clearselection", "undo",
        "fill", "set", "setgrid", "replace", "replacegrid", "pattern", "walls", "outline", "floor", "ceiling", "hollow",
        "cylinder", "hcylinder", "hollowcylinder", "circle", "hcircle", "hollowcircle", "sphere", "hsphere", "hollowsphere", "line",
        "confirm", "cancel"
    );

    public VSWorldEditListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(VSWorldEditService.class);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (!message.startsWith("//")) {
            return;
        }

        String withoutPrefix = message.substring(2);
        while (withoutPrefix.startsWith("/")) {
            withoutPrefix = withoutPrefix.substring(1);
        }
        if (withoutPrefix.isBlank()) {
            return;
        }

        String command = withoutPrefix.split("\\s+", 2)[0].toLowerCase();
        if (!DOUBLE_SLASH_COMMANDS.contains(command)) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().performCommand("vwe " + withoutPrefix);
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
