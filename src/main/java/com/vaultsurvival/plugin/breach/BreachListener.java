package com.vaultsurvival.plugin.breach;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.vaults.VaultService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Event listener for the Breach system.
 *
 * Handles:
 * - GUI interactions during minigame
 * - Proximity enforcement (handled in BreachServiceImpl tick tasks)
 * - Logout protection (cancel breach on disconnect)
 * - Death during breach
 * - ESC key (inventory close) fails the breach
 * - Teleport blocking after breach
 * - Blocking vault interaction during active breach
 * - Vault right-click with breach kit to start breach
 */
public class BreachListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final BreachService breachService;
    private final VaultService vaultService;

    public BreachListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.breachService = plugin.getServiceRegistry().get(BreachService.class);
        this.vaultService = plugin.getServiceRegistry().get(VaultService.class);
    }

    /**
     * Right-click a vault with a breach kit to initiate breach.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVaultInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!breachService.isBreachKit(item)) return;

        Location clicked = event.getClickedBlock().getLocation();
        var vault = vaultService.getVaultAt(clicked);
        if (vault == null) return;

        // Cancel the interaction (prevent barrel opening)
        event.setCancelled(true);

        // Try to start breach
        breachService.startBreach(player, vault.getVaultUuid());
    }

    /**
     * Handle GUI clicks during the breach minigame.
     * We prevent all normal inventory interaction and route clicks to the breach handler.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (!breachService.isBreaching(player.getUniqueId())) return;

        // Cancel all normal interaction
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        breachService.handleMinigameClick(player, slot);
    }

    /**
     * Prevent drag interactions during the breach minigame.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (breachService.isBreaching(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Closing the GUI (ESC) during breach = failure.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (breachService.isBreaching(player.getUniqueId())) {
            // Player closed the breach GUI - fail the breach
            breachService.cancelBreach(player.getUniqueId(), "Breach abandoned (closed interface)");
        }
    }

    /**
     * Player quits during breach = failure.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (breachService.isBreaching(event.getPlayer().getUniqueId())) {
            breachService.cancelBreach(event.getPlayer().getUniqueId(), "Disconnected");
        }
    }

    /**
     * Player dies during breach = failure.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (breachService.isBreaching(event.getEntity().getUniqueId())) {
            breachService.cancelBreach(event.getEntity().getUniqueId(), "Died during breach");
        }
    }

    /**
     * Block teleportation during the escape cooldown after a breach.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (breachService.isTeleportBlocked(event.getPlayer().getUniqueId())) {
            // Allow chorus fruit teleports (short-range, not escape)
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;

            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessageFormatter().error(
                "You cannot teleport after a breach! You must escape on foot."));
        }
    }

    /**
     * Prevent vault manipulation (deposit/withdraw/break) while being breached.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location loc = event.getBlock().getLocation();
        var vault = vaultService.getVaultAt(loc);
        if (vault == null) return;

        // If this vault is being breached, block placement near it
        if (breachService.isVaultBeingBreached(vault.getVaultUuid())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessageFormatter().error(
                "Cannot build near a vault currently being breached!"));
        }
    }

    /**
     * Block vault interaction (opening barrels, etc.) during active breach.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onVaultInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        var vault = vaultService.getVaultAt(event.getClickedBlock().getLocation());
        if (vault == null) return;

        // If vault is being breached, block owner interaction (prevent deposit/withdraw)
        if (breachService.isVaultBeingBreached(vault.getVaultUuid())) {
            Player player = event.getPlayer();

            // Allow the thief to interact (they need to stay during breach)
            if (breachService.isBreaching(player.getUniqueId())) return;

            // Block everyone else (including the owner)
            event.setCancelled(true);
            player.sendMessage(plugin.getMessageFormatter().error(
                "This vault is currently being breached! Cannot interact right now."));
        }
    }
}
