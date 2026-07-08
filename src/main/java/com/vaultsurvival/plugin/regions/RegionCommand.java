package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Region management commands.
 *
 * /region wand                     — Get the selection wand
 * /region create <name> <type>     — Create region from wand selection
 * /region delete <id>              — Delete a region
 * /region info                     — Show active regions at your location
 * /region info <id>                — Show region details
 * /region flag <id> <flag> <true|false> — Set a rule flag
 * /region list                     — List all regions
 * /region here                     — Show all regions at your position
 */
public class RegionCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final RegionService regionService;
    private final MessageFormatter fmt;
    private final Map<UUID, Location[]> selections = new HashMap<>(); // pos1, pos2
    private final NamespacedKey wandKey;

    public RegionCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.regionService = plugin.getServiceRegistry().get(RegionService.class);
        this.fmt = plugin.getMessageFormatter();
        this.wandKey = new NamespacedKey(plugin, "vs_region_wand");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vs.region.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "wand" -> handleWand(sender);
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "info" -> handleInfo(sender, args);
            case "flag" -> handleFlag(sender, args);
            case "list" -> handleList(sender);
            case "here" -> handleHere(sender);
            case "show" -> handleShow(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use the selection wand."));
            return true;
        }

        ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageFormatter.deserializeLegacy("&6&lRegion Selection Wand"));
            meta.lore(List.of(
                MessageFormatter.deserializeLegacy("&7Left-click: Set position 1"),
                MessageFormatter.deserializeLegacy("&7Right-click: Set position 2"),
                MessageFormatter.deserializeLegacy("&7Then use &e/region create <name> <type>")
            ));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BOOLEAN, true);
            wand.setItemMeta(meta);
        }
        player.getInventory().addItem(wand);
        player.sendMessage(fmt.success("Region selection wand added to your inventory!"));
        player.sendMessage(fmt.info("Left-click = pos1, Right-click = pos2"));
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can create regions."));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /region create <name> <type> [priority]"));
            sender.sendMessage(fmt.info("Types: " + String.join(", ",
                Arrays.stream(RegionData.RegionType.values()).map(Enum::name).toList())));
            return true;
        }

        Location[] sel = selections.get(player.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            player.sendMessage(fmt.error("You must select two points first!"));
            player.sendMessage(fmt.info("Get a selection wand with &e/region wand"));
            return true;
        }

        RegionData.RegionType type;
        try {
            type = RegionData.RegionType.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(fmt.error("Unknown region type. Options: " +
                String.join(", ", Arrays.stream(RegionData.RegionType.values()).map(Enum::name).toList())));
            return true;
        }

        int priority = 0;
        if (args.length >= 4) {
            try { priority = Integer.parseInt(args[3]); }
            catch (NumberFormatException e) {
                player.sendMessage(fmt.error("Invalid priority number."));
                return true;
            }
        }

        Location p1 = sel[0], p2 = sel[1];
        if (!p1.getWorld().equals(p2.getWorld())) {
            player.sendMessage(fmt.error("Selection points must be in the same world!"));
            return true;
        }

        var region = regionService.createRegion(args[1], type, p1.getWorld().getName(),
            p1.getBlockX(), p1.getBlockY(), p1.getBlockZ(),
            p2.getBlockX(), p2.getBlockY(), p2.getBlockZ(), priority);

        if (region != null) {
            player.sendMessage(fmt.success("Created region #" + region.getId() + ": &e" + region.getName()));
            player.sendMessage(fmt.info("Type: &e" + type + " &7| Priority: &e" + priority));
            selections.remove(player.getUniqueId());
        }
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /region delete <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (regionService.deleteRegion(id)) {
            sender.sendMessage(fmt.success("Deleted region #" + id));
        } else {
            sender.sendMessage(fmt.error("Region not found."));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length >= 2 && !args[1].equalsIgnoreCase("show")) {
            int id = parseInt(args[1]);
            if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }
            var region = regionService.getRegion(id);
            if (region == null) {
                sender.sendMessage(fmt.error("Region not found."));
                return true;
            }
            showRegionInfo(sender, region);
        } else if (sender instanceof Player player) {
            var regionsAt = regionService.getRegionsAt(player.getLocation());
            sender.sendMessage(fmt.header("Rules at your location"));
            if (regionsAt.isEmpty()) {
                sender.sendMessage(fmt.info("No regions apply here — all actions are allowed."));
            } else {
                for (var r : regionsAt) {
                    sender.sendMessage(fmt.info("&e" + r.getName() + " &7(" + r.getType() + ") &8| P:" + r.getPriority()));
                }
                sender.sendMessage(fmt.info("&8Use &e/region info <id> &8for detailed flags"));
            }
        } else {
            sender.sendMessage(fmt.error("Usage: /region info <id>"));
        }
        return true;
    }

    private boolean handleFlag(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(fmt.error("Usage: /region flag <id> <flag_name> <true|false>"));
            sender.sendMessage(fmt.info("Flags: " + String.join(", ",
                Arrays.stream(RegionData.RuleFlag.values()).map(Enum::name).toList())));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        RegionData.RuleFlag flag;
        try { flag = RegionData.RuleFlag.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(fmt.error("Unknown flag. Options: " +
                String.join(", ", Arrays.stream(RegionData.RuleFlag.values()).map(Enum::name).toList())));
            return true;
        }

        boolean value = args[3].equalsIgnoreCase("true");
        if (regionService.setFlag(id, flag, value)) {
            sender.sendMessage(fmt.success("Set &e" + flag.name() + " &7= &e" + value + " &7on region #" + id));
        } else {
            sender.sendMessage(fmt.error("Region not found."));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var all = regionService.getAllRegions();
        sender.sendMessage(fmt.header("Regions (" + all.size() + ")"));
        if (all.isEmpty()) {
            sender.sendMessage(fmt.info("No regions. Create one with &e/region create <name> <type>"));
        } else {
            for (var r : all) {
                sender.sendMessage(fmt.info(
                    "&e#" + r.getId() + " &f" + r.getName() +
                    " &7(" + r.getType() + ") &8| &7" + r.getWorldName() +
                    " &8| P:" + r.getPriority()
                ));
            }
        }
        return true;
    }

    private boolean handleHere(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can check regions at their location."));
            return true;
        }
        var regionsAt = regionService.getRegionsAt(player.getLocation());
        sender.sendMessage(fmt.header("Regions here (" + regionsAt.size() + ")"));
        if (regionsAt.isEmpty()) {
            sender.sendMessage(fmt.info("No regions cover this location."));
        } else {
            for (var r : regionsAt) {
                sender.sendMessage(fmt.info(
                    "&e#" + r.getId() + " &f" + r.getName() +
                    " &7(" + r.getType() + ") &8| P:" + r.getPriority()
                ));
            }
        }
        return true;
    }

    private boolean handleShow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can visualize regions."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /region show <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        var region = regionService.getRegion(id);
        if (region == null) {
            sender.sendMessage(fmt.error("Region not found."));
            return true;
        }

        // Show corner particles for visual feedback
        var world = player.getWorld();
        if (!world.getName().equals(region.getWorldName())) {
            player.sendMessage(fmt.error("You must be in the same world as the region."));
            return true;
        }

        // Spawn particles at the 8 corners of the cuboid
        int[][] corners = {
            {region.getX1(), region.getY1(), region.getZ1()},
            {region.getX1(), region.getY1(), region.getZ2()},
            {region.getX1(), region.getY2(), region.getZ1()},
            {region.getX1(), region.getY2(), region.getZ2()},
            {region.getX2(), region.getY1(), region.getZ1()},
            {region.getX2(), region.getY1(), region.getZ2()},
            {region.getX2(), region.getY2(), region.getZ1()},
            {region.getX2(), region.getY2(), region.getZ2()},
        };
        for (int[] corner : corners) {
            org.bukkit.Location loc = new org.bukkit.Location(world,
                corner[0] + 0.5, corner[1] + 0.5, corner[2] + 0.5);
            player.spawnParticle(org.bukkit.Particle.END_ROD, loc, 3, 0.1, 0.1, 0.1, 0);
        }

        player.sendMessage(fmt.success("Showing corners of region &e" + region.getName()));
        return true;
    }

    private void showRegionInfo(CommandSender sender, RegionData.Region r) {
        sender.sendMessage(fmt.header("Region #" + r.getId() + " — " + r.getName()));
        sender.sendMessage(fmt.info("Type: &e" + r.getType() + " &8| Priority: &e" + r.getPriority()));
        sender.sendMessage(fmt.info("World: &e" + r.getWorldName()));
        sender.sendMessage(fmt.info("Bounds: &e" + r.getX1() + "," + r.getY1() + "," + r.getZ1() +
            " &7→ &e" + r.getX2() + "," + r.getY2() + "," + r.getZ2()));
        sender.sendMessage(fmt.info("Size: &e" + (r.getX2()-r.getX1()+1) + "x" +
            (r.getY2()-r.getY1()+1) + "x" + (r.getZ2()-r.getZ1()+1)));
        sender.sendMessage(fmt.header("Flags"));
        for (var entry : r.getFlags().entrySet()) {
            String status = entry.getValue() ? "&a✔" : "&c✘";
            sender.sendMessage(fmt.info(status + " &7" + entry.getKey().name()));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Region Commands"));
        sender.sendMessage(fmt.info("/region wand &8- Get selection tool"));
        sender.sendMessage(fmt.info("/region create <name> <type> [priority] &8- Create from selection"));
        sender.sendMessage(fmt.info("/region delete <id> &8- Delete region"));
        sender.sendMessage(fmt.info("/region info [id] &8- Region details or rules at your location"));
        sender.sendMessage(fmt.info("/region flag <id> <flag> <true|false> &8- Toggle rule flag"));
        sender.sendMessage(fmt.info("/region list &8- List all regions"));
        sender.sendMessage(fmt.info("/region here &8- Regions at your location"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("wand", "create", "delete", "info", "flag", "list", "here", "show")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return Arrays.stream(RegionData.RegionType.values())
                .map(Enum::name).filter(t -> t.startsWith(args[2].toUpperCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("flag")) {
            return Arrays.stream(RegionData.RuleFlag.values())
                .map(Enum::name).filter(f -> f.startsWith(args[2].toUpperCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("flag")) {
            return List.of("true", "false");
        }
        return List.of();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    /** Called by RegionListener when a player clicks with the wand. */
    public void setSelection(UUID playerUuid, Location loc, boolean isPos1) {
        Location[] sel = selections.computeIfAbsent(playerUuid, k -> new Location[2]);
        if (isPos1) sel[0] = loc.clone(); else sel[1] = loc.clone();
        selections.put(playerUuid, sel);
    }

    /** Check if player is holding a region wand. */
    public boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BOOLEAN);
    }
}
