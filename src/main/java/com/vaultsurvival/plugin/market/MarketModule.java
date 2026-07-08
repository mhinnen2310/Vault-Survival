package com.vaultsurvival.plugin.market;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Market module: Physical Auction Hall.
 *
 * There is no global /ah — players must physically visit the Auction Hall.
 * Items are held in escrow. Buyers pay with physical cash.
 * Seller earnings go into a physical Auction Locker.
 */
public class MarketModule extends Module {

    private MarketServiceImpl marketService;

    public MarketModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Market";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Currency" };
    }

    @Override
    public void onLoad() {
        marketService = new MarketServiceImpl(plugin);
        plugin.getServiceRegistry().register(MarketService.class, marketService);
        plugin.getLogger().info("Market service registered");
    }

    @Override
    public void onEnable() {
        // Register commands
        var marketCmd = new MarketCommand(plugin);
        plugin.getCommand("ah").setExecutor(marketCmd);
        plugin.getCommand("ah").setTabCompleter(marketCmd);

        // Expire stale listings on startup
        marketService.expireStaleListings();
        plugin.getLogger().info("Checked for expired auction listings on startup");
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(MarketService.class);
    }

    public MarketServiceImpl getMarketService() {
        return marketService;
    }
}
