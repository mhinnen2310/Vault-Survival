package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionVisualizationService;
import com.vaultsurvival.plugin.regions.RegionVisualizationSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * /vwe commands for VS-WorldEdit.
 */
public class VSWorldEditCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final VSWorldEditService service;
    private final MessageFormatter fmt;
    private final RegionVisualizationService visualization;
    private final PatternParser patternParser;
    private final VweSchematicService schematics;

    public VSWorldEditCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(VSWorldEditService.class);
        this.fmt = plugin.getMessageFormatter();
        this.visualization = plugin.getServiceRegistry().get(RegionVisualizationService.class);
        this.patternParser = new PatternParser(plugin.getConfigManager().getConfig());
        this.schematics = plugin.getServiceRegistry().get(VweSchematicService.class);
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
        if (effectiveCommand.equals("vswe") || effectiveCommand.equals("vedit")) {
            effectiveCommand = "vwe";
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
            case "visualize" -> handleVisualize(player);
            case "hide" -> {
                visualization.hide(player.getUniqueId());
                plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                    "VWE_SELECTION_HIDE", player.getUniqueId().toString(), "");
                handleSelection(player);
                yield true;
            }
            case "clearselection" -> { service.clearSelection(player); yield true; }
            case "fill", "set" -> handleFill(player, args);
            case "setgrid" -> handleSetGrid(player, args);
            case "replace" -> handleReplace(player, args);
            case "replacegrid" -> handleReplaceGrid(player, args);
            case "pattern", "previewpattern" -> handlePatternPreview(player, args);
            case "operation" -> { openOperationDialog(player, "Choose an operation and validated pattern."); yield true; }
            case "operation-submit" -> handleOperationForm(player, args, false);
            case "operation-preview" -> handleOperationForm(player, args, true);
            case "debugpatterns" -> handlePatternDiagnostics(player);
            case "schematic", "schem" -> handleSchematic(player, args);
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
            case "confirm" -> {
                boolean confirmed = service.confirm(player);
                openOperationDialog(player, confirmed ? "Operation confirmed and started." : "There was no pending operation.");
                yield true;
            }
            case "cancel" -> {
                boolean cancelled = service.cancelOperation(player);
                openOperationDialog(player, cancelled ? "Cancellation accepted; active pattern changes are being rolled back." : "There was no operation to cancel.");
                yield true;
            }
            case "undo" -> { service.undo(player); yield true; }
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleSelection(Player player) {
        var sel = service.getSelection(player);
        Location p1 = service.getPos1(player);
        Location p2 = service.getPos2(player);
        if (plugin.getServiceRegistry().has(DialogService.class)) {
            String body = "POS1: " + point(p1) + "\nPOS2: " + point(p2)
                + (sel == null ? "\nSelection is incomplete."
                : "\nSize: " + sel.getWidth() + " x " + sel.getHeight() + " x " + sel.getDepth()
                + " (" + sel.getVolume() + " blocks)"
                + (sel.getVolume() > service.getMaxBlocksPerOperation() ? "\nExceeds operation maximum: " + service.getMaxBlocksPerOperation() : ""));
            plugin.getServiceRegistry().get(DialogService.class).openResult(player, "VWE Selection", body, List.of(
                DialogMenuItem.adminItem("Set POS1 here", "Set the first point at your block.", "vwe pos1", "vaultsurvival.vwe.use", Material.LIME_CONCRETE),
                DialogMenuItem.adminItem("Set POS2 here", "Set the second point at your block.", "vwe pos2", "vaultsurvival.vwe.use", Material.RED_CONCRETE),
                DialogMenuItem.adminItem("Visualize", "Show dense 3D project borders.", "vwe visualize", "vaultsurvival.vwe.use", Material.ENDER_EYE),
                DialogMenuItem.adminItem("Hide", "Hide the active visualization.", "vwe hide", "vaultsurvival.vwe.use", Material.INK_SAC),
                DialogMenuItem.adminItem("Clear", "Clear both selection positions.", "vwe clearselection", "vaultsurvival.vwe.use", Material.BARRIER)));
            return true;
        }
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
        if (args.length < 2) { player.sendMessage(fmt.error("//set <material|pattern>")); return true; }
        PatternValidationResult result = patternParser.parse(args[1], args.length >= 3 ? args[2] : null);
        if (!sendPatternError(player, result)) return true;
        service.fillPattern(player, result.pattern());
        if (service.hasPendingConfirmation(player)) openOperationDialog(player, "Pending: " + service.getPendingDescription(player));
        return true;
    }

    private boolean handleSetGrid(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("//setgrid <materials>")); return true; }
        String expression = args[1].toLowerCase().startsWith("grid:") ? args[1] : "grid:" + args[1];
        PatternValidationResult result = patternParser.parse(expression);
        if (!sendPatternError(player, result)) return true;
        service.fillPattern(player, result.pattern());
        if (service.hasPendingConfirmation(player)) openOperationDialog(player, "Pending: " + service.getPendingDescription(player));
        return true;
    }

    private boolean handleReplace(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(fmt.error("/vwe replace <fromBlock> <toBlock>")); return true; }
        var from = patternParser.materialResolver().resolve(args[1]);
        if (!sendMaterialError(player, from)) return true;
        PatternValidationResult result = patternParser.parse(args[2], args.length >= 4 ? args[3] : null);
        if (!sendPatternError(player, result)) return true;
        service.replacePattern(player, from.material(), result.pattern());
        if (service.hasPendingConfirmation(player)) openOperationDialog(player, "Pending: " + service.getPendingDescription(player));
        return true;
    }

    private boolean handleReplaceGrid(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(fmt.error("//replacegrid <from> <materials>")); return true; }
        var from = patternParser.materialResolver().resolve(args[1]);
        if (!sendMaterialError(player, from)) return true;
        String expression = args[2].toLowerCase().startsWith("grid:") ? args[2] : "grid:" + args[2];
        PatternValidationResult result = patternParser.parse(expression);
        if (!sendPatternError(player, result)) return true;
        service.replacePattern(player, from.material(), result.pattern());
        if (service.hasPendingConfirmation(player)) openOperationDialog(player, "Pending: " + service.getPendingDescription(player));
        return true;
    }

    private boolean handlePatternPreview(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("/vwe pattern <material|pattern>")); return true; }
        PatternValidationResult result = patternParser.parse(args[1], args.length >= 3 ? args[2] : null);
        if (!sendPatternError(player, result)) return true;
        openOperationDialog(player, "Parsed successfully: " + result.pattern().describe());
        return true;
    }

    private boolean handlePatternDiagnostics(Player player) {
        PatternParserDiagnostics.Result result = PatternParserDiagnostics.run(patternParser);
        player.sendMessage(fmt.header("VWE Pattern Parser Diagnostics"));
        player.sendMessage(result.passed() ? fmt.success("PASS: " + result.checks() + " checks")
            : fmt.error("FAIL: " + String.join(", ", result.failures())));
        return true;
    }

    private boolean handleSchematic(Player player, String[] args) {
        if (!player.hasPermission("vaultsurvival.vwe.schematic")
            || !plugin.isStaffModeActive(player.getUniqueId())) {
            player.sendMessage(fmt.error("Activate staffmode and obtain vaultsurvival.vwe.schematic."));
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            var files = schematics.list();
            String body = "Supported format: vanilla structure .nbt\nFolder: " + schematics.getDirectory()
                + "\nFiles: " + files.size() + "\nWorldEdit .schem/.schematic files are not accepted.";
            if (plugin.getServiceRegistry().has(DialogService.class)) {
                List<DialogMenuItem> items = new java.util.ArrayList<>();
                files.stream().limit(20).forEach(file -> items.add(DialogMenuItem.adminItem(file.fileName(),
                    file.fileBytes() + " bytes - validate and preview at your current block.",
                    "vwe schematic preview " + file.fileName(), "vaultsurvival.vwe.schematic", Material.STRUCTURE_BLOCK)));
                if (items.isEmpty()) items.add(DialogMenuItem.adminItem("Refresh", "Scan the dedicated folder again.",
                    "vwe schematic list", "vaultsurvival.vwe.schematic", Material.CLOCK));
                plugin.getServiceRegistry().get(DialogService.class).openResult(player, "VWE Structures", body, items);
            } else {
                player.sendMessage(fmt.header("VWE Structures"));
                player.sendMessage(fmt.info(body.replace('\n', ' ')));
                files.forEach(file -> player.sendMessage(fmt.info(file.fileName() + " &7- " + file.fileBytes() + " bytes")));
            }
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(fmt.error("/vwe schematic <list|preview|paste> <file.nbt>"));
            return true;
        }
        String action = args[1].toLowerCase();
        String fileName = args[2];
        VweSchematicService.Result result = switch (action) {
            case "preview", "inspect" -> schematics.preview(player, fileName);
            case "paste", "load" -> schematics.paste(player, fileName);
            default -> VweSchematicService.Result.failure("Unknown schematic action. Use list, preview, or paste.");
        };
        showSchematicResult(player, action, fileName, result);
        return true;
    }

    private void showSchematicResult(Player player, String action, String fileName,
                                     VweSchematicService.Result result) {
        if (!plugin.getServiceRegistry().has(DialogService.class)) {
            player.sendMessage(result.success() ? fmt.success(result.message()) : fmt.error(result.message()));
            return;
        }
        List<DialogMenuItem> items = new java.util.ArrayList<>();
        if (result.success() && action.equals("preview")) {
            items.add(DialogMenuItem.adminItem("Paste at current block", "Loads through VWE with limits, confirmation, and undo.",
                "vwe schematic paste " + result.info().fileName(), "vaultsurvival.vwe.schematic", Material.LIME_CONCRETE));
        }
        if (result.pendingConfirmation()) {
            items.add(DialogMenuItem.adminItem("Confirm paste", "Dangerous action: begin the validated structure paste.",
                "vwe confirm", "vaultsurvival.vwe.schematic", Material.RED_CONCRETE));
            items.add(DialogMenuItem.adminItem("Cancel", "Discard the pending paste without changing blocks.",
                "vwe cancel", "vaultsurvival.vwe.schematic", Material.BARRIER));
        }
        if (result.success() && !result.pendingConfirmation() && action.equals("paste")) {
            items.add(DialogMenuItem.adminItem("Undo", "Restore every block changed by the paste.",
                "vwe undo", "vaultsurvival.vwe.schematic", Material.RECOVERY_COMPASS));
        }
        items.add(DialogMenuItem.adminItem("Back to structures", "Return to the validated file list.",
            "vwe schematic list", "vaultsurvival.vwe.schematic", Material.ARROW));
        plugin.getServiceRegistry().get(DialogService.class).openResult(player,
            result.success() ? "VWE Structure" : "VWE Structure Error", result.message(), items);
    }

    private boolean handleOperationForm(Player player, String[] args, boolean previewOnly) {
        if (args.length < 4) {
            openOperationDialog(player, "Choose an operation, pattern mode, and material/pattern.");
            return true;
        }
        String operation = args[1].toUpperCase();
        BlockPattern.Mode mode;
        try { mode = BlockPattern.Mode.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException exception) {
            openOperationDialog(player, "Unknown pattern mode: " + args[2]);
            return true;
        }
        String input = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        Material replaceFrom = null;
        String patternExpression = input;
        if (operation.equals("REPLACE")) {
            String[] parts = input.split("\\s+", 2);
            if (parts.length != 2) {
                openOperationDialog(player, "Replace requires: source-material target-material-or-pattern.");
                return true;
            }
            var source = patternParser.materialResolver().resolve(parts[0]);
            if (!source.valid()) {
                openOperationDialog(player, source.error() + (source.suggestion() == null ? "" : " " + source.suggestion()));
                return true;
            }
            replaceFrom = source.material();
            patternExpression = parts[1];
        } else if (!operation.equals("SET")) {
            openOperationDialog(player, "Unknown operation type: " + operation);
            return true;
        }

        PatternValidationResult result = parseForMode(mode, patternExpression);
        if (!result.valid()) {
            openOperationDialog(player, result.error() + (result.suggestion() == null ? "" : " " + result.suggestion()));
            return true;
        }
        if (previewOnly) {
            openOperationDialog(player, "Preview Parsed Pattern: " + result.pattern().describe());
            return true;
        }
        if (replaceFrom == null) service.fillPattern(player, result.pattern());
        else service.replacePattern(player, replaceFrom, result.pattern());
        openOperationDialog(player, service.hasPendingConfirmation(player)
            ? "Pending confirmation: " + service.getPendingDescription(player)
            : "Validated and started: " + result.pattern().describe());
        return true;
    }

    private PatternValidationResult parseForMode(BlockPattern.Mode mode, String expression) {
        PatternValidationResult result = switch (mode) {
            case SINGLE -> patternParser.parse(expression);
            case RANDOM -> patternParser.parse("random:" + expression);
            case WEIGHTED_RANDOM -> patternParser.parse(expression);
            case GRID -> patternParser.parse(expression.toLowerCase().startsWith("grid:") ? expression : "grid:" + expression);
        };
        if (!result.valid()) return result;
        boolean modeMatches = result.pattern().mode() == mode;
        if (mode == BlockPattern.Mode.SINGLE) modeMatches = result.pattern().entries().size() == 1;
        if (!modeMatches) return PatternValidationResult.invalid("Pattern syntax does not match selected mode " + mode + ".");
        return result;
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

    private boolean handleVisualize(Player player) {
        var sel = service.getSelection(player);
        if (sel == null) {
            handleSelection(player);
            return true;
        }
        visualization.showBounds(player, new RegionVisualizationSession.Bounds(player.getWorld(),
            sel.getX1(), sel.getY1(), sel.getZ1(), sel.getX2(), sel.getY2(), sel.getZ2()),
            RegionData.RegionType.PROJECT_REGION, "VWE selection",
            RegionVisualizationSession.Mode.WHILE_EDITING, false);
        plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(), "VWE_SELECTION_VISUALIZE",
            player.getWorld().getName(), "volume=" + sel.getVolume());
        handleSelection(player);
        return true;
    }

    private static String point(Location location) {
        return location == null ? "not set" : location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
            + " (" + location.getWorld().getName() + ")";
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
        player.sendMessage(fmt.info("/vwe visualize|hide &8- Show or hide dense 3D selection borders"));
        player.sendMessage(fmt.info("/vwe clearselection &8- Clear selection"));
        player.sendMessage(fmt.info("//set <material|random|weighted|grid pattern> &8- Fill selection"));
        player.sendMessage(fmt.info("//setgrid <materials> &8- Deterministic coordinate grid"));
        player.sendMessage(fmt.info("//replace <from> <material|pattern> &8- Replace blocks"));
        player.sendMessage(fmt.info("//replacegrid <from> <materials> &8- Replace with deterministic grid"));
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
        player.sendMessage(fmt.info("/vwe debugpatterns &8- Run live parser acceptance checks"));
        player.sendMessage(fmt.info("/vwe schematic <list|preview|paste> [file.nbt] &8- Safe vanilla structure loader"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("wand","pos1","pos2","selection","visualize","hide","clearselection","operation","pattern","debugpatterns","schematic","fill","set","setgrid","replace","replacegrid","walls","outline",
                "floor","ceiling","hollow","cylinder","hcylinder","circle","hcircle","sphere","hsphere","line","confirm","cancel","undo")
                .filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            if ((sub.equals("schematic") || sub.equals("schem")) && args.length == 2) {
                return Stream.of("list", "preview", "paste").filter(value -> value.startsWith(args[1].toLowerCase())).toList();
            }
            if ((sub.equals("schematic") || sub.equals("schem")) && args.length == 3
                && (args[1].equalsIgnoreCase("preview") || args[1].equalsIgnoreCase("paste"))) {
                return schematics.list().stream().map(VweSchematicService.AvailableFile::fileName)
                    .filter(value -> value.toLowerCase().startsWith(args[2].toLowerCase())).toList();
            }
            if (sub.equals("fill") || sub.equals("set") || sub.equals("setgrid") || sub.equals("pattern") || sub.equals("walls") || sub.equals("outline")
                || sub.equals("floor") || sub.equals("ceiling") || sub.equals("line"))
                return blockTabComplete(args[args.length - 1]);
            if ((sub.equals("replace") || sub.equals("replacegrid")) && args.length <= 3)
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

    private boolean sendPatternError(Player player, PatternValidationResult result) {
        if (result.valid()) return true;
        player.sendMessage(fmt.error(result.error()));
        if (result.suggestion() != null) player.sendMessage(fmt.info(result.suggestion()));
        return false;
    }

    private boolean sendMaterialError(Player player, MaterialResolver.ResolveResult result) {
        if (result.valid()) return true;
        player.sendMessage(fmt.error(result.error()));
        if (result.suggestion() != null) player.sendMessage(fmt.info(result.suggestion()));
        player.sendMessage(fmt.info("Accepted examples: stone, minecraft:stone, air, 0"));
        return false;
    }

    private void openOperationDialog(Player player, String status) {
        if (!plugin.getServiceRegistry().has(DialogService.class)) {
            player.sendMessage(fmt.info(status));
            return;
        }
        var selection = service.getSelection(player);
        String body = status + "\n\nOperation Type: choose below"
            + "\nMaterial/Pattern: validated before execution"
            + "\nPattern Mode: SINGLE / RANDOM / WEIGHTED_RANDOM / GRID"
            + "\nBlock Count: " + (selection == null ? "no complete selection" : selection.getVolume())
            + (service.hasPendingConfirmation(player) ? "\nPending confirmation: " + service.getPendingDescription(player) : "");
        List<DialogMenuItem> fallbackItems = List.of(
            DialogMenuItem.adminItem("Set / Pattern", "SINGLE, RANDOM, or WEIGHTED_RANDOM material expression.", "vsmenu input vwe_pattern_set", "vaultsurvival.vwe.use", Material.BRICKS),
            DialogMenuItem.adminItem("Grid", "Deterministic coordinate-based GRID pattern.", "vsmenu input vwe_pattern_grid", "vaultsurvival.vwe.use", Material.TARGET),
            DialogMenuItem.adminItem("Replace", "Source material plus target material/pattern.", "vsmenu input vwe_pattern_replace", "vaultsurvival.vwe.use", Material.STONECUTTER),
            DialogMenuItem.adminItem("Preview Parsed Pattern", "Validate and preview without editing blocks.", "vsmenu input vwe_pattern_preview", "vaultsurvival.vwe.use", Material.SPYGLASS),
            DialogMenuItem.adminItem("Confirm", "Confirm the pending large or air operation.", "vwe confirm", "vaultsurvival.vwe.use", Material.EMERALD),
            DialogMenuItem.adminItem("Cancel", "Cancel pending work or roll back an active pattern operation.", "vwe cancel", "vaultsurvival.vwe.use", Material.BARRIER));
        plugin.getServiceRegistry().get(DialogService.class).openVweOperation(player, body,
            selection == null ? 0 : selection.getVolume(), fallbackItems);
    }
}
