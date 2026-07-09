package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

        if (command.getName().equalsIgnoreCase("vsmenu")
            && args.length >= 2
            && args[0].equalsIgnoreCase("input")) {
            dialogService.openInput(player, args[1]);
            return true;
        }

        if (command.getName().equalsIgnoreCase("vsmenu")
            && args.length >= 1
            && args[0].equalsIgnoreCase("locked")) {
            dialogService.showLocked(player, join(args, 1));
            return true;
        }

        if (command.getName().equalsIgnoreCase("vsmenu")
            && args.length >= 4
            && args[0].equalsIgnoreCase("confirm")) {
            dialogService.openConfirmation(player, args[1], args[2], join(args, 3), "main");
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
            List<String> menuIds = Arrays.stream(DialogMenuType.values())
                .map(DialogMenuType::id)
                .filter(id -> id.startsWith(args[0].toLowerCase()))
                .toList();
            if ("input".startsWith(args[0].toLowerCase())) {
                return java.util.stream.Stream.concat(menuIds.stream(), java.util.stream.Stream.of("input")).toList();
            }
            return menuIds;
        }
        if (args.length == 2 && command.getName().equalsIgnoreCase("vsmenu")
            && args[0].equalsIgnoreCase("input")) {
            return dialogService.inputIds().stream()
                .filter(id -> id.startsWith(args[1].toLowerCase()))
                .toList();
        }
        return List.of();
    }

    private String join(String[] args, int start) {
        if (args.length <= start) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }
}
