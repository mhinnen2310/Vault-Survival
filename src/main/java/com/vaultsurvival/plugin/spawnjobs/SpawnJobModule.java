package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class SpawnJobModule extends Module {
    private SpawnJobServiceImpl service;
    public SpawnJobModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-SpawnJobs"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Contracts", "VS-Currency", "VS-NPC" }; }
    @Override public void onLoad() {
        service = new SpawnJobServiceImpl(plugin);
        plugin.getServiceRegistry().register(SpawnJobService.class, service);
    }
    @Override public void onEnable() {
        service.seedStarterJobs();
        service.loadAll();
        var cmd = new SpawnJobCommand(plugin);
        plugin.getCommand("spawnjobs").setExecutor(cmd);
        plugin.getCommand("spawnjobs").setTabCompleter(cmd);
        plugin.getServer().getPluginManager().registerEvents(new SpawnJobListener(plugin, service), plugin);
    }
    @Override public void onDisable() {
        plugin.getServiceRegistry().unregister(SpawnJobService.class);
    }
}
