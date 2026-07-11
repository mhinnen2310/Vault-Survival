package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Event listeners for the physical currency system.
 * Handles: forbidden container prevention, death drops, pickup validation,
 * inventory scanning, and anti-dupe enforcement.
 */
public class CurrencyListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currencyService;
    private final RegionService regionService;

    // Containers where cash must NOT be stored
    private static final Set<Material> FORBIDDEN_CONTAINERS = Set.of(
        Material.SHULKER_BOX,
        Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
        Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX,
        Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
        Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
        Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX,
        Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
        Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
        Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
        Material.BUNDLE,
        Material.ENDER_CHEST,
        Material.HOPPER,
        Material.HOPPER_MINECART,
        Material.DROPPER,
        Material.DISPENSER
    );

    public CurrencyListener(VaultSurvivalPlugin plugin, CurrencyService currencyService) {
        this.plugin = plugin;
        this.currencyService = currencyService;
        this.regionService = plugin.getServiceRegistry().get(RegionService.class);
    }

    // ========================================================================
    // Forbidden Container Prevention
    // ========================================================================

    /**
     * Prevent cash items from being placed into forbidden containers.
     * This fires when a player clicks to move an item.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // Check if player is placing cash into a forbidden container
        Inventory topInv = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();

        if (clickedInv != null && topInv != null) {
            // If the clicked inventory is a container and the cursor has cash
            if (cursor != null && currencyService.isCashItem(cursor)) {
                if (isForbiddenContainer(clickedInv)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMessageFormatter().error(
                        "Cash cannot be stored in that container type."
                    ));
                    return;
                }

                // If clicking into top inventory and it's forbidden
                if (clickedInv.equals(topInv) && isForbiddenContainer(topInv)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMessageFormatter().error(
                        "Cash cannot be stored in that container type."
                    ));
                    return;
                }
            }

            // Check shift-click into forbidden containers
            if (event.isShiftClick() && current != null && currencyService.isCashItem(current)) {
                Inventory destInv = clickedInv.equals(topInv)
                    ? event.getView().getBottomInventory()
                    : topInv;

                if (isForbiddenContainer(destInv)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMessageFormatter().error(
                        "Cash cannot be stored in that container type."
                    ));
                }
            }
        }
    }

    /**
     * Prevent hoppers/droppers from moving cash items.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (currencyService.isCashItem(item)) {
            Inventory dest = event.getDestination();
            if (isForbiddenContainer(dest) || dest.getType() == InventoryType.HOPPER
                || dest.getType() == InventoryType.DISPENSER
                || dest.getType() == InventoryType.DROPPER) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Prevent putting cash into item frames.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemStack cursor = event.getOldCursor();
        if (cursor != null && currencyService.isCashItem(cursor)) {
            for (Integer slot : event.getRawSlots()) {
                Inventory inv = event.getView().getInventory(slot);
                if (inv != null && isForbiddenContainer(inv)) {
                    event.setCancelled(true);
                    if (event.getWhoClicked() instanceof Player player) {
                        player.sendMessage(plugin.getMessageFormatter().error(
                            "Cash cannot be stored in that container type."
                        ));
                    }
                    return;
                }
            }
        }
    }

    // ========================================================================
    // Cash Drop Region Check
    // ========================================================================

    /**
     * Prevent dropping cash in areas where CASH_DROP_ENABLED is false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (regionService == null) return;
        if (!currencyService.isCashItem(event.getItemDrop().getItemStack())) return;

        if (!regionService.isAllowed(event.getPlayer().getLocation(), RegionData.RuleFlag.CASH_DROP_ENABLED)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessageFormatter().error(
                "Cash cannot be dropped in this area."));
        }
    }
    // ========================================================================

    /**
     * Ensure cash drops on death.
     * Cash in player inventory becomes DROPPED state.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerUuid = player.getUniqueId();

        // Mark all dropped cash items as DROPPED in the database
        List<ItemStack> drops = event.getDrops();
        for (ItemStack drop : drops) {
            if (currencyService.isCashItem(drop)) {
                UUID cashUuid = currencyService.getCashUuid(drop);
                if (cashUuid != null) {
                    currencyService.updateCashLocation(cashUuid, "DROPPED", playerUuid.toString());
                }
            }
        }
    }

    // ========================================================================
    // Pickup Validation
    // ========================================================================

    /**
     * When a player picks up an item, validate cash.
     * Invalid cash is destroyed on pickup.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (!currencyService.isCashItem(item)) return;

        // Validate the cash
        if (!currencyService.validateCash(item)) {
            event.setCancelled(true);
            event.getItem().remove();
            currencyService.invalidateCash(item, "COUNTERFEIT");
            return;
        }

        // Update location on pickup
        UUID cashUuid = currencyService.getCashUuid(item);
        if (cashUuid != null) {
            currencyService.updateCashLocation(cashUuid, "INVENTORY", player.getUniqueId().toString());
        }
    }

    /**
     * Prevent placing cash items into item frames.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.ItemFrame)) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (currencyService.isCashItem(held)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessageFormatter().error(
                "Cash cannot be placed in item frames."
            ));
        }
    }

    // ========================================================================
    // Player Join/Quit: Inventory Scanning
    // ========================================================================

    /**
     * Scan player inventory for cash on join.
     * Updates cash locations and removes counterfeit items.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        record SlotCash(int slot,CashSnapshot snapshot) { }
        List<SlotCash> captured=new java.util.ArrayList<>();ItemStack[] contents=player.getInventory().getContents();
        for(int slot=0;slot<contents.length;slot++){CashSnapshot snapshot=currencyService.snapshot(contents[slot]);if(snapshot!=null)captured.add(new SlotCash(slot,snapshot));}
        List<CashSnapshot> snapshots=captured.stream().map(SlotCash::snapshot).toList();
        currencyService.validateCashSnapshots(snapshots).whenComplete((records,failure)->plugin.getScheduler().runSync(()->{
            if(!player.isOnline())return;if(failure!=null){plugin.getLogger().warning("Cash join validation failed for "+player.getUniqueId()+": "+failure.getMessage());return;}
            int invalid=0;List<UUID> valid=new java.util.ArrayList<>();
            for(SlotCash capturedCash:captured){ItemStack current=player.getInventory().getItem(capturedCash.slot());CashSnapshot currentSnapshot=currencyService.snapshot(current);if(!capturedCash.snapshot().equals(currentSnapshot))continue;CashRecord record=records.get(currentSnapshot.cashUuid());if(record==null||!record.matches(currentSnapshot)){player.getInventory().setItem(capturedCash.slot(),null);invalid++;}else valid.add(record.cashUuid());}
            currencyService.updateCashLocations(valid,"INVENTORY",player.getUniqueId().toString());
            if(invalid>0)player.sendMessage(plugin.getMessageFormatter().warn(invalid+" counterfeit coin(s) were removed from your inventory."));
        }));
    }

    /**
     * On quit, mark cash as still in inventory (location tracking).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        List<UUID> cash=java.util.Arrays.stream(player.getInventory().getContents()).map(currencyService::snapshot)
            .filter(java.util.Objects::nonNull).map(CashSnapshot::cashUuid).toList();
        currencyService.updateCashLocations(cash,"OFFLINE",player.getUniqueId().toString());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private boolean isForbiddenContainer(Inventory inv) {
        if (inv == null) return false;
        // Check if it's a player inventory (allowed)
        if (inv.getType() == InventoryType.PLAYER) return false;

        // Check by inventory type
        return switch (inv.getType()) {
            case SHULKER_BOX, ENDER_CHEST, HOPPER, DROPPER, DISPENSER -> true;
            default -> false;
        };
    }

    /**
     * Check if an ItemStack is a forbidden container type.
     */
    public static boolean isForbiddenContainerItem(ItemStack item) {
        return item != null && FORBIDDEN_CONTAINERS.contains(item.getType());
    }
}
