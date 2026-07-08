package com.vaultsurvival.plugin.staffmode;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Staff mode event listener.
 * Handles: inventory separation, block tracking/revert, chest protection,
 * item drop prevention, and staff visibility indicators.
 *
 * Staff in staffmode:
 * - Cannot place/take items from chests (unless bypass)
 * - Cannot drop items
 * - Cannot pick up items
 * - Blocks broken/placed are tracked and reverted on exit
 * - Glowing effect for visibility
 *
 * Owner bypass (*): ignores ALL of these restrictions for testing.
 */
public class StaffmodeListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final ConfigManager config;
    private final MessageFormatter fmt;
    private final Map<UUID, StaffmodeData> staffData;

    public StaffmodeListener(VaultSurvivalPlugin plugin, Map<UUID, StaffmodeData> staffData) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.fmt = plugin.getMessageFormatter();
        this.staffData = staffData;
    }

    // ========================================================================
    // Inventory Separation
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("vs.staffmode.use")) return;

        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data != null && data.isStaffModeActive()) {
            // Re-apply full staffmode state on rejoin
            applyVisibilityEffects(player, data);
            player.setGameMode(org.bukkit.GameMode.valueOf(
                config.getConfig().getString("staffmode.gamemode", "CREATIVE")
            ));
            String prefix = config.getStaffModePrefix();
            player.displayName(fmt.deserialize(prefix + " &r" + player.getName()));
            player.playerListName(fmt.deserialize(prefix + " &r" + player.getName()));
            // Restore bypass mode if it was active
            if (data.isBypassMode()) {
                player.sendMessage(fmt.info("Bypass mode is still active."));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data != null && data.isStaffModeActive()) {
            // Save state but don't auto-disable (restore on rejoin)
        }
    }

    // ========================================================================
    // Block Tracking (for revert on staffmode exit)
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;

        if (!config.isStaffModeRevertBlocks()) return;
        if (data.getBlockChangeCount() >= config.getStaffModeMaxTrackedBlocks()) {
            player.sendMessage(fmt.warn("Block tracking limit reached. Some blocks will not revert."));
            return;
        }

        data.addBlockChange(new BlockChange(
            event.getBlock().getLocation(),
            event.getBlock().getType(),
            Material.AIR,
            true
        ));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;

        if (!config.isStaffModeRevertBlocks()) return;
        if (data.getBlockChangeCount() >= config.getStaffModeMaxTrackedBlocks()) {
            player.sendMessage(fmt.warn("Block tracking limit reached. Some blocks will not revert."));
            return;
        }

        data.addBlockChange(new BlockChange(
            event.getBlock().getLocation(),
            Material.AIR, // Previous was air (player placed it)
            event.getBlock().getType(),
            false
        ));
    }

    // ========================================================================
    // Chest/Container Protection
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;
        if (config.isStaffModeAllowContainers()) return;

        Inventory inv = event.getInventory();
        // Allow player's own inventory
        if (inv.getType() == InventoryType.PLAYER) return;
        // Allow creative-like inventories (crafting, etc.)
        if (inv.getType() == InventoryType.CRAFTING) return;
        if (inv.getType() == InventoryType.CREATIVE) return;

        // Block all other containers (chests, barrels, hoppers, furnaces, etc.)
        event.setCancelled(true);
        player.sendMessage(fmt.warn("You cannot open containers in staff mode. Use /staffmode * to bypass."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;
        if (config.isStaffModeAllowContainers()) return;

        // Block interacting with container inventories
        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getType() != InventoryType.PLAYER
            && topInv.getType() != InventoryType.CRAFTING
            && topInv.getType() != InventoryType.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;
        if (config.isStaffModeAllowContainers()) return;

        // Block drag operations that involve container slots
        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getType() != InventoryType.PLAYER
            && topInv.getType() != InventoryType.CRAFTING
            && topInv.getType() != InventoryType.CREATIVE) {
            for (int slot : event.getRawSlots()) {
                // Raw slots 0..topInv.size-1 are the container (top inventory)
                if (slot < topInv.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // ========================================================================
    // Item Drop / Pickup Prevention
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;
        if (config.isStaffModeAllowDrop()) return;

        event.setCancelled(true);
        player.sendMessage(fmt.warn("You cannot drop items in staff mode."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return;
        if (data.isBypassMode()) return;
        if (config.isStaffModeAllowPickup()) return;

        event.setCancelled(true);
    }

    // ========================================================================
    // PvP Protection (staff in staffmode can't be attacked)
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player) {
            StaffmodeData data = staffData.get(player.getUniqueId());
            if (data != null && data.isStaffModeActive() && !data.isBypassMode()) {
                event.setCancelled(true);
            }
        }
    }

    // ========================================================================
    // Visibility Effects
    // ========================================================================

    public void applyVisibilityEffects(Player player, StaffmodeData data) {
        String effect = config.getStaffModeVisEffect();
        if ("GLOWING".equalsIgnoreCase(effect)) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.GLOWING,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                true // show particles
            ));
        }
    }

    public void removeVisibilityEffects(Player player) {
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    // ========================================================================
    // Block Revert
    // ========================================================================

    /**
     * Revert all blocks changed during this staff mode session.
     * Called when a player exits staffmode (unless bypass was used).
     */
    public void revertBlocks(Player player, StaffmodeData data) {
        List<BlockChange> changes = data.getBlockChanges();
        int reverted = 0;

        for (BlockChange change : changes) {
            World world = Bukkit.getWorld(change.worldName);
            if (world == null) continue;

            Location loc = new Location(world, change.x, change.y, change.z);

            if (change.wasBreak) {
                loc.getBlock().setType(change.previousType);
            } else {
                loc.getBlock().setType(Material.AIR);
            }
            reverted++;
        }

        if (reverted > 0) {
            player.sendMessage(fmt.info("Reverted " + reverted + " block changes from staff mode."));
        }

        data.clearBlockChanges();
    }
}
