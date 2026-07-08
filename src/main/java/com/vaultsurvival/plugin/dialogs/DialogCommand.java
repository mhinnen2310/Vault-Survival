package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class DialogCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final DialogService dialogService;

    public DialogCommand(VaultSurvivalPlugin plugin, DialogService dialogService) {
        this.plugin = plugin;
        this.dialogService = dialogService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageFormatter().error("Only players can open Vault Survival menus."));
            return true;
        }

        DialogMenuType menuType = command.getName().equalsIgnoreCase("quickactions")
            ? DialogMenuType.MAIN
            : DialogMenuType.from(args.length > 0 ? args[0] : null);
        dialogService.openMenu(player, menuType);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && command.getName().equalsIgnoreCase("vsmenu")) {
            return Arrays.stream(DialogMenuType.values())
                .map(DialogMenuType::id)
                .filter(id -> id.startsWith(args[0].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
