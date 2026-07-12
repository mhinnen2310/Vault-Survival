package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Regions module: Custom region/rules system.
 *
 * Replaces WorldGuard for core gameplay rules. Regions are cuboid areas
 * with configurable rule flags. Overlapping regions resolve by priority.
 */
public class RegionModule extends Module {

    private RegionServiceImpl regionService;
    private RegionVisualizationService visualizationService;
    private RegionSelectionService selectionService;
    private RegionListener regionListener;

    public RegionModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Regions";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Access" };
    }

    @Override
    public void onLoad() {
        regionService = new RegionServiceImpl(plugin);
        visualizationService = new RegionVisualizationService(plugin);
        selectionService = new RegionSelectionService(visualizationService);
        plugin.getServiceRegistry().register(RegionService.class, regionService);
        plugin.getServiceRegistry().register(RegionVisualizationService.class, visualizationService);
        plugin.getServiceRegistry().register(RegionSelectionService.class, selectionService);
        plugin.getLogger().info("Region service registered");
    }

    @Override
    public void onEnable() {
        // Load regions from DB
        regionService.loadAll();
        visualizationService.start();
        plugin.getServer().getPluginManager().registerEvents(visualizationService, plugin);

        // Register command
        var regionCmd = new RegionCommand(plugin, selectionService, visualizationService);
        plugin.getCommand("region").setExecutor(regionCmd);
        plugin.getCommand("region").setTabCompleter(regionCmd);

        // Register wand listener
        regionListener = new RegionListener(plugin, regionCmd, selectionService);
        plugin.getServer().getPluginManager().registerEvents(regionListener, plugin);
    }

    @Override
    public void onDisable() {
        visualizationService.shutdown();
        plugin.getServiceRegistry().unregister(RegionSelectionService.class);
        plugin.getServiceRegistry().unregister(RegionVisualizationService.class);
        plugin.getServiceRegistry().unregister(RegionService.class);
    }

    public RegionServiceImpl getRegionService() {
        return regionService;
    }
}
