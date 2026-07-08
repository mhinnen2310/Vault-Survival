package com.vaultsurvival.plugin.damage;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Listener that intercepts block breaks and places by visitors in district regions.
 *
 * When a non-member breaks/places a block in a region with TEMPORARY_DAMAGE_ENABLED:
 * - Drops are cancelled
 * - The original block state is saved
 * - A restore is scheduled
 */
public class DamageListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final DamageService damageService;
    private final MessageFormatter fmt;

    public DamageListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.damageService = plugin.getServiceRegistry().get(DamageService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (damageService == null) return;

        if (damageService.shouldTrackDamage(block.getLocation(), player.getUniqueId())) {
            // Cancel drops — visitor shouldn't get structure blocks
            event.setDropItems(false);

            // Don't cancel exp drops for functional/value blocks
            // Actually, cancel all drops for consistency
            event.setExpToDrop(0);

        int districtId = damageService.getDistrictIdForLocation(block.getLocation());
        if (districtId < 0) return;

        var record = damageService.recordBreak(block, player.getUniqueId(), districtId);
            if (record != null) {
                player.sendMessage(fmt.info("&7Block break recorded. It will be restored in &e" +
                    plugin.getConfigManager().getRestoreNormalDelayMinutes() + " minutes&7."));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (damageService == null) return;

        if (damageService.shouldTrackDamage(block.getLocation(), player.getUniqueId())) {
        int districtId = damageService.getDistrictIdForLocation(block.getLocation());
        if (districtId < 0) return;

        var record = damageService.recordPlace(block, player.getUniqueId(), districtId);
            if (record != null) {
                player.sendMessage(fmt.info("&7Block placed in a district zone. It will be removed in &e" +
                    plugin.getConfigManager().getRestoreNormalDelayMinutes() + " minutes&7."));
            }
        }
    }

    /** Handle TNT/creeper explosions. Block any explosion damage in protected zones. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            if (damageService != null && damageService.shouldTrackDamage(block.getLocation(), null)) {
                return true; // Remove from explosion — blocks in districts are protected
            }
            return false;
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> {
            if (damageService != null && damageService.shouldTrackDamage(block.getLocation(), null)) {
                return true;
            }
            return false;
        });
    }
}
