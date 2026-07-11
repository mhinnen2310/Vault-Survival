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
    private DistrictDevelopmentService developmentService;
    private DistrictTreasuryServiceImpl treasuryService;
    private DistrictTreasuryListener treasuryListener;
    private DistrictRestrictedLandService restrictedLandService;
    private TownClerkService townClerkService;

    public DistrictModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Districts";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Currency", "VS-Regions", "VS-NPC" };
    }

    @Override
    public void onLoad() {
        districtService = new DistrictServiceImpl(plugin);
        plugin.getServiceRegistry().register(DistrictService.class, districtService);
        selectionService = new DistrictSelectionService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictSelectionService.class, selectionService);
        npcPlanningService = new DistrictNpcPlanningService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictNpcPlanningService.class, npcPlanningService);
        developmentService = new DistrictDevelopmentService(plugin);
        plugin.getServiceRegistry().register(DistrictDevelopmentService.class, developmentService);
        treasuryService = new DistrictTreasuryServiceImpl(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictTreasuryService.class, treasuryService);
        restrictedLandService = new DistrictRestrictedLandService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictRestrictedLandService.class, restrictedLandService);
        townClerkService=new TownClerkService(plugin);
        plugin.getServiceRegistry().register(TownClerkService.class,townClerkService);
        plugin.getServiceRegistry().register(TownClerkNpcHandler.class,new TownClerkNpcHandler(townClerkService));
        plugin.getLogger().info("District service registered");
    }

    @Override
    public void onEnable() {
        districtService.loadAll();
        districtService.startLawReloadScheduler();
        developmentService.startMaintenanceScheduler();
        selectionService.startOverlay();
        plugin.getServer().getPluginManager().registerEvents(selectionService, plugin);
        plugin.getServer().getPluginManager().registerEvents(npcPlanningService, plugin);
        treasuryListener = new DistrictTreasuryListener(plugin, treasuryService);
        plugin.getServer().getPluginManager().registerEvents(treasuryListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(restrictedLandService, plugin);
        treasuryService.reportLegacyBalances();

        var cmd = new DistrictCommand(plugin, developmentService);
        plugin.getCommand("district").setExecutor(cmd);
        plugin.getCommand("district").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        districtService.stopLawReloadScheduler();
        developmentService.stopMaintenanceScheduler();
        selectionService.shutdown();
        plugin.getServiceRegistry().unregister(DistrictNpcPlanningService.class);
        plugin.getServiceRegistry().unregister(DistrictTreasuryService.class);
        plugin.getServiceRegistry().unregister(DistrictRestrictedLandService.class);
        plugin.getServiceRegistry().unregister(TownClerkNpcHandler.class);
        plugin.getServiceRegistry().unregister(TownClerkService.class);
        plugin.getServiceRegistry().unregister(DistrictDevelopmentService.class);
        plugin.getServiceRegistry().unregister(DistrictSelectionService.class);
        plugin.getServiceRegistry().unregister(DistrictService.class);
    }

    public DistrictServiceImpl getDistrictService() {
        return districtService;
    }
}
