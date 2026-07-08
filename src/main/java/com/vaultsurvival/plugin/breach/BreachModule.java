package com.vaultsurvival.plugin.breach;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Breach module: Vault breaching system.
 *
 * The only way to steal cash from vaults. Players use breach kits
 * to initiate a multi-stage minigame. Success steals up to 50% of
 * the vault's balance. Vaults enter lockdown after any breach attempt.
 */
public class BreachModule extends Module {

    private BreachServiceImpl breachService;
    private BreachListener breachListener;

    public BreachModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Breach";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Vaults", "VS-Currency", "VS-Regions" };
    }

    @Override
    public void onLoad() {
        breachService = new BreachServiceImpl(plugin);
        plugin.getServiceRegistry().register(BreachService.class, breachService);
        plugin.getLogger().info("Breach service registered");
    }

    @Override
    public void onEnable() {
        // Register event listener
        breachListener = new BreachListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(breachListener, plugin);

        // Register commands
        var breachCmd = new BreachCommand(plugin);
        plugin.getCommand("breach").setExecutor(breachCmd);
        plugin.getCommand("breach").setTabCompleter(breachCmd);
    }

    @Override
    public void onDisable() {
        // Cancel all active breaches
        breachService.cancelAllBreaches();
        plugin.getServiceRegistry().unregister(BreachService.class);
    }

    public BreachServiceImpl getBreachService() {
        return breachService;
    }
}
