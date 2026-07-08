package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

public class DialogModule extends Module {

    private DialogService dialogService;

    public DialogModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public void onLoad() {
        this.dialogService = new DialogService(plugin);
        plugin.getServiceRegistry().register(DialogService.class, dialogService);
        new QuickActionsPackGenerator(plugin).generate();
        plugin.getLogger().info("Dialog service registered. Native support: " + dialogService.isNativeSupported());
    }

    @Override
    public void onEnable() {
        DialogCommand command = new DialogCommand(plugin, dialogService);
        plugin.getCommand("vsmenu").setExecutor(command);
        plugin.getCommand("vsmenu").setTabCompleter(command);
        plugin.getCommand("quickactions").setExecutor(command);
    }

    @Override
    public void onDisable() {
    }

    @Override
    public String getName() {
        return "VS-Dialogs";
    }

    @Override
    public String[] getDependencies() {
        return new String[] {
            "VS-Access",
            "VS-SpawnCity",
            "VS-Vaults",
            "VS-Staffmode",
            "VS-Market",
            "VS-WorldEdit",
            "VS-Districts"
        };
    }
}
