package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionVisualizationService;
import com.vaultsurvival.plugin.regions.RegionVisualizationSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full implementation of VSWorldEditService with batched operations.
 */
public class VSWorldEditServiceImpl implements VSWorldEditService {

    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final RegionVisualizationService visualization;

    // Per-player selection state
    private final Map<UUID, Location> pos1Map = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2Map = new ConcurrentHashMap<>();

    // Per-player undo stacks
    private final Map<UUID, Deque<VSWorldEditData.UndoEntry>> undoStacks = new ConcurrentHashMap<>();

    // Pending confirmation: player must /vwe confirm
    private final Map<UUID, VSWorldEditData.ActiveOperation> pendingOps = new ConcurrentHashMap<>();
    private final Map<UUID, List<VSWorldEditData.BlockPlacement>> pendingPlacementOps = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPatternOperation> pendingPatternOps = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPositionOperation> pendingPositionOps = new ConcurrentHashMap<>();
    // Currently running operations
    private final Map<UUID, VSWorldEditData.ActiveOperation> activeOps = new ConcurrentHashMap<>();
    private final Set<UUID> undoing = ConcurrentHashMap.newKeySet();

    // Wand key
    private final NamespacedKey wandKey;

    public VSWorldEditServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
        this.visualization = plugin.getServiceRegistry().get(RegionVisualizationService.class);
        this.wandKey = new NamespacedKey(plugin, VSWorldEditData.WAND_KEY);
    }

    // ========================================================================
    // Wand (with PersistentDataContainer)
    // ========================================================================

    @Override
    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(fmt.deserialize("&6&lVWE Wand &7(Right-click)"));
        meta.lore(List.of(
            fmt.deserialize("&7Left-click &8→ &eSet Pos #1"),
            fmt.deserialize("&7Right-click &8→ &eSet Pos #2")
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BOOLEAN, true);
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand);
        player.sendMessage(fmt.success("VWE Wand given."));
    }

    @Override
    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_AXE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return Boolean.TRUE.equals(meta.getPersistentDataContainer().get(wandKey, PersistentDataType.BOOLEAN));
    }

    // ========================================================================
    // Selection
    // ========================================================================

    @Override
    public void setPos1(Player player, Location loc) {
        pos1Map.put(player.getUniqueId(), loc.clone());
        player.sendMessage(fmt.info("Pos #1: &e" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
        notifySelection(player);
    }

    @Override
    public void setPos2(Player player, Location loc) {
        pos2Map.put(player.getUniqueId(), loc.clone());
        player.sendMessage(fmt.info("Pos #2: &e" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
        notifySelection(player);
    }

    @Override public Location getPos1(Player player) { return pos1Map.get(player.getUniqueId()); }
    @Override public Location getPos2(Player player) { return pos2Map.get(player.getUniqueId()); }

    @Override
    public VSWorldEditData.Selection getSelection(Player player) {
        Location p1 = pos1Map.get(player.getUniqueId());
        Location p2 = pos2Map.get(player.getUniqueId());
        if (p1 == null || p2 == null) return null;
        if (!p1.getWorld().equals(p2.getWorld())) return null;
        return new VSWorldEditData.Selection(player.getUniqueId(), p1, p2);
    }

    @Override
    public void clearSelection(Player player) {
        pos1Map.remove(player.getUniqueId());
        pos2Map.remove(player.getUniqueId());
        visualization.hide(player.getUniqueId());
        player.sendMessage(fmt.info("Selection cleared."));
    }

    private void notifySelection(Player player) {
        var sel = getSelection(player);
        if (sel != null) {
            player.sendMessage(fmt.info("Selection: &e" +
                sel.getWidth() + "x" + sel.getHeight() + "x" + sel.getDepth() +
                " &7(" + sel.getVolume() + " blocks)"));
            visualization.showBounds(player, new RegionVisualizationSession.Bounds(player.getWorld(),
                sel.getX1(), sel.getY1(), sel.getZ1(), sel.getX2(), sel.getY2(), sel.getZ2()),
                RegionData.RegionType.PROJECT_REGION, "VWE selection",
                RegionVisualizationSession.Mode.WHILE_EDITING, false);
        }
    }

    // ========================================================================
    // Operations (fill, replace, walls, outline)
    // ========================================================================

    @Override
    public boolean fill(Player player, Material material) {
        return startOperation(player, VSWorldEditData.OperationType.FILL, material, null);
    }

    @Override
    public boolean fillPattern(Player player, BlockPattern pattern) {
        return startPatternOperation(player, VSWorldEditData.OperationType.PATTERN_FILL, null, pattern);
    }

    @Override
    public boolean replacePattern(Player player, Material from, BlockPattern pattern) {
        if (from == null || (!from.isBlock() && !from.isAir())) {
            player.sendMessage(fmt.error("Invalid source material."));
            return false;
        }
        return startPatternOperation(player, VSWorldEditData.OperationType.PATTERN_REPLACE, from, pattern);
    }

    private boolean startPatternOperation(Player player, VSWorldEditData.OperationType type,
                                          Material from, BlockPattern pattern) {
        UUID uuid = player.getUniqueId();
        if (activeOps.containsKey(uuid) || pendingOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("An operation is already pending or running."));
            return false;
        }
        var selection = getSelection(player);
        if (selection == null) {
            player.sendMessage(fmt.error("No selection. Set pos1 and pos2 first."));
            return false;
        }
        if (selection.getVolume() > getMaxBlocksPerOperation()) {
            player.sendMessage(fmt.error("Selection too large: " + selection.getVolume() + " blocks (max: " + getMaxBlocksPerOperation() + ")"));
            return false;
        }
        if (pattern == null || pattern.entries().isEmpty() || pattern.entries().stream().anyMatch(entry ->
            entry.material() == null || (!entry.material().isBlock() && !entry.material().isAir()) || entry.weight() <= 0)) {
            player.sendMessage(fmt.error("Invalid block pattern."));
            return false;
        }
        var operation = new VSWorldEditData.ActiveOperation(uuid, type, selection,
            pattern.entries().getFirst().material(), from);
        operation.setTotalBlocks(selection.getVolume());
        boolean airConfirmation = pattern.containsAir() && requireConfirmationForAirOperations();
        if (selection.getVolume() > getRequireConfirmationAbove() || airConfirmation) {
            pendingOps.put(uuid, operation);
            pendingPatternOps.put(uuid, new PendingPatternOperation(pattern, from));
            player.sendMessage(fmt.warn("Pattern operation: &e" + pattern.describe() + "&e over " + selection.getVolume() + " blocks."));
            if (airConfirmation) player.sendMessage(fmt.warn("Air operations always require confirmation."));
            player.sendMessage(fmt.warn("Type &e/vwe confirm&e to proceed, or &e/vwe cancel&e to abort."));
            return false;
        }
        executePatternOperation(operation, player, pattern, from);
        return true;
    }

    @Override
    public boolean replace(Player player, Material from, Material to) {
        return startOperation(player, VSWorldEditData.OperationType.REPLACE, to, from);
    }

    @Override
    public boolean walls(Player player, Material material) {
        return startOperation(player, VSWorldEditData.OperationType.WALLS, material, null);
    }

    @Override
    public boolean outline(Player player, Material material) {
        return startOperation(player, VSWorldEditData.OperationType.OUTLINE, material, null);
    }

    @Override
    public boolean floor(Player player, Material material) {
        return startOperation(player, VSWorldEditData.OperationType.FLOOR, material, null);
    }

    @Override
    public boolean ceiling(Player player, Material material) {
        return startOperation(player, VSWorldEditData.OperationType.CEILING, material, null);
    }

    @Override
    public boolean hollow(Player player, Material wallBlock, Material airBlock) {
        UUID uuid = player.getUniqueId();
        if (activeOps.containsKey(uuid) || pendingOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("An operation is already pending or running."));
            return false;
        }
        var sel = getSelection(player);
        if (sel == null) {
            player.sendMessage(fmt.error("No selection. Set pos1 and pos2 first."));
            return false;
        }
        if (sel.getWidth() < 3 || sel.getHeight() < 3 || sel.getDepth() < 3) {
            player.sendMessage(fmt.error("Selection must be at least 3x3x3 for hollow."));
            return false;
        }

        // Pre-compute wall + interior positions with their materials
        List<VSWorldEditData.BlockPlacement> placements = computeHollowPlacements(sel, wallBlock, airBlock);
        int total = placements.size();
        if (total > getMaxBlocksPerOperation()) {
            player.sendMessage(fmt.error("Too many blocks: " + total + " (max: " + getMaxBlocksPerOperation() + ")"));
            return false;
        }

        var op = new VSWorldEditData.ActiveOperation(uuid, VSWorldEditData.OperationType.HOLLOW, sel, wallBlock, airBlock);
        op.setTotalBlocks(total);

        if (total > getRequireConfirmationAbove()
            || ((wallBlock.isAir() || airBlock.isAir()) && requireConfirmationForAirOperations())) {
            pendingOps.put(uuid, op);
            pendingPlacementOps.put(uuid, placements);
            player.sendMessage(fmt.warn("Operation: &eHOLLOW &e" + total + " blocks (" + wallBlock.name() + " / " + airBlock.name() + ")"));
            player.sendMessage(fmt.warn("Type &e/vwe confirm &eto proceed, or &e/vwe cancel&e to abort."));
            return false;
        }
        executeOperationWithPlacements(op, player, placements);
        return true;
    }

    @Override
    public boolean cylinder(Player player, int radius, int height, Material material) {
        if (radius < 1 || height < 1) {
            player.sendMessage(fmt.error("Radius and height must be at least 1."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.CYLINDER, material,
            computeDiscPositions(player, radius, height));
    }

    @Override
    public boolean hollowCylinder(Player player, int radius, int height, Material material) {
        if (radius < 1 || height < 1) {
            player.sendMessage(fmt.error("Radius and height must be at least 1."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.CYLINDER, material,
            computeCirclePositions(player, radius, height));
    }

    @Override
    public boolean circle(Player player, int radius, Material material) {
        if (radius < 1) {
            player.sendMessage(fmt.error("Radius must be at least 1."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.CIRCLE, material,
            computeDiscPositions(player, radius, 1));
    }

    @Override
    public boolean hollowCircle(Player player, int radius, Material material) {
        if (radius < 1) {
            player.sendMessage(fmt.error("Radius must be at least 1."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.CIRCLE, material,
            computeCirclePositions(player, radius, 1));
    }

    @Override
    public boolean sphere(Player player, int radius, Material material) {
        if (radius < 1) {
            player.sendMessage(fmt.error("Radius must be at least 1."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.SPHERE, material,
            computeSpherePositions(player, radius, false));
    }

    @Override
    public boolean hollowSphere(Player player, int radius, Material material) {
        if (radius < 2) {
            player.sendMessage(fmt.error("Hollow sphere radius must be at least 2."));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.HSPHERE, material,
            computeSpherePositions(player, radius, true));
    }

    @Override
    public boolean line(Player player, Material material) {
        UUID uuid = player.getUniqueId();
        if (activeOps.containsKey(uuid) || pendingOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("An operation is already pending or running."));
            return false;
        }
        Location p1 = getPos1(player);
        Location p2 = getPos2(player);
        if (p1 == null || p2 == null) {
            player.sendMessage(fmt.error("Set both pos1 and pos2 for a line."));
            return false;
        }
        if (!p1.getWorld().equals(p2.getWorld())) {
            player.sendMessage(fmt.error("Positions are in different worlds!"));
            return false;
        }
        return startPositionOp(player, VSWorldEditData.OperationType.LINE, material,
            computeLinePositions(p1, p2));
    }

    private boolean startOperation(Player player, VSWorldEditData.OperationType type,
                                    Material primary, Material secondary) {
        UUID uuid = player.getUniqueId();

        // Check no active operation
        if (activeOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("An operation is already running. Wait or /vwe cancel."));
            return false;
        }
        if (pendingOps.containsKey(uuid)) {
            player.sendMessage(fmt.error("You have a pending operation. /vwe confirm or /vwe cancel."));
            return false;
        }

        // Check selection
        var sel = getSelection(player);
        if (sel == null) {
            Location p1 = getPos1(player);
            Location p2 = getPos2(player);
            if (p1 != null && p2 != null && !p1.getWorld().equals(p2.getWorld())) {
                player.sendMessage(fmt.error("Positions are in different worlds! Set both in the same world."));
            } else {
                player.sendMessage(fmt.error("No selection. Set pos1 and pos2 first."));
            }
            return false;
        }
        if (sel.getVolume() > getMaxBlocksPerOperation()) {
            player.sendMessage(fmt.error("Selection too large: " + sel.getVolume() +
                " blocks (max: " + getMaxBlocksPerOperation() + ")"));
            return false;
        }

        // Validate material
        if (primary == null || (!primary.isBlock() && !primary.isAir())) {
            player.sendMessage(fmt.error("Invalid block type: " + (primary != null ? primary.name() : "null")));
            return false;
        }

        var op = new VSWorldEditData.ActiveOperation(uuid, type, sel, primary, secondary);

        // Require confirmation for large operations
        if (sel.getVolume() > getRequireConfirmationAbove()
            || (primary.isAir() && requireConfirmationForAirOperations())) {
            pendingOps.put(uuid, op);
            player.sendMessage(fmt.warn("Operation: &e" + type.name() + "&e " + sel.getVolume() +
                " blocks with " + primary.name()));
            player.sendMessage(fmt.warn("Type &e/vwe confirm &eto proceed, or &e/vwe cancel&e to abort."));
            return false;
        }

        // Start immediately
        executeOperation(op, player);
        return true;
    }

    // ========================================================================
    // Confirm / Cancel
    // ========================================================================

    /** Start an operation from pre-computed positions (single material). */
    private boolean startPositionOp(Player player, VSWorldEditData.OperationType type,
                                     Material material, List<int[]> positions) {
        UUID uuid = player.getUniqueId();
        if (activeOps.containsKey(uuid) || pendingOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("An operation is already pending or running."));
            return false;
        }
        if (positions == null || positions.isEmpty()) {
            player.sendMessage(fmt.error("No blocks to place."));
            return false;
        }
        int total = positions.size();
        if (total > getMaxBlocksPerOperation()) {
            player.sendMessage(fmt.error("Too many blocks: " + total + " (max: " + getMaxBlocksPerOperation() + ")"));
            return false;
        }
        if (material == null || (!material.isBlock() && !material.isAir())) {
            player.sendMessage(fmt.error("Invalid block type."));
            return false;
        }

        // Create a synthetic selection for the operation record
        String worldName = player.getWorld().getName();
        var sel = new VSWorldEditData.Selection(uuid,
            new Location(player.getWorld(), 0, 0, 0),
            new Location(player.getWorld(), 1, 1, 1));
        var op = new VSWorldEditData.ActiveOperation(uuid, type, sel, material, null);
        op.setTotalBlocks(total);

        if (total > getRequireConfirmationAbove() || (material.isAir() && requireConfirmationForAirOperations())) {
            pendingOps.put(uuid, op);
            pendingPositionOps.put(uuid, new PendingPositionOperation(positions, material));
            player.sendMessage(fmt.warn("Operation: &e" + type.name() + " " + total + " blocks with " + material.name()));
            player.sendMessage(fmt.warn("Type &e/vwe confirm &eto proceed, or &e/vwe cancel&e to abort."));
            return false;
        }
        executeOpWithPositions(op, player, positions, material);
        return true;
    }

    // --- Circle algorithm (midpoint circle for cylinder/circle) ---
    private List<int[]> computeCirclePositions(Player player, int radius, int height) {
        Location center = player.getLocation();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        List<int[]> positions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int r = radius;
        int x = 0, z = r;
        int d = 3 - 2 * r;
        while (x <= z) {
            for (int y = 0; y < height; y++) {
                addCircle(positions, seen, cx, cy + y, cz, x, z);
            }
            if (d < 0) { d += 4 * x + 6; }
            else { d += 4 * (x - z) + 10; z--; }
            x++;
        }
        return positions;
    }

    private List<int[]> computeDiscPositions(Player player, int radius, int height) {
        Location center = player.getLocation();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int radiusSquared = radius * radius;
        List<int[]> positions = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radiusSquared) positions.add(new int[]{cx + x, cy + y, cz + z});
                }
            }
        }
        return positions;
    }

    private List<int[]> computeSpherePositions(Player player, int radius, boolean hollow) {
        Location center = player.getLocation();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        List<int[]> positions = new ArrayList<>();
        int r2 = radius * radius;
        int inner = Math.max(radius - 1, 0);
        int inner2 = inner * inner;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    int distance = x * x + y * y + z * z;
                    if (distance > r2) {
                        continue;
                    }
                    if (hollow && distance < inner2) {
                        continue;
                    }
                    positions.add(new int[]{cx + x, cy + y, cz + z});
                }
            }
        }
        return positions;
    }

    private void addCircle(List<int[]> list, Set<String> seen, int cx, int cy, int cz, int x, int z) {
        int[][] coords = {{cx + x, cz + z}, {cx - x, cz + z}, {cx + x, cz - z}, {cx - x, cz - z},
                          {cx + z, cz + x}, {cx - z, cz + x}, {cx + z, cz - x}, {cx - z, cz - x}};
        for (int[] c : coords) {
            String key = c[0] + "," + cy + "," + c[1];
            if (seen.add(key)) list.add(new int[]{c[0], cy, c[1]});
        }
    }

    // --- Bresenham 3D line ---
    private List<int[]> computeLinePositions(Location p1, Location p2) {
        List<int[]> positions = new ArrayList<>();
        int x1 = p1.getBlockX(), y1 = p1.getBlockY(), z1 = p1.getBlockZ();
        int x2 = p2.getBlockX(), y2 = p2.getBlockY(), z2 = p2.getBlockZ();
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, sz = z1 < z2 ? 1 : -1;
        int x = x1, y = y1, z = z1;

        if (dx >= dy && dx >= dz) {
            int py = 2 * dy - dx, pz = 2 * dz - dx;
            for (int i = 0; i <= dx; i++) {
                positions.add(new int[]{x, y, z});
                if (py >= 0) { y += sy; py -= 2 * dx; }
                if (pz >= 0) { z += sz; pz -= 2 * dx; }
                py += 2 * dy; pz += 2 * dz; x += sx;
            }
        } else if (dy >= dx && dy >= dz) {
            int px = 2 * dx - dy, pz = 2 * dz - dy;
            for (int i = 0; i <= dy; i++) {
                positions.add(new int[]{x, y, z});
                if (px >= 0) { x += sx; px -= 2 * dy; }
                if (pz >= 0) { z += sz; pz -= 2 * dy; }
                px += 2 * dx; pz += 2 * dz; y += sy;
            }
        } else {
            int px = 2 * dx - dz, py = 2 * dy - dz;
            for (int i = 0; i <= dz; i++) {
                positions.add(new int[]{x, y, z});
                if (px >= 0) { x += sx; px -= 2 * dz; }
                if (py >= 0) { y += sy; py -= 2 * dz; }
                px += 2 * dx; py += 2 * dy; z += sz;
            }
        }
        return positions;
    }

    // --- Hollow placement (walls + interior air) ---
    private List<VSWorldEditData.BlockPlacement> computeHollowPlacements(
            VSWorldEditData.Selection sel, Material wallBlock, Material airBlock) {
        List<VSWorldEditData.BlockPlacement> placements = new ArrayList<>();
        int x1 = sel.getX1(), x2 = sel.getX2(), y1 = sel.getY1(), y2 = sel.getY2(), z1 = sel.getZ1(), z2 = sel.getZ2();

        // Walls: all blocks on the 6 faces
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            for (int y : new int[]{y1, y2}) placements.add(new VSWorldEditData.BlockPlacement(x, y, z, wallBlock));
        }
        for (int x = x1; x <= x2; x++) for (int y = y1 + 1; y < y2; y++) {
            placements.add(new VSWorldEditData.BlockPlacement(x, y, z1, wallBlock));
            placements.add(new VSWorldEditData.BlockPlacement(x, y, z2, wallBlock));
        }
        for (int z = z1 + 1; z < z2; z++) for (int y = y1 + 1; y < y2; y++) {
            placements.add(new VSWorldEditData.BlockPlacement(x1, y, z, wallBlock));
            placements.add(new VSWorldEditData.BlockPlacement(x2, y, z, wallBlock));
        }

        // Interior: all blocks NOT on the faces get airBlock
        for (int x = x1 + 1; x < x2; x++) for (int y = y1 + 1; y < y2; y++) for (int z = z1 + 1; z < z2; z++) {
            placements.add(new VSWorldEditData.BlockPlacement(x, y, z, airBlock));
        }
        return placements;
    }

    // ========================================================================
    // Batched execution with BlockPlacement list (per-block materials)
    // ========================================================================

    private void executeOperationWithPlacements(VSWorldEditData.ActiveOperation op, Player player,
                                                 List<VSWorldEditData.BlockPlacement> placements) {
        activeOps.put(player.getUniqueId(), op);
        int blocksPerTick = getBlocksPerTick();
        String worldName = op.getSelection().getWorldName();
        World world = player.getWorld();

        player.sendMessage(fmt.info("Started &e" + op.getType().name() + "&7: " +
            placements.size() + " blocks. Processing..."));

        new BukkitRunnable() {
            int idx = 0, placedCount = 0, lastPct = -1;
            @Override
            public void run() {
                if (!player.isOnline() || op.isCancelled()) {
                    if (player.isOnline()) player.sendMessage(fmt.info("Operation cancelled after &e" + placedCount + "&7 blocks."));
                    activeOps.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                int processed = 0;
                while (processed < blocksPerTick && idx < placements.size()) {
                    var bp = placements.get(idx);
                    Location loc = new Location(world, bp.x, bp.y, bp.z);
                    op.getUndoEntry().addSnapshot(new VSWorldEditData.BlockSnapshot(loc, loc.getBlock().getType().name()));
                    loc.getBlock().setType(bp.material);
                    placedCount++; idx++; processed++;
                }
                if (idx >= placements.size()) {
                    finishHollow(player, op, placedCount);
                    cancel();
                    return;
                }
                int pct = idx * 100 / placements.size();
                if (pct >= lastPct + 10) {
                    lastPct = pct - (pct % 10);
                    player.sendMessage(fmt.info(op.getType().name() + " progress: &e" + pct + "%&7 (" + placedCount + " placed)"));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishHollow(Player player, VSWorldEditData.ActiveOperation op, int placedCount) {
        activeOps.remove(player.getUniqueId());
        if (placedCount > 0) pushUndo(player, op.getUndoEntry());
        player.sendMessage(fmt.success(op.getType().name() + " complete: &e" + placedCount + "&a blocks placed."));
        plugin.getLogger().info("[VWE] " + player.getName() + " " + op.getType().name() + " " + placedCount + " blocks");
    }

    /** Resolves patterns lazily inside the existing batched engine; no million-entry placement list. */
    private void executePatternOperation(VSWorldEditData.ActiveOperation op, Player player,
                                         BlockPattern pattern, Material replaceFrom) {
        UUID uuid = player.getUniqueId();
        activeOps.put(uuid, op);
        VSWorldEditData.Selection selection = op.getSelection();
        World world = Bukkit.getWorld(selection.getWorldName());
        if (world == null) {
            activeOps.remove(uuid);
            player.sendMessage(fmt.error("World not found."));
            return;
        }
        int blocksPerTick = getBlocksPerTick();
        Random random = new Random(uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()
            ^ selection.getVolume() ^ pattern.describe().hashCode());
        player.sendMessage(fmt.info("Started &e" + op.getType().name() + "&7: " + selection.getVolume()
            + " blocks using " + pattern.mode() + "."));

        new BukkitRunnable() {
            int x = selection.getX1(), y = selection.getY1(), z = selection.getZ1();
            int iterated = 0, changed = 0, lastPct = -1;

            @Override public void run() {
                if (!player.isOnline() || op.isCancelled()) {
                    rollbackPartial(player, op, changed);
                    cancel();
                    return;
                }
                int processed = 0;
                while (processed < blocksPerTick && y <= selection.getY2()) {
                    var block = world.getBlockAt(x, y, z);
                    if (replaceFrom == null || block.getType() == replaceFrom) {
                        Material target = pattern.materialAt(x, y, z, random);
                        if (block.getType() != target) {
                            op.getUndoEntry().addSnapshot(new VSWorldEditData.BlockSnapshot(block.getLocation(), block.getType().name()));
                            block.setType(target, false);
                            changed++;
                        }
                    }
                    iterated++;
                    processed++;
                    x++;
                    if (x > selection.getX2()) { x = selection.getX1(); z++; }
                    if (z > selection.getZ2()) { z = selection.getZ1(); y++; }
                }
                if (y > selection.getY2()) {
                    activeOps.remove(uuid);
                    if (changed > 0) pushUndo(player, op.getUndoEntry());
                    player.sendMessage(fmt.success(pattern.mode() + " operation complete: &e" + changed + "&a blocks changed."));
                    plugin.getAuditLogger().logAdminAction(uuid, player.getName(), "VWE_" + op.getType().name(),
                        selection.getWorldName(), "changed=" + changed + " pattern=" + pattern.describe());
                    cancel();
                    return;
                }
                int pct = selection.getVolume() == 0 ? 100 : iterated * 100 / selection.getVolume();
                if (pct >= lastPct + 10) {
                    lastPct = pct - pct % 10;
                    player.sendMessage(fmt.info(pattern.mode() + " progress: &e" + pct + "%&7 (" + changed + " changed)"));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** A cancelled pattern operation restores every already changed block in reverse order. */
    private void rollbackPartial(Player player, VSWorldEditData.ActiveOperation op, int changed) {
        List<VSWorldEditData.BlockSnapshot> snapshots = op.getUndoEntry().getSnapshots();
        if (snapshots.isEmpty()) {
            activeOps.remove(op.getPlayerUuid());
            if (player.isOnline()) player.sendMessage(fmt.info("Operation cancelled before any block changed."));
            return;
        }
        if (player.isOnline()) player.sendMessage(fmt.warn("Cancelling: rolling back &e" + changed + "&7 changed blocks..."));
        new BukkitRunnable() {
            int index = snapshots.size() - 1;
            @Override public void run() {
                int processed = 0;
                while (processed++ < getBlocksPerTick() && index >= 0) restoreSnapshot(snapshots.get(index--));
                if (index < 0) {
                    activeOps.remove(op.getPlayerUuid());
                    if (player.isOnline()) player.sendMessage(fmt.success("Cancelled operation was fully rolled back."));
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void restoreSnapshot(VSWorldEditData.BlockSnapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.worldName);
        if (world == null) return;
        try {
            if (snapshot.previousTileState != null) {
                snapshot.previousTileState.update(true, false);
                return;
            }
            world.getBlockAt(snapshot.x, snapshot.y, snapshot.z)
                .setBlockData(Bukkit.createBlockData(snapshot.previousBlockData), false);
        } catch (IllegalArgumentException invalidData) {
            Material fallback = Material.matchMaterial(snapshot.previousType);
            if (fallback != null) world.getBlockAt(snapshot.x, snapshot.y, snapshot.z).setType(fallback, false);
        }
    }

    // ========================================================================
    // Batched execution with position list (single material)
    // ========================================================================

    private void executeOpWithPositions(VSWorldEditData.ActiveOperation op, Player player,
                                         List<int[]> positions, Material material) {
        activeOps.put(player.getUniqueId(), op);
        int blocksPerTick = getBlocksPerTick();
        World world = player.getWorld();

        player.sendMessage(fmt.info("Started &e" + op.getType().name() + "&7: " +
            positions.size() + " blocks. Processing..."));

        new BukkitRunnable() {
            int idx = 0, placedCount = 0, lastPct = -1;
            @Override
            public void run() {
                if (!player.isOnline() || op.isCancelled()) {
                    if (player.isOnline()) player.sendMessage(fmt.info("Operation cancelled after &e" + placedCount + "&7 blocks."));
                    activeOps.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                int processed = 0;
                while (processed < blocksPerTick && idx < positions.size()) {
                    int[] p = positions.get(idx);
                    Location loc = new Location(world, p[0], p[1], p[2]);
                    op.getUndoEntry().addSnapshot(new VSWorldEditData.BlockSnapshot(loc, loc.getBlock().getType().name()));
                    loc.getBlock().setType(material);
                    placedCount++; idx++; processed++;
                }
                if (idx >= positions.size()) {
                    activeOps.remove(player.getUniqueId());
                    if (placedCount > 0) pushUndo(player, op.getUndoEntry());
                    player.sendMessage(fmt.success(op.getType().name() + " complete: &e" + placedCount + "&a blocks placed."));
                    plugin.getLogger().info("[VWE] " + player.getName() + " " + op.getType().name() + " " + placedCount + " blocks");
                    cancel();
                    return;
                }
                int pct = idx * 100 / positions.size();
                if (pct >= lastPct + 10) {
                    lastPct = pct - (pct % 10);
                    player.sendMessage(fmt.info(op.getType().name() + " progress: &e" + pct + "%&7 (" + placedCount + " placed)"));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public boolean confirm(Player player) {
        UUID uuid = player.getUniqueId();
        var op = pendingOps.remove(uuid);
        if (op == null) {
            player.sendMessage(fmt.info("Nothing to confirm."));
            return false;
        }
        player.sendMessage(fmt.success("Confirmed. Starting " + op.getType().name() + "..."));
        plugin.getAuditLogger().logAdminAction(uuid, player.getName(), "VWE_CONFIRM",
            op.getType().name(), "blocks=" + op.getTotalBlocks());
        List<VSWorldEditData.BlockPlacement> placements = pendingPlacementOps.remove(uuid);
        PendingPatternOperation patternOperation = pendingPatternOps.remove(uuid);
        PendingPositionOperation positionOperation = pendingPositionOps.remove(uuid);
        if (patternOperation != null) {
            executePatternOperation(op, player, patternOperation.pattern(), patternOperation.replaceFrom());
        } else if (placements != null) {
            executeOperationWithPlacements(op, player, placements);
        } else if (positionOperation != null) {
            executeOpWithPositions(op, player, positionOperation.positions(), positionOperation.material());
        } else {
            executeOperation(op, player);
        }
        return true;
    }

    @Override
    public boolean cancelOperation(Player player) {
        UUID uuid = player.getUniqueId();

        // Cancel pending
        var pending = pendingOps.remove(uuid);
        if (pending != null) {
            pendingPlacementOps.remove(uuid);
            pendingPatternOps.remove(uuid);
            pendingPositionOps.remove(uuid);
            player.sendMessage(fmt.info("Pending operation cancelled."));
            plugin.getAuditLogger().logAdminAction(uuid, player.getName(), "VWE_CANCEL_PENDING",
                pending.getType().name(), "blocks=" + pending.getTotalBlocks());
            return true;
        }

        // Cancel active
        var active = activeOps.get(uuid);
        if (active != null) {
            active.cancel();
            player.sendMessage(fmt.info("Cancelling operation..."));
            plugin.getAuditLogger().logAdminAction(uuid, player.getName(), "VWE_CANCEL_ACTIVE",
                active.getType().name(), "processed=" + active.getUndoEntry().getSize());
            return true;
        }

        player.sendMessage(fmt.info("No operation to cancel."));
        return false;
    }

    @Override
    public boolean hasPendingConfirmation(Player player) {
        return pendingOps.containsKey(player.getUniqueId());
    }

    @Override
    public boolean hasActiveOperation(Player player) {
        return activeOps.containsKey(player.getUniqueId()) || undoing.contains(player.getUniqueId());
    }

    @Override
    public String getPendingDescription(Player player) {
        var op = pendingOps.get(player.getUniqueId());
        if (op == null) return null;
        PendingPatternOperation pattern = pendingPatternOps.get(player.getUniqueId());
        if (pattern != null) {
            return op.getType().name() + " " + op.getTotalBlocks() + " blocks using " + pattern.pattern().describe();
        }
        return op.getType().name() + " " + op.getTotalBlocks() + " blocks with " +
            op.getPrimaryMaterial().name();
    }

    // ========================================================================
    // Batched execution engine
    // ========================================================================

    private void executeOperation(VSWorldEditData.ActiveOperation op, Player player) {
        activeOps.put(player.getUniqueId(), op);

        int blocksPerTick = getBlocksPerTick();
        var sel = op.getSelection();
        World world = Bukkit.getWorld(sel.getWorldName());
        if (world == null) {
            player.sendMessage(fmt.error("World not found."));
            activeOps.remove(player.getUniqueId());
            return;
        }

        // For walls, outline, floor, ceiling — pre-compute the actual positions to iterate
        List<int[]> positions;
        boolean isWalls = op.getType() == VSWorldEditData.OperationType.WALLS;
        boolean isOutline = op.getType() == VSWorldEditData.OperationType.OUTLINE;
        boolean isFloor = op.getType() == VSWorldEditData.OperationType.FLOOR;
        boolean isCeiling = op.getType() == VSWorldEditData.OperationType.CEILING;

        if (isWalls) {
            positions = computeWallPositions(sel);
        } else if (isOutline) {
            positions = computeOutlinePositions(sel);
        } else if (isFloor) {
            positions = computeLayerPositions(sel, sel.getY1());
        } else if (isCeiling) {
            positions = computeLayerPositions(sel, sel.getY2());
        } else {
            positions = null; // Full cuboid for FILL/REPLACE
        }

        int totalIterations = positions != null ? positions.size() : sel.getVolume();
        op.setTotalBlocks(totalIterations);

        player.sendMessage(fmt.info("Started &e" + op.getType().name() + "&7: " +
            totalIterations + " blocks. Processing..."));

        new BukkitRunnable() {
            int idx = 0;
            int x = sel.getX1(), y = sel.getY1(), z = sel.getZ1();
            int placedCount = 0;
            int lastPct = -1;

            @Override
            public void run() {
                // Check player still online
                if (!player.isOnline()) {
                    op.cancel();
                    activeOps.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                if (op.isCancelled()) {
                    player.sendMessage(fmt.info("Operation cancelled after &e" + placedCount + "&7 blocks placed."));
                    activeOps.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                int processedThisTick = 0;
                while (processedThisTick < blocksPerTick) {
                    if (positions != null ? idx >= positions.size() : y > sel.getY2()) {
                        finish(player, op, placedCount);
                        cancel();
                        return;
                    }

                    Location loc;
                    if (positions != null) {
                        int[] p = positions.get(idx);
                        loc = new Location(world, p[0], p[1], p[2]);
                    } else {
                        loc = new Location(world, x, y, z);
                    }

                    boolean shouldPlace = switch (op.getType()) {
                        case FILL -> true;
                        case REPLACE -> loc.getBlock().getType() == op.getSecondaryMaterial();
                        case PATTERN_FILL, PATTERN_REPLACE -> true; // Executed by executePatternOperation.
                        case WALLS, OUTLINE, FLOOR, CEILING -> true; // Pre-filtered positions
                        case HOLLOW, CYLINDER, CIRCLE, SPHERE, HSPHERE, LINE -> true; // Not reached via this path
                    };

                    if (shouldPlace) {
                        op.getUndoEntry().addSnapshot(new VSWorldEditData.BlockSnapshot(
                            loc, loc.getBlock().getType().name()));
                        loc.getBlock().setType(op.getPrimaryMaterial());
                        placedCount++;
                    }

                    idx++;
                    processedThisTick++;

                    if (positions == null) {
                        x++;
                        if (x > sel.getX2()) { x = sel.getX1(); z++; }
                        if (z > sel.getZ2()) { z = sel.getZ1(); y++; }
                    }
                }

                int pct = totalIterations > 0 ? (idx * 100 / totalIterations) : 100;
                if (pct >= lastPct + 10) {
                    lastPct = pct - (pct % 10);
                    player.sendMessage(fmt.info(op.getType().name() + " progress: &e" + pct + "%&7 (" + placedCount + " placed)"));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private List<int[]> computeWallPositions(VSWorldEditData.Selection sel) {
        Set<String> seen = new HashSet<>();
        List<int[]> list = new ArrayList<>();
        int x1 = sel.getX1(), x2 = sel.getX2(), y1 = sel.getY1(), y2 = sel.getY2(), z1 = sel.getZ1(), z2 = sel.getZ2();
        // Floor + ceiling (full xz planes)
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            addIfNew(list, seen, x, y1, z); addIfNew(list, seen, x, y2, z);
        }
        // North/South walls (exclude top/bottom rows already covered)
        for (int x = x1; x <= x2; x++) for (int y = y1 + 1; y < y2; y++) {
            addIfNew(list, seen, x, y, z1); addIfNew(list, seen, x, y, z2);
        }
        // East/West walls (exclude top/bottom rows and north/south edges)
        for (int z = z1 + 1; z < z2; z++) for (int y = y1 + 1; y < y2; y++) {
            addIfNew(list, seen, x1, y, z); addIfNew(list, seen, x2, y, z);
        }
        return list;
    }

    private List<int[]> computeOutlinePositions(VSWorldEditData.Selection sel) {
        Set<String> seen = new HashSet<>();
        List<int[]> list = new ArrayList<>();
        int x1 = sel.getX1(), x2 = sel.getX2(), y1 = sel.getY1(), y2 = sel.getY2(), z1 = sel.getZ1(), z2 = sel.getZ2();
        // Bottom and top edges (4 edges each = 8 total)
        for (int y : new int[]{y1, y2}) {
            for (int x = x1; x <= x2; x++) { addIfNew(list, seen, x, y, z1); addIfNew(list, seen, x, y, z2); }
            for (int z = z1 + 1; z < z2; z++) { addIfNew(list, seen, x1, y, z); addIfNew(list, seen, x2, y, z); }
        }
        // Vertical pillars (4 corners, exclude top/bottom already covered)
        for (int x : new int[]{x1, x2}) for (int z : new int[]{z1, z2})
            for (int y = y1 + 1; y < y2; y++) addIfNew(list, seen, x, y, z);
        return list;
    }

    private void addIfNew(List<int[]> list, Set<String> seen, int x, int y, int z) {
        String key = x + "," + y + "," + z;
        if (seen.add(key)) list.add(new int[]{x, y, z});
    }

    private List<int[]> computeLayerPositions(VSWorldEditData.Selection sel, int yLevel) {
        List<int[]> positions = new ArrayList<>();
        for (int x = sel.getX1(); x <= sel.getX2(); x++)
            for (int z = sel.getZ1(); z <= sel.getZ2(); z++)
                positions.add(new int[]{x, yLevel, z});
        return positions;
    }

    private void finish(Player player, VSWorldEditData.ActiveOperation op, int placedCount) {
        activeOps.remove(player.getUniqueId());
        if (placedCount > 0) {
            pushUndo(player, op.getUndoEntry());
        }
        player.sendMessage(fmt.success(op.getType().name() + " complete: &e" + placedCount + "&a blocks placed."));
        plugin.getLogger().info("[VWE] " + player.getName() + " " + op.getType().name() +
            " " + placedCount + " blocks at " + op.getSelection());
    }

    /** Clean up all state for a player (called on quit). */
    public void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        pos1Map.remove(uuid);
        pos2Map.remove(uuid);
        pendingOps.remove(uuid);
        pendingPlacementOps.remove(uuid);
        pendingPatternOps.remove(uuid);
        pendingPositionOps.remove(uuid);
        var active = activeOps.get(uuid);
        if (active != null) active.cancel();
    }

    private record PendingPositionOperation(List<int[]> positions, Material material) {}
    private record PendingPatternOperation(BlockPattern pattern, Material replaceFrom) {}

    // ========================================================================
    // Undo
    // ========================================================================

    @Override
    public void pushUndo(Player player, VSWorldEditData.UndoEntry entry) {
        Deque<VSWorldEditData.UndoEntry> stack = undoStacks.computeIfAbsent(
            player.getUniqueId(), k -> new ArrayDeque<>());
        stack.push(entry);
        int max = getMaxUndoOperations();
        while (stack.size() > max) stack.removeLast();
    }

    @Override
    public int undo(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeOps.containsKey(uuid) || pendingOps.containsKey(uuid) || undoing.contains(uuid)) {
            player.sendMessage(fmt.error("Wait for the current operation, confirmation, or undo to finish."));
            return -1;
        }
        Deque<VSWorldEditData.UndoEntry> stack = undoStacks.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.sendMessage(fmt.info("Nothing to undo."));
            return -1;
        }

        var entry = stack.pop();
        List<VSWorldEditData.BlockSnapshot> snapshots = entry.getSnapshots();
        undoing.add(uuid);
        player.sendMessage(fmt.info("Undo started: &e" + snapshots.size() + "&7 blocks. Processing in batches..."));
        new BukkitRunnable() {
            int index = snapshots.size() - 1;
            int reverted = 0;
            @Override public void run() {
                int processed = 0;
                while (processed++ < getBlocksPerTick() && index >= 0) {
                    restoreSnapshot(snapshots.get(index--));
                    reverted++;
                }
                if (index < 0) {
                    undoing.remove(uuid);
                    if (player.isOnline()) player.sendMessage(fmt.success("Undo: &e" + reverted + "&a blocks reverted. &7(" + entry.getDescription() + ")"));
                    plugin.getAuditLogger().logAdminAction(uuid, player.getName(), "VWE_UNDO",
                        entry.getDescription(), "reverted=" + reverted);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return snapshots.size();
    }

    // ========================================================================
    // Limits (from config)
    // ========================================================================

    @Override public int getMaxBlocksPerOperation() {
        return plugin.getConfigManager().getVweMaxBlocks(); }
    @Override public int getBlocksPerTick() {
        return plugin.getConfigManager().getVweBlocksPerTick(); }
    @Override public int getMaxUndoOperations() {
        return plugin.getConfigManager().getVweMaxUndo(); }
    @Override public int getRequireConfirmationAbove() {
        return plugin.getConfigManager().getVweRequireConfirmAbove(); }

    private boolean requireConfirmationForAirOperations() {
        var config = plugin.getConfigManager().getConfig();
        return config.getBoolean("vsWorldEdit.safety.requireConfirmationForAirOperations",
            config.getBoolean("vsworldedit.safety.requireConfirmationForAirOperations", true));
    }
}
