package com.vaultsurvival.plugin.spawncity;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.vsworldedit.VSWorldEditData;
import com.vaultsurvival.plugin.vsworldedit.VSWorldEditService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * /spawncity commands.
 */
public class SpawnCityCommand implements CommandExecutor, TabCompleter {

    private final SpawnCityService service;
    private final VSWorldEditService vwe;
    private final MessageFormatter fmt;

    public SpawnCityCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(SpawnCityService.class);
        this.vwe = plugin.getServiceRegistry().get(VSWorldEditService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendUsage(sender); return true; }

        return switch (args[0].toLowerCase()) {
            case "info" -> handleInfo(sender);
            case "setname" -> handleSetName(sender, args);
            case "setspawn" -> handleSetSpawn(sender);
            case "teleport" -> handleTeleport(sender);
            case "setcapitalregion" -> handleSetRegion(sender, "capital");
            case "setauctionhallregion" -> handleSetRegion(sender, "auction_hall");
            case "setmintregion" -> handleSetRegion(sender, "mint");
            case "regions" -> handleRegions(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("vaultsurvival.spawncity.info")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        sender.sendMessage(fmt.header(service.getCityName()));
        sender.sendMessage(fmt.info("City name: &e" + service.getCityName()));
        var loc = service.getSpawnLocation();
        sender.sendMessage(fmt.info("Spawn: " + (loc != null ?
            "&e" + loc.getWorld().getName() + " &7" + String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ()) :
            "&cNot set")));
        var types = service.getRegionTypes();
        sender.sendMessage(fmt.info("Regions saved: &e" + (!types.isEmpty() ? String.join(", ", types) : "&7none")));
        return true;
    }

    private boolean handleSetName(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vaultsurvival.spawncity.admin")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        if (args.length < 2) { sender.sendMessage(fmt.error("/spawncity setname <name>")); return true; }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        service.setCityName(name);
        sender.sendMessage(fmt.success("City name set to: &e" + name));
        return true;
    }

    private boolean handleSetSpawn(CommandSender sender) {
        if (!sender.hasPermission("vaultsurvival.spawncity.admin")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can set spawn.")); return true;
        }
        service.setSpawnLocation(player.getLocation());
        player.sendMessage(fmt.success("Spawn set for " + service.getCityName() + "."));
        return true;
    }

    private boolean handleTeleport(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can teleport.")); return true;
        }
        service.teleportToSpawn(player);
        return true;
    }

    private boolean handleSetRegion(CommandSender sender, String type) {
        if (!sender.hasPermission("vaultsurvival.spawncity.admin")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can set regions.")); return true;
        }

        VSWorldEditData.Selection sel = vwe.getSelection(player);
        if (sel == null) {
            player.sendMessage(fmt.error("No /vwe selection. Make a selection with the wand first."));
            return true;
        }

        String label = type.replace("_", " ");
        boolean wasOverwrite = service.hasRegion(type);
        switch (type) {
            case "capital" -> service.setCapitalRegion(sel);
            case "auction_hall" -> service.setAuctionHallRegion(sel);
            case "mint" -> service.setMintRegion(sel);
        }

        player.sendMessage(fmt.success(service.getCityName() + " " + label + " region " +
            (wasOverwrite ? "updated!" : "saved!")));
        player.sendMessage(fmt.info("&7" + sel.getWidth() + "x" + sel.getHeight() + "x" + sel.getDepth() +
            " &8(" + sel.getVolume() + " blocks)"));
        return true;
    }

    private boolean handleRegions(CommandSender sender) {
        if (!sender.hasPermission("vaultsurvival.spawncity.info")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        var types = service.getRegionTypes();
        sender.sendMessage(fmt.header(service.getCityName() + " — Regions"));

        if (types.isEmpty()) {
            sender.sendMessage(fmt.info("No regions saved. Use /vwe to select, then /spawncity setcapitalregion etc."));
            return true;
        }

        for (String type : types) {
            var sel = service.getRegion(type);
            if (sel != null) {
                sender.sendMessage(fmt.info("&e" + type.replace("_", " ") + "&7: " +
                    sel.getWidth() + "x" + sel.getHeight() + "x" + sel.getDepth() +
                    " &8(" + sel.getVolume() + " blocks) &7@" + sel.getWorldName()));
            } else {
                sender.sendMessage(fmt.info("&e" + type.replace("_", " ") + "&7: &cworld not loaded"));
            }
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Spawn City Commands"));
        sender.sendMessage(fmt.info("/spawncity info &8- Show city info"));
        sender.sendMessage(fmt.info("/spawncity teleport &8- Teleport to spawn"));
        if (sender.hasPermission("vaultsurvival.spawncity.admin")) {
            sender.sendMessage(fmt.info("/spawncity setname <name> &8- Rename the city"));
            sender.sendMessage(fmt.info("/spawncity setspawn &8- Set spawn point"));
            sender.sendMessage(fmt.info("/spawncity setcapitalregion &8- Save /vwe selection as capital"));
            sender.sendMessage(fmt.info("/spawncity setauctionhallregion &8- Save /vwe selection as AH"));
            sender.sendMessage(fmt.info("/spawncity setmintregion &8- Save /vwe selection as Mint"));
        }
        sender.sendMessage(fmt.info("/spawncity regions &8- List all saved regions"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("info","setname","setspawn","teleport","setcapitalregion",
                "setauctionhallregion","setmintregion","regions")
                .filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
