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
    private CashPaymentService cashPaymentService;
    private CashRecoveryService cashRecoveryService;
    private org.bukkit.scheduler.BukkitTask statsTask;
    private PayoutDeliveryService payoutDeliveryService;

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
        CashLedgerRepository ledger = new CashLedgerRepository(plugin.getDatabase());
        CashRecoveryJournal journal = new CashRecoveryJournal(plugin.getDatabase());
        CashTransactionCoordinator coordinator = new CashTransactionCoordinator(ledger, journal);
        cashPaymentService = new CashPaymentService(plugin, currencyService, coordinator);
        cashRecoveryService = new CashRecoveryService(plugin,currencyService);
        payoutDeliveryService=new PayoutDeliveryService(plugin,cashRecoveryService);
        plugin.getServiceRegistry().register(CashLedgerRepository.class, ledger);
        plugin.getServiceRegistry().register(CashRecoveryJournal.class, journal);
        plugin.getServiceRegistry().register(CashTransactionCoordinator.class, coordinator);
        plugin.getServiceRegistry().register(CashPaymentService.class, cashPaymentService);
        plugin.getServiceRegistry().register(CashRecoveryService.class,cashRecoveryService);
        plugin.getServiceRegistry().register(PayoutDeliveryService.class,payoutDeliveryService);
        plugin.getLogger().info("Currency service registered");
    }

    @Override
    public void onEnable() {
        // Register event listener
        currencyListener = new CurrencyListener(plugin, currencyService);
        plugin.getServer().getPluginManager().registerEvents(currencyListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(cashRecoveryService,plugin);
        cashRecoveryService.recoverStartupTransactions().whenComplete((count,failure)->{
            if(failure!=null)plugin.getLogger().severe("Cash recovery startup scan failed: "+failure.getMessage());
            else if(count>0)plugin.getLogger().warning("Moved "+count+" interrupted cash deliveries to recovery lockers.");
        });
        currencyService.refreshStatsAsync();
        statsTask=plugin.getServer().getScheduler().runTaskTimer(plugin,currencyService::refreshStatsAsync,1200L,1200L);

        // Register admin command
        plugin.getCommand("cash").setExecutor(new CurrencyCommand(plugin));
        plugin.getCommand("cashsplit").setExecutor(new CashSplitCommand(plugin, currencyService));
    }

    @Override
    public void onDisable() {
        if(statsTask!=null)statsTask.cancel();
        plugin.getServiceRegistry().unregister(CurrencyService.class);
        plugin.getServiceRegistry().unregister(CashPaymentService.class);
        plugin.getServiceRegistry().unregister(CashRecoveryService.class);
        plugin.getServiceRegistry().unregister(PayoutDeliveryService.class);
        plugin.getServiceRegistry().unregister(CashTransactionCoordinator.class);
        plugin.getServiceRegistry().unregister(CashRecoveryJournal.class);
        plugin.getServiceRegistry().unregister(CashLedgerRepository.class);
    }

    public CurrencyServiceImpl getCurrencyService() {
        return currencyService;
    }
}
