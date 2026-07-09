package com.vaultsurvival.plugin.rail;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class RailCommand implements CommandExecutor, TabCompleter {
    private final RailService service;
    private final MessageFormatter fmt;

    public RailCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(RailService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { showHelp(sender); return true; }

        return switch (args[0].toLowerCase()) {
            case "applications" -> handleApplications(sender);
            case "approve" -> handleApprove(sender, args);
            case "deny" -> handleDeny(sender, args);
            case "stations" -> handleStations(sender);
            case "routes" -> handleRoutes(sender);
            case "createroute" -> handleCreateRoute(sender, args);
            case "suspend" -> handleSuspend(sender, args);
            case "unsuspend" -> handleUnsuspend(sender, args);
            case "travel" -> handleTravel(sender, args);
            default -> { showHelp(sender); yield true; }
        };
    }

    private boolean handleApplications(CommandSender sender) {
        if (!hasRailAdmin(sender)) return true;
        var pending = service.getPendingStations();
        if (pending.isEmpty()) {
            sender.sendMessage(fmt.info("No pending station applications."));
            return true;
        }
        sender.sendMessage(fmt.header("Pending Station Applications (" + pending.size() + ")"));
        for (var s : pending) {
            sender.sendMessage(fmt.info(
                "&e#" + s.getId() + " &7| &f" + s.getName() +
                " &7| District: &e" + s.getDistrictId() +
                " &7| Ticket: &6" + s.getTicketPrice()));
            sender.sendMessage(fmt.info("  &7Approve: &e/rail approve " + s.getId() +
                "  &7Deny: &e/rail deny " + s.getId() + " <reason>"));
        }
        return true;
    }

    private boolean handleApprove(CommandSender sender, String[] args) {
        if (!hasRailAdmin(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /rail approve <id>"));
            return true;
        }
        int id = Integer.parseInt(args[1]);
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        if (service.approveStation(id, admin)) {
            sender.sendMessage(fmt.success("Station #" + id + " approved!"));
        } else {
            sender.sendMessage(fmt.error("Failed to approve station #" + id));
        }
        return true;
    }

    private boolean handleDeny(CommandSender sender, String[] args) {
        if (!hasRailAdmin(sender)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /rail deny <id> <reason>"));
            return true;
        }
        int id = Integer.parseInt(args[1]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        if (service.denyStation(id, admin, reason)) {
            sender.sendMessage(fmt.success("Station #" + id + " denied."));
        } else {
            sender.sendMessage(fmt.error("Failed to deny station #" + id));
        }
        return true;
    }

    private boolean handleStations(CommandSender sender) {
        var active = service.getActiveStations();
        var suspended = service.getSuspendedStations();
        sender.sendMessage(fmt.header("Train Stations"));
        sender.sendMessage(fmt.info("Active: &a" + active.size() +
            "  &7| Suspended: &c" + suspended.size()));
        for (var s : active) {
            var routes = service.getRoutesFrom(s.getId());
            sender.sendMessage(fmt.info(
                "&a#" + s.getId() + " &7| &f" + s.getName() +
                " &7| District: &e" + s.getDistrictId() +
                " &7| Routes: &e" + routes.size() +
                " &7| Ticket: &6" + s.getTicketPrice()));
        }
        for (var s : suspended) {
            sender.sendMessage(fmt.info(
                "&c#" + s.getId() + " &7| &f" + s.getName() +
                " &7| SUSPENDED"));
        }
        return true;
    }

    private boolean handleRoutes(CommandSender sender) {
        var routes = service.getAllRoutes();
        var stations = service.getAllStations();
        sender.sendMessage(fmt.header("Rail Routes (" + routes.size() + ")"));
        for (var r : routes) {
            var from = stations.stream().filter(s -> s.getId() == r.getFromStationId()).findFirst();
            var to = stations.stream().filter(s -> s.getId() == r.getToStationId()).findFirst();
            String fromName = from.map(RailData.Station::getName).orElse("?");
            String toName = to.map(RailData.Station::getName).orElse("?");
            sender.sendMessage(fmt.info(
                "&e#" + r.getId() + " &7| " + fromName + " &8→ &f" + toName +
                " &7| Price: &6" + r.getTicketPrice() +
                " &7| Tax: &e" + r.getKingdomTaxPercent() + "%" +
                " &7| " + r.getStatus().name()));
        }
        return true;
    }

    private boolean handleSuspend(CommandSender sender, String[] args) {
        if (!hasRailAdmin(sender)) return true;
        if (args.length < 2) { sender.sendMessage(fmt.error("Usage: /rail suspend <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        if (service.suspendStation(id, admin)) {
            sender.sendMessage(fmt.success("Station #" + id + " suspended."));
        } else {
            sender.sendMessage(fmt.error("Failed to suspend station #" + id));
        }
        return true;
    }

    private boolean handleUnsuspend(CommandSender sender, String[] args) {
        if (!hasRailAdmin(sender)) return true;
        if (args.length < 2) { sender.sendMessage(fmt.error("Usage: /rail unsuspend <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        if (service.unsuspendStation(id, admin)) {
            sender.sendMessage(fmt.success("Station #" + id + " unsuspended."));
        } else {
            sender.sendMessage(fmt.error("Failed to unsuspend station #" + id));
        }
        return true;
    }

    private boolean handleCreateRoute(CommandSender sender, String[] args) {
        if (!hasRailAdmin(sender)) return true;
        if (args.length < 6) {
            sender.sendMessage(fmt.error("Usage: /rail createroute <fromStationId> <toStationId> <price> <tax%> <travelTicks>"));
            return true;
        }
        int fromId = Integer.parseInt(args[1]);
        int toId = Integer.parseInt(args[2]);
        long price = Long.parseLong(args[3]);
        int tax = Integer.parseInt(args[4]);
        int ticks = Integer.parseInt(args[5]);
        var route = service.createRoute(fromId, toId, price, tax, ticks);
        if (route != null) {
            sender.sendMessage(fmt.success("Route #" + route.getId() + " created!"));
        } else {
            sender.sendMessage(fmt.error("Failed to create route. Check both stations are ACTIVE."));
        }
        return true;
    }

    private boolean handleTravel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Players only."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /rail travel <routeId>"));
            return true;
        }
        int routeId = Integer.parseInt(args[1]);
        service.buyTicket(player, routeId);
        return true;
    }

    private boolean hasRailAdmin(CommandSender sender) {
        if (sender instanceof Player player && !player.hasPermission("vs.admin")) {
            player.sendMessage(fmt.permissionDenied());
            return false;
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(fmt.header("Rail Commands"));
        sender.sendMessage(fmt.info("/rail stations &8- List stations"));
        sender.sendMessage(fmt.info("/rail routes &8- List routes"));
        sender.sendMessage(fmt.info("/rail travel <routeId> &8- Buy ticket & travel"));
        if (sender instanceof Player p && p.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.info("/rail applications &8- Pending applications"));
            sender.sendMessage(fmt.info("/rail approve <id> &8- Approve station"));
            sender.sendMessage(fmt.info("/rail deny <id> <reason> &8- Deny station"));
            sender.sendMessage(fmt.info("/rail createroute <from> <to> <price> <tax%> <ticks> &8- Create route"));
            sender.sendMessage(fmt.info("/rail suspend <id> &8- Suspend station"));
            sender.sendMessage(fmt.info("/rail unsuspend <id> &8- Unsuspend station"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) {
            return List.of("applications","approve","deny","createroute","stations","routes","suspend","unsuspend","travel")
                .stream().filter(x -> x.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && ("approve".equalsIgnoreCase(args[0]) || "deny".equalsIgnoreCase(args[0]) ||
            "suspend".equalsIgnoreCase(args[0]) || "unsuspend".equalsIgnoreCase(args[0]))) {
            return service.getAllStations().stream()
                .map(st -> String.valueOf(st.getId())).toList();
        }
        if (args.length == 2 && "travel".equalsIgnoreCase(args[0])) {
            return service.getAllRoutes().stream()
                .map(r -> String.valueOf(r.getId())).toList();
        }
        return List.of();
    }
}
