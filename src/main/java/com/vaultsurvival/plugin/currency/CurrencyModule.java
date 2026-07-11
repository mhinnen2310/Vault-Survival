package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Currency module: Physical cash system.
 *
 * Core principles:
 * - No /balance. No digital bank.
 * - Every coin has a server-authoritative database record.
 * - Cash items in inventory must match valid DB records.
 * - Forbidden containers: shulkers, bundles, ender chests, hoppers, etc.
 */
public class CurrencyModule extends Module {

    private CurrencyServiceImpl currencyService;
    private CurrencyListener currencyListener;

    public CurrencyModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Currency";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Access" };
    }

    @Override
    public void onLoad() {
        currencyService = new CurrencyServiceImpl(plugin);
        plugin.getServiceRegistry().register(CurrencyService.class, currencyService);
        plugin.getLogger().info("Currency service registered");
    }

    @Override
    public void onEnable() {
        // Register event listener
        currencyListener = new CurrencyListener(plugin, currencyService);
        plugin.getServer().getPluginManager().registerEvents(currencyListener, plugin);

        // Register admin command
        plugin.getCommand("cash").setExecutor(new CurrencyCommand(plugin));
        plugin.getCommand("cashsplit").setExecutor(new CashSplitCommand(plugin, currencyService));
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(CurrencyService.class);
    }

    public CurrencyServiceImpl getCurrencyService() {
        return currencyService;
    }
}
