package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Admin command surface over the shared region selection and visualization services. */
public final class RegionCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final RegionService regions;
    private final RegionSelectionService selections;
    private final RegionVisualizationService visualization;
    private final MessageFormatter fmt;
    private final NamespacedKey wandKey;

    public RegionCommand(VaultSurvivalPlugin plugin, RegionSelectionService selections,
                         RegionVisualizationService visualization) {
        this.plugin = plugin;
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.selections = selections;
        this.visualization = visualization;
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
            if (sender instanceof Player player) openSelectionDialog(player, "Region selection is ready.");
            else sendUsage(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "wand" -> handleWand(sender);
            case "pos1" -> handlePosition(sender, true);
            case "pos2" -> handlePosition(sender, false);
            case "type" -> handleType(sender, args);
            case "cancel" -> handleCancel(sender);
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "info" -> handleInfo(sender, args);
            case "flag" -> handleFlag(sender, args);
            case "list" -> handleList(sender);
            case "here" -> handleHere(sender);
            case "show" -> handleShow(sender, args);
            case "hide" -> handleHide(sender);
            case "showtime" -> handleShowTime(sender, args);
            case "grid" -> handleGrid(sender, args, false);
            case "floorgrid" -> handleGrid(sender, args, true);
            case "debug" -> handleDebug(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleWand(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        ItemStack wand = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(MessageFormatter.deserializeLegacy("&6&lRegion Selection Wand"));
        meta.lore(List.of(
            MessageFormatter.deserializeLegacy("&7Left-click: Set position 1"),
            MessageFormatter.deserializeLegacy("&7Right-click: Set position 2"),
            MessageFormatter.deserializeLegacy("&7Borders stay visible while editing")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BOOLEAN, true);
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand);
        audit(player, "REGION_WAND_GIVE", player.getUniqueId().toString(), "material=GOLDEN_AXE");
        openSelectionDialog(player, "Selection wand added. Left-click POS1 and right-click POS2.");
        return true;
    }

    private boolean handlePosition(CommandSender sender, boolean pos1) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        selections.setPoint(player, player.getLocation().getBlock().getLocation(), pos1);
        audit(player, pos1 ? "REGION_SELECTION_POS1" : "REGION_SELECTION_POS2", player.getWorld().getName(),
            "x=" + player.getLocation().getBlockX() + " y=" + player.getLocation().getBlockY() + " z=" + player.getLocation().getBlockZ());
        openSelectionDialog(player, (pos1 ? "POS1" : "POS2") + " set at your current block.");
        return true;
    }

    private boolean handleType(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /region type <type>"));
            return true;
        }
        RegionData.RegionType type = parseType(args[1]);
        if (type == null) {
            player.sendMessage(fmt.error("Unknown region type."));
            return true;
        }
        selections.setType(player, type);
        audit(player, "REGION_SELECTION_TYPE", player.getUniqueId().toString(), "type=" + type);
        openSelectionDialog(player, "Region type changed to " + type + ".");
        return true;
    }

    private boolean handleCancel(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        selections.clear(player.getUniqueId());
        audit(player, "REGION_SELECTION_CANCEL", player.getUniqueId().toString(), "");
        openSelectionDialog(player, "Selection and visualization cancelled.");
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 3) {
            player.sendMessage(fmt.error("Usage: /region create <name> <type> [priority]"));
            return true;
        }
        var selected = selections.get(player.getUniqueId()).orElse(null);
        if (selected == null || !selected.complete()) {
            openSelectionDialog(player, "Set both positions before creating a region.");
            return true;
        }
        RegionData.RegionType type = parseType(args[2]);
        if (type == null) {
            player.sendMessage(fmt.error("Unknown type. Use tab completion for the supported types."));
            return true;
        }
        int priority = args.length >= 4 ? parseInt(args[3]) : 0;
        if (priority == Integer.MIN_VALUE) {
            player.sendMessage(fmt.error("Priority must be a whole number."));
            return true;
        }
        var b = selected.bounds();
        RegionData.Region region = regions.createRegion(args[1], type, b.world().getName(),
            b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), priority);
        if (region == null) {
            player.sendMessage(fmt.error("The region could not be created. Check the server log."));
            return true;
        }
        plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(), "REGION_CREATE",
            String.valueOf(region.getId()), "name=" + region.getName() + " type=" + type + " priority=" + priority);
        selections.clear(player.getUniqueId());
        visualization.showRegion(player, region, RegionVisualizationSession.Mode.THIRTY_SECONDS, false);
        openRegionDialog(player, region, "Region created and visualized for 30 seconds.");
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /region delete <id> [confirm]"));
            return true;
        }
        int id = parseId(args[1]);
        RegionData.Region region = id < 0 ? null : regions.getRegion(id);
        if (region == null) {
            sender.sendMessage(fmt.error("Region not found."));
            return true;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            if (sender instanceof Player player && plugin.getServiceRegistry().has(DialogService.class)) {
                plugin.getServiceRegistry().get(DialogService.class).openConfirmation(player,
                    "Delete region #" + id, "This permanently deletes " + region.getName() + ".",
                    "region delete " + id + " confirm", "admin.regions");
            } else sender.sendMessage(fmt.error("Dangerous action: repeat /region delete " + id + " confirm"));
            return true;
        }
        if (regions.deleteRegion(id)) {
            audit(sender, "REGION_DELETE", String.valueOf(id), "name=" + region.getName());
            sender.sendMessage(fmt.success("Deleted region #" + id));
        } else sender.sendMessage(fmt.error("Region could not be deleted."));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        RegionData.Region region = null;
        if (args.length >= 2) region = regions.getRegion(parseId(args[1]));
        else if (sender instanceof Player player) region = regions.getRegionsAt(player.getLocation()).stream().findFirst().orElse(null);
        if (region == null) {
            sender.sendMessage(fmt.error("No matching region found."));
            return true;
        }
        if (sender instanceof Player player) openRegionDialog(player, region, "Saved region details.");
        else showRegionInfo(sender, region);
        return true;
    }

    private boolean handleFlag(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(fmt.error("Usage: /region flag <id> <flag> <true|false>"));
            return true;
        }
        int id = parseId(args[1]);
        RegionData.RuleFlag flag;
        try { flag = RegionData.RuleFlag.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException exception) { sender.sendMessage(fmt.error("Unknown flag.")); return true; }
        if (!args[3].equalsIgnoreCase("true") && !args[3].equalsIgnoreCase("false")) {
            sender.sendMessage(fmt.error("Value must be true or false."));
            return true;
        }
        boolean value = Boolean.parseBoolean(args[3]);
        if (regions.setFlag(id, flag, value)) {
            audit(sender, "REGION_FLAG", String.valueOf(id), flag + "=" + value);
            sender.sendMessage(fmt.success("Updated " + flag + " on region #" + id));
        } else sender.sendMessage(fmt.error("Region not found."));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (sender instanceof Player player && plugin.getServiceRegistry().has(DialogService.class)) {
            List<DialogMenuItem> items = regions.getAllRegions().stream().limit(45)
                .map(r -> DialogMenuItem.adminItem("#" + r.getId() + " " + r.getName(),
                    r.getType() + " | " + r.getWorldName() + " | P:" + r.getPriority(),
                    "region info " + r.getId(), "vs.region.admin", Material.MAP)).toList();
            plugin.getServiceRegistry().get(DialogService.class).openResult(player, "Saved Regions",
                regions.getAllRegions().size() + " region(s). Select one for details.", items);
        } else {
            sender.sendMessage(fmt.header("Regions (" + regions.getAllRegions().size() + ")"));
            for (var r : regions.getAllRegions()) sender.sendMessage(fmt.info("#" + r.getId() + " " + r.getName() + " (" + r.getType() + ")"));
        }
        return true;
    }

    private boolean handleHere(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        var here = regions.getRegionsAt(player.getLocation());
        if (here.isEmpty()) openSelectionDialog(player, "No saved regions cover your position.");
        else openRegionDialog(player, here.iterator().next(), here.size() + " region(s) cover this position.");
        return true;
    }

    private boolean handleShow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        boolean shared = args.length >= 3 && args[2].equalsIgnoreCase("nearby");
        boolean shown;
        String name;
        if (args.length < 2) {
            var selection = selections.get(player.getUniqueId()).orElse(null);
            shown = selection != null && selection.complete() && visualization.showBounds(player, selection.bounds(),
                selection.type(), "Unsaved selection", visualization.defaultMode(), shared);
            name = "current selection";
        } else {
            RegionData.Region region = regions.getRegion(parseId(args[1]));
            shown = region != null && visualization.showRegion(player, region,
                RegionVisualizationSession.Mode.THIRTY_SECONDS, shared);
            name = region == null ? "region" : region.getName();
        }
        if (!shown) {
            openSelectionDialog(player, "Nothing to show, or you are in a different world.");
            return true;
        }
        if (shared) plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
            "REGION_VISUALIZE_NEARBY", name, "radius=128");
        else audit(player, "REGION_VISUALIZE", name, "scope=private");
        openSelectionDialog(player, "Showing " + name + (shared ? " to nearby players." : " privately."));
        return true;
    }

    private boolean handleHide(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        visualization.hide(player.getUniqueId());
        audit(player, "REGION_VISUALIZE_HIDE", player.getUniqueId().toString(), "");
        openSelectionDialog(player, "Visualization hidden.");
        return true;
    }

    private boolean handleShowTime(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        RegionVisualizationSession.Mode mode = args.length < 2 ? null : RegionVisualizationSession.Mode.parse(args[1]);
        if (mode == null) {
            player.sendMessage(fmt.error("Usage: /region showtime <10|30|60|persistent>"));
            return true;
        }
        boolean changed = visualization.setMode(player.getUniqueId(), mode);
        if (changed) audit(player, "REGION_VISUALIZE_MODE", player.getUniqueId().toString(), "mode=" + mode);
        openSelectionDialog(player, changed ? "Visualization duration updated." : "Start a visualization first.");
        return true;
    }

    private boolean handleGrid(CommandSender sender, String[] args, boolean floor) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            player.sendMessage(fmt.error("Usage: /region " + (floor ? "floorgrid" : "grid") + " <on|off>"));
            return true;
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        boolean changed = floor ? visualization.setFloorGrid(player.getUniqueId(), enabled)
            : visualization.setSideGrid(player.getUniqueId(), enabled);
        if (changed) audit(player, floor ? "REGION_FLOOR_GRID" : "REGION_SIDE_GRID",
            player.getUniqueId().toString(), "enabled=" + enabled);
        openSelectionDialog(player, changed ? (floor ? "Floor grid" : "Side grid") + " updated." : "Start a visualization first.");
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        var current = visualization.session(player.getUniqueId()).orElse(null);
        var world = player.getWorld();
        var synthetic = new RegionVisualizationSession.Bounds(world, 0, 0, 0, 29, 14, 29);
        var plan = visualization.debugPlan(current == null ? synthetic : current.bounds(), true, true);
        sender.sendMessage(fmt.header("Region Visualization Debug"));
        sender.sendMessage(fmt.info("Session: " + (current == null ? "none (30x30x15 acceptance model)" : current.displayName())));
        sender.sendMessage(fmt.info("Points: " + plan.points().size() + "/" + plan.requestedPoints() + " | spacing=" + plan.effectiveSpacing()));
        sender.sendMessage(fmt.info("Perimeter=" + plan.perimeterPoints() + " pillars=" + plan.pillarPoints()
            + " sideGrid=" + plan.sideGridPoints() + " floorGrid=" + plan.floorGridPoints() + " markers=" + plan.markerPoints()));
        sender.sendMessage(fmt.info("Cap respected: " + (plan.points().size() <= 2500)));
        return true;
    }

    private void openSelectionDialog(Player player, String status) {
        if (!plugin.getServiceRegistry().has(DialogService.class)) {
            player.sendMessage(fmt.info(status));
            return;
        }
        var selected = selections.get(player.getUniqueId()).orElse(null);
        var session = visualization.session(player.getUniqueId()).orElse(null);
        String geometry = "Width: -\nHeight: -\nLength: -\nVolume: -";
        if (selected != null && selected.complete()) {
            var bounds = selected.bounds();
            geometry = "Width: " + bounds.width() + "\nHeight: " + bounds.height()
                + "\nLength: " + bounds.depth() + "\nVolume: " + bounds.volume();
        }
        String body = status + "\n\n" + (selected == null ? "Region Type: CUSTOM\nPOS1: not set\nPOS2: not set"
            : "Region Type: " + selected.type() + "\nPOS1: " + formatPoint(selected.pos1()) + "\nPOS2: " + formatPoint(selected.pos2()))
            + "\n" + geometry + "\nVisualization: " + (session == null ? "hidden" : session.mode());
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.adminItem("Region Type", "Change the visualization and saved region type.", "vsmenu input region_type", "vs.region.admin", Material.NAME_TAG));
        items.add(DialogMenuItem.adminItem("Change POS1", "Use your current block as POS1.", "region pos1", "vs.region.admin", Material.LIME_CONCRETE));
        items.add(DialogMenuItem.adminItem("Change POS2", "Use your current block as POS2.", "region pos2", "vs.region.admin", Material.RED_CONCRETE));
        items.add(DialogMenuItem.adminItem("Get selection tool", "Receive the region wand.", "region wand", "vs.region.admin", Material.GOLDEN_AXE));
        items.add(DialogMenuItem.adminItem("Show Border", "Render the current selection.", "region show", "vs.region.admin", Material.ENDER_EYE));
        items.add(DialogMenuItem.adminItem("Hide visualization", "Stop particles and labels.", "region hide", "vs.region.admin", Material.INK_SAC));
        items.add(DialogMenuItem.adminItem("10 seconds", "Show for 10 seconds.", "region showtime 10", "vs.region.admin", Material.CLOCK));
        items.add(DialogMenuItem.adminItem("30 seconds", "Show for 30 seconds.", "region showtime 30", "vs.region.admin", Material.CLOCK));
        items.add(DialogMenuItem.adminItem("60 seconds", "Show for 60 seconds.", "region showtime 60", "vs.region.admin", Material.CLOCK));
        items.add(DialogMenuItem.adminItem("Keep Visible", "Keep rendering until hidden or editing ends.", "region showtime persistent", "vs.region.admin", Material.RECOVERY_COMPASS));
        boolean sideEnabled = session == null || session.sideGrid();
        boolean floorEnabled = session == null || session.floorGrid();
        items.add(DialogMenuItem.adminItem("Toggle Side Grid", "Currently " + (sideEnabled ? "ON" : "OFF") + "; click to toggle.",
            "region grid " + (sideEnabled ? "off" : "on"), "vs.region.admin", Material.IRON_BARS));
        items.add(DialogMenuItem.adminItem("Toggle Floor Grid", "Currently " + (floorEnabled ? "ON" : "OFF") + "; click to toggle.",
            "region floorgrid " + (floorEnabled ? "off" : "on"), "vs.region.admin", Material.IRON_TRAPDOOR));
        items.add(DialogMenuItem.adminItem("Confirm Region", "Enter name, type and priority.", "vsmenu input region_create", "vs.region.admin", Material.EMERALD_BLOCK));
        items.add(DialogMenuItem.adminItem("Cancel selection", "Clear points and stop visualization.", "region cancel", "vs.region.admin", Material.BARRIER));
        plugin.getServiceRegistry().get(DialogService.class).openResult(player, "Region Selection", body, items);
    }

    private void openRegionDialog(Player player, RegionData.Region region, String status) {
        String body = status + "\nType: " + region.getType() + "\nWorld: " + region.getWorldName()
            + "\nBounds: " + region.getX1() + "," + region.getY1() + "," + region.getZ1() + " -> "
            + region.getX2() + "," + region.getY2() + "," + region.getZ2()
            + "\nSize: " + (region.getX2() - region.getX1() + 1) + " x " + (region.getY2() - region.getY1() + 1)
            + " x " + (region.getZ2() - region.getZ1() + 1) + " | Priority: " + region.getPriority();
        List<DialogMenuItem> items = List.of(
            DialogMenuItem.adminItem("Show privately", "Dense 3D border for 30 seconds.", "region show " + region.getId(), "vs.region.admin", Material.ENDER_EYE),
            DialogMenuItem.adminItem("Show nearby", "Share the border within 128 blocks; audited.", "region show " + region.getId() + " nearby", "vs.region.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Hide", "Stop your visualization.", "region hide", "vs.region.admin", Material.INK_SAC),
            DialogMenuItem.adminItem("Delete", "Open a confirmation before deletion.", "region delete " + region.getId(), "vs.region.admin", Material.TNT),
            DialogMenuItem.adminItem("All regions", "Return to saved regions.", "region list", "vs.region.admin", Material.MAP));
        plugin.getServiceRegistry().get(DialogService.class).openResult(player, "Region #" + region.getId() + " " + region.getName(), body, items);
    }

    private void showRegionInfo(CommandSender sender, RegionData.Region r) {
        sender.sendMessage(fmt.header("Region #" + r.getId() + " " + r.getName()));
        sender.sendMessage(fmt.info("Type=" + r.getType() + " priority=" + r.getPriority() + " world=" + r.getWorldName()));
        sender.sendMessage(fmt.info("Bounds=" + r.getX1() + "," + r.getY1() + "," + r.getZ1() + " -> " + r.getX2() + "," + r.getY2() + "," + r.getZ2()));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Region Commands"));
        sender.sendMessage(fmt.info("/region wand|pos1|pos2|type <type>|cancel"));
        sender.sendMessage(fmt.info("/region create <name> <type> [priority] | delete <id> [confirm]"));
        sender.sendMessage(fmt.info("/region show [id] [nearby] | hide | showtime <10|30|60|persistent>"));
        sender.sendMessage(fmt.info("/region grid <on|off> | floorgrid <on|off> | debug"));
    }

    private void audit(CommandSender sender, String action, String target, String details) {
        if (sender instanceof Player player) plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(), action, target, details);
        else plugin.getAuditLogger().logAdminAction(null, sender.getName(), action, target, details);
    }

    private boolean playerOnly(CommandSender sender) { sender.sendMessage(fmt.error("Only players can use this command.")); return true; }
    private static RegionData.RegionType parseType(String value) {
        try { return RegionData.RegionType.valueOf(value.toUpperCase()); }
        catch (IllegalArgumentException exception) { return null; }
    }
    private static int parseId(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return -1; } }
    private static int parseInt(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return Integer.MIN_VALUE; } }
    private static String formatPoint(org.bukkit.Location point) {
        return point == null ? "not set" : point.getBlockX() + ", " + point.getBlockY() + ", " + point.getBlockZ();
    }

    public boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BOOLEAN);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("wand", "pos1", "pos2", "type", "cancel", "create", "delete",
            "info", "flag", "list", "here", "show", "hide", "showtime", "grid", "floorgrid", "debug"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("type"))
            return filter(Arrays.stream(RegionData.RegionType.values()).map(Enum::name).toList(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("create"))
            return filter(Arrays.stream(RegionData.RegionType.values()).map(Enum::name).toList(), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("flag"))
            return filter(Arrays.stream(RegionData.RuleFlag.values()).map(Enum::name).toList(), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("showtime")) return filter(List.of("10", "30", "60", "persistent"), args[1]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("grid") || args[0].equalsIgnoreCase("floorgrid"))) return filter(List.of("on", "off"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("show")) return filter(List.of("nearby"), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("delete")) return filter(List.of("confirm"), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        return values.stream().filter(value -> value.toLowerCase().startsWith(lower)).toList();
    }
}
