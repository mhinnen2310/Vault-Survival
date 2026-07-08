package com.vaultsurvival.plugin.monitor;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class MonitorModule extends Module {
    public MonitorModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Monitor"; }
    @Override public void onLoad() {
        var analytics = new AnalyticsServiceImpl(plugin);
        plugin.getServiceRegistry().register(AnalyticsService.class, analytics);
    }
    @Override public void onEnable() {
        plugin.getCommand("analytics").setExecutor(new AnalyticsCommand(plugin));
        plugin.getCommand("vsadmin").setExecutor(new AdminCommand(plugin));
        plugin.getCommand("vsadmin").setTabCompleter(new AdminCommand(plugin));
    }
    @Override public void onDisable() {
        plugin.getServiceRegistry().unregister(AnalyticsService.class);
    }
}
