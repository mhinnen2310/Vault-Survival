package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Persistent operational alert queue, live staff routing, and staff teleport history. */
public final class StaffAlertService extends Handler implements Listener {
    public record Alert(int id, String type, String severity, UUID playerUuid, String playerName,
                        String details, String world, Double x, Double y, Double z,
                        String status, String assignedTo, long createdAt) {
        public boolean hasLocation() { return world != null && x != null && y != null && z != null; }
    }

    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final List<String> startupReport = new ArrayList<>();
    private final AtomicInteger serverErrors = new AtomicInteger();
    private final Map<UUID, Deque<Location>> returnLocations = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> publishing = ThreadLocal.withInitial(() -> false);
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
        returnLocations.clear();
    }

    public void runStartupAudit() {
        startupReport.clear();
        boolean database = plugin.getDatabase().isConnected();
        startupReport.add("Database: " + (database ? "OK" : "FAILED"));
        startupReport.add("District claim: " + plugin.getConfigManager().getDistrictInitialClaimChunks()
            + " chunks; Spawn distance: " + plugin.getConfigManager().getDistrictMinDistanceFromSpawn()
            + "; district distance: " + plugin.getConfigManager().getDistrictMinDistanceBetween());
        startupReport.add("Anti-cheat: " + (plugin.getConfigManager().getConfig().getBoolean("security.anticheat.enabled", true) ? "enabled" : "disabled")
            + "; required Paper anti-xray engine: " + plugin.getConfigManager().getConfig().getInt("security.antiXray.requireEngineMode", 2));
        startupReport.add("Modules: " + plugin.getModuleManager().getModuleNames().size() + " registered");
        if (!database || plugin.getConfigManager().getDistrictInitialClaimChunks() < 1) {
            recordAlert("STARTUP_AUDIT", "CRITICAL", null, null, "FAILED: " + String.join(" | ", startupReport), null);
            alertAdmins("Startup audit", "FAILED: " + String.join(" | ", startupReport));
        } else plugin.getLogger().info("[Audit] " + String.join(" | ", startupReport));
    }

    public List<String> getStartupReport() { return List.copyOf(startupReport); }
    public void alertStaff(String title, String detail) { send("vs.staff.alerts", title, detail); }
    public void alertAdmins(String title, String detail) { send("vs.admin.alerts", title, detail); }

    public int recordAlert(String type, String severity, UUID playerUuid, String playerName, String details, Location location) {
        long now = System.currentTimeMillis();
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO staff_alerts(alert_type,severity,player_uuid,player_name,details,world,x,y,z,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'OPEN',?)",
            Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalize(type, "OTHER"));
            statement.setString(2, normalize(severity, "MEDIUM"));
            statement.setString(3, playerUuid == null ? null : playerUuid.toString());
            statement.setString(4, playerName);
            statement.setString(5, trim(details, 1000));
            if (location == null || location.getWorld() == null) {
                statement.setString(6, null); statement.setObject(7, null); statement.setObject(8, null); statement.setObject(9, null);
            } else {
                statement.setString(6, location.getWorld().getName()); statement.setDouble(7, location.getX());
                statement.setDouble(8, location.getY()); statement.setDouble(9, location.getZ());
            }
            statement.setLong(10, now); statement.executeUpdate();
            ResultSet keys = statement.getGeneratedKeys(); int id = keys.next() ? keys.getInt(1) : -1;
            alertStaff(type + " #" + id, (playerName == null ? "" : playerName + ": ") + trim(details, 180));
            return id;
        } catch (Exception error) {
            if (!Boolean.TRUE.equals(publishing.get())) plugin.getLogger().log(Level.WARNING, "Failed to persist staff alert", error);
            return -1;
        }
    }

    public List<Alert> alerts(String type, boolean includeClosed, int limit) {
        List<Alert> rows = new ArrayList<>();
        String sql = "SELECT * FROM staff_alerts WHERE 1=1 " + (includeClosed ? "" : "AND status IN ('OPEN','CLAIMED') ")
            + (type == null || type.equalsIgnoreCase("ALL") ? "" : "AND alert_type LIKE ? ")
            + "ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,created_at DESC LIMIT ?";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (type != null && !type.equalsIgnoreCase("ALL")) statement.setString(index++, "%" + normalize(type, "OTHER") + "%");
            statement.setInt(index, Math.max(1, Math.min(200, limit)));
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(read(result));
        } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Failed to load staff alerts", error); }
        return rows;
    }

    public Alert alert(int id) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM staff_alerts WHERE id=?")) {
            statement.setInt(1, id); ResultSet result = statement.executeQuery(); return result.next() ? read(result) : null;
        } catch (Exception error) { return null; }
    }

    public boolean claim(Player staff, int id) {
        return update(staff, id, "CLAIMED", null, "status='OPEN'");
    }

    public boolean resolve(Player staff, int id, String resolution) {
        return update(staff, id, "RESOLVED", resolution, "status IN ('OPEN','CLAIMED')");
    }

    private boolean update(Player staff, int id, String status, String resolution, String condition) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
            status.equals("CLAIMED")
                ? "UPDATE staff_alerts SET status='CLAIMED',assigned_to=? WHERE id=? AND " + condition
                : "UPDATE staff_alerts SET status='RESOLVED',assigned_to=COALESCE(assigned_to,?),resolution=?,resolved_at=? WHERE id=? AND " + condition)) {
            statement.setString(1, staff.getUniqueId().toString());
            if (status.equals("CLAIMED")) statement.setInt(2, id);
            else { statement.setString(2, trim(resolution, 500)); statement.setLong(3, System.currentTimeMillis()); statement.setInt(4, id); }
            int changed = statement.executeUpdate();
            if (changed > 0) plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "STAFF_ALERT_" + status, String.valueOf(id), "resolution=" + trim(resolution, 160));
            return changed > 0;
        } catch (Exception error) { return false; }
    }

    public boolean teleportToAlert(Player staff, int id) {
        Alert alert = alert(id);
        if (alert == null || !alert.hasLocation()) return false;
        World world = Bukkit.getWorld(alert.world());
        if (world == null) return false;
        pushReturn(staff);
        boolean teleported = staff.teleport(new Location(world, alert.x(), alert.y(), alert.z(), staff.getYaw(), staff.getPitch()));
        if (!teleported) returnLocations.getOrDefault(staff.getUniqueId(), new ArrayDeque<>()).pollFirst();
        else plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "STAFF_ALERT_TELEPORT", String.valueOf(id), "world=" + alert.world());
        return teleported;
    }

    public boolean teleportToLastAlert(Player staff) {
        return alerts("ALL", false, 100).stream().filter(Alert::hasLocation).findFirst().map(row -> teleportToAlert(staff, row.id())).orElse(false);
    }

    public void pushReturn(Player staff) {
        Deque<Location> locations = returnLocations.computeIfAbsent(staff.getUniqueId(), ignored -> new ArrayDeque<>());
        locations.addFirst(staff.getLocation().clone());
        while (locations.size() > 10) locations.removeLast();
    }

    public boolean returnStaff(Player staff) {
        Deque<Location> locations = returnLocations.get(staff.getUniqueId());
        if (locations == null || locations.isEmpty()) return false;
        Location target = locations.removeFirst();
        boolean teleported = target.getWorld() != null && staff.teleport(target);
        if (teleported) plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "STAFF_RETURN", "LOCATION", target.getWorld().getName());
        return teleported;
    }

    private void send(String permission, String title, String detail) {
        Runnable task = () -> Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission(permission) && plugin.isStaffModeActive(player.getUniqueId()))
            .forEach(player -> player.sendMessage(fmt.warn("[" + title + "] " + detail)));
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < Level.SEVERE.intValue() || Boolean.TRUE.equals(publishing.get())) return;
        if (record.getLoggerName() != null && record.getLoggerName().contains("StaffAlertService")) return;
        publishing.set(true);
        try {
            int count = serverErrors.incrementAndGet();
            String message = record.getMessage() == null ? record.getLevel().getName() : record.getMessage().replace('\n', ' ');
            if (message.length() > 500) message = message.substring(0, 500) + "...";
            recordAlert("SERVER_ERROR", "CRITICAL", null, null, message, null);
            alertAdmins("Server error #" + count, trim(message, 180));
        } finally { publishing.set(false); }
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
            player.sendMessage(fmt.info("Open alerts: &e" + alerts("ALL", false, 200).size()
                + " &7| Anti-cheat flags (24h): &e" + countFlagsSince(System.currentTimeMillis() - 86_400_000L)
                + " &7| Active evidence: &e" + countActiveEvidence()));
            player.sendMessage(fmt.info("Startup audit: &a" + String.join(" &8| &a", startupReport)));
        }, 20L);
    }

    private boolean isStaff(Player player) {
        try { return plugin.getServiceRegistry().get(AccessService.class).isStaff(player.getUniqueId()); }
        catch (RuntimeException ignored) { return player.hasPermission("vs.staff.alerts") || player.hasPermission("vs.admin.alerts"); }
    }

    private int countFlagsSince(long since) { return count("SELECT COUNT(*) FROM anticheat_flags WHERE created_at >= ?", since); }
    private int countActiveEvidence() { return count("SELECT COUNT(*) FROM district_evidence WHERE status IN ('UNHANDLED', 'ACTIVE')", null); }
    private int count(String sql, Long value) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value != null) statement.setLong(1, value); ResultSet result = statement.executeQuery(); return result.next() ? result.getInt(1) : 0;
        } catch (Exception ignored) { return 0; }
    }
    private Alert read(ResultSet result) throws Exception {
        String uuid = result.getString("player_uuid"); Object x = result.getObject("x"), y = result.getObject("y"), z = result.getObject("z");
        return new Alert(result.getInt("id"), result.getString("alert_type"), result.getString("severity"),
            uuid == null ? null : UUID.fromString(uuid), result.getString("player_name"), result.getString("details"), result.getString("world"),
            x == null ? null : ((Number) x).doubleValue(), y == null ? null : ((Number) y).doubleValue(), z == null ? null : ((Number) z).doubleValue(),
            result.getString("status"), result.getString("assigned_to"), result.getLong("created_at"));
    }
    private String normalize(String value, String fallback) { String out = value == null ? fallback : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_"); return out.isBlank() ? fallback : out; }
    private String trim(String value, int max) { if (value == null) return ""; String clean = value.trim(); return clean.length() <= max ? clean : clean.substring(0, max); }
}
