package com.vaultsurvival.plugin.display;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Display module: Display Auction Hall.
 *
 * ItemDisplays and TextDisplays are spawned at designated slot locations
 * to physically show auction listings in the Auction Hall.
 * Sold animations play when items are purchased.
 */
public class DisplayModule extends Module {

    private DisplayServiceImpl displayService;

    public DisplayModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Display";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Market" };
    }

    @Override
    public void onLoad() {
        displayService = new DisplayServiceImpl(plugin);
        plugin.getServiceRegistry().register(DisplayService.class, displayService);
        plugin.getLogger().info("Display service registered");
    }

    @Override
    public void onEnable() {
        // Load existing slots from DB
        displayService.loadAll();

        // Initial refresh of all displays
        int refreshed = displayService.refreshAll();
        plugin.getLogger().info("Display system enabled — " + refreshed + " slots refreshed");

        // Start periodic refresh (every 60 seconds)
        displayService.startRefreshScheduler();

        // Register command
        var cmd = new DisplayCommand(plugin);
        plugin.getCommand("displays").setExecutor(cmd);
        plugin.getCommand("displays").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        displayService.stopRefreshScheduler();
        displayService.despawnAll();
        plugin.getServiceRegistry().unregister(DisplayService.class);
    }
}
