package com.vaultsurvival.plugin.travel;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.breach.BreachService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Safe player TPA and persistent player/district homes. */
public final class TravelService implements CommandExecutor, TabCompleter, Listener {
    private record Request(UUID requester, long expiresAt) { }
    private record Warmup(Location origin, BukkitTask task, String reason) { }
    private final VaultSurvivalPlugin plugin;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();

    public TravelService(VaultSurvivalPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> tpa(player, args);
            case "tpaccept" -> answer(player, true);
            case "tpdeny" -> answer(player, false);
            case "sethome" -> setHome(player, name(args));
            case "home" -> home(player, name(args));
            case "delhome" -> deleteHome(player, name(args));
            case "homes" -> homes(player);
            default -> true;
        };
    }

    private boolean tpa(Player requester, String[] args) {
        if (args.length != 1) { requester.sendMessage(plugin.getMessageFormatter().info("Usage: /tpa <player>")); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(requester)) { requester.sendMessage(plugin.getMessageFormatter().error("That player is not available.")); return true; }
        if (!canTravel(requester) || !canTravel(target)) return true;
        long timeout = Math.max(10, plugin.getConfigManager().getConfig().getLong("travel.tpa.timeoutSeconds", 60));
        requests.put(target.getUniqueId(), new Request(requester.getUniqueId(), System.currentTimeMillis() + timeout * 1000));
        requester.sendMessage(plugin.getMessageFormatter().success("Teleport request sent to " + target.getName() + "."));
        try {
            plugin.getServiceRegistry().get(DialogService.class).openResult(target, "Teleport Request",
                requester.getName() + " wants to teleport to you. This expires in " + timeout + " seconds.", List.of(
                    DialogMenuItem.item("Accept", "Teleport the requester to you.", "tpaccept", null, Material.LIME_DYE),
                    DialogMenuItem.item("Deny", "Reject this request.", "tpdeny", null, Material.RED_DYE)));
        } catch (RuntimeException fallback) {
            target.sendMessage(plugin.getMessageFormatter().info(requester.getName() + " requested to teleport. Use /tpaccept or /tpdeny."));
        }
        return true;
    }

    private boolean answer(Player target, boolean accept) {
        Request request = requests.remove(target.getUniqueId());
        Player requester = request == null ? null : Bukkit.getPlayer(request.requester());
        if (request == null || requester == null || request.expiresAt() < System.currentTimeMillis()) {
            target.sendMessage(plugin.getMessageFormatter().error("No active teleport request.")); return true;
        }
        if (!accept) {
            requester.sendMessage(plugin.getMessageFormatter().warn(target.getName() + " denied your teleport request."));
            target.sendMessage(plugin.getMessageFormatter().info("Teleport request denied.")); return true;
        }
        if (!canTravel(target) || !canTravel(requester)) return true;
        beginTeleport(requester, target.getLocation(), "TPA to " + target.getName());
        plugin.getAuditLogger().log(target.getUniqueId(), target.getName(), "TPA_ACCEPT", "PLAYER", requester.getUniqueId().toString(), "target=" + target.getUniqueId());
        return true;
    }

    private boolean setHome(Player player, String name) {
        String normalized = normalize(name);
        if (normalized == null) { player.sendMessage(plugin.getMessageFormatter().error("Home names use letters, numbers, _ or - (max 24).")); return true; }
        int existing = countHomes(player.getUniqueId()); int limit = homeLimit(player);
        boolean existed = homeExists(player.getUniqueId(), normalized);
        if (!existed && existing >= limit) {
            player.sendMessage(plugin.getMessageFormatter().error("Your rank allows " + limit + " home" + (limit == 1 ? "" : "s") + ".")); return true;
        }
        Location l = player.getLocation();
        try (Connection c = plugin.getDatabase().getConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO player_homes(player_uuid,name,world,x,y,z,yaw,pitch,created_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid,name) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,created_at=excluded.created_at")) {
            ps.setString(1, player.getUniqueId().toString()); ps.setString(2, normalized); location(ps, 3, l); ps.setLong(9, System.currentTimeMillis()); ps.executeUpdate();
            player.sendMessage(plugin.getMessageFormatter().success("Home '" + normalized + "' saved (" + (existing + (existed ? 0 : 1)) + "/" + limit + ")."));
        } catch (SQLException e) { player.sendMessage(plugin.getMessageFormatter().error("Home could not be saved.")); }
        return true;
    }

    private boolean home(Player player, String name) {
        Location destination = readLocation("SELECT world,x,y,z,yaw,pitch FROM player_homes WHERE player_uuid=? AND name=?", player.getUniqueId().toString(), normalize(name));
        if (destination == null) { player.sendMessage(plugin.getMessageFormatter().error("Home not found. Use /homes.")); return true; }
        beginTeleport(player, destination, "home " + name);
        return true;
    }

    private boolean deleteHome(Player player, String name) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM player_homes WHERE player_uuid=? AND name=?")) {
            statement.setString(1, player.getUniqueId().toString()); statement.setString(2, normalize(name)); int rows = statement.executeUpdate();
            player.sendMessage(rows == 1 ? plugin.getMessageFormatter().success("Home deleted.") : plugin.getMessageFormatter().error("Home not found."));
        } catch (SQLException e) { player.sendMessage(plugin.getMessageFormatter().error("Home could not be deleted.")); }
        return true;
    }

    private boolean homes(Player player) {
        try (Connection c = plugin.getDatabase().getConnection(); PreparedStatement ps = c.prepareStatement("SELECT name FROM player_homes WHERE player_uuid=? ORDER BY name")) {
            ps.setString(1, player.getUniqueId().toString()); ResultSet rs = ps.executeQuery(); java.util.ArrayList<String> names = new java.util.ArrayList<>(); while (rs.next()) names.add(rs.getString(1));
            player.sendMessage(plugin.getMessageFormatter().info("Homes (" + names.size() + "/" + homeLimit(player) + "): " + (names.isEmpty() ? "none" : String.join(", ", names))));
        } catch (SQLException e) { player.sendMessage(plugin.getMessageFormatter().error("Homes could not be loaded.")); }
        return true;
    }

    public boolean districtHome(Player player) {
        DistrictData.District district = districts().getPlayerDistrict(player.getUniqueId());
        if (district == null) { player.sendMessage(plugin.getMessageFormatter().error("You are not a district member.")); return true; }
        Location location = readLocation("SELECT world,x,y,z,yaw,pitch FROM district_homes WHERE district_id=?", String.valueOf(district.getId()));
        if (location == null) { player.sendMessage(plugin.getMessageFormatter().error("The mayor has not set a district home.")); return true; }
        beginTeleport(player, location, "district home");
        return true;
    }

    public boolean setDistrictHome(Player player) {
        DistrictService districts = districts(); DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !district.hasRole(player.getUniqueId(), DistrictData.DistrictRole.MAYOR)) {
            player.sendMessage(plugin.getMessageFormatter().error("Only the district MAYOR can set the district home.")); return true;
        }
        DistrictData.BlockClaim claim = districts.getClaim(district.getId()); Location l = player.getLocation();
        if (claim == null || !claim.worldName().equals(l.getWorld().getName()) || !claim.contains(l.getBlockX(), l.getBlockZ())) { player.sendMessage(plugin.getMessageFormatter().error("Set the district home inside the district claim.")); return true; }
        try (Connection c = plugin.getDatabase().getConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO district_homes(district_id,world,x,y,z,yaw,pitch,set_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(district_id) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,set_by=excluded.set_by,updated_at=excluded.updated_at")) {
            ps.setInt(1, district.getId()); location(ps, 2, l); ps.setString(8, player.getUniqueId().toString()); ps.setLong(9, System.currentTimeMillis()); ps.executeUpdate();
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_HOME_SET", "DISTRICT", String.valueOf(district.getId()), "world=" + l.getWorld().getName() + " x=" + l.getX() + " y=" + l.getY() + " z=" + l.getZ());
            player.sendMessage(plugin.getMessageFormatter().success("District home set."));
        } catch (SQLException e) { player.sendMessage(plugin.getMessageFormatter().error("District home could not be saved.")); }
        return true;
    }

    private int homeLimit(Player player) {
        if (player.hasPermission("vs.home.limit.3")) return 3;
        if (player.hasPermission("vs.home.limit.2")) return 2;
        try {
            String group = plugin.getServiceRegistry().get(AccessService.class).getPrimaryGroup(player.getUniqueId()).toLowerCase(Locale.ROOT);
            return Math.max(1, plugin.getConfigManager().getConfig().getInt("travel.homes.rankLimits." + group, 1));
        } catch (RuntimeException ignored) { return 1; }
    }

    private DistrictService districts() { return plugin.getServiceRegistry().get(DistrictService.class); }
    public boolean beginTeleport(Player player, Location destination, String reason) {
        if (destination == null || !canTravel(player)) return false;
        if (carriesCash(player)) {
            player.sendMessage(plugin.getMessageFormatter().error("You cannot teleport while carrying physical cash. Store or spend it first."));
            return false;
        }
        try {
            if (plugin.getServiceRegistry().get(BreachService.class).isTeleportBlocked(player.getUniqueId())) {
                player.sendMessage(plugin.getMessageFormatter().error("You cannot teleport during a breach cooldown.")); return false;
            }
        } catch (RuntimeException ignored) { }
        cancelWarmup(player, null);
        int seconds = Math.max(0, plugin.getConfigManager().getConfig().getInt("travel.teleportWarmupSeconds", 3));
        if (seconds == 0) {
            player.teleportAsync(destination); return true;
        }
        Location origin = player.getLocation().clone();
        player.sendMessage(plugin.getMessageFormatter().info("Teleporting in " + seconds + " seconds. Do not move."));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Warmup current = warmups.remove(player.getUniqueId());
            if (current == null || !player.isOnline()) return;
            player.teleportAsync(destination).thenAccept(ok -> {
                if (ok) player.sendMessage(plugin.getMessageFormatter().success("Teleport complete."));
            });
        }, seconds * 20L);
        warmups.put(player.getUniqueId(), new Warmup(origin, task, reason));
        for (int elapsed = 0; elapsed < seconds; elapsed++) {
            int delay = elapsed;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (warmups.containsKey(player.getUniqueId()) && player.isOnline()) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, .7f, 1.25f + delay * .08f);
                }
            }, elapsed * 20L);
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Warmup warmup = warmups.get(event.getPlayer().getUniqueId());
        if (warmup == null || event.getTo() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
            || event.getFrom().getBlockY() != event.getTo().getBlockY()
            || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            cancelWarmup(event.getPlayer(), "Teleport cancelled because you moved.");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancelWarmup(event.getPlayer(), null); requests.remove(event.getPlayer().getUniqueId()); }

    private void cancelWarmup(Player player, String message) {
        Warmup previous = warmups.remove(player.getUniqueId());
        if (previous != null) previous.task().cancel();
        if (previous != null && message != null) player.sendMessage(plugin.getMessageFormatter().warn(message));
    }

    private boolean carriesCash(Player player) {
        try {
            CurrencyService currency = plugin.getServiceRegistry().get(CurrencyService.class);
            for (var item : player.getInventory().getContents()) if (item != null && currency.isCashItem(item)) return true;
            return currency.isCashItem(player.getInventory().getItemInOffHand());
        } catch (RuntimeException unavailable) { return true; }
    }

    private boolean canTravel(Player p) { if (p.hasMetadata("combat_tagged") || p.hasMetadata("staffmode_frozen")) { p.sendMessage(plugin.getMessageFormatter().error("You cannot teleport right now.")); return false; } return true; }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equalsIgnoreCase("tpa")) return Bukkit.getOnlinePlayers().stream()
            .filter(other -> !other.equals(player)).map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().toList();
        if (command.getName().equalsIgnoreCase("home") || command.getName().equalsIgnoreCase("delhome")) {
            try (Connection c = plugin.getDatabase().getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT name FROM player_homes WHERE player_uuid=? AND lower(name) LIKE ? ORDER BY name")) {
                ps.setString(1, player.getUniqueId().toString()); ps.setString(2, typed + "%");
                ResultSet rows = ps.executeQuery(); java.util.ArrayList<String> names = new java.util.ArrayList<>();
                while (rows.next()) names.add(rows.getString(1)); return names;
            } catch (SQLException ignored) { return List.of(); }
        }
        return command.getName().equalsIgnoreCase("sethome") ? List.of("home") : List.of();
    }
    private String name(String[] args) { return args.length == 0 ? "home" : args[0]; }
    private String normalize(String name) { return name != null && name.matches("[A-Za-z0-9_-]{1,24}") ? name.toLowerCase(Locale.ROOT) : null; }
    private int countHomes(UUID uuid) { return scalar("SELECT COUNT(*) FROM player_homes WHERE player_uuid=?", uuid.toString()); }
    private boolean homeExists(UUID uuid, String name) { return name != null && scalar("SELECT COUNT(*) FROM player_homes WHERE player_uuid=? AND name=?", uuid.toString(), name) > 0; }
    private int scalar(String sql, String... values) { try (Connection c=plugin.getDatabase().getConnection(); PreparedStatement ps=c.prepareStatement(sql)) { for(int i=0;i<values.length;i++) ps.setString(i+1,values[i]); ResultSet rs=ps.executeQuery(); return rs.next()?rs.getInt(1):0; } catch(SQLException e){return 0;} }
    private Location readLocation(String sql, String... values) { try(Connection c=plugin.getDatabase().getConnection();PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)ps.setString(i+1,values[i]);ResultSet rs=ps.executeQuery();if(!rs.next())return null;World w=Bukkit.getWorld(rs.getString("world"));return w==null?null:new Location(w,rs.getDouble("x"),rs.getDouble("y"),rs.getDouble("z"),rs.getFloat("yaw"),rs.getFloat("pitch"));}catch(SQLException e){return null;} }
    private void location(PreparedStatement ps,int offset,Location l)throws SQLException{ps.setString(offset,l.getWorld().getName());ps.setDouble(offset+1,l.getX());ps.setDouble(offset+2,l.getY());ps.setDouble(offset+3,l.getZ());ps.setFloat(offset+4,l.getYaw());ps.setFloat(offset+5,l.getPitch());}
}
