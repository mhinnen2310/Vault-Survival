package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Operational alerts for staff, plus a compact startup audit and join briefing. */
public final class StaffAlertService extends Handler implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final List<String> startupReport = new ArrayList<>();
    private final AtomicInteger serverErrors = new AtomicInteger();
    private Logger rootLogger;

    public StaffAlertService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
        setLevel(Level.SEVERE);
    }

    public void install() {
        rootLogger = Logger.getLogger("");
        rootLogger.addHandler(this);
    }

    public void shutdown() {
        if (rootLogger != null) rootLogger.removeHandler(this);
    }

    public void runStartupAudit() {
        startupReport.clear();
        boolean database = plugin.getDatabase().isConnected();
        startupReport.add("Database: " + (database ? "OK" : "FAILED"));
        startupReport.add("District claim: " + plugin.getConfigManager().getDistrictInitialClaimChunks()
            + " chunks; Spawn distance: " + plugin.getConfigManager().getDistrictMinDistanceFromSpawn()
            + "; district distance: " + plugin.getConfigManager().getDistrictMinDistanceBetween());
        startupReport.add("Anti-cheat: " + (plugin.getConfigManager().getConfig().getBoolean("security.anticheat.enabled", true) ? "enabled" : "disabled")
            + "; Paper anti-xray engine: " + plugin.getConfigManager().getConfig().getInt("security.antiXray.requireEngineMode", 2));
        startupReport.add("Modules: " + plugin.getModuleManager().getModuleNames().size() + " registered");
        if (!database || plugin.getConfigManager().getDistrictInitialClaimChunks() < 1) {
            alertAdmins("Startup audit", "FAILED: " + String.join(" | ", startupReport));
        } else {
            plugin.getLogger().info("[Audit] " + String.join(" | ", startupReport));
        }
    }

    public List<String> getStartupReport() { return List.copyOf(startupReport); }

    public void alertStaff(String title, String detail) { send("vs.staff.alerts", title, detail); }
    public void alertAdmins(String title, String detail) { send("vs.admin.alerts", title, detail); }

    private void send(String permission, String title, String detail) {
        Runnable task = () -> Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission(permission))
            .forEach(player -> player.sendMessage(fmt.warn("[" + title + "] " + detail)));
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < Level.SEVERE.intValue()) return;
        if (record.getLoggerName() != null && record.getLoggerName().contains("StaffAlertService")) return;
        int count = serverErrors.incrementAndGet();
        String message = record.getMessage() == null ? record.getLevel().getName() : record.getMessage().replace('\n', ' ');
        if (message.length() > 180) message = message.substring(0, 180) + "...";
        alertAdmins("Server error #" + count, message);
    }

    @Override public void flush() { }
    @Override public void close() { shutdown(); }

    @EventHandler
    public void onStaffJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isStaff(player)) return;
            double tps = Bukkit.getServer().getTPS()[0];
            player.sendMessage(fmt.header("Staff Briefing"));
            player.sendMessage(fmt.info("TPS: &e" + String.format(java.util.Locale.ROOT, "%.2f", tps)
                + " &7| Online: &e" + Bukkit.getOnlinePlayers().size() + " &7| Server errors: &e" + serverErrors.get()));
            player.sendMessage(fmt.info("Anti-cheat flags (24h): &e" + countFlagsSince(System.currentTimeMillis() - 86_400_000L)
                + " &7| Active evidence: &e" + countActiveEvidence()));
            player.sendMessage(fmt.info("Startup audit: &a" + String.join(" &8| &a", startupReport)));
        }, 20L);
    }

    private boolean isStaff(Player player) {
        try {
            AccessService access = plugin.getServiceRegistry().get(AccessService.class);
            return access.isStaff(player.getUniqueId());
        } catch (RuntimeException ignored) {
            return player.hasPermission("vs.staff.alerts") || player.hasPermission("vs.admin.alerts");
        }
    }

    private int countFlagsSince(long since) { return count("SELECT COUNT(*) FROM anticheat_flags WHERE created_at >= ?", since); }
    private int countActiveEvidence() { return count("SELECT COUNT(*) FROM district_evidence WHERE status IN ('UNHANDLED', 'ACTIVE')", null); }

    private int count(String sql, Long value) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value != null) statement.setLong(1, value);
            ResultSet result = statement.executeQuery();
            return result.next() ? result.getInt(1) : 0;
        } catch (Exception ignored) { return 0; }
    }
}
