package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class FriendModule extends Module {
    private FriendServiceImpl service;
    public FriendModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Friends"; }
    @Override public void onLoad() {
        service = new FriendServiceImpl(plugin);
        plugin.getServiceRegistry().register(FriendService.class, service);
    }
    @Override public void onEnable() {
        service.loadAll();
        var cmd = new FriendCommand(plugin);
        plugin.getCommand("friend").setExecutor(cmd);
        plugin.getCommand("friend").setTabCompleter(cmd);
    }
    @Override public void onDisable() { plugin.getServiceRegistry().unregister(FriendService.class); }
}
