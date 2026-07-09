package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class ContractModule extends Module {
    private ContractServiceImpl service;
    private ContractAuditServiceImpl audit;
    private PayoutLockerServiceImpl payouts;
    private EscrowServiceImpl escrow;
    private ContractDisputeServiceImpl disputes;
    private com.vaultsurvival.plugin.districts.DistrictJobServiceImpl districtJobs;
    public ContractModule(VaultSurvivalPlugin plugin) { super(plugin); }
    @Override public String getName() { return "VS-Contracts"; }
    @Override public String[] getDependencies() { return new String[] { "VS-Currency" }; }
    @Override public void onLoad() {
        audit = new ContractAuditServiceImpl(plugin);
        plugin.getServiceRegistry().register(ContractAuditService.class, audit);
        payouts = new PayoutLockerServiceImpl(plugin, audit);
        plugin.getServiceRegistry().register(PayoutLockerService.class, payouts);
        escrow = new EscrowServiceImpl(plugin, payouts, audit);
        plugin.getServiceRegistry().register(EscrowService.class, escrow);
        disputes = new ContractDisputeServiceImpl(plugin, escrow, audit);
        plugin.getServiceRegistry().register(ContractDisputeService.class, disputes);
        service = new ContractServiceImpl(plugin, escrow, audit, disputes);
        plugin.getServiceRegistry().register(ContractService.class, service);
    }
    @Override public void onEnable() {
        service.loadAll();
        var cmd = new ContractCommand(plugin);
        plugin.getCommand("contract").setExecutor(cmd);
        plugin.getCommand("contract").setTabCompleter(cmd);
        plugin.getCommand("escrow").setExecutor(cmd);
        plugin.getCommand("escrow").setTabCompleter(cmd);
        plugin.getCommand("payouts").setExecutor(cmd);
        plugin.getCommand("payouts").setTabCompleter(cmd);

        districtJobs = new com.vaultsurvival.plugin.districts.DistrictJobServiceImpl(plugin);
        districtJobs.loadAll();
        plugin.getServiceRegistry().register(com.vaultsurvival.plugin.districts.DistrictJobService.class, districtJobs);
    }
    @Override public void onDisable() {
        plugin.getServiceRegistry().unregister(ContractService.class);
        plugin.getServiceRegistry().unregister(ContractDisputeService.class);
        plugin.getServiceRegistry().unregister(EscrowService.class);
        plugin.getServiceRegistry().unregister(PayoutLockerService.class);
        plugin.getServiceRegistry().unregister(ContractAuditService.class);
        plugin.getServiceRegistry().unregister(com.vaultsurvival.plugin.districts.DistrictJobService.class);
    }
}
