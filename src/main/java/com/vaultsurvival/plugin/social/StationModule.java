package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class StationModule extends Module {
    private StationServiceImpl service;
    public StationModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Stations"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Currency" }; }
    @Override public void onLoad() {
        service = new StationServiceImpl(plugin);
        plugin.getServiceRegistry().register(StationService.class, service);
    }
    @Override public void onEnable() {
        service.loadAll();
        var cmd = new StationCommand(plugin);
        plugin.getCommand("station").setExecutor(cmd);
        plugin.getCommand("station").setTabCompleter(cmd);
    }
    @Override public void onDisable() { plugin.getServiceRegistry().unregister(StationService.class); }
}
