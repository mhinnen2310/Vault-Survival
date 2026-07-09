package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class StationCommand implements CommandExecutor, TabCompleter {
    private final StationService service;
    private final MessageFormatter fmt;
    private final VaultSurvivalPlugin plugin;

    public StationCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(StationService.class);
        this.fmt = plugin.getMessageFormatter();
        this.plugin = plugin;
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendUsage(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "remove" -> handleRemove(player, args);
            case "list" -> handleList(player);
            case "travel" -> handleTravel(player, args);
            case "next" -> handleNext(player);
            case "buy" -> handleBuy(player, args);
            case "board" -> handleBoard(player, args);
            case "journey" -> handleJourneyStatus(player);
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
    private boolean handleBuy(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /station buy <routeId>")); return true; }
        int routeId = Integer.parseInt(args[1]);
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            if (!railService.startJourney(player, routeId)) {
                player.sendMessage(fmt.error("Could not buy ticket. Check the route and your location."));
            }
        } catch (Exception e) {
            player.sendMessage(fmt.error("Rail service not available."));
        }
        return true;
    }
    private boolean handleBoard(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /station board <routeId>")); return true; }
        int routeId = Integer.parseInt(args[1]);
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            if (!railService.boardTrain(player, routeId)) {
                player.sendMessage(fmt.error("Could not board. Check your journey status with /station journey."));
            }
        } catch (Exception e) {
            player.sendMessage(fmt.error("Rail service not available."));
        }
        return true;
    }
    private boolean handleJourneyStatus(Player player) {
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            var journey = railService.getActiveJourney(player.getUniqueId());
            if (journey == null) {
                player.sendMessage(fmt.info("You have no active journey."));
                return true;
            }
            player.sendMessage(fmt.header("Your Journey"));
            player.sendMessage(fmt.info("Route: &e" + journey.getFromStationName() +
                " &8→ &f" + journey.getToStationName()));
            player.sendMessage(fmt.info("Status: &e" + journey.getState().name()));
            player.sendMessage(fmt.info("Ticket: &6" + fmt.formatMoney(journey.getTicketPrice(),
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            if (journey.getState() == com.vaultsurvival.plugin.rail.RailJourneyData.JourneyState.IN_TRANSIT) {
                player.sendMessage(fmt.info("Time remaining: &e" + journey.getTimeRemaining()));
            }
            if (journey.canBoard()) {
                player.sendMessage(fmt.info("Board with: &e/station board " + journey.getRouteId()));
            }
        } catch (Exception e) {
            player.sendMessage(fmt.info("Rail service not available."));
        }
        return true;
    }
    private boolean handleNext(Player player) {
        // Show departures from nearby rail stations
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            var stations = railService.getActiveStations();
            var routes = railService.getAllRoutes();

            player.sendMessage(fmt.header("Next Departures"));
            boolean found = false;
            for (var station : stations) {
                var stationRoutes = routes.stream()
                    .filter(r -> r.getFromStationId() == station.getId() &&
                                 r.getStatus() == com.vaultsurvival.plugin.rail.RailData.RouteStatus.ACTIVE)
                    .toList();
                if (!stationRoutes.isEmpty()) {
                    found = true;
                    player.sendMessage(fmt.info("&e" + station.getName() + " &7(#" + station.getId() + ")"));
                    for (var route : stationRoutes) {
                        var toStation = stations.stream()
                            .filter(s -> s.getId() == route.getToStationId()).findFirst();
                        String toName = toStation.map(com.vaultsurvival.plugin.rail.RailData.Station::getName).orElse("?");
                        player.sendMessage(fmt.info("  &8→ &f" + toName +
                            " &7| Price: &6" + route.getTicketPrice() +
                            " &7| Route: &e/rail travel " + route.getId()));
                    }
                }
            }
            if (!found) {
                player.sendMessage(fmt.info("No departures available."));
            }
        } catch (Exception e) {
            player.sendMessage(fmt.info("Rail service not available."));
        }
        return true;
    }

    private void sendUsage(Player p) {
        p.sendMessage(fmt.header("Station Commands"));
        p.sendMessage(fmt.info("/station list &8- List all stations"));
        p.sendMessage(fmt.info("/station next &8- Show next departures"));
        p.sendMessage(fmt.info("/station buy <route> &8- Buy a ticket"));
        p.sendMessage(fmt.info("/station board <route> &8- Board the train"));
        p.sendMessage(fmt.info("/station journey &8- Show journey status"));
        p.sendMessage(fmt.info("/station travel <id> &8- Travel to a station"));
        if (p.hasPermission("vs.station.admin")) {
            p.sendMessage(fmt.info("/station create <name> <free|paid> <cost> &8- Create station"));
            p.sendMessage(fmt.info("/station remove <id> &8- Remove station"));
        }
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create","remove","list","travel","next","buy","board","journey");
        return List.of();
    }
}
