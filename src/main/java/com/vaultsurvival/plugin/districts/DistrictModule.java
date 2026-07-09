package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-Districts module: District foundation system.
 *
 * Players can apply to found official districts. Admins approve/reject.
 * Districts have members with roles, a treasury, and configurable local laws.
 */
public class DistrictModule extends Module {

    private DistrictServiceImpl districtService;
    private DistrictSelectionService selectionService;
    private DistrictNpcPlanningService npcPlanningService;

    public DistrictModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Districts";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Currency", "VS-Regions" };
    }

    @Override
    public void onLoad() {
        districtService = new DistrictServiceImpl(plugin);
        plugin.getServiceRegistry().register(DistrictService.class, districtService);
        selectionService = new DistrictSelectionService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictSelectionService.class, selectionService);
        npcPlanningService = new DistrictNpcPlanningService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictNpcPlanningService.class, npcPlanningService);
        plugin.getLogger().info("District service registered");
    }

    @Override
    public void onEnable() {
        districtService.loadAll();
        districtService.startLawReloadScheduler();
        selectionService.startOverlay();
        plugin.getServer().getPluginManager().registerEvents(selectionService, plugin);
        plugin.getServer().getPluginManager().registerEvents(npcPlanningService, plugin);

        var cmd = new DistrictCommand(plugin);
        plugin.getCommand("district").setExecutor(cmd);
        plugin.getCommand("district").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        districtService.stopLawReloadScheduler();
        selectionService.shutdown();
        plugin.getServiceRegistry().unregister(DistrictNpcPlanningService.class);
        plugin.getServiceRegistry().unregister(DistrictSelectionService.class);
        plugin.getServiceRegistry().unregister(DistrictService.class);
    }

    public DistrictServiceImpl getDistrictService() {
        return districtService;
    }
}
