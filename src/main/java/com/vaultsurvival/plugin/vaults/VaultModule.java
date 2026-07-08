package com.vaultsurvival.plugin.vaults;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Vaults module: Physical vault storage system.
 * Vaults are physical blocks in the world that store cash.
 * They cannot be broken normally and always protect 50% of their value.
 */
public class VaultModule extends Module {

    private VaultServiceImpl vaultService;
    private VaultListener vaultListener;

    public VaultModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Vaults";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Access", "VS-Currency", "VS-Regions" };
    }

    @Override
    public void onLoad() {
        vaultService = new VaultServiceImpl(plugin);
        plugin.getServiceRegistry().register(VaultService.class, vaultService);
        plugin.getLogger().info("Vault service registered");
    }

    @Override
    public void onEnable() {
        // Register event listener
        vaultListener = new VaultListener(plugin, vaultService);
        plugin.getServer().getPluginManager().registerEvents(vaultListener, plugin);

        // Register commands
        var vaultCmd = new VaultCommand(plugin);
        plugin.getCommand("vault").setExecutor(vaultCmd);
        plugin.getCommand("vault").setTabCompleter(vaultCmd);
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(VaultService.class);
    }

    public VaultServiceImpl getVaultService() {
        return vaultService;
    }
}
