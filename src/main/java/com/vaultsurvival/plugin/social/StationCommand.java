package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class StationCommand implements CommandExecutor, TabCompleter {
    private final StationService service;
    private final MessageFormatter fmt;

    public StationCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(StationService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendUsage(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "travel" -> handleTravel(player, args);
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleCreate(Player player, String[] args) {
        if (!player.hasPermission("vs.station.admin")) { player.sendMessage(fmt.permissionDenied()); return true; }
        if (args.length < 4) { player.sendMessage(fmt.error("Usage: /station create <name> <free|paid> <cost>")); return true; }
        StationData.RouteType type = args[2].equalsIgnoreCase("paid") ? StationData.RouteType.PAID : StationData.RouteType.FREE;
        long cost = type == StationData.RouteType.PAID ? Long.parseLong(args[3]) : 0;
        var s = service.createStation(args[1], player.getLocation(), type, cost, player.getUniqueId());
        if (s != null) player.sendMessage(fmt.success("Station &e" + s.getName() + " &acreated! (#" + s.getId() + ")"));
        return true;
    }
    private boolean handleRemove(Player player, String[] args) {
        if (!player.hasPermission("vs.station.admin")) { player.sendMessage(fmt.permissionDenied()); return true; }
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /station remove <id>")); return true; }
        service.removeStation(Integer.parseInt(args[1]));
        player.sendMessage(fmt.success("Station removed.")); return true;
    }
    private boolean handleList(Player player) {
        var all = service.getAllStations();
        player.sendMessage(fmt.header("Stations (" + all.size() + ")"));
        for (var s : all) {
            player.sendMessage(fmt.info("&e#" + s.getId() + " &f" + s.getName() + " &7" + s.getType() + (s.getCost() > 0 ? " &6" + s.getCost() : "")));
        }
        return true;
    }
    private boolean handleTravel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /station travel <id>")); return true; }
        int targetId = Integer.parseInt(args[1]);
        var all = service.getAllStations();
        StationData.Station nearest = null;
        double nearestDist = Double.MAX_VALUE;        for (var s : all) {
                if (s.getId() == targetId) continue;
                var sLoc = s.getLocation();
                if (sLoc == null) continue;
                double d = sLoc.distanceSquared(player.getLocation());
                if (d < nearestDist) { nearestDist = d; nearest = s; }
            }
        if (nearest != null) service.travel(nearest.getId(), targetId, player.getUniqueId());
        return true;
    }
    private void sendUsage(Player p) {
        p.sendMessage(fmt.header("Station Commands"));
        p.sendMessage(fmt.info("/station list &8- List all stations"));
        p.sendMessage(fmt.info("/station travel <id> &8- Travel to a station"));
        if (p.hasPermission("vs.station.admin")) {
            p.sendMessage(fmt.info("/station create <name> <free|paid> <cost> &8- Create station"));
            p.sendMessage(fmt.info("/station remove <id> &8- Remove station"));
        }
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create","remove","list","travel");
        return List.of();
    }
}
