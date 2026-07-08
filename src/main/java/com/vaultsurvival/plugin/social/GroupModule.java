package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class GroupModule extends Module {
    private GroupServiceImpl service;
    public GroupModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Groups"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Friends" }; }
    @Override public void onLoad() {
        service = new GroupServiceImpl(plugin);
        plugin.getServiceRegistry().register(GroupService.class, service);
    }
    @Override public void onEnable() {
        service.loadAll();
        var cmd = new GroupCommand(plugin);
        plugin.getCommand("group").setExecutor(cmd);
        plugin.getCommand("group").setTabCompleter(cmd);
    }
    @Override public void onDisable() { plugin.getServiceRegistry().unregister(GroupService.class); }
}
