package com.vaultsurvival.plugin;

import com.vaultsurvival.plugin.access.AccessModule;
import com.vaultsurvival.plugin.area.CurrentAreaService;
import com.vaultsurvival.plugin.area.WhereAmICommand;
import com.vaultsurvival.plugin.breach.BreachModule;
import com.vaultsurvival.plugin.chat.ChatListener;
import com.vaultsurvival.plugin.chat.ChatCommand;
import com.vaultsurvival.plugin.chat.ChatChannelService;
import com.vaultsurvival.plugin.commands.VSVersionCommand;
import com.vaultsurvival.plugin.commands.VSGiveCommand;
import com.vaultsurvival.plugin.core.*;
import com.vaultsurvival.plugin.currency.CurrencyModule;
import com.vaultsurvival.plugin.crime.CrimeModule;
import com.vaultsurvival.plugin.damage.DamageModule;
import com.vaultsurvival.plugin.commands.ResourcePackCommand;
import com.vaultsurvival.plugin.display.DisplayModule;
import com.vaultsurvival.plugin.dialogs.DialogModule;
import com.vaultsurvival.plugin.merchant.MerchantOrderModule;
import com.vaultsurvival.plugin.rail.RailModule;
import com.vaultsurvival.plugin.monitor.MonitorModule;
import com.vaultsurvival.plugin.store.StoreModule;
import com.vaultsurvival.plugin.social.FriendModule;
import com.vaultsurvival.plugin.social.GroupModule;
import com.vaultsurvival.plugin.social.StationModule;
import com.vaultsurvival.plugin.social.ContractModule;
import com.vaultsurvival.plugin.districts.DistrictModule;
import com.vaultsurvival.plugin.repair.RepairModule;
import com.vaultsurvival.plugin.market.MarketModule;
import com.vaultsurvival.plugin.npc.NpcModule;
import com.vaultsurvival.plugin.regions.RegionModule;
import com.vaultsurvival.plugin.staffmode.StaffmodeModule;
import com.vaultsurvival.plugin.spawncity.SpawnCityModule;
import com.vaultsurvival.plugin.spawnjobs.SpawnJobModule;
import com.vaultsurvival.plugin.updates.UpdateService;
import com.vaultsurvival.plugin.vaults.VaultModule;
import com.vaultsurvival.plugin.vsworldedit.VSWorldEditModule;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vault Survival - Main Paper Plugin
 *
 * Physical economy Minecraft gamemode where:
 * - Money is physical items, not digital balances
 * - Vaults protect wealth but can be partially breached
 * - Districts protect buildings but not loot
 * - Crime, policing, trade, and politics emerge naturally
 */
public class VaultSurvivalPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private AuditLogger auditLogger;
    private MessageFormatter messageFormatter;
    private SchedulerHelper schedulerHelper;
    private TransactionHelper transactionHelper;
    private GUIFramework guiFramework;
    private ServiceRegistry serviceRegistry;
    private ModuleManager moduleManager;

    // Modules
    private AccessModule accessModule;
    private StaffmodeModule staffmodeModule;

    @Override
    public void onEnable() {
        // Initialize core infrastructure
        this.serviceRegistry = new ServiceRegistry();
        this.configManager = new ConfigManager(getLogger(), getDataFolder());
        this.databaseManager = new DatabaseManager(getLogger(), getDataFolder());
        this.moduleManager = new ModuleManager(getLogger());

        // Load configuration
        configManager.load(getResource("config.yml"));
        this.messageFormatter = new MessageFormatter(configManager.getChatPrefix());

        // Connect to database
        try {
            databaseManager.connect(configManager);
            databaseManager.initializeSchema();
        } catch (Exception e) {
            getLogger().severe("Failed to connect to database! Plugin will not function.");
            getLogger().severe("Check that the plugin data folder is writable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize audit logger
        this.auditLogger = new AuditLogger(getLogger(), databaseManager);

        // Initialize utility helpers
        this.schedulerHelper = new SchedulerHelper(this);
        this.transactionHelper = new TransactionHelper(databaseManager, getLogger());
        this.guiFramework = new GUIFramework(this);

        // Register core services
        serviceRegistry.register(ConfigManager.class, configManager);
        serviceRegistry.register(DatabaseManager.class, databaseManager);
        serviceRegistry.register(AuditLogger.class, auditLogger);
        serviceRegistry.register(MessageFormatter.class, messageFormatter);
        serviceRegistry.register(SchedulerHelper.class, schedulerHelper);
        serviceRegistry.register(TransactionHelper.class, transactionHelper);
        serviceRegistry.register(GUIFramework.class, guiFramework);
        serviceRegistry.register(UpdateService.class, new UpdateService(this));

        // Load modules in dependency order
        loadModules();

        getLogger().info("Vault Survival has been enabled!");
        getLogger().info("Modules loaded: " + String.join(", ", moduleManager.getModuleNames()));
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (serviceRegistry != null) {
            serviceRegistry.clear();
        }
        getLogger().info("Vault Survival has been disabled!");
    }

    private void loadModules() {
        // Phase 0: Core platform modules
        accessModule = new AccessModule(this);
        moduleManager.registerModule(accessModule);

        // Spawn City: Kingdom capital management
        SpawnCityModule spawnCityModule = new SpawnCityModule(this);
        moduleManager.registerModule(spawnCityModule);

        // Phase 1: Physical currency
        CurrencyModule currencyModule = new CurrencyModule(this);
        moduleManager.registerModule(currencyModule);

        // Phase 2: Vaults
        VaultModule vaultModule = new VaultModule(this);
        moduleManager.registerModule(vaultModule);

        // Sprint 9: Merchant Buy Orders
        MerchantOrderModule merchantModule = new MerchantOrderModule(this);
        moduleManager.registerModule(merchantModule);

        // Sprint 11: District Train Stations
        RailModule railModule = new RailModule(this);
        moduleManager.registerModule(railModule);

        // Register staff mode module
        staffmodeModule = new StaffmodeModule(this);
        moduleManager.registerModule(staffmodeModule);

        // Phase 3: Breach system
        BreachModule breachModule = new BreachModule(this);
        moduleManager.registerModule(breachModule);

        // Phase 4: Auction Hall
        MarketModule marketModule = new MarketModule(this);
        moduleManager.registerModule(marketModule);

        // NPC system (custom fake players with skins, shops, commands)
        NpcModule npcModule = new NpcModule(this);
        moduleManager.registerModule(npcModule);

        // Phase 5: Regions system
        RegionModule regionModule = new RegionModule(this);
        moduleManager.registerModule(regionModule);

        // VS-WorldEdit: Lightweight internal building toolkit
        VSWorldEditModule vsWorldEditModule = new VSWorldEditModule(this);
        moduleManager.registerModule(vsWorldEditModule);

        // Phase 6: District Foundation
        DistrictModule districtModule = new DistrictModule(this);
        moduleManager.registerModule(districtModule);

        // Phase 7: Temporary District Damage
        DamageModule damageModule = new DamageModule(this);
        moduleManager.registerModule(damageModule);

        // Phase 8: Repairmen
        RepairModule repairModule = new RepairModule(this);
        moduleManager.registerModule(repairModule);

        // Phase 9: Crime & Police
        CrimeModule crimeModule = new CrimeModule(this);
        moduleManager.registerModule(crimeModule);

        // Phase 10: Display Auction Hall
        DisplayModule displayModule = new DisplayModule(this);
        moduleManager.registerModule(displayModule);

        // Phase 11: Social (Friends, Groups, Stations, Contracts)
        FriendModule friendModule = new FriendModule(this);
        moduleManager.registerModule(friendModule);
        GroupModule groupModule = new GroupModule(this);
        moduleManager.registerModule(groupModule);
        StationModule stationModule = new StationModule(this);
        moduleManager.registerModule(stationModule);
        ContractModule contractModule = new ContractModule(this);
        moduleManager.registerModule(contractModule);
        SpawnJobModule spawnJobModule = new SpawnJobModule(this);
        moduleManager.registerModule(spawnJobModule);

        // Phase 12: Polish (Monitor, Store, Resource Pack)
        MonitorModule monitorModule = new MonitorModule(this);
        moduleManager.registerModule(monitorModule);
        StoreModule storeModule = new StoreModule(this);
        moduleManager.registerModule(storeModule);

        // Modern menus (native dialogs when available, inventory/clickable fallback otherwise)
        DialogModule dialogModule = new DialogModule(this);
        moduleManager.registerModule(dialogModule);

        // Load and enable all modules (AccessService, etc. get registered here)
        moduleManager.loadAll();
        moduleManager.enableAll();

        // --- Post-enable registrations (depend on services being registered) ---
        CurrentAreaService currentAreaService = new CurrentAreaService(this);
        serviceRegistry.register(CurrentAreaService.class, currentAreaService);
        ChatChannelService chatChannelService = new ChatChannelService(this);
        serviceRegistry.register(ChatChannelService.class, chatChannelService);

        // Register /vs version command
        var vsCmd = new VSVersionCommand(this);
        getCommand("vs").setExecutor(vsCmd);
        getCommand("vs").setTabCompleter(vsCmd);

        // Register chat listener and commands for rank prefixes and channels.
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        var chatCmd = new ChatCommand(this);
        for (String commandName : java.util.List.of("chat", "g", "l", "dc", "ac", "pc", "mc", "sc", "helpchat", "chatsettings", "chatpreview")) {
            if (getCommand(commandName) != null) {
                getCommand(commandName).setExecutor(chatCmd);
                getCommand(commandName).setTabCompleter(chatCmd);
            }
        }

        // Register current-area context command
        getCommand("whereami").setExecutor(new WhereAmICommand(this, currentAreaService));

        // Register resource pack command
        var rpCmd = new ResourcePackCommand(this);
        getCommand("vsresourcepack").setExecutor(rpCmd);

        // Register /vsgive command for staffmode bypass testing
        var vsGiveCmd = new VSGiveCommand(this, staffmodeModule.getStaffData());
        getCommand("vsgive").setExecutor(vsGiveCmd);
        getCommand("vsgive").setTabCompleter(vsGiveCmd);
    }

    // --- Getters for internal use ---

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabase() {
        return databaseManager;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public SchedulerHelper getScheduler() {
        return schedulerHelper;
    }

    public TransactionHelper getTransactionHelper() {
        return transactionHelper;
    }

    public GUIFramework getGuiFramework() {
        return guiFramework;
    }
}
