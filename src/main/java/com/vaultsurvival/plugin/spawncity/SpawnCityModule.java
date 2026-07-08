package com.vaultsurvival.plugin.spawncity;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-SpawnCity module: Manages the Kingdom capital city.
 */
public class SpawnCityModule extends Module {

    private SpawnCityServiceImpl service;

    public SpawnCityModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-SpawnCity";
    }

    @Override
    public void onLoad() {
        service = new SpawnCityServiceImpl(plugin);
        plugin.getServiceRegistry().register(SpawnCityService.class, service);
        plugin.getLogger().info("Spawn city service registered: " + service.getCityName());
    }

    @Override
    public void onEnable() {
        var cmd = new SpawnCityCommand(plugin);
        plugin.getCommand("spawncity").setExecutor(cmd);
        plugin.getCommand("spawncity").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(SpawnCityService.class);
    }

    public SpawnCityServiceImpl getService() {
        return service;
    }
}
