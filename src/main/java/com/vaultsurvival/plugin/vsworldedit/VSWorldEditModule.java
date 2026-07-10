package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-WorldEdit module: Lightweight internal admin building toolkit.
 * No external WorldEdit dependency. Purpose-built for Spawn City construction,
 * region selection, and safe admin block operations.
 */
public class VSWorldEditModule extends Module {

    private VSWorldEditServiceImpl service;
    private VSWorldEditListener listener;

    public VSWorldEditModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-WorldEdit";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Regions" };
    }

    @Override
    public void onLoad() {
        service = new VSWorldEditServiceImpl(plugin);
        plugin.getServiceRegistry().register(VSWorldEditService.class, service);
        plugin.getLogger().info("VS-WorldEdit service registered");
    }

    @Override
    public void onEnable() {
        PatternParserDiagnostics.Result diagnostics = PatternParserDiagnostics.runDefaults();
        if (!diagnostics.passed()) {
            throw new IllegalStateException("VWE pattern parser self-test failed: " + String.join(", ", diagnostics.failures()));
        }
        plugin.getLogger().info("VWE pattern parser self-test passed (" + diagnostics.checks() + " checks)");

        // Register wand listener
        listener = new VSWorldEditListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        // Register commands
        var cmd = new VSWorldEditCommand(plugin);
        plugin.getCommand("vwe").setExecutor(cmd);
        plugin.getCommand("vwe").setTabCompleter(cmd);

        plugin.getLogger().info("VS-WorldEdit enabled");
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(VSWorldEditService.class);
    }

    public VSWorldEditServiceImpl getService() {
        return service;
    }
}
