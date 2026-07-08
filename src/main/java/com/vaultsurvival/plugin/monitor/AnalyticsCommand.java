package com.vaultsurvival.plugin.monitor;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class AnalyticsCommand implements CommandExecutor, TabCompleter {
    private final AnalyticsService service;
    private final MessageFormatter fmt;

    public AnalyticsCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(AnalyticsService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("vs.admin")) { sender.sendMessage(fmt.permissionDenied()); return true; }
        var stats = service.getStats();
        sender.sendMessage(fmt.header("Vault Survival Analytics"));
        sender.sendMessage(fmt.info("Server: &e" + stats.get("server_name") + " &8| Players: &e" + stats.get("online_players") + "/" + stats.get("max_players")));
        sender.sendMessage(fmt.info("TPS: &e" + stats.get("tps_1m") + " &7(1m) &e" + stats.get("tps_5m") + " &7(5m) &8| Memory: &e" + stats.get("memory_used_mb") + "MB"));
        sender.sendMessage(fmt.info("Cash circulation: &6" + stats.get("cash_in_circulation")));
        sender.sendMessage(fmt.info("Active listings: &e" + stats.get("active_listings") + " &8| Wanted: &c" + stats.get("wanted_players")));
        sender.sendMessage(fmt.info("Districts: &e" + stats.get("districts_active") + " active &8| Applications: &e" + stats.get("districts_applications")));
        sender.sendMessage(fmt.info("Pending restores: &e" + stats.get("pending_restores") + " &8| Repair states: &e" + stats.get("repair_states")));
        sender.sendMessage(fmt.info("NPCs: &e" + stats.get("npcs") + " &8| Groups: &e" + stats.get("groups")));
        sender.sendMessage(fmt.info("Modules: &e" + stats.get("modules_loaded") + " &8| Uptime: &7" + formatUptime((Long)stats.get("uptime_ms"))));
        return true;
    }

    private String formatUptime(long ms) {
        long s = ms / 1000, m = s / 60, h = m / 60;
        return h + "h " + (m % 60) + "m";
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) { return List.of(); }
}
