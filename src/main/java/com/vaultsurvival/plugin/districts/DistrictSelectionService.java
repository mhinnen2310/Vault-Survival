package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.rail.RailService;
import com.vaultsurvival.plugin.rail.RailServiceImpl;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chunk-only district claim selection with a server-side particle boundary overlay. */
public final class DistrictSelectionService implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final MessageFormatter fmt;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private BukkitTask overlayTask;

    public DistrictSelectionService(VaultSurvivalPlugin plugin, DistrictService districts) {
        this.plugin = plugin;
        this.districts = districts;
        this.fmt = plugin.getMessageFormatter();
        this.wandKey = new NamespacedKey(plugin, "district_selection_wand");
    }

    public void start(String name, Player player) {
        if (districts.getPlayerDistrict(player.getUniqueId()) != null) {
            player.sendMessage(fmt.error("You are already in a district. Use /district expand after your district levels up."));
            return;
        }
        begin(player, name, false, null, plugin.getConfigManager().getDistrictInitialClaimChunks());
    }

    public void startExpansion(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null) {
            player.sendMessage(fmt.error("You are not in a district."));
            return;
        }
        if (!districts.canManageDevelopment(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires MAYOR, CO_MAYOR, or BUILDER."));
            return;
        }
        DistrictData.ChunkClaim claim = districts.getClaim(district.getId());
        if (claim == null) {
            player.sendMessage(fmt.error("This legacy district must be migrated by staff before it can expand."));
            return;
        }
        int limit = districts.getClaimChunkLimit(district);
        if (claim.chunkCount() >= limit) {
            player.sendMessage(fmt.info("Your district already uses its level-based claim limit of &e" + limit + " chunks."));
            return;
        }
        begin(player, district.getName(), true, claim, limit);
    }

    public void startMarketZone(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null) {
            player.sendMessage(fmt.error("You must be in a district to select a market zone."));
            return;
        }
        if (!districts.canCreateMerchantNpc(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires the MERCHANT, CO_MAYOR, or MAYOR district role."));
            return;
        }
        DistrictData.ChunkClaim claim = districts.getClaim(district.getId());
        if (claim == null) {
            player.sendMessage(fmt.error("This legacy district has no chunk claim yet."));
            return;
        }
        int limit = Math.max(1, Math.min(claim.chunkCount(), (int) Math.ceil(claim.chunkCount()
            * plugin.getConfigManager().getConfig().getDouble("districts.marketZone.maxPercentOfDistrict", 0.40))));
        cancel(player, false);
        Selection selection = new Selection(district.getName(), claim.worldName(), false, null, limit, district);
        selections.put(player.getUniqueId(), selection);
        giveWand(player);
        player.sendMessage(fmt.success("Market-zone selection started for &e" + district.getName() + "&a."));
        player.sendMessage(fmt.info("Select up to &e" + limit + " chunks&7 inside your district, then use &e/district marketzone confirm&7."));
        updateActionbar(player, selection);
    }

    public void startStationPlatform(Player player, int stationId) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        DistrictData.ChunkClaim claim = district == null ? null : districts.getClaim(district.getId());
        if (district == null || claim == null || !districts.canRequestStation(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires a claimed district and MAYOR, CO_MAYOR, or DIPLOMAT role."));
            return;
        }
        try {
            RailService rail = plugin.getServiceRegistry().get(RailService.class);
            var station = rail.getStation(stationId);
            if (station == null || station.getDistrictId() != district.getId() || !station.getRequesterUuid().equals(player.getUniqueId())) {
                player.sendMessage(fmt.error("Station not found or not yours to configure."));
                return;
            }
        } catch (RuntimeException unavailable) { player.sendMessage(fmt.error("Rail service is unavailable.")); return; }
        int limit = Math.max(1, Math.min(claim.chunkCount(), (int) Math.ceil(claim.chunkCount()
            * plugin.getConfigManager().getConfig().getDouble("districts.stationPlatform.maxPercentOfDistrict", 0.25))));
        cancel(player, false);
        Selection selection = new Selection(district.getName(), claim.worldName(), false, null, limit, null, district, stationId);
        selections.put(player.getUniqueId(), selection);
        giveWand(player);
        player.sendMessage(fmt.success("Station-platform selection started. Select up to &e" + limit + " chunks&a, then use &e/district station confirm&a."));
        updateActionbar(player, selection);
    }

    public void startSpawnCityClaim(Player player) {
        if (!player.hasPermission("vaultsurvival.spawncity.admin")) { player.sendMessage(fmt.permissionDenied()); return; }
        cancel(player, false);
        Selection selection = new Selection("Spawn City", player.getWorld().getName(), false, null,
            plugin.getConfigManager().getSpawnClaimMaxChunks(), null, null, -1, true);
        selections.put(player.getUniqueId(), selection);
        giveWand(player, plugin.getConfigManager().getSpawnClaimWandMaterial(), "&6&lSpawn City Claim Wand");
        player.sendMessage(fmt.success("Spawn City chunk claim started. Select up to &e" + selection.limit + " chunks&a, then use &e/spawncity claim confirm&a."));
        updateActionbar(player, selection);
    }

    private void begin(Player player, String name, boolean expansion, DistrictData.ChunkClaim existing, int limit) {
        cancel(player, false);
        Selection selection = new Selection(name, player.getWorld().getName(), expansion, existing, limit);
        if (existing != null) {
            for (int x = existing.minChunkX(); x <= existing.maxChunkX(); x++) {
                for (int z = existing.minChunkZ(); z <= existing.maxChunkZ(); z++) selection.chunks.add(new ChunkKey(x, z));
            }
        }
        selections.put(player.getUniqueId(), selection);
        giveWand(player);
        player.sendMessage(fmt.success((expansion ? "District expansion" : "District claim") + " started for &e" + name + "&a."));
        player.sendMessage(fmt.info("Use the District Selection Wand: click a chunk to add it, sneak-click to remove it."));
        player.sendMessage(fmt.info("Select " + (expansion ? "up to" : "exactly") + " &e" + limit + " chunks&7, then use &e/district confirm&7. Use &e/district cancel&7 to abort."));
        updateActionbar(player, selection);
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), expansion ? "DISTRICT_CLAIM_EXPAND_START" : "DISTRICT_CLAIM_START",
            "DISTRICT", name, "chunkLimit=" + limit);
    }

    public void confirm(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) {
            player.sendMessage(fmt.error("No district chunk selection is active."));
            return;
        }
        if (!selection.worldName.equals(player.getWorld().getName())) {
            player.sendMessage(fmt.error("Return to the selected world before confirming."));
            return;
        }
        if ((!selection.isMarketZone() && !selection.isStationPlatform() && !selection.isSpawnCityClaim() && !selection.expansion && selection.chunks.size() != selection.limit)
            || (selection.expansion && (selection.chunks.size() <= selection.existing.chunkCount() || selection.chunks.size() > selection.limit))) {
            player.sendMessage(fmt.error(selection.expansion
                ? "Select more than " + selection.existing.chunkCount() + " and no more than " + selection.limit + " chunks."
                : "Select exactly " + selection.limit + " chunks before confirming."));
            return;
        }
        DistrictData.ChunkClaim claim = asRectangle(selection);
        if (claim == null) {
            player.sendMessage(fmt.error("Selected chunks must form one filled rectangle. Fill any gaps before confirming."));
            return;
        }
        boolean completed;
        if (selection.isSpawnCityClaim()) {
            completed = setSpawnCityClaim(player, claim);
        } else if (selection.isStationPlatform()) {
            completed = setStationPlatform(player, selection.stationId, claim);
        } else if (selection.isMarketZone()) {
            completed = createMarketZone(player, selection.marketDistrict, claim);
            if (completed) player.sendMessage(fmt.success("Market zone updated: &e" + claim.chunkCount() + " chunks&a."));
        } else if (selection.expansion) {
            DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
            completed = district != null && districts.updateClaim(district, player.getUniqueId(), claim);
            if (completed) player.sendMessage(fmt.success("District claim expanded to &e" + claim.chunkCount() + " chunks&a."));
        } else {
            completed = districts.apply(player, selection.name, claim) != null;
        }
        if (!completed) {
            player.sendMessage(fmt.error("The claim could not be confirmed. Your selection remains active so you can adjust it."));
            return;
        }
        cancel(player, false);
    }

    public void cancel(Player player) {
        if (selections.containsKey(player.getUniqueId())) {
            cancel(player, true);
            player.sendMessage(fmt.info("District chunk selection cancelled."));
        } else {
            removeWands(player);
            player.sendMessage(fmt.info("No district selection was active."));
        }
    }

    private void cancel(Player player, boolean audited) {
        Selection previous = selections.remove(player.getUniqueId());
        removeWands(player);
        if (audited && previous != null) {
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_CLAIM_CANCEL", "DISTRICT", previous.name,
                "selected=" + previous.chunks.size());
        }
    }

    public void showStatus(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) {
            player.sendMessage(fmt.info("No district chunk selection is active."));
            return;
        }
        player.sendMessage(fmt.header("District Chunk Selection"));
        player.sendMessage(fmt.info("District: &e" + selection.name + " &7| Selected: &e" + selection.chunks.size() + "/" + selection.limit));
        player.sendMessage(fmt.info("Mode: &e" + (selection.isSpawnCityClaim() ? "SPAWN CITY" : selection.isStationPlatform() ? "STATION PLATFORM" : selection.isMarketZone() ? "MARKET ZONE" : selection.expansion ? "EXPAND" : "CREATE") + " &7| Rectangle: " + (asRectangle(selection) == null ? "&cNo" : "&aYes")));
    }

    public void showDistrictBorders(Player player) {
        DistrictData.District district = districts.getAllDistricts().stream()
            .filter(d -> d.getStatus() == DistrictData.DistrictStatus.ACTIVE && d.getWorldName().equals(player.getWorld().getName()))
            .filter(d -> {
                DistrictData.ChunkClaim claim = districts.getClaim(d.getId());
                return claim != null && player.getChunk().getX() >= claim.minChunkX() && player.getChunk().getX() <= claim.maxChunkX()
                    && player.getChunk().getZ() >= claim.minChunkZ() && player.getChunk().getZ() <= claim.maxChunkZ();
            }).findFirst().orElseGet(() -> districts.getAllDistricts().stream()
                .filter(d -> d.getStatus() == DistrictData.DistrictStatus.ACTIVE && d.getWorldName().equals(player.getWorld().getName()))
                .min(Comparator.comparingDouble(d -> Math.hypot(player.getLocation().getBlockX() - d.getCenterX(), player.getLocation().getBlockZ() - d.getCenterZ())))
                .orElse(null));
        if (district == null || districts.getClaim(district.getId()) == null) {
            player.sendMessage(fmt.error("No chunk-based district border is nearby."));
            return;
        }
        DistrictData.ChunkClaim claim = districts.getClaim(district.getId());
        drawRectangle(player, claim, Color.YELLOW, 2);
        player.sendMessage(fmt.info("Showing &e" + district.getName() + "&7 borders: &e" + claim.chunkCount() + " chunks &7(" + claim.minBlockX() + ", " + claim.minBlockZ() + " to " + claim.maxBlockX() + ", " + claim.maxBlockZ() + ")."));
    }

    public void showMarketZoneBorders(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canCreateMerchantNpc(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires the MERCHANT, CO_MAYOR, or MAYOR district role."));
            return;
        }
        try {
            RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
            String regionName = "district_market_" + district.getId();
            RegionData.Region zone = regions.getAllRegions().stream()
                .filter(region -> region.getName().equalsIgnoreCase(regionName))
                .findFirst().orElse(null);
            if (zone == null) {
                player.sendMessage(fmt.error("This district does not have a market zone yet."));
                return;
            }
            if (!zone.getWorldName().equals(player.getWorld().getName())) {
                player.sendMessage(fmt.error("Travel to &e" + zone.getWorldName() + "&c to view this market zone."));
                return;
            }
            DistrictData.ChunkClaim claim = new DistrictData.ChunkClaim(zone.getWorldName(),
                Math.floorDiv(Math.min(zone.getX1(), zone.getX2()), 16),
                Math.floorDiv(Math.min(zone.getZ1(), zone.getZ2()), 16),
                Math.floorDiv(Math.max(zone.getX1(), zone.getX2()), 16),
                Math.floorDiv(Math.max(zone.getZ1(), zone.getZ2()), 16));
            drawRectangle(player, claim, Color.LIME, 2);
            player.sendMessage(fmt.info("Showing &a" + district.getName() + " market-zone&7 borders: &e" + claim.chunkCount() + " chunks&7."));
        } catch (RuntimeException error) {
            player.sendMessage(fmt.error("Market-zone borders are unavailable right now."));
        }
    }

    public void startOverlay() {
        if (overlayTask != null) return;
        overlayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Selection> entry : selections.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                Selection selection = entry.getValue();
                if (player == null || now - selection.lastTouched > plugin.getConfigManager().getDistrictSelectionTimeoutMinutes() * 60_000L) {
                    if (player != null) {
                        removeWands(player);
                        player.sendMessage(fmt.warn("District chunk selection expired and its wand was removed."));
                    }
                    selections.remove(entry.getKey());
                    continue;
                }
                if (plugin.getConfigManager().isDistrictSelectionOverlayEnabled()) {
                    DistrictData.ChunkClaim rectangle = asRectangle(selection);
                    if (rectangle != null) drawRectangle(player, rectangle, Color.AQUA, 4);
                    else drawChunkMarkers(player, selection);
                }
            }
        }, 20L, 20L);
    }

    public void shutdown() {
        if (overlayTask != null) overlayTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) removeWands(player);
        selections.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) return;
        Player player = event.getPlayer();
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) {
            event.setCancelled(true);
            removeWands(player);
            player.sendMessage(fmt.warn("This district wand is no longer active and was removed."));
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Chunk chunk = event.getClickedBlock().getChunk();
        if (!chunk.getWorld().getName().equals(selection.worldName)) return;
        ChunkKey key = new ChunkKey(chunk.getX(), chunk.getZ());
        boolean remove = player.isSneaking();
        if (remove) {
            if (selection.existing != null && isInClaim(key, selection.existing)) {
                player.sendMessage(fmt.error("Existing district chunks cannot be removed during an expansion."));
                return;
            }
            if (!selection.chunks.remove(key)) {
                player.sendMessage(fmt.info("That chunk was not selected."));
                return;
            }
        } else {
            if (selection.chunks.contains(key)) {
                player.sendMessage(fmt.info("That chunk is already selected. Sneak-click it to remove it."));
                return;
            }
            if (selection.chunks.size() >= selection.limit) {
                player.sendMessage(fmt.error("You reached the " + selection.limit + "-chunk limit."));
                return;
            }
            if ((selection.isMarketZone() || selection.isStationPlatform()) && !isInClaim(key, districts.getClaim(selection.ownerDistrict().getId()))) {
                player.sendMessage(fmt.error("Selected chunks must be inside your district claim."));
                return;
            }
            selection.chunks.add(key);
        }
        selection.lastTouched = System.currentTimeMillis();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, remove ? 0.7f : 1.3f);
        updateActionbar(player, selection);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer(), true); }
    @EventHandler public void onKick(PlayerKickEvent event) { cancel(event.getPlayer(), true); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        if (selections.containsKey(event.getPlayer().getUniqueId())) {
            cancel(event.getPlayer(), true);
            event.getPlayer().sendMessage(fmt.warn("District chunk selection reset after changing worlds."));
        }
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (!selections.containsKey(event.getEntity().getUniqueId())) return;
        event.getDrops().removeIf(this::isWand);
        cancel(event.getEntity(), true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { removeWands(event.getPlayer()); }

    private void giveWand(Player player) {
        giveWand(player, plugin.getConfigManager().getConfig().getString("districts.selection.wandMaterial", "GOLDEN_AXE"), "&b&lDistrict Selection Wand");
    }

    private void giveWand(Player player, String materialName, String displayName) {
        removeWands(player);
        Material material;
        try { material = Material.valueOf(materialName.toUpperCase()); }
        catch (IllegalArgumentException ignored) { material = Material.GOLDEN_AXE; }
        ItemStack wand = new ItemStack(material);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(fmt.deserialize(displayName));
        meta.lore(java.util.List.of(fmt.deserialize("&7Click: add chunk"), fmt.deserialize("&7Sneak-click: remove chunk"), fmt.deserialize("&8/district confirm to finish")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(wand);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void removeWands(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isWand(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
        if (isWand(player.getInventory().getItemInOffHand())) player.getInventory().setItemInOffHand(null);
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private DistrictData.ChunkClaim asRectangle(Selection selection) {
        if (selection.chunks.isEmpty()) return null;
        int minX = selection.chunks.stream().mapToInt(ChunkKey::x).min().orElseThrow();
        int maxX = selection.chunks.stream().mapToInt(ChunkKey::x).max().orElseThrow();
        int minZ = selection.chunks.stream().mapToInt(ChunkKey::z).min().orElseThrow();
        int maxZ = selection.chunks.stream().mapToInt(ChunkKey::z).max().orElseThrow();
        if ((maxX - minX + 1) * (maxZ - minZ + 1) != selection.chunks.size()) return null;
        return new DistrictData.ChunkClaim(selection.worldName, minX, minZ, maxX, maxZ);
    }

    private boolean isInClaim(ChunkKey key, DistrictData.ChunkClaim claim) {
        return key.x >= claim.minChunkX() && key.x <= claim.maxChunkX() && key.z >= claim.minChunkZ() && key.z <= claim.maxChunkZ();
    }

    private boolean createMarketZone(Player player, DistrictData.District district, DistrictData.ChunkClaim claim) {
        if (district == null || claim == null || claim.chunkCount() == 0 || !districts.canCreateMerchantNpc(player.getUniqueId(), district)) return false;
        try {
            RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
            String regionName = "district_market_" + district.getId();
            regions.getAllRegions().stream()
                .filter(region -> region.getName().equalsIgnoreCase(regionName))
                .map(RegionData.Region::getId).toList().forEach(regions::deleteRegion);
            var world = player.getWorld();
            var region = regions.createRegion(regionName, RegionData.RegionType.DISTRICT_PUBLIC, claim.worldName(),
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight(), claim.maxBlockZ(), 30);
            if (region == null) return false;
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_MARKET_ZONE_SET", "DISTRICT",
                String.valueOf(district.getId()), "chunks=" + claim.chunkCount());
            return true;
        } catch (RuntimeException ex) {
            player.sendMessage(fmt.error("Region service is unavailable; market zone was not changed."));
            return false;
        }
    }

    private boolean setStationPlatform(Player player, int stationId, DistrictData.ChunkClaim claim) {
        try {
            RailService rail = plugin.getServiceRegistry().get(RailService.class);
            if (rail instanceof RailServiceImpl implementation) return implementation.setPlatformChunks(stationId, player, claim);
        } catch (RuntimeException ignored) { }
        player.sendMessage(fmt.error("Rail service is unavailable; platform was not changed."));
        return false;
    }

    private boolean setSpawnCityClaim(Player player, DistrictData.ChunkClaim claim) {
        if (!player.hasPermission("vaultsurvival.spawncity.admin")) return false;
        try {
            RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
            regions.getAllRegions().stream().filter(region -> region.getName().equalsIgnoreCase("spawn_city_claim"))
                .map(RegionData.Region::getId).toList().forEach(regions::deleteRegion);
            var world = player.getWorld();
            var region = regions.createRegion("spawn_city_claim", RegionData.RegionType.SPAWN_PUBLIC, claim.worldName(),
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight(), claim.maxBlockZ(), 50);
            if (region == null) return false;
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "SPAWN_CITY_CHUNK_CLAIM_SET", "REGION", String.valueOf(region.getId()), "chunks=" + claim.chunkCount());
            player.sendMessage(fmt.success("Spawn City claim set to &e" + claim.chunkCount() + " chunks&a."));
            return true;
        } catch (RuntimeException unavailable) {
            player.sendMessage(fmt.error("Region service is unavailable; Spawn City claim was not changed."));
            return false;
        }
    }

    private void updateActionbar(Player player, Selection selection) {
        player.sendActionBar(Component.text("Chunks: " + selection.chunks.size() + "/" + selection.limit
            + " | " + (asRectangle(selection) == null ? "fill rectangle" : "rectangle ready") + " | confirm"));
    }

    private void drawChunkMarkers(Player player, Selection selection) {
        int previewLimit = Math.max(1, plugin.getConfigManager().getConfig().getInt("districts.selection.overlay.maxPreviewChunks", 64));
        int shown = 0;
        for (ChunkKey key : selection.chunks) {
            if (shown++ >= previewLimit) break;
            int x = (key.x << 4) + 8;
            int z = (key.z << 4) + 8;
            int y = player.getWorld().getHighestBlockYAt(x, z) + 1;
            Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.8f);
            player.spawnParticle(Particle.DUST, x + .5, y + .5, z + .5, 5, .5, .3, .5, 0, dust);
            drawVerticalMarker(player, (key.x << 4), (key.z << 4), dust);
            drawVerticalMarker(player, (key.x << 4) + 15, (key.z << 4) + 15, dust);
        }
    }

    private void drawRectangle(Player player, DistrictData.ChunkClaim claim, Color color, int step) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.8f);
        for (int x = claim.minBlockX(); x <= claim.maxBlockX(); x += step) {
            particleAtSurface(player, x, claim.minBlockZ(), dust);
            particleAtSurface(player, x, claim.maxBlockZ(), dust);
        }
        for (int z = claim.minBlockZ(); z <= claim.maxBlockZ(); z += step) {
            particleAtSurface(player, claim.minBlockX(), z, dust);
            particleAtSurface(player, claim.maxBlockX(), z, dust);
        }
        drawVerticalMarker(player, claim.minBlockX(), claim.minBlockZ(), dust);
        drawVerticalMarker(player, claim.minBlockX(), claim.maxBlockZ(), dust);
        drawVerticalMarker(player, claim.maxBlockX(), claim.minBlockZ(), dust);
        drawVerticalMarker(player, claim.maxBlockX(), claim.maxBlockZ(), dust);
    }

    private void particleAtSurface(Player player, int x, int z, Particle.DustOptions dust) {
        int y = player.getWorld().getHighestBlockYAt(x, z) + 1;
        player.spawnParticle(Particle.DUST, x + .5, y + .5, z + .5, 2, .05, .05, .05, 0, dust);
    }

    private void drawVerticalMarker(Player player, int x, int z, Particle.DustOptions dust) {
        int y = player.getWorld().getHighestBlockYAt(x, z) + 1;
        for (int height = 0; height <= 8; height += 2) {
            player.spawnParticle(Particle.DUST, x + .5, y + height + .5, z + .5, 2, .08, .08, .08, 0, dust);
        }
    }

    private record ChunkKey(int x, int z) { }
    private static final class Selection {
        private final String name;
        private final String worldName;
        private final boolean expansion;
        private final DistrictData.ChunkClaim existing;
        private final int limit;
        private final DistrictData.District marketDistrict;
        private final DistrictData.District stationDistrict;
        private final int stationId;
        private final boolean spawnCityClaim;
        private final LinkedHashSet<ChunkKey> chunks = new LinkedHashSet<>();
        private long lastTouched = System.currentTimeMillis();

        private Selection(String name, String worldName, boolean expansion, DistrictData.ChunkClaim existing, int limit) {
            this(name, worldName, expansion, existing, limit, null);
        }

        private Selection(String name, String worldName, boolean expansion, DistrictData.ChunkClaim existing, int limit, DistrictData.District marketDistrict) {
            this(name, worldName, expansion, existing, limit, marketDistrict, null, -1);
        }

        private Selection(String name, String worldName, boolean expansion, DistrictData.ChunkClaim existing, int limit,
                          DistrictData.District marketDistrict, DistrictData.District stationDistrict, int stationId) {
            this(name, worldName, expansion, existing, limit, marketDistrict, stationDistrict, stationId, false);
        }

        private Selection(String name, String worldName, boolean expansion, DistrictData.ChunkClaim existing, int limit,
                          DistrictData.District marketDistrict, DistrictData.District stationDistrict, int stationId, boolean spawnCityClaim) {
            this.name = name;
            this.worldName = worldName;
            this.expansion = expansion;
            this.existing = existing;
            this.limit = limit;
            this.marketDistrict = marketDistrict;
            this.stationDistrict = stationDistrict;
            this.stationId = stationId;
            this.spawnCityClaim = spawnCityClaim;
        }

        private boolean isMarketZone() { return marketDistrict != null; }
        private boolean isStationPlatform() { return stationDistrict != null; }
        private boolean isSpawnCityClaim() { return spawnCityClaim; }
        private DistrictData.District ownerDistrict() { return marketDistrict != null ? marketDistrict : stationDistrict; }
    }
}
