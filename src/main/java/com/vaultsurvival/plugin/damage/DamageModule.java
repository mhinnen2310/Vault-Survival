package com.vaultsurvival.plugin.damage;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Damage module: Temporary District Damage system.
 *
 * When non-district-members break or place blocks inside district regions,
 * the damage is recorded and automatically restored after a configurable delay.
 * Drops are cancelled to prevent permanent grief/stealing of structure blocks.
 *
 * Chests can be looted by visitors, but restored empty.
 * Explosions are blocked in protected zones.
 */
public class DamageModule extends Module {

    private DamageServiceImpl damageService;

    public DamageModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Damage";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Regions", "VS-Districts" };
    }

    @Override
    public void onLoad() {
        damageService = new DamageServiceImpl(plugin);
        plugin.getServiceRegistry().register(DamageService.class, damageService);
        plugin.getLogger().info("Damage service registered");
    }

    @Override
    public void onEnable() {
        // Load existing records from DB
        damageService.loadAll();

        // Start periodic restore checker (every 30 seconds)
        damageService.startRestoreScheduler();

        // Register listener for block events
        plugin.getServer().getPluginManager().registerEvents(new DamageListener(plugin), plugin);

        // Register command
        var cmd = new DamageCommand(plugin);
        plugin.getCommand("damage").setExecutor(cmd);
        plugin.getCommand("damage").setTabCompleter(cmd);

        plugin.getLogger().info("Damage system enabled — " + damageService.getPendingCount() + " pending restores");
    }

    @Override
    public void onDisable() {
        damageService.stopRestoreScheduler();
        // Process any remaining restores
        damageService.processRestores();
        plugin.getServiceRegistry().unregister(DamageService.class);
    }

    public DamageServiceImpl getDamageService() {
        return damageService;
    }
}
