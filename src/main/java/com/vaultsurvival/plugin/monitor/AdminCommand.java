package com.vaultsurvival.plugin.monitor;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.districts.DistrictTreasuryService;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;

    public AdminCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("vs.admin")) { sender.sendMessage(fmt.permissionDenied()); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("dashboard")) { showDashboard(sender); return true; }
        if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("vs.admin.reload")) {
            plugin.getConfigManager().reload(plugin.getResource("config.yml"));
            plugin.getMessageFormatter().setPrefix(plugin.getConfigManager().getChatPrefix());
            sender.sendMessage(fmt.success("Config reloaded from disk."));
            return true;
        }
        if (args[0].equalsIgnoreCase("modules") && sender.hasPermission("vs.admin")) {
            showModules(sender); return true;
        }
        if (args[0].equalsIgnoreCase("optimize") && sender.hasPermission("vs.admin.reload")) {
            sender.sendMessage(fmt.info("Running WAL checkpoint..."));
            try (var stmt = plugin.getDatabase().getConnection().createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                sender.sendMessage(fmt.success("WAL checkpoint complete. Database optimized."));
            } catch (Exception e) { sender.sendMessage(fmt.error("Optimization failed: " + e.getMessage())); }
            return true;
        }
        if (args[0].equalsIgnoreCase("treasury") && args.length >= 3 && args[1].equalsIgnoreCase("migrate-district")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(fmt.error("Stand at the destination physical vault in-game.")); return true; }
            DistrictService districts = plugin.getServiceRegistry().get(DistrictService.class);
            var district = districts.getAllDistricts().stream().filter(d -> String.valueOf(d.getId()).equals(args[2]) || d.getName().equalsIgnoreCase(args[2])).findFirst().orElse(null);
            if (district == null) { sender.sendMessage(fmt.error("District not found.")); return true; }
            DistrictTreasuryService treasury = plugin.getServiceRegistry().get(DistrictTreasuryService.class);
            var target = player.getTargetBlockExact(6);
            var vault = target == null ? null : treasury.getVault(target);
            if (vault == null || vault.districtId() != district.getId()) { sender.sendMessage(fmt.error("Look at a registered physical vault belonging to that district.")); return true; }
            var result = treasury.migrateLegacy(player, district.getId(), vault.vaultUuid());
            sender.sendMessage(result.success() ? fmt.success(result.message()) : fmt.error(result.message()));
            return true;
        }
        return true;
    }

    private void showDashboard(CommandSender sender) {
        var rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        long max = rt.maxMemory() / 1048576;
        double[] tps = Bukkit.getTPS();

        sender.sendMessage(fmt.header("Admin Dashboard"));
        sender.sendMessage(fmt.info("&e" + Bukkit.getOnlinePlayers().size() + "&7/&e" + Bukkit.getMaxPlayers() + " players &8| TPS: &e" + String.format("%.1f", tps[0])));
        sender.sendMessage(fmt.info("Memory: &e" + used + "&7/&e" + max + " MB"));
        sender.sendMessage(fmt.info("Modules: &e" + plugin.getModuleManager().getModuleNames().size() + " loaded"));
        sender.sendMessage(fmt.info("&7/vsadmin modules &8- List all modules"));
        sender.sendMessage(fmt.info("&7/vsadmin optimize &8- Optimize database (WAL checkpoint)"));
        sender.sendMessage(fmt.info("&7/vsadmin reload &8- Reload config"));
        sender.sendMessage(fmt.info("&7/analytics &8- Full server analytics"));
    }

    private void showModules(CommandSender sender) {
        sender.sendMessage(fmt.header("Loaded Modules"));
        for (String name : plugin.getModuleManager().getModuleNames()) {
            sender.sendMessage(fmt.info("  &a" + name));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("dashboard", "reload", "modules", "optimize", "treasury");
        if (args.length == 2 && args[0].equalsIgnoreCase("treasury")) return List.of("migrate-district");
        if (args.length == 3 && args[0].equalsIgnoreCase("treasury")) return plugin.getServiceRegistry().get(DistrictService.class).getAllDistricts().stream().map(d -> String.valueOf(d.getId())).toList();
        return List.of();
    }
}
