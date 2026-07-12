package com.vaultsurvival.plugin.commands;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.core.DatabaseHealthService;
import com.vaultsurvival.plugin.core.Module;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.updates.UpdateService;
import com.vaultsurvival.plugin.security.StaffAlertService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
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
        if (args.length == 0 || args[0].equalsIgnoreCase("version")) {
            return handleVersion(sender);
        }
        if (args[0].equalsIgnoreCase("modules")) {
            return handleModules(sender);
        }
        if (args[0].equalsIgnoreCase("debug")) {
            return handleDebug(sender);
        }
        if (args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }
        if (args[0].equalsIgnoreCase("update")) {
            return handleUpdate(sender, args);
        }
        if (args[0].equalsIgnoreCase("checklist")) return handleChecklist(sender);
        if (args[0].equalsIgnoreCase("permissions")) return handlePermissions(sender);
        if (args[0].equalsIgnoreCase("configcheck")) return handleConfigCheck(sender);
        if (args[0].equalsIgnoreCase("debugbundle")) return handleDebugBundle(sender);
        if (args[0].equalsIgnoreCase("audit")) return handleAudit(sender);
        if (args[0].equalsIgnoreCase("dbmetrics") || (args[0].equalsIgnoreCase("database")
            && args.length > 1 && args[1].equalsIgnoreCase("status"))) return handleDatabaseMetrics(sender);
        return handleVersion(sender);
    }

    private boolean handleChecklist(CommandSender sender) {
        sender.sendMessage(fmt.header("Final Gameplay Checklist"));
        for (String item : List.of("New player: Spawn Job Board and starter payout", "Current area and district join/create", "District roles, laws, jobs, merchant orders", "Station request, ticket, and train journey", "Staff profile and economy/security dashboards")) sender.sendMessage(fmt.info("&7[ ] " + item));
        sender.sendMessage(fmt.info("Run this list on a staging server with two players before production."));
        return true;
    }

    private boolean handlePermissions(CommandSender sender) {
        sender.sendMessage(fmt.header("Vault Survival Permissions"));
        sender.sendMessage(fmt.info("Player: vs.menu, vs.vault.use, vs.market.buy, vs.breach.use"));
        sender.sendMessage(fmt.info("District: roles are checked server-side; no menu button bypass."));
        sender.sendMessage(fmt.info("Staff: vs.staffmode.use + active staffmode; vs.staffinspect; vs.cash.admin; vs.vault.admin.inspect"));
        sender.sendMessage(fmt.info("Admin: vs.admin, vs.admin.reload, vs.update"));
        return true;
    }

    private boolean handleConfigCheck(CommandSender sender) {
        var config = plugin.getConfigManager().getConfig();
        List<String> missing = new java.util.ArrayList<>();
        for (String key : List.of(
            "configVersion", "server.name", "database.file", "database.writeQueueCapacity", "database.readThreads", "database.busyTimeoutMillis", "staffSandbox.transfer.testPort",
            "staffmode.utilities.breaker.allowedSizes", "spawn.blockClaim.maxAreaBlocks",
            "dialogs.enabled", "chat.channels.default", "security.anticheat.enabled",
            "currency.material", "vaults.vault_material", "districtTreasury.enabled",
            "districts.selection.requiredAreaBlocks", "districts.selection.areaBlocksByLevel.0",
            "districtDevelopment.scaling.enabled", "districtMarket.requireMarketZone",
            "merchant.max_active_orders", "rail.defaultTicketPrice",
            "regions.visualization.enabled", "vsWorldEdit.patterns.maxPatternEntries",
            "vsworldedit.schematics.enabled", "updates.githubOwner")) {
            if (!config.contains(key)) missing.add(key);
        }
        List<String> invalid = new java.util.ArrayList<>();
        if (config.getInt("configVersion", 0) < 3) invalid.add("configVersion must be at least 3");
        if (config.getLong("spawn.blockClaim.maxAreaBlocks", 0) < 1) invalid.add("spawn.blockClaim.maxAreaBlocks must be positive");
        if (config.getLong("districts.selection.requiredAreaBlocks", 0) < 1) invalid.add("districts.selection.requiredAreaBlocks must be positive");
        if (config.getDouble("districts.marketZone.maxPercentOfDistrict", -1) <= 0 || config.getDouble("districts.marketZone.maxPercentOfDistrict", -1) > 1) invalid.add("districts.marketZone.maxPercentOfDistrict must be > 0 and <= 1");
        if (config.getDouble("market.tax_percent", -1) < 0 || config.getDouble("market.tax_percent", -1) > 100) invalid.add("market.tax_percent must be between 0 and 100");
        if (config.getInt("vsWorldEdit.safety.maxBlocksPerOperation", 0) < 1) invalid.add("vsWorldEdit.safety.maxBlocksPerOperation must be positive");
        if (config.getInt("vsworldedit.schematics.maxBlocks", 0) < 1) invalid.add("vsworldedit.schematics.maxBlocks must be positive");
        sender.sendMessage(fmt.header("Config Check"));
        if (!missing.isEmpty()) sender.sendMessage(fmt.error("Missing config keys: " + String.join(", ", missing)));
        if (!invalid.isEmpty()) invalid.forEach(issue -> sender.sendMessage(fmt.error(issue)));
        if (missing.isEmpty() && invalid.isEmpty()) sender.sendMessage(fmt.success("Configuration v" + config.getInt("configVersion") + " is complete and valid."));
        return true;
    }

    private boolean handleDebugBundle(CommandSender sender) {
        if (!hasVsPermission(sender, "vs.admin")) { sender.sendMessage(fmt.permissionDenied()); return true; }
        try {
            File folder = new File(plugin.getDataFolder(), "debug-bundles"); folder.mkdirs();
            File output = new File(folder, "vs-debug-" + System.currentTimeMillis() + ".txt");
            String body = "Generated: " + Instant.now() + "\nVersion: " + plugin.getDescription().getVersion() + "\nDatabase: " + plugin.getDatabase().isConnected() + "\nModules:\n" + String.join("\n", plugin.getModuleManager().getModuleNames()) + "\nConfig check: run /vs configcheck\nOperational queues: /civic reports and /staffalerts list\n";
            Files.writeString(output.toPath(), body, StandardCharsets.UTF_8);
            sender.sendMessage(fmt.success("Debug bundle written: " + output.getName()));
        } catch (Exception e) { sender.sendMessage(fmt.error("Could not write debug bundle.")); }
        return true;
    }

    private boolean handleAudit(CommandSender sender) {
        if (!hasVsPermission(sender, "vs.admin.alerts")) { sender.sendMessage(fmt.permissionDenied()); return true; }
        try {
            StaffAlertService alerts = plugin.getServiceRegistry().get(StaffAlertService.class);
            sender.sendMessage(fmt.header("Startup Audit"));
            alerts.getStartupReport().forEach(line -> sender.sendMessage(fmt.info(line)));
        } catch (RuntimeException unavailable) {
            sender.sendMessage(fmt.error("Operational alert service is unavailable."));
        }
        return true;
    }

    private boolean handleDatabaseMetrics(CommandSender sender) {
        if (!hasVsPermission(sender,"vs.admin")){sender.sendMessage(fmt.permissionDenied());return true;}
        var metrics=plugin.getServiceRegistry().get(DatabaseHealthService.class).metrics();var writes=metrics.writes();
        sender.sendMessage(fmt.header("Database Executor"));
        sender.sendMessage(fmt.info("Write queue: &e"+writes.depth()+"/"+writes.capacity()+" &7| submitted &e"+writes.submitted()+" &7| completed &e"+writes.completed()));
        sender.sendMessage(fmt.info("Write failures: &e"+writes.failed()+" &7| rejected &e"+writes.rejected()+" &7| average &e"+writes.averageQueryMicros()+"us"));
        sender.sendMessage(fmt.info("Longest write: &e"+writes.longestQueryMicros()+"us &7| queue pressure: "
            +(metrics.criticalQueuePressure()?"&cCRITICAL":"&aNORMAL")));
        sender.sendMessage(fmt.info("Read queue: &e"+metrics.readQueueDepth()+"/"+metrics.readQueueCapacity()+" &7| completed &e"+metrics.readsCompleted()+" &7| failures &e"+metrics.readsFailed()+" &7| average &e"+metrics.averageReadMicros()+"us"));
        sender.sendMessage(fmt.info("Longest read: &e"+metrics.longestReadMicros()+"us &7| accepting: &e"+metrics.acceptingWork()+" &7| shutdown flush: &e"+metrics.shutdownFlushStatus()));
        return true;
    }

    private boolean handleVersion(CommandSender sender) {
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

    private boolean handleModules(CommandSender sender) {
        sender.sendMessage(fmt.header("Vault Survival Modules"));
        for (String name : plugin.getModuleManager().getModuleNames()) {
            Module module = plugin.getModuleManager().getModule(name);
            boolean enabled = module != null && module.isEnabled();
            sender.sendMessage(fmt.info((enabled ? "&aONLINE " : "&cOFFLINE") + " &e" + name));
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!hasVsPermission(sender, "vs.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        sender.sendMessage(fmt.header("Vault Survival Debug"));
        sender.sendMessage(fmt.info("Plugin: &e" + plugin.getDescription().getFullName()));
        sender.sendMessage(fmt.info("Server: &e" + plugin.getServer().getName() + " " + plugin.getServer().getVersion()));
        sender.sendMessage(fmt.info("Java: &e" + System.getProperty("java.version")));
        sender.sendMessage(fmt.info("Database: " + (plugin.getDatabase().isConnected() ? "&aConnected" : "&cDisconnected")));
        sender.sendMessage(fmt.info("Data folder: &e" + plugin.getDataFolder().getPath()));
        sender.sendMessage(fmt.info("Modules: &e" + plugin.getModuleManager().getModuleNames().size()
            + " registered, " + enabledModuleCount() + " enabled"));
        sender.sendMessage(fmt.info("Commands: &e" + plugin.getDescription().getCommands().keySet().size() + " declared"));

        try {
            DialogService dialogs = plugin.getServiceRegistry().get(DialogService.class);
            sender.sendMessage(fmt.info("Dialogs: &e" + dialogs.getLastProviderName()
                + " &7(native=" + dialogs.isNativeSupported() + ")"));
        } catch (RuntimeException ignored) {
            sender.sendMessage(fmt.info("Dialogs: &cservice unavailable"));
        }

        File jarFile = getPluginJarFile();
        sender.sendMessage(fmt.info("Jar: &e" + jarFile.getName() + " &7(" + jarFile.length() + " bytes)"));
        return true;
    }

    private long enabledModuleCount() {
        return plugin.getModuleManager().getModuleNames().stream()
            .filter(name -> plugin.getModuleManager().isEnabled(name))
            .count();
    }

    private File getPluginJarFile() {
        try {
            return new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception ignored) {
            return new File("unknown");
        }
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
            return List.of("version", "modules", "debug", "dbmetrics", "reload", "update", "checklist", "debugbundle", "permissions", "configcheck", "audit").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
            return List.of("check", "stage", "download", "install", "apply", "status").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
