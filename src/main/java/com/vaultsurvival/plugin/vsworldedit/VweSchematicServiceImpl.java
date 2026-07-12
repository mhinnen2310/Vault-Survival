package com.vaultsurvival.plugin.vsworldedit;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionVisualizationService;
import com.vaultsurvival.plugin.regions.RegionVisualizationSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads vanilla structure NBT and Sponge/WorldEdit schematics. Entity and
 * block-entity payloads are intentionally ignored; block data remains exact.
 */
public final class VweSchematicServiceImpl implements VweSchematicService {
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.(?:nbt|schem|schematic)",
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
            Material material = block.blockData.getMaterial();
            if (material == Material.STRUCTURE_VOID || (!pasteAir && material.isAir())) continue;
            placements.add(block.state == null
                ? new VSWorldEditData.SchematicPlacement(origin.getBlockX() + block.x, origin.getBlockY() + block.y,
                    origin.getBlockZ() + block.z, block.blockData)
                : new VSWorldEditData.SchematicPlacement(origin.getBlockX() + block.x, origin.getBlockY() + block.y,
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

            String extension = extension(fileName);
            if (extension.equals("schem")) return loadSponge(real, fileName, bytes);
            if (extension.equals("schematic")) return loadLegacy(real, fileName, bytes);
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
                blocks.add(new RelativeBlock(x, y, z, state.copy(), state.getBlockData().clone()));
            }
            SchematicInfo info = new SchematicInfo(fileName, width, height, length,
                blocks.size(), structure.getEntityCount(), bytes);
            return new LoadResult(info, List.copyOf(blocks), null);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Rejected VWE structure '" + fileName + "': " + exception.getMessage());
            return LoadResult.error("Could not load that schematic: " + exception.getMessage());
        }
    }

    private String normalizeName(String requested) {
        if (requested == null) return null;
        String trimmed = requested.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).matches(".*\\.(nbt|schem|schematic)$")) trimmed += ".nbt";
        return SAFE_FILE.matcher(trimmed).matches() ? trimmed : null;
    }

    private String extension(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }

    private LoadResult loadSponge(Path path, String fileName, long bytes) throws IOException {
        net.querz.nbt.tag.Tag<?> raw = net.querz.nbt.io.NBTUtil.read(path.toFile()).getTag();
        if (!(raw instanceof net.querz.nbt.tag.CompoundTag root)) return LoadResult.error("Sponge schematic root is not a compound.");
        net.querz.nbt.tag.CompoundTag schematic = root.getCompoundTag("Schematic");
        if (schematic != null) root = schematic; // Sponge v3 wrapper
        int width = number(root, "Width"), height = number(root, "Height"), length = number(root, "Length");
        String dimensionsError = validateDimensions(width, height, length);
        if (dimensionsError != null) return LoadResult.error(dimensionsError);
        net.querz.nbt.tag.CompoundTag paletteTag = root.getCompoundTag("Palette");
        byte[] encoded = root.getByteArray("BlockData").orElse(null);
        if (paletteTag == null || encoded == null) return LoadResult.error("Sponge schematic has no Palette or BlockData.");
        Map<Integer, BlockData> palette = new HashMap<>();
        for (Map.Entry<String, net.querz.nbt.tag.Tag<?>> entry : paletteTag.entrySet()) {
            if (!(entry.getValue() instanceof net.querz.nbt.tag.NumberTag<?> number)) continue;
            try { palette.put(number.asInt(), Bukkit.createBlockData(entry.getKey())); }
            catch (IllegalArgumentException invalid) { return LoadResult.error("Unknown block data in palette: " + entry.getKey()); }
        }
        int volume = Math.multiplyExact(Math.multiplyExact(width, height), length);
        int maxBlocks = Math.min(vwe.getMaxBlocksPerOperation(), Math.max(1, configInt("maxBlocks", 250_000)));
        if (volume > maxBlocks) return LoadResult.error("Schematic volume is " + volume + " blocks (maximum: " + maxBlocks + ").");
        int[] ids = decodeVarInts(encoded, volume);
        if (ids == null) return LoadResult.error("Sponge BlockData is truncated or has more entries than its dimensions.");
        List<RelativeBlock> blocks = new ArrayList<>(volume);
        for (int index = 0; index < volume; index++) {
            BlockData data = palette.get(ids[index]);
            if (data == null) return LoadResult.error("Sponge palette index " + ids[index] + " is missing.");
            int x = index % width, z = (index / width) % length, y = index / (width * length);
            blocks.add(new RelativeBlock(x, y, z, null, data.clone()));
        }
        return new LoadResult(new SchematicInfo(fileName, width, height, length, blocks.size(), 0, bytes), List.copyOf(blocks), null);
    }

    private LoadResult loadLegacy(Path path, String fileName, long bytes) throws IOException {
        net.querz.nbt.tag.Tag<?> raw = net.querz.nbt.io.NBTUtil.read(path.toFile()).getTag();
        if (!(raw instanceof net.querz.nbt.tag.CompoundTag root)) return LoadResult.error("Legacy schematic root is not a compound.");
        int width = number(root, "Width"), height = number(root, "Height"), length = number(root, "Length");
        String dimensionsError = validateDimensions(width, height, length);
        if (dimensionsError != null) return LoadResult.error(dimensionsError);
        byte[] blocksRaw = root.getByteArray("Blocks").orElse(null), dataRaw = root.getByteArray("Data").orElse(null);
        int volume = Math.multiplyExact(Math.multiplyExact(width, height), length);
        if (blocksRaw == null || blocksRaw.length != volume) return LoadResult.error("Legacy Blocks length does not match its dimensions.");
        if (dataRaw == null || dataRaw.length != volume) dataRaw = new byte[volume];
        byte[] add = root.getByteArray("AddBlocks").orElse(null);
        List<RelativeBlock> blocks = new ArrayList<>(volume);
        for (int index = 0; index < volume; index++) {
            int id = blocksRaw[index] & 0xff;
            if (add != null && index / 2 < add.length) id |= ((index & 1) == 0 ? add[index / 2] & 0x0f : (add[index / 2] >>> 4) & 0x0f) << 8;
            BlockData blockData = legacyBlockData(id, dataRaw[index]);
            if (blockData == null) return LoadResult.error("Unsupported legacy block id " + id + " at index " + index + ". Convert this file to Sponge .schem for exact modern mappings.");
            int x = index % width, z = (index / width) % length, y = index / (width * length);
            blocks.add(new RelativeBlock(x, y, z, null, blockData));
        }
        return new LoadResult(new SchematicInfo(fileName, width, height, length, blocks.size(), 0, bytes), List.copyOf(blocks), null);
    }

    private BlockData legacyBlockData(int id, byte data) {
        String name = switch (id) {
            case 0 -> "air"; case 1 -> "stone"; case 2 -> "grass_block"; case 3 -> "dirt";
            case 4 -> "cobblestone"; case 5 -> "oak_planks"; case 7 -> "bedrock"; case 8, 9 -> "water";
            case 10, 11 -> "lava"; case 12 -> "sand"; case 13 -> "gravel"; case 17 -> "oak_log";
            case 18 -> "oak_leaves"; case 20 -> "glass"; case 24 -> "sandstone"; case 35 -> "white_wool";
            case 41 -> "gold_block"; case 42 -> "iron_block"; case 43, 44 -> "stone_slab"; case 45 -> "bricks";
            case 46 -> "tnt"; case 47 -> "bookshelf"; case 48 -> "mossy_cobblestone"; case 49 -> "obsidian";
            case 50 -> "torch"; case 53 -> "oak_stairs"; case 54 -> "chest"; case 57 -> "diamond_block";
            case 58 -> "crafting_table"; case 61, 62 -> "furnace"; case 67 -> "cobblestone_stairs";
            case 79 -> "ice"; case 80 -> "snow_block"; case 82 -> "clay"; case 85 -> "oak_fence";
            case 87 -> "netherrack"; case 88 -> "soul_sand"; case 89 -> "glowstone"; case 98 -> "stone_bricks";
            case 103 -> "melon"; case 112 -> "nether_bricks"; case 121 -> "end_stone"; case 129 -> "emerald_ore";
            case 133 -> "emerald_block"; case 152 -> "redstone_block"; case 155 -> "quartz_block"; case 159 -> "white_terracotta";
            case 168 -> "prismarine"; case 169 -> "sea_lantern"; case 172 -> "terracotta"; case 173 -> "coal_block";
            default -> null;
        };
        return name == null ? null : Bukkit.createBlockData(name);
    }

    private int number(net.querz.nbt.tag.CompoundTag tag, String key) {
        Number number = tag.getNumber(key); return number == null ? -1 : number.intValue();
    }

    private String validateDimensions(int width, int height, int length) {
        int max = Math.max(1, configInt("maxDimensionBlocks", 256));
        return width <= 0 || height <= 0 || length <= 0 || width > max || height > max || length > max
            ? "Schematic dimensions must each be 1-" + max + " blocks." : null;
    }

    private int[] decodeVarInts(byte[] bytes, int expected) {
        int[] values = new int[expected]; int count = 0, value = 0, shift = 0;
        for (byte raw : bytes) {
            value |= (raw & 0x7f) << shift;
            if ((raw & 0x80) == 0) {
                if (count >= expected) return null;
                values[count++] = value; value = 0; shift = 0;
            } else if ((shift += 7) > 28) return null;
        }
        return count == expected && shift == 0 ? values : null;
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

    private record RelativeBlock(int x, int y, int z, BlockState state, BlockData blockData) { }
    private record LoadResult(SchematicInfo info, List<RelativeBlock> blocks, String error) {
        private static LoadResult error(String message) { return new LoadResult(null, List.of(), message); }
    }
}
