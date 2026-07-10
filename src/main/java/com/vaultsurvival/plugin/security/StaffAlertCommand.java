package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Audited command surface for the persistent staff alert queue and security diagnostics. */
public final class StaffAlertCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final StaffAlertService alerts;

    public StaffAlertCommand(VaultSurvivalPlugin plugin, StaffAlertService alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) { sender.sendMessage(plugin.getMessageFormatter().error("Players only.")); return true; }
        if (!plugin.isStaffModeActive(staff.getUniqueId()) || (!staff.hasPermission("vs.staff.alerts") && !staff.hasPermission("vs.admin"))) {
            staff.sendMessage(plugin.getMessageFormatter().error("Activate staffmode and use a staff-alert permission.")); return true;
        }
        try {
            String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            return switch (action) {
                case "list", "queue" -> list(staff, args.length > 1 ? args[1] : "ALL");
                case "claim" -> update(staff, args, true);
                case "resolve" -> update(staff, args, false);
                case "tp", "teleport" -> teleport(staff, args);
                case "last" -> last(staff);
                case "return", "back" -> back(staff);
                case "antixray", "xray" -> antiXray(staff, args);
                case "storage", "chestesp" -> storage(staff, args);
                case "storagetp" -> storageTeleport(staff, args);
                case "scores", "scoring" -> scores(staff, args.length > 1 ? args[1] : "ALL");
                case "payouts", "payout" -> payouts(staff);
                default -> { staff.sendMessage(plugin.getMessageFormatter().error("Usage: /staffalerts <list|claim|resolve|tp|last|return|antixray|storage|scores|payouts>")); yield true; }
            };
        } catch (IllegalArgumentException expected) {
            staff.sendMessage(plugin.getMessageFormatter().error(expected.getMessage())); return true;
        }
    }

    private boolean list(Player staff, String type) {
        List<StaffAlertService.Alert> queue = alerts.alerts(type, false, 100);
        List<DialogMenuItem> items = new ArrayList<>();
        for (StaffAlertService.Alert alert : queue) {
            String who = alert.playerName() == null ? "system" : alert.playerName();
            String command = alert.status().equals("OPEN") ? "staffalerts claim " + alert.id()
                : alert.hasLocation() ? "staffalerts tp " + alert.id() : "staffalerts resolve " + alert.id() + " Reviewed";
            items.add(DialogMenuItem.adminItem("#" + alert.id() + " " + alert.severity() + " " + alert.type(),
                who + " | " + shortText(alert.details(), 105) + " | " + age(alert.createdAt()), command, "vs.staff.alerts",
                material(alert.severity())));
        }
        if (items.isEmpty()) items.add(info("Queue Clear", "No actionable staff alerts match this filter.", Material.LIME_DYE));
        items.add(DialogMenuItem.adminItem("Resolve by ID", "Enter alert id and resolution note.", "vsmenu input alert_resolve", "vs.staff.alerts", Material.EMERALD));
        items.add(DialogMenuItem.item("Back", "Return to staff security.", "vsmenu security", null, Material.ARROW));
        open(staff, "Security Alert Queue", queue.size() + " open or claimed alert(s). Click OPEN rows to claim them.", items);
        return true;
    }

    private boolean update(Player staff, String[] args, boolean claim) {
        if (args.length < 2) throw new IllegalArgumentException("An alert id is required.");
        int id = integer(args[1]);
        boolean success = claim ? alerts.claim(staff, id) : alerts.resolve(staff, id, args.length > 2 ? join(args, 2) : "Resolved by staff");
        staff.sendMessage(success ? plugin.getMessageFormatter().success("Alert queue updated.") : plugin.getMessageFormatter().error("Alert was already handled or not found."));
        return list(staff, "ALL");
    }

    private boolean teleport(Player staff, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("An alert id is required.");
        boolean success = alerts.teleportToAlert(staff, integer(args[1]));
        staff.sendMessage(success ? plugin.getMessageFormatter().success("Teleported to alert location. Use /staffalerts return to go back.")
            : plugin.getMessageFormatter().error("That alert has no available location."));
        return true;
    }

    private boolean last(Player staff) {
        boolean success = alerts.teleportToLastAlert(staff);
        staff.sendMessage(success ? plugin.getMessageFormatter().success("Teleported to the newest located alert. Use /staffalerts return to go back.")
            : plugin.getMessageFormatter().error("There is no open alert with a loaded-world location."));
        return true;
    }

    private boolean back(Player staff) {
        staff.sendMessage(alerts.returnStaff(staff) ? plugin.getMessageFormatter().success("Returned to your previous staff location.")
            : plugin.getMessageFormatter().error("No previous staff location is stored."));
        return true;
    }

    private boolean antiXray(Player staff, String[] args) {
        if (args.length >= 4 && args[1].equalsIgnoreCase("set")) {
            if (!hasPermission(staff, "vs.admin")) throw new IllegalArgumentException("Full admin permission is required to change Paper anti-xray configuration.");
            String worldName = args[2]; boolean enabled;
            if (args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true")) enabled = true;
            else if (args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("false")) enabled = false;
            else throw new IllegalArgumentException("Use on or off.");
            int mode = args.length > 4 ? integer(args[4]) : plugin.getConfigManager().getConfig().getInt("security.antiXray.requireEngineMode", 2);
            File target = antiXrayFile(worldName);
            if (target == null) throw new IllegalArgumentException("World not found. Use 'default' or a loaded world name.");
            try {
                File parent = target.getParentFile(); if (parent != null) parent.mkdirs();
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(target);
                yaml.set("anticheat.anti-xray.enabled", enabled);
                yaml.set("anticheat.anti-xray.engine-mode", mode);
                yaml.save(target);
                plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "PAPER_ANTIXRAY_CONFIG", worldName,
                    "enabled=" + enabled + " engine=" + mode + " file=" + target.getAbsolutePath());
                staff.sendMessage(plugin.getMessageFormatter().success("Paper anti-xray config updated. Restart Paper to apply it safely."));
            } catch (Exception error) { throw new IllegalArgumentException("Could not write Paper anti-xray configuration: " + error.getMessage()); }
        }
        List<DialogMenuItem> items = new ArrayList<>();
        int required = plugin.getConfigManager().getConfig().getInt("security.antiXray.requireEngineMode", 2);
        items.add(antiXrayRow("default", antiXrayFile("default"), required));
        for (World world : Bukkit.getWorlds()) items.add(antiXrayRow(world.getName(), antiXrayFile(world.getName()), required));
        items.add(DialogMenuItem.adminItem("Enable Defaults", "Enable engine mode " + required + " in Paper world defaults.", "staffalerts antixray set default on " + required, "vs.admin", Material.EMERALD));
        items.add(DialogMenuItem.item("Back", "Return to security tools.", "vsmenu security", null, Material.ARROW));
        open(staff, "Paper Anti-Xray", "Reads actual Paper world configuration. Changes are audited and require a safe restart.", items);
        return true;
    }

    private DialogMenuItem antiXrayRow(String name, File file, int required) {
        if (file == null || !file.isFile()) return info(name, "No explicit paper-world config; inherits defaults.", Material.GRAY_DYE);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean enabled = yaml.getBoolean("anticheat.anti-xray.enabled", false);
        int mode = yaml.getInt("anticheat.anti-xray.engine-mode", -1);
        boolean healthy = enabled && mode == required;
        return info(name, "enabled=" + enabled + " | engine-mode=" + mode + " | " + (healthy ? "VERIFIED" : "ACTION REQUIRED"),
            healthy ? Material.DEEPSLATE_DIAMOND_ORE : Material.BARRIER);
    }

    private File antiXrayFile(String worldName) {
        File root = plugin.getServer().getWorldContainer();
        if (worldName.equalsIgnoreCase("default")) return new File(root, "config/paper-world-defaults.yml");
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new File(world.getWorldFolder(), "paper-world.yml");
    }

    private boolean storage(Player staff, String[] args) {
        int configured = plugin.getConfigManager().isStaffSandbox() ? 2048 : 128;
        int radius = args.length > 1 ? Math.min(configured, Math.max(8, integer(args[1]))) : Math.min(64, configured);
        double maxDistance = (double) radius * radius;
        List<Container> found = new ArrayList<>();
        for (Chunk chunk : staff.getWorld().getLoadedChunks()) {
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Container container && state.getLocation().distanceSquared(staff.getLocation()) <= maxDistance) found.add(container);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(container -> container.getLocation().distanceSquared(staff.getLocation())));
        List<DialogMenuItem> items = new ArrayList<>();
        for (Container container : found.stream().limit(50).toList()) {
            Location location = container.getLocation(); int used = (int) Arrays.stream(container.getInventory().getContents()).filter(item -> item != null && !item.getType().isAir()).count();
            staff.spawnParticle(Particle.END_ROD, location.clone().add(.5, .8, .5), 8, .35, .35, .35, 0.01);
            items.add(DialogMenuItem.adminItem(container.getType().name() + " @ " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(),
                used + "/" + container.getInventory().getSize() + " occupied slots | click to teleport",
                "staffalerts storagetp " + location.getWorld().getName() + " " + location.getX() + " " + (location.getY() + 1) + " " + location.getZ(), "vs.staff.alerts", Material.CHEST));
        }
        if (items.isEmpty()) items.add(info("No Loaded Storage", "No container tile entities were found within " + radius + " blocks in already loaded chunks.", Material.BARRIER));
        items.add(DialogMenuItem.item("Back", "Return to security tools.", "vsmenu security", null, Material.ARROW));
        plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "STAFF_STORAGE_SCAN", staff.getWorld().getName(), "radius=" + radius + " found=" + found.size());
        open(staff, "Storage Discovery", found.size() + " loaded container(s) within " + radius + " blocks. Containers are marked with particles.", items);
        return true;
    }

    private boolean storageTeleport(Player staff, String[] args) {
        if (args.length < 5) throw new IllegalArgumentException("Invalid storage teleport target.");
        World world = Bukkit.getWorld(args[1]); if (world == null) throw new IllegalArgumentException("Storage world is not loaded.");
        alerts.pushReturn(staff);
        boolean success;
        try { success = staff.teleport(new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]), staff.getYaw(), staff.getPitch())); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid storage coordinates."); }
        staff.sendMessage(success ? plugin.getMessageFormatter().success("Teleported to storage. Use /staffalerts return to go back.") : plugin.getMessageFormatter().error("Storage teleport failed."));
        return true;
    }

    private boolean scores(Player staff, String filter) {
        List<DialogMenuItem> items = new ArrayList<>();
        String normalized = filter.toUpperCase(Locale.ROOT);
        String sql = "SELECT player_uuid,check_type,COUNT(*) flags,SUM(score) total,MAX(created_at) latest FROM anticheat_flags WHERE created_at>=? "
            + (normalized.equals("ALL") ? "" : "AND check_type LIKE ? ") + "GROUP BY player_uuid,check_type ORDER BY total DESC LIMIT 100";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis() - 86_400_000L);
            if (!normalized.equals("ALL")) statement.setString(2, "%" + normalized + "%");
            ResultSet result = statement.executeQuery();
            while (result.next()) items.add(info(shortText(result.getString("player_uuid"), 12) + " / " + result.getString("check_type"),
                result.getInt("flags") + " flags | score " + String.format(Locale.ROOT, "%.1f", result.getDouble("total")) + " | " + age(result.getLong("latest")), Material.REDSTONE_TORCH));
        } catch (Exception error) { throw new IllegalArgumentException("Could not load anti-cheat scores."); }
        if (items.isEmpty()) items.add(info("No Signals", "No matching anti-cheat scores were recorded in the last 24 hours.", Material.LIME_DYE));
        items.add(DialogMenuItem.item("Back", "Return to security tools.", "vsmenu security", null, Material.ARROW));
        open(staff, "Anti-Cheat Scoring: " + normalized, "Movement, combat, break, and inventory signals. Scores never auto-ban.", items);
        return true;
    }

    private boolean payouts(Player staff) {
        long threshold = plugin.getConfigManager().getConfig().getLong("security.suspiciousPayout.minimumAmount", 25_000L);
        List<DialogMenuItem> items = new ArrayList<>();
        String sql = "SELECT id,player_uuid,amount,source_type,source_id,status,created_at FROM payout_lockers WHERE amount>=? ORDER BY created_at DESC LIMIT 100";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, threshold); ResultSet result = statement.executeQuery();
            while (result.next()) items.add(info("Payout #" + result.getInt("id") + " / " + shortText(result.getString("player_uuid"), 12),
                result.getLong("amount") + " | " + result.getString("source_type") + " #" + result.getString("source_id") + " | " + result.getString("status") + " | " + age(result.getLong("created_at")), Material.GOLD_BLOCK));
        } catch (Exception error) { throw new IllegalArgumentException("Could not load suspicious payout review."); }
        if (items.isEmpty()) items.add(info("No High-Value Payouts", "No payout meets the configured review threshold of " + threshold + ".", Material.LIME_DYE));
        items.add(DialogMenuItem.adminItem("Payout Alerts", "Open persistent payout-related alerts.", "staffalerts list PAYOUT", "vs.staff.alerts", Material.BELL));
        items.add(DialogMenuItem.item("Back", "Return to contract oversight.", "vsmenu contracts", null, Material.ARROW));
        open(staff, "Suspicious Payout Review", "High-value payout lockers plus the actionable alert queue. Review never auto-confiscates funds.", items);
        return true;
    }

    private void open(Player staff, String title, String body, List<DialogMenuItem> items) {
        try { plugin.getServiceRegistry().get(DialogService.class).openResult(staff, title, body, items); }
        catch (RuntimeException unavailable) { staff.sendMessage(plugin.getMessageFormatter().info(title + ": " + body)); }
    }
    private boolean hasPermission(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        try { return plugin.getServiceRegistry().get(com.vaultsurvival.plugin.access.AccessService.class).hasPermission(player.getUniqueId(), permission); }
        catch (RuntimeException ignored) { return false; }
    }
    private DialogMenuItem info(String label, String description, Material material) { return DialogMenuItem.locked(label, description, description, material); }
    private Material material(String severity) { return switch (severity) { case "CRITICAL" -> Material.BARRIER; case "HIGH" -> Material.REDSTONE_BLOCK; case "MEDIUM" -> Material.REDSTONE_TORCH; default -> Material.PAPER; }; }
    private int integer(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid number: " + value); } }
    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private String shortText(String value, int max) { String clean = value == null ? "" : value.replace('\n', ' '); return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 3)) + "..."; }
    private String age(long timestamp) { long seconds = Math.max(0, Duration.ofMillis(System.currentTimeMillis() - timestamp).toSeconds()); if (seconds < 60) return seconds + "s ago"; if (seconds < 3600) return seconds / 60 + "m ago"; if (seconds < 86400) return seconds / 3600 + "h ago"; return seconds / 86400 + "d ago"; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("list", "claim", "resolve", "tp", "last", "return", "antixray", "storage", "scores", "payouts"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) return filter(List.of("ALL", "ANTICHEAT", "REPORT", "PAYOUT", "SERVER_ERROR", "JOB_DISPUTE", "KINGDOM_SUPPORT"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("scores")) return filter(List.of("ALL", "MOVEMENT", "COMBAT", "INVENTORY", "FAST_BREAK"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("antixray")) return filter(List.of("set"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("antixray") && args[1].equalsIgnoreCase("set")) return filter(java.util.stream.Stream.concat(java.util.stream.Stream.of("default"), Bukkit.getWorlds().stream().map(World::getName)).toList(), args[2]);
        return List.of();
    }
    private List<String> filter(List<String> values, String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList(); }
}
