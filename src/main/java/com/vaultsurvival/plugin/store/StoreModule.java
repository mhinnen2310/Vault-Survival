package com.vaultsurvival.plugin.store;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class StoreModule extends Module {
    private StoreServiceImpl service;
    public StoreModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Store"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Currency" }; }
    @Override public void onLoad() {
        service = new StoreServiceImpl(plugin);
        plugin.getServiceRegistry().register(StoreService.class, service);
        service.loadItems();
    }
    @Override public void onEnable() {
        var cmd = new StoreCommand(plugin);
        plugin.getCommand("store").setExecutor(cmd);
        plugin.getCommand("store").setTabCompleter(cmd);
    }
    @Override public void onDisable() { plugin.getServiceRegistry().unregister(StoreService.class); }
}
