package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * /vwe commands for VS-WorldEdit.
 */
public class VSWorldEditCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final VSWorldEditService service;
    private final MessageFormatter fmt;

    public VSWorldEditCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(VSWorldEditService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle slash aliases when they are dispatched by Bukkit.
        String effectiveCommand = label.toLowerCase();
        if (effectiveCommand.startsWith("//")) {
            effectiveCommand = effectiveCommand.substring(2);
        } else if (effectiveCommand.startsWith("/")) {
            effectiveCommand = effectiveCommand.substring(1);
        }
        if (!effectiveCommand.equals("vwe")) {
            // Reconstruct args so the alias subcommand becomes args[0]
            if (args.length == 0) {
                args = new String[]{effectiveCommand};
            } else {
                String[] newArgs = new String[args.length + 1];
                newArgs[0] = effectiveCommand;
                System.arraycopy(args, 0, newArgs, 1, args.length);
                args = newArgs;
            }
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use VS-WorldEdit."));
            return true;
        }
        if (!player.hasPermission("vaultsurvival.vwe.use")) {
            player.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length == 0) { sendUsage(player); return true; }

        return switch (args[0].toLowerCase()) {
            case "wand" -> { service.giveWand(player); yield true; }
            case "pos1" -> { service.setPos1(player, player.getLocation()); yield true; }
            case "pos2" -> { service.setPos2(player, player.getLocation()); yield true; }
            case "selection" -> handleSelection(player);
            case "clearselection" -> { service.clearSelection(player); yield true; }
            case "fill", "set" -> handleFill(player, args);
            case "replace" -> handleReplace(player, args);
            case "walls" -> handleWalls(player, args);
            case "outline" -> handleOutline(player, args);
            case "floor" -> handleFloor(player, args);
            case "ceiling" -> handleCeiling(player, args);
            case "hollow" -> handleHollow(player, args);
            case "cylinder" -> handleCylinder(player, args, false);
            case "hcylinder", "hollowcylinder" -> handleCylinder(player, args, true);
            case "circle" -> handleCircle(player, args, false);
            case "hcircle", "hollowcircle" -> handleCircle(player, args, true);
            case "sphere" -> handleSphere(player, args, false);
            case "hsphere", "hollowsphere" -> handleSphere(player, args, true);
            case "line" -> handleLine(player, args);
            case "confirm" -> { service.confirm(player); yield true; }
            case "cancel" -> { service.cancelOperation(player); yield true; }
            case "undo" -> { service.undo(player); yield true; }
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleSelection(Player player) {
        var sel = service.getSelection(player);
        Location p1 = service.getPos1(player);
        Location p2 = service.getPos2(player);
        player.sendMessage(fmt.header("VWE Selection"));
        player.sendMessage(fmt.info("Pos #1: " + (p1 != null ? "&e" + p1.getBlockX() + ", " + p1.getBlockY() + ", " + p1.getBlockZ() + " &7(" + p1.getWorld().getName() + ")" : "&cNot set")));
        player.sendMessage(fmt.info("Pos #2: " + (p2 != null ? "&e" + p2.getBlockX() + ", " + p2.getBlockY() + ", " + p2.getBlockZ() + " &7(" + p2.getWorld().getName() + ")" : "&cNot set")));
        if (sel != null) {
            player.sendMessage(fmt.info("Size: &e" + sel.getWidth() + "x" + sel.getHeight() + "x" + sel.getDepth() + " &7(&e" + sel.getVolume() + "&7 blocks)"));
            if (sel.getVolume() > service.getMaxBlocksPerOperation())
                player.sendMessage(fmt.warn("Exceeds max blocks: &e" + service.getMaxBlocksPerOperation()));
        }
        return true;
    }

    private boolean handleFill(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("//set <block[,block...]> [weight,weight...]")); return true; }
        List<VSWorldEditData.WeightedMaterial> pattern = parsePattern(player, args[1], args.length >= 3 ? args[2] : null);
        if (pattern == null) return true;
        if (pattern.size() == 1) service.fill(player, pattern.getFirst().material());
        else service.fillPattern(player, pattern);
        return true;
    }

    private boolean handleReplace(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(fmt.error("/vwe replace <fromBlock> <toBlock>")); return true; }
        Material from = Material.matchMaterial(args[1].toUpperCase());
        Material to = Material.matchMaterial(args[2].toUpperCase());
        if (from == null || !from.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        if (to == null || !to.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[2])); return true; }
        service.replace(player, from, to);
        return true;
    }

    private boolean handleWalls(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe walls <block>")); return true; }
        Material m = Material.matchMaterial(args[1].toUpperCase());
        if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        service.walls(player, m);
        return true;
    }

    private boolean handleOutline(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe outline <block>")); return true; }
        Material m = Material.matchMaterial(args[1].toUpperCase());
        if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        service.outline(player, m);
        return true;
    }

    private boolean handleFloor(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe floor <block>")); return true; }
        Material m = Material.matchMaterial(args[1].toUpperCase());
        if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        service.floor(player, m);
        return true;
    }

    private boolean handleCeiling(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe ceiling <block>")); return true; }
        Material m = Material.matchMaterial(args[1].toUpperCase());
        if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        service.ceiling(player, m);
        return true;
    }

    private boolean handleHollow(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(fmt.error("/vwe hollow <wallBlock> <airBlock>")); return true; }
        Material wall = Material.matchMaterial(args[1].toUpperCase());
        Material air = Material.matchMaterial(args[2].toUpperCase());
        if (wall == null || !wall.isBlock() || wall.isAir()) { player.sendMessage(fmt.error("Unknown wall block: " + args[1])); return true; }
        if (air == null) { player.sendMessage(fmt.error("Unknown air block: " + args[2])); return true; }
        boolean validAir = air == Material.AIR || (air.isBlock() && !air.isAir());
        if (!validAir) { player.sendMessage(fmt.error("Unknown air block: " + args[2])); return true; }
        service.hollow(player, wall, air);
        return true;
    }

    private boolean handleCylinder(Player player, String[] args, boolean hollow) {
        if (args.length < 4) { player.sendMessage(fmt.error((hollow ? "//hcylinder" : "//cylinder") + " <radius> <height> <block>")); return true; }
        try {
            int radius = Integer.parseInt(args[1]);
            int height = Integer.parseInt(args[2]);
            Material m = Material.matchMaterial(args[3].toUpperCase());
            if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[3])); return true; }
            if (hollow) service.hollowCylinder(player, radius, height, m); else service.cylinder(player, radius, height, m);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Radius and height must be numbers."));
        }
        return true;
    }

    private boolean handleCircle(Player player, String[] args, boolean hollow) {
        if (args.length < 3) { player.sendMessage(fmt.error((hollow ? "//hcircle" : "//circle") + " <radius> <block>")); return true; }
        try {
            int radius = Integer.parseInt(args[1]);
            Material m = Material.matchMaterial(args[2].toUpperCase());
            if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[2])); return true; }
            if (hollow) service.hollowCircle(player, radius, m); else service.circle(player, radius, m);
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Radius must be a number."));
        }
        return true;
    }

    private boolean handleSphere(Player player, String[] args, boolean hollow) {
        String usage = hollow ? "/vwe hsphere <radius> <block>" : "/vwe sphere <radius> <block>";
        if (args.length < 3) { player.sendMessage(fmt.error(usage)); return true; }
        try {
            int radius = Integer.parseInt(args[1]);
            Material m = Material.matchMaterial(args[2].toUpperCase());
            if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[2])); return true; }
            if (hollow) {
                service.hollowSphere(player, radius, m);
            } else {
                service.sphere(player, radius, m);
            }
        } catch (NumberFormatException e) {
            player.sendMessage(fmt.error("Radius must be a number."));
        }
        return true;
    }

    private boolean handleLine(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe line <block>")); return true; }
        Material m = Material.matchMaterial(args[1].toUpperCase());
        if (m == null || !m.isBlock()) { player.sendMessage(fmt.error("Unknown block: " + args[1])); return true; }
        service.line(player, m);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(fmt.header("VS-WorldEdit Commands"));
        player.sendMessage(fmt.info("/vwe wand &8- Get selection wand"));
        player.sendMessage(fmt.info("/vwe pos1|pos2 &8- Set position"));
        player.sendMessage(fmt.info("/vwe selection &8- View selection"));
        player.sendMessage(fmt.info("/vwe clearselection &8- Clear selection"));
        player.sendMessage(fmt.info("//set <block[,block...]> [weights] &8- Fill selection; example: //set oak_planks,birch_planks 50,50"));
        player.sendMessage(fmt.info("/vwe replace <from> <to> &8- Replace blocks"));
        player.sendMessage(fmt.info("/vwe walls <block> &8- Build walls"));
        player.sendMessage(fmt.info("/vwe outline <block> &8- Build outline"));
        player.sendMessage(fmt.info("/vwe floor <block> &8- Fill bottom layer"));
        player.sendMessage(fmt.info("/vwe ceiling <block> &8- Fill top layer"));
        player.sendMessage(fmt.info("/vwe hollow <wall> <air> &8- Hollow with walls"));
        player.sendMessage(fmt.info("//cylinder|//hcylinder <radius> <height> <block> &8- Solid or hollow cylinder"));
        player.sendMessage(fmt.info("//circle|//hcircle <radius> <block> &8- Solid or hollow flat circle"));
        player.sendMessage(fmt.info("/vwe sphere <radius> <block> &8- Solid sphere"));
        player.sendMessage(fmt.info("/vwe hsphere <radius> <block> &8- Hollow sphere"));
        player.sendMessage(fmt.info("/vwe line <block> &8- Line pos1→pos2"));
        player.sendMessage(fmt.info("/vwe confirm &8- Confirm large operation"));
        player.sendMessage(fmt.info("/vwe cancel &8- Cancel pending/active"));
        player.sendMessage(fmt.info("/vwe undo &8- Undo last operation"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("wand","pos1","pos2","selection","clearselection","fill","set","replace","walls","outline",
                "floor","ceiling","hollow","cylinder","hcylinder","circle","hcircle","sphere","hsphere","line","confirm","cancel","undo")
                .filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("fill") || sub.equals("set") || sub.equals("walls") || sub.equals("outline")
                || sub.equals("floor") || sub.equals("ceiling") || sub.equals("line"))
                return blockTabComplete(args[args.length - 1]);
            if (sub.equals("replace") && args.length <= 3)
                return blockTabComplete(args[args.length - 1]);
            if ((sub.equals("circle") || sub.equals("hcircle") || sub.equals("sphere") || sub.equals("hsphere")) && args.length == 3)
                return blockTabComplete(args[args.length - 1]);
            if ((sub.equals("cylinder") || sub.equals("hcylinder")) && args.length == 4)
                return blockTabComplete(args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> blockTabComplete(String partial) {
        return Arrays.stream(Material.values())
            .filter(Material::isBlock)
            .map(Material::name)
            .filter(n -> n.toLowerCase().startsWith(partial.toLowerCase()))
            .limit(30).toList();
    }

    private List<VSWorldEditData.WeightedMaterial> parsePattern(Player player, String blockList, String weights) {
        String[] blockNames = blockList.split(",");
        if (blockNames.length == 0 || blockNames.length > 8) {
            player.sendMessage(fmt.error("A pattern must contain between 1 and 8 blocks."));
            return null;
        }
        String[] weightParts = weights == null ? new String[0] : weights.split(",");
        if (weightParts.length != 0 && weightParts.length != blockNames.length) {
            player.sendMessage(fmt.error("Pattern weights must match the number of blocks."));
            return null;
        }
        List<VSWorldEditData.WeightedMaterial> pattern = new ArrayList<>();
        for (int i = 0; i < blockNames.length; i++) {
            String raw = blockNames[i].trim().replace("minecraft:", "");
            Material material = Material.matchMaterial(raw.toUpperCase());
            if (material == null || !material.isBlock() || material.isAir()) {
                player.sendMessage(fmt.error("Unknown block: " + blockNames[i]));
                return null;
            }
            int weight = 1;
            if (weightParts.length > 0) {
                try { weight = Integer.parseInt(weightParts[i].trim()); }
                catch (NumberFormatException ignored) { player.sendMessage(fmt.error("Pattern weights must be whole numbers.")); return null; }
            }
            int maximumWeight = plugin.getConfigManager().getConfig().getInt("vsworldedit.patterns.maxWeight", 10_000);
            if (weight < 1 || weight > maximumWeight) {
                player.sendMessage(fmt.error("Pattern weights must be between 1 and " + maximumWeight + "."));
                return null;
            }
            pattern.add(new VSWorldEditData.WeightedMaterial(material, weight));
        }
        return pattern;
    }
}
