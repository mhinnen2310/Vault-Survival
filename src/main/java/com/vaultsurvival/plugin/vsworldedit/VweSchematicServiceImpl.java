package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionVisualizationService;
import com.vaultsurvival.plugin.regions.RegionVisualizationSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads Paper's native vanilla structure-NBT format. WorldEdit .schem and
 * legacy MCEdit .schematic formats are deliberately rejected rather than
 * guessed or partially decoded.
 */
public final class VweSchematicServiceImpl implements VweSchematicService {
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.nbt",
        Pattern.CASE_INSENSITIVE);

    private final VaultSurvivalPlugin plugin;
    private final VSWorldEditService vwe;
    private final RegionVisualizationService visualization;
    private final Path directory;

    public VweSchematicServiceImpl(VaultSurvivalPlugin plugin, VSWorldEditService vwe) {
        this.plugin = plugin;
        this.vwe = vwe;
        this.visualization = plugin.getServiceRegistry().get(RegionVisualizationService.class);
        this.directory = plugin.getDataFolder().toPath().resolve("schematics").toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not create VWE schematic directory: " + exception.getMessage());
        }
    }

    @Override public Path getDirectory() { return directory; }

    @Override
    public List<AvailableFile> list() {
        if (!enabled() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> SAFE_FILE.matcher(path.getFileName().toString()).matches())
                .map(path -> new AvailableFile(path.getFileName().toString(), safeSize(path)))
                .filter(file -> file.fileBytes() >= 0)
                .sorted(Comparator.comparing(AvailableFile::fileName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list VWE schematics: " + exception.getMessage());
            return List.of();
        }
    }

    @Override
    public Result inspect(String fileName) {
        LoadResult loaded = load(fileName);
        return loaded.error == null
            ? new Result(true, describe(loaded.info), loaded.info, false)
            : Result.failure(loaded.error);
    }

    @Override
    public Result preview(Player player, String fileName) {
        LoadResult loaded = load(fileName);
        if (loaded.error != null) return Result.failure(loaded.error);
        Location origin = player.getLocation().getBlock().getLocation();
        int maxY = origin.getBlockY() + loaded.info.height() - 1;
        if (origin.getBlockY() < player.getWorld().getMinHeight() || maxY >= player.getWorld().getMaxHeight()) {
            return Result.failure("The preview would extend beyond this world's build height.");
        }
        if (visualization != null) {
            visualization.showBounds(player, new RegionVisualizationSession.Bounds(player.getWorld(),
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                origin.getBlockX() + loaded.info.width() - 1, maxY,
                origin.getBlockZ() + loaded.info.length() - 1),
                RegionData.RegionType.PROJECT_REGION, loaded.info.fileName(),
                RegionVisualizationSession.Mode.THIRTY_SECONDS, false);
        }
        plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
            "VWE_SCHEMATIC_PREVIEW", loaded.info.fileName(),
            "blocks=" + loaded.info.blockCount() + " dimensions=" + dimensions(loaded.info)
                + " world=" + player.getWorld().getName());
        return new Result(true, describe(loaded.info)
            + "\nTarget origin: your current block. Entities are never pasted.", loaded.info, false);
    }

    @Override
    public Result paste(Player player, String fileName) {
        LoadResult loaded = load(fileName);
        if (loaded.error != null) return Result.failure(loaded.error);
        Location origin = player.getLocation().getBlock().getLocation();
        boolean pasteAir = configBoolean("pasteAir", true);
        List<VSWorldEditData.SchematicPlacement> placements = new ArrayList<>(loaded.blocks.size());
        for (RelativeBlock block : loaded.blocks) {
            Material material = block.state.getType();
            if (material == Material.STRUCTURE_VOID || (!pasteAir && material.isAir())) continue;
            placements.add(new VSWorldEditData.SchematicPlacement(
                origin.getBlockX() + block.x, origin.getBlockY() + block.y,
                origin.getBlockZ() + block.z, block.state));
        }
        if (placements.isEmpty()) return Result.failure("The structure has no pasteable blocks with the current settings.");

        int threshold = Math.max(1, configInt("confirmationThresholdBlocks", 10_000));
        boolean forceConfirmation = configBoolean("alwaysRequireConfirmation", false)
            || loaded.info.volume() >= threshold || placements.size() >= threshold;
        boolean started = vwe.pasteSchematic(player, loaded.info.fileName(), placements, forceConfirmation);
        boolean pending = vwe.hasPendingConfirmation(player);
        if (!started && !pending) return Result.failure("The VWE engine rejected this paste. Check active operations and limits.");
        plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
            "VWE_SCHEMATIC_PASTE_REQUEST", loaded.info.fileName(),
            "blocks=" + placements.size() + " dimensions=" + dimensions(loaded.info)
                + " confirmation=" + pending + " entitiesIgnored=" + loaded.info.entityCount());
        return new Result(true, pending
            ? "Paste validated and awaiting confirmation."
            : "Paste validated and started through the VWE undo engine.", loaded.info, pending);
    }

    private LoadResult load(String requestedName) {
        if (!enabled()) return LoadResult.error("Schematic loading is disabled in config.yml.");
        String fileName = normalizeName(requestedName);
        if (fileName == null) {
            return LoadResult.error("Use a simple .nbt filename (letters, numbers, underscore or hyphen; maximum 64 characters).");
        }
        try {
            Files.createDirectories(directory);
            Path baseReal = directory.toRealPath();
            Path candidate = directory.resolve(fileName).normalize();
            if (!candidate.startsWith(directory) || !candidate.getParent().equals(directory)
                || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return LoadResult.error("Structure file not found in " + directory + ".");
            }
            Path real = candidate.toRealPath();
            if (!real.getParent().equals(baseReal)) return LoadResult.error("Structure path is outside the dedicated folder.");
            long bytes = Files.size(real);
            long maxBytes = Math.max(1L, configLong("maxFileSizeBytes", 16L * 1024 * 1024));
            if (bytes > maxBytes) return LoadResult.error("Structure file is " + bytes + " bytes (maximum: " + maxBytes + ").");

            Structure structure = Bukkit.getStructureManager().loadStructure(real.toFile());
            if (structure == null) return LoadResult.error("Paper could not decode this vanilla structure NBT file.");
            BlockVector size = structure.getSize();
            int width = size.getBlockX(), height = size.getBlockY(), length = size.getBlockZ();
            int maxDimension = Math.max(1, configInt("maxDimensionBlocks", 256));
            if (width <= 0 || height <= 0 || length <= 0
                || width > maxDimension || height > maxDimension || length > maxDimension) {
                return LoadResult.error("Structure dimensions must each be 1-" + maxDimension + " blocks.");
            }
            long volume = (long) width * height * length;
            int maxBlocks = Math.min(vwe.getMaxBlocksPerOperation(),
                Math.max(1, configInt("maxBlocks", 250_000)));
            if (volume > maxBlocks) return LoadResult.error("Structure volume is " + volume + " blocks (maximum: " + maxBlocks + ").");
            List<Palette> palettes = structure.getPalettes();
            if (palettes.isEmpty()) return LoadResult.error("Structure has no block palette.");
            Palette palette = palettes.getFirst();
            if (palette.getBlockCount() > maxBlocks) {
                return LoadResult.error("Structure palette has " + palette.getBlockCount() + " blocks (maximum: " + maxBlocks + ").");
            }
            List<RelativeBlock> blocks = new ArrayList<>(palette.getBlockCount());
            for (BlockState state : palette.getBlocks()) {
                int x = state.getX(), y = state.getY(), z = state.getZ();
                if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                    return LoadResult.error("Structure contains a block outside its declared dimensions.");
                }
                blocks.add(new RelativeBlock(x, y, z, state.copy()));
            }
            SchematicInfo info = new SchematicInfo(fileName, width, height, length,
                blocks.size(), structure.getEntityCount(), bytes);
            return new LoadResult(info, List.copyOf(blocks), null);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Rejected VWE structure '" + fileName + "': " + exception.getMessage());
            return LoadResult.error("Could not load that vanilla .nbt structure: " + exception.getMessage());
        }
    }

    private String normalizeName(String requested) {
        if (requested == null) return null;
        String trimmed = requested.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).endsWith(".nbt")) trimmed += ".nbt";
        return SAFE_FILE.matcher(trimmed).matches() ? trimmed : null;
    }

    private boolean enabled() { return configBoolean("enabled", true); }
    private int configInt(String key, int fallback) {
        return plugin.getConfigManager().getConfig().getInt("vsworldedit.schematics." + key, fallback);
    }
    private long configLong(String key, long fallback) {
        return plugin.getConfigManager().getConfig().getLong("vsworldedit.schematics." + key, fallback);
    }
    private boolean configBoolean(String key, boolean fallback) {
        return plugin.getConfigManager().getConfig().getBoolean("vsworldedit.schematics." + key, fallback);
    }
    private long safeSize(Path path) {
        try { return Files.size(path); }
        catch (IOException ignored) { return -1; }
    }
    private String describe(SchematicInfo info) {
        return info.fileName() + "\nDimensions: " + dimensions(info) + "\nBlocks: " + info.blockCount()
            + "\nEntities ignored: " + info.entityCount() + "\nFile size: " + info.fileBytes() + " bytes";
    }
    private String dimensions(SchematicInfo info) {
        return info.width() + " x " + info.height() + " x " + info.length();
    }

    private record RelativeBlock(int x, int y, int z, BlockState state) { }
    private record LoadResult(SchematicInfo info, List<RelativeBlock> blocks, String error) {
        private static LoadResult error(String message) { return new LoadResult(null, List.of(), message); }
    }
}
