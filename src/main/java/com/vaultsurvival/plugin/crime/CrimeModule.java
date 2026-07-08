package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Crime module: Crime & Police system.
 *
 * Automatically logs crimes when valuable blocks are stolen in districts.
 * Police players can arrest, fine, or set bounties on wanted criminals.
 */
public class CrimeModule extends Module {

    private CrimeServiceImpl crimeService;

    public CrimeModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Crime";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Damage", "VS-Districts", "VS-Currency" };
    }

    @Override
    public void onLoad() {
        crimeService = new CrimeServiceImpl(plugin);
        plugin.getServiceRegistry().register(CrimeService.class, crimeService);
        plugin.getLogger().info("Crime service registered");
    }

    @Override
    public void onEnable() {
        crimeService.loadAll();

        // Register listeners
        plugin.getServer().getPluginManager().registerEvents(new CrimeListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new CrimeJailListener(plugin), plugin);

        // Start jail release scheduler (every 30 seconds)
        crimeService.startJailScheduler();

        // Register command
        var cmd = new CrimeCommand(plugin);
        plugin.getCommand("crime").setExecutor(cmd);
        plugin.getCommand("crime").setTabCompleter(cmd);

        plugin.getLogger().info("Crime system enabled — " + getWantedCount() + " wanted, " + getJailedCount() + " jailed");
    }

    @Override
    public void onDisable() {
        crimeService.stopJailScheduler();
        plugin.getServiceRegistry().unregister(CrimeService.class);
    }

    private int getJailedCount() {
        int count = 0;
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService != null) {
            for (var d : districtService.getAllDistricts()) {
                count += crimeService.getJailedPlayers(d.getId()).size();
            }
        }
        return count;
    }

    private int getWantedCount() {
        int count = 0;
        var districtService = plugin.getServiceRegistry().get(
            com.vaultsurvival.plugin.districts.DistrictService.class);
        if (districtService != null) {
            for (var d : districtService.getAllDistricts()) {
                count += crimeService.getWantedPlayers(d.getId()).size();
            }
        }
        return count;
    }
}
