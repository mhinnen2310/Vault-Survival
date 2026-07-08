package com.vaultsurvival.plugin.core;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;

/**
 * Abstract base class for all Vault Survival modules.
 * Each module represents a self-contained system (VS-Currency, VS-Vaults, etc.)
 * and communicates with other modules through the ServiceRegistry.
 */
public abstract class Module {

    protected final VaultSurvivalPlugin plugin;
    private boolean enabled = false;

    public Module(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Called when the module is loaded and all dependencies are available.
     */
    public abstract void onLoad();

    /**
     * Called when the module should activate (register listeners, commands, tasks).
     */
    public abstract void onEnable();

    /**
     * Called when the module should deactivate and clean up.
     */
    public abstract void onDisable();

    /**
     * @return Unique name identifier for this module (e.g. "VS-Currency").
     */
    public abstract String getName();

    /**
     * @return List of module names this module depends on, or empty array if none.
     */
    public String[] getDependencies() {
        return new String[0];
    }

    public boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
