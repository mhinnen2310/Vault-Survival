package com.vaultsurvival.plugin.vaults;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Iterator;
import java.util.UUID;

/**
 * Event listener for vault block protection.
 * Vaults cannot be broken, moved by pistons, or destroyed by explosions.
 */
public class VaultListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final VaultService vaultService;
    private final RegionService regionService;
    private final MessageFormatter fmt;

    public VaultListener(VaultSurvivalPlugin plugin, VaultService vaultService) {
        this.plugin = plugin;
        this.vaultService = vaultService;
        this.regionService = plugin.getServiceRegistry().get(RegionService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    /**
     * Prevent vault blocks from being broken.
     * Only the owner removing an empty vault via the command can break it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        if (vaultService.isVaultBlock(loc)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(fmt.error("This vault cannot be broken. Use the vault command to remove it."));

            VaultData vault = vaultService.getVaultAt(loc);
            if (vault != null) {
                player.sendMessage(fmt.info("Vault: &e" + vault.getTier().getDisplayName()));
                player.sendMessage(fmt.info("Owner: &e" + vault.getOwnerUuid()));
                player.sendMessage(fmt.info("Balance: &6" + vault.getBalance() + " &ecoins"));
            }
        }
    }

    /**
     * Vault interaction - right-click to open vault.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;

        Location loc = block.getLocation();
        if (!vaultService.isVaultBlock(loc)) return;

        // Don't let the block do its normal function (like opening a barrel)
        event.setCancelled(true);

        VaultData vault = vaultService.getVaultAt(loc);
        if (vault == null) return;

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        // Check access
        if (!vaultService.canAccess(vault.getVaultUuid(), playerUuid)) {
            player.sendMessage(fmt.error("You don't have access to this vault."));
            return;
        }

        // Show vault info
        player.sendMessage(fmt.header(vault.getTier().getDisplayName()));
        player.sendMessage(fmt.info("Balance: &6" + fmt.formatMoney(vault.getBalance(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        player.sendMessage(fmt.info("Protected: &e" + fmt.formatMoney(vault.getProtectedAmount(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        if (vault.isLockedDown()) {
            long remaining = vaultService.getLockdownRemaining(vault.getVaultUuid());
            player.sendMessage(fmt.warn("LOCKDOWN: " + remaining + "s remaining"));
        }

        player.sendMessage(fmt.info("Use &e/vault deposit&7 or &e/vault withdraw <amount>"));
    }

    /**
     * Vault barrels are only physical markers. Money is stored in cash records,
     * so direct container storage must stay blocked even if another plugin opens it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isVaultInventory(event.getInventory())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(fmt.error("Vaults only accept physical cash through /vault deposit."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isVaultInventory(event.getInventory())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(fmt.error("Vaults only accept physical cash through /vault deposit."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (isVaultInventory(event.getSource()) || isVaultInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevent placing blocks where BLOCK_PLACE is disallowed by region rules.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (regionService == null) return;
        Player player = event.getPlayer();
        if (!regionService.isBuildAllowed(player, event.getBlock().getLocation(), RegionData.RuleFlag.BLOCK_PLACE)) {
            event.setCancelled(true);
            player.sendMessage(fmt.error("You cannot place blocks in this area."));
        }
    }

    /**
     * Prevent breaking blocks where BLOCK_BREAK is disallowed by region rules.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreakRegion(BlockBreakEvent event) {
        if (regionService == null) return;
        if (vaultService.isVaultBlock(event.getBlock().getLocation())) return; // handled by onBlockBreak
        Player player = event.getPlayer();
        if (!regionService.isBuildAllowed(player, event.getBlock().getLocation(), RegionData.RuleFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
            player.sendMessage(fmt.error("You cannot break blocks in this area."));
        }
    }

    /**
     * Prevent explosions from destroying vaults.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (vaultService.isVaultBlock(block.getLocation())) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (vaultService.isVaultBlock(block.getLocation())) {
                it.remove();
            }
        }
    }

    /**
     * Prevent pistons from moving vault blocks.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (vaultService.isVaultBlock(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (vaultService.isVaultBlock(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isVaultInventory(Inventory inventory) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof Container container)) return false;
        return vaultService.isVaultBlock(container.getLocation());
    }
}
