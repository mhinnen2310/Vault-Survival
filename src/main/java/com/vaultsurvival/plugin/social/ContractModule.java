package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class ContractModule extends Module {
    private ContractServiceImpl service;
    public ContractModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Contracts"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Currency" }; }
    @Override public void onLoad() {
        service = new ContractServiceImpl(plugin);
        plugin.getServiceRegistry().register(ContractService.class, service);
    }
    @Override public void onEnable() {
        service.loadAll();
        var cmd = new ContractCommand(plugin);
        plugin.getCommand("contract").setExecutor(cmd);
        plugin.getCommand("contract").setTabCompleter(cmd);
    }
    @Override public void onDisable() { plugin.getServiceRegistry().unregister(ContractService.class); }
}
