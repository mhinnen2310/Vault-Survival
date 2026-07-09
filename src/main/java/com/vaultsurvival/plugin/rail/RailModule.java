package com.vaultsurvival.plugin.rail;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class RailModule extends Module {
    private RailServiceImpl service;

    public RailModule(VaultSurvivalPlugin plugin) { super(plugin); }

    @Override public String getName() { return "VS-Rail"; }

    @Override public String[] getDependencies() {
        return new String[] { "VS-Currency", "VS-Districts" };
    }

    @Override public void onLoad() {
        service = new RailServiceImpl(plugin);
        plugin.getServiceRegistry().register(RailService.class, service);
        plugin.getLogger().info("Rail service registered");
    }

    @Override public void onEnable() {
        service.loadAll();
        var cmd = new RailCommand(plugin);
        plugin.getCommand("rail").setExecutor(cmd);
        plugin.getCommand("rail").setTabCompleter(cmd);
        plugin.getServer().getPluginManager().registerEvents(new RailJourneyListener(plugin), plugin);
        plugin.getLogger().info("Rail commands and journey listener registered");
    }

    @Override public void onDisable() {
        plugin.getServiceRegistry().unregister(RailService.class);
    }
}
