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
    private DistrictFoundingService foundingService;
    private DistrictFacilityService facilityService;
    private DistrictFarmService farmService;

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
        DistrictFoundingRepository foundingRepository=new DistrictFoundingRepository(plugin.getDatabase());
        DistrictFoundingValidationService foundingValidation=new DistrictFoundingValidationService(plugin,districtService);
        foundingService=new DistrictFoundingService(plugin,foundingRepository,foundingValidation);
        plugin.getServiceRegistry().register(DistrictFoundingRepository.class,foundingRepository);
        plugin.getServiceRegistry().register(DistrictFoundingValidationService.class,foundingValidation);
        plugin.getServiceRegistry().register(DistrictFoundingService.class,foundingService);
        selectionService = new DistrictSelectionService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictSelectionService.class, selectionService);
        npcPlanningService = new DistrictNpcPlanningService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictNpcPlanningService.class, npcPlanningService);
        developmentService = new DistrictDevelopmentService(plugin);
        plugin.getServiceRegistry().register(DistrictDevelopmentService.class, developmentService);
        treasuryService = new DistrictTreasuryServiceImpl(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictTreasuryService.class, treasuryService);
        facilityService=new DistrictFacilityService(plugin,districtService);
        plugin.getServiceRegistry().register(DistrictFacilityService.class,facilityService);
        farmService=new DistrictFarmService(plugin,districtService);
        plugin.getServiceRegistry().register(DistrictFarmService.class,farmService);
        restrictedLandService = new DistrictRestrictedLandService(plugin, districtService);
        plugin.getServiceRegistry().register(DistrictRestrictedLandService.class, restrictedLandService);
        townClerkService=new TownClerkService(plugin,foundingService);
        plugin.getServiceRegistry().register(TownClerkService.class,townClerkService);
        plugin.getServiceRegistry().register(TownClerkNpcHandler.class,new TownClerkNpcHandler(townClerkService));
        plugin.getLogger().info("District service registered");
    }

    @Override
    public void onEnable() {
        districtService.loadAll();
        facilityService.load().exceptionally(failure->{plugin.getLogger().severe("Facility levels failed to load: "+failure.getMessage());return null;});
        farmService.load().thenRun(farmService::start).exceptionally(failure->{plugin.getLogger().severe("District farms failed to load: "+failure.getMessage());return null;});
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
        farmService.stop();
        developmentService.stopMaintenanceScheduler();
        selectionService.shutdown();
        plugin.getServiceRegistry().unregister(DistrictNpcPlanningService.class);
        plugin.getServiceRegistry().unregister(DistrictTreasuryService.class);
        plugin.getServiceRegistry().unregister(DistrictFacilityService.class);
        plugin.getServiceRegistry().unregister(DistrictFarmService.class);
        plugin.getServiceRegistry().unregister(DistrictRestrictedLandService.class);
        plugin.getServiceRegistry().unregister(TownClerkNpcHandler.class);
        plugin.getServiceRegistry().unregister(TownClerkService.class);
        plugin.getServiceRegistry().unregister(DistrictFoundingService.class);
        plugin.getServiceRegistry().unregister(DistrictFoundingValidationService.class);
        plugin.getServiceRegistry().unregister(DistrictFoundingRepository.class);
        plugin.getServiceRegistry().unregister(DistrictDevelopmentService.class);
        plugin.getServiceRegistry().unregister(DistrictSelectionService.class);
        plugin.getServiceRegistry().unregister(DistrictService.class);
    }

    public DistrictServiceImpl getDistrictService() {
        return districtService;
    }
}
