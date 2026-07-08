package com.vaultsurvival.plugin.repair;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;
import com.vaultsurvival.plugin.damage.DamageService;

/**
 * VS-Repair module: Repairmen system.
 *
 * Each district has a pool of daily repair points (default 500).
 * When temporary damage is recorded, points are consumed.
 * When points run out, restores slow to 30 min (exhaustion delay).
 * Repairmen must be paid a daily wage from district treasury or points won't reset.
 * Council+ can use emergency repair (5 points) to force immediate restoration.
 */
public class RepairModule extends Module {

    private RepairServiceImpl repairService;

    public RepairModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Repair";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Damage", "VS-Districts", "VS-Currency", "VS-NPC" };
    }

    @Override
    public void onLoad() {
        repairService = new RepairServiceImpl(plugin);
        plugin.getServiceRegistry().register(RepairService.class, repairService);
        plugin.getLogger().info("Repair service registered");
    }

    @Override
    public void onEnable() {
        // Load existing states from DB
        repairService.loadAll();

        // Wire into DamageService so it uses repair-aware restore delays
        var damageService = plugin.getServiceRegistry().get(DamageService.class);
        if (damageService != null) {
            damageService.setRepairService(repairService);
        }

        // Start daily reset scheduler (checks every 5 minutes)
        repairService.startDailyScheduler();

        // Process any due resets immediately
        int reset = repairService.processDailyReset();
        if (reset > 0) {
            plugin.getLogger().info("Processed " + reset + " daily resets on startup");
        }

        // Register command
        var cmd = new RepairCommand(plugin);
        plugin.getCommand("repair").setExecutor(cmd);
        plugin.getCommand("repair").setTabCompleter(cmd);

        plugin.getLogger().info("Repair system enabled");
    }

    @Override
    public void onDisable() {
        repairService.stopDailyScheduler();
        repairService.saveAll();
        plugin.getServiceRegistry().unregister(RepairService.class);
    }

    public RepairServiceImpl getRepairService() {
        return repairService;
    }
}
