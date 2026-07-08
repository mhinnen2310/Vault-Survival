package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.damage.DamageData;
import com.vaultsurvival.plugin.damage.DamageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Listener that automatically logs crimes when valuable blocks are stolen.
 * Runs at MONITOR priority (after DamageListener has already processed the break).
 */
public class CrimeListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final DamageService damageService;
    private final CrimeService crimeService;

    public CrimeListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.damageService = plugin.getServiceRegistry().get(DamageService.class);
        this.crimeService = plugin.getServiceRegistry().get(CrimeService.class);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (damageService == null || crimeService == null) return;

        var block = event.getBlock();
        var player = event.getPlayer();

        // Only auto-log if this is tracked damage in a district (non-member breaking blocks)
        if (!damageService.shouldTrackDamage(block.getLocation(), player.getUniqueId())) return;

        int districtId = damageService.getDistrictIdForLocation(block.getLocation());
        if (districtId < 0) return;

        // Check if the block is valuable — if so, auto-log as theft
        var blockClass = DamageData.DamageRecord.classify(block.getType());
        if (blockClass == DamageData.BlockClass.VALUABLE) {
            String location = block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
            crimeService.logCrime(
                player.getUniqueId(),
                districtId,
                CrimeData.CrimeType.THEFT,
                CrimeData.CrimeSeverity.MODERATE,
                block.getType().name(),
                location
            );
        } else if (blockClass == DamageData.BlockClass.CONTAINER) {
            // Breaking into containers is also a crime
            String location = block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
            crimeService.logCrime(
                player.getUniqueId(),
                districtId,
                CrimeData.CrimeType.THEFT,
                CrimeData.CrimeSeverity.MINOR,
                block.getType().name(),
                location
            );
        }
    }
}
