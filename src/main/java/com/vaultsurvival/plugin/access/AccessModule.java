package com.vaultsurvival.plugin.access;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Access module: Custom permission and rank system.
 * Loaded early because other modules depend on permission checks.
 */
public class AccessModule extends Module {

    private AccessServiceImpl accessService;

    public AccessModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Access";
    }

    @Override
    public void onLoad() {
        accessService = new AccessServiceImpl(plugin);
        accessService.loadGroups();
        accessService.initializeDefaultGroups();
        accessService.normalizeDefaultGroupHierarchy();

        // Register the service so other modules can use it
        plugin.getServiceRegistry().register(AccessService.class, accessService);
    }

    @Override
    public void onEnable() {
        // Register commands
        plugin.getCommand("rank").setExecutor(new AccessCommand(plugin));

        // Handle player join to ensure profile exists
        plugin.getServer().getPluginManager().registerEvents(
            new AccessListener(plugin, accessService), plugin
        );
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(AccessService.class);
    }

    public AccessServiceImpl getAccessService() {
        return accessService;
    }
}
