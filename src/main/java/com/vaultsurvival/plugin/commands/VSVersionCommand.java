package com.vaultsurvival.plugin.commands;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.updates.UpdateService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /vs version — Shows plugin version, build info, module count, and database status.
 * /vs reload — Reloads config.yml at runtime.
 */
public class VSVersionCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;

    public VSVersionCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            return handleUpdate(sender, args);
        }
        sender.sendMessage(fmt.header("Vault Survival"));
        sender.sendMessage(fmt.info("Version: &e" + plugin.getDescription().getVersion()));
        sender.sendMessage(fmt.info("API: &ePaper 26.1.2"));
        sender.sendMessage(fmt.info("Database: &eSQLite &7— &a" +
            (plugin.getDatabase().isConnected() ? "Connected" : "Disconnected")));
        sender.sendMessage(fmt.info("Modules loaded: &e" +
            plugin.getModuleManager().getModuleNames().size()));
        sender.sendMessage(fmt.info("Server: &e" + plugin.getConfigManager().getServerName()));
        sender.sendMessage(fmt.info("Spawn City: &e" + plugin.getConfigManager().getSpawnCityName()));
        sender.sendMessage(fmt.info("Commands: &e/help vaultsurvival"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasVsPermission(sender, "vs.admin.reload")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        plugin.getConfigManager().reload(plugin.getResource("config.yml"));
        // Update MessageFormatter prefix from reloaded config
        plugin.getMessageFormatter().setPrefix(plugin.getConfigManager().getChatPrefix());
        sender.sendMessage(fmt.success("Config reloaded from config.yml"));
        sender.sendMessage(fmt.info("Chat format, rank labels, and all settings updated."));
        plugin.getLogger().info(sender.getName() + " reloaded the plugin configuration.");
        return true;
    }

    private boolean handleUpdate(CommandSender sender, String[] args) {
        if (!hasVsPermission(sender, "vs.update")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (!plugin.getConfigManager().areUpdatesEnabled()) {
            sender.sendMessage(fmt.error("GitHub updates are disabled in config.yml."));
            return true;
        }
        UpdateService updates;
        try {
            updates = plugin.getServiceRegistry().get(UpdateService.class);
        } catch (IllegalStateException e) {
            sender.sendMessage(fmt.error("Update service is not loaded."));
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase() : "check";
        switch (action) {
            case "check" -> updates.check(sender);
            case "stage", "download" -> updates.stage(sender);
            case "install", "apply" -> updates.install(sender);
            case "status" -> updates.status(sender);
            default -> {
                sender.sendMessage(fmt.header("Vault Survival Updates"));
                sender.sendMessage(fmt.info("/vs update check &8- Check latest GitHub release"));
                sender.sendMessage(fmt.info("/vs update stage &8- Download latest release jar"));
                sender.sendMessage(fmt.info("/vs update install &8- Queue staged jar for next restart"));
                sender.sendMessage(fmt.info("/vs update status &8- Show staged/update paths"));
            }
        }
        return true;
    }

    private boolean hasVsPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }
        try {
            AccessService accessService = plugin.getServiceRegistry().get(AccessService.class);
            return accessService.hasPermission(player.getUniqueId(), permission);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "update").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
            return List.of("check", "stage", "download", "install", "apply", "status").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
