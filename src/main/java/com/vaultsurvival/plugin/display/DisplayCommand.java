package com.vaultsurvival.plugin.display;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.market.MarketData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Commands for managing the Display Auction Hall.
 *
 * /displays add <category>  — Add a display slot at your location
 * /displays remove <id>     — Remove a display slot
 * /displays list            — List all display slots
 * /displays refresh         — Force refresh all displays
 */
public class DisplayCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final DisplayService displayService;
    private final MessageFormatter fmt;

    public DisplayCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.displayService = plugin.getServiceRegistry().get(DisplayService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vs.display.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "refresh" -> handleRefresh(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can add display slots."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /displays add <category>"));
            sender.sendMessage(fmt.info("Categories: " + Arrays.toString(MarketData.Category.values())));
            return true;
        }
        MarketData.Category category;
        try { category = MarketData.Category.valueOf(args[1].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Unknown category. Use: " + Arrays.toString(MarketData.Category.values())));
            return true;
        }

        var slot = displayService.addSlot(player.getLocation().getBlock().getLocation(), category);
        if (slot != null) {
            player.sendMessage(fmt.success("Display slot #" + slot.getId() + " added for &e" + category));
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /displays remove <id>"));
            return true;
        }
        int id;
        try { id = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
            sender.sendMessage(fmt.error("Invalid ID."));
            return true;
        }
        if (displayService.removeSlot(id)) {
            sender.sendMessage(fmt.success("Display slot #" + id + " removed."));
        } else {
            sender.sendMessage(fmt.error("Display slot not found."));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var all = displayService.getAllSlots();
        sender.sendMessage(fmt.header("Display Slots (" + all.size() + ")"));
        if (all.isEmpty()) {
            sender.sendMessage(fmt.info("No display slots. Add one with &e/displays add <category>"));
        } else {
            for (var s : all) {
                String listing = s.getCurrentListingUuid() != null ?
                    s.getCurrentListingUuid().toString().substring(0, 8) + "..." : "none";
                sender.sendMessage(fmt.info(
                    "&e#" + s.getId() + " &7" + s.getCategory() + " &8| " +
                    s.getWorldName() + " " + s.getX() + "," + s.getY() + "," + s.getZ() +
                    " &8| Showing: &7" + listing
                ));
            }
        }
        return true;
    }

    private boolean handleRefresh(CommandSender sender) {
        int count = displayService.refreshAll();
        sender.sendMessage(fmt.success("Refreshed " + count + " display slots."));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Display Auction Hall Commands"));
        sender.sendMessage(fmt.info("/displays add <category> &8- Add display slot at your location"));
        sender.sendMessage(fmt.info("/displays remove <id> &8- Remove a display slot"));
        sender.sendMessage(fmt.info("/displays list &8- List all display slots"));
        sender.sendMessage(fmt.info("/displays refresh &8- Force refresh all displays"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("add", "remove", "list", "refresh")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return Arrays.stream(MarketData.Category.values())
                .map(Enum::name).filter(n -> n.startsWith(args[1].toUpperCase())).toList();
        }
        return List.of();
    }
}
