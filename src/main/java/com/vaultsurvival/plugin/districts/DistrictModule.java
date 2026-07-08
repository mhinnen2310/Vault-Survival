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
        plugin.getLogger().info("District service registered");
    }

    @Override
    public void onEnable() {
        districtService.loadAll();

        var cmd = new DistrictCommand(plugin);
        plugin.getCommand("district").setExecutor(cmd);
        plugin.getCommand("district").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(DistrictService.class);
    }

    public DistrictServiceImpl getDistrictService() {
        return districtService;
    }
}
