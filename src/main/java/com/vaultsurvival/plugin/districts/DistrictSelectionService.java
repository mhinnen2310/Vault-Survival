package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.rail.RailService;
import com.vaultsurvival.plugin.rail.RailServiceImpl;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.regions.RegionVisualizationService;
import com.vaultsurvival.plugin.regions.RegionVisualizationSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Exact-block district and district-owned subregion selection. */
public final class DistrictSelectionService implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final MessageFormatter fmt;
    private final NamespacedKey wandKey;
    private final RegionVisualizationService visualization;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private BukkitTask overlayTask;

    public DistrictSelectionService(VaultSurvivalPlugin plugin, DistrictService districts) {
        this.plugin = plugin;
        this.districts = districts;
        this.fmt = plugin.getMessageFormatter();
        this.wandKey = new NamespacedKey(plugin, "district_selection_wand");
        this.visualization = plugin.getServiceRegistry().get(RegionVisualizationService.class);
    }

    public void start(String name, Player player) {
        if (districts.getPlayerDistrict(player.getUniqueId()) != null) {
            player.sendMessage(fmt.error("You are already in a district. Use /district expand after your district levels up."));
            return;
        }
        begin(player, name, false, null, plugin.getConfigManager().getDistrictInitialClaimBlocks());
    }

    public void startExpansion(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null) { player.sendMessage(fmt.error("You are not in a district.")); return; }
        if (!districts.canManageDevelopment(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires MAYOR, CO_MAYOR, or BUILDER."));
            return;
        }
        DistrictData.BlockClaim claim = districts.getClaim(district.getId());
        if (claim == null) { player.sendMessage(fmt.error("This legacy district must be migrated by staff before it can expand.")); return; }
        long limit = districts.getClaimBlockLimit(district);
        if (claim.areaBlocks() >= limit) {
            player.sendMessage(fmt.info("Your district already uses its level-based limit of &e" + limit + " blocks&7."));
            return;
        }
        begin(player, district.getName(), true, claim, limit);
    }

    public void startMarketZone(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canCreateMerchantNpc(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires a district and the MERCHANT, CO_MAYOR, or MAYOR role."));
            return;
        }
        DistrictData.BlockClaim claim = districts.getClaim(district.getId());
        if (claim == null) { player.sendMessage(fmt.error("This legacy district has no block claim yet.")); return; }
        long limit = Math.max(1L, (long) Math.floor(claim.areaBlocks()
            * plugin.getConfigManager().getConfig().getDouble("districts.marketZone.maxPercentOfDistrict", 0.40)));
        startOwnedSelection(player, new Selection(district.getName(), claim.worldName(), false, null, limit, district, null, -1, false),
            "Market-zone block selection started", "/district marketzone confirm");
    }

    public void startStationPlatform(Player player, int stationId) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        DistrictData.BlockClaim claim = district == null ? null : districts.getClaim(district.getId());
        if (district == null || claim == null || !districts.canRequestStation(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires a claimed district and MAYOR, CO_MAYOR, or DIPLOMAT role."));
            return;
        }
        try {
            RailService rail = plugin.getServiceRegistry().get(RailService.class);
            var station = rail.getStation(stationId);
            if (station == null || station.getDistrictId() != district.getId()) {
                player.sendMessage(fmt.error("Station not found or not yours to configure."));
                return;
            }
        } catch (RuntimeException unavailable) { player.sendMessage(fmt.error("Rail service is unavailable.")); return; }
        long limit = Math.max(1L, (long) Math.floor(claim.areaBlocks()
            * plugin.getConfigManager().getConfig().getDouble("districts.stationPlatform.maxPercentOfDistrict", 0.25)));
        startOwnedSelection(player, new Selection(district.getName(), claim.worldName(), false, null, limit, null, district, stationId, false),
            "Station-platform block selection started", "/district station confirm");
    }

    public void startRestrictedLand(Player player, String landName) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        DistrictData.BlockClaim claim = district == null ? null : districts.getClaim(district.getId());
        if (district == null || claim == null || !district.hasRole(player.getUniqueId(), DistrictData.DistrictRole.MAYOR)) {
            player.sendMessage(fmt.error("Only the district MAYOR can select restricted land.")); return;
        }
        long limit = Math.max(1L, (long)Math.floor(claim.areaBlocks()
            * plugin.getConfigManager().getConfig().getDouble("districts.restrictedLand.maxPercentOfDistrict", 0.25)));
        Selection selection = new Selection(landName, claim.worldName(), false, null, limit, null, null, -1, false);
        selection.restrictedDistrict = district;
        startOwnedSelection(player, selection, "Restricted-land block selection started", "/district restricted confirm");
    }

    public void startSpawnCityClaim(Player player) {
        if (!player.hasPermission("vaultsurvival.spawncity.admin")) { player.sendMessage(fmt.permissionDenied()); return; }
        cancel(player, false);
        Selection selection = new Selection("Spawn City", player.getWorld().getName(), false, null,
            plugin.getConfigManager().getSpawnClaimMaxBlocks(), null, null, -1, true);
        selections.put(player.getUniqueId(), selection);
        giveWand(player, plugin.getConfigManager().getSpawnClaimWandMaterial(), "&6&lSpawn City Block Selection Wand");
        player.sendMessage(fmt.success("Spawn City block selection started. Maximum area: &e" + selection.limit + " blocks&a."));
        player.sendMessage(fmt.info("Left-click block 1, right-click block 2, then use &e/spawncity claim confirm&7."));
        updateActionbar(player, selection);
    }

    private void begin(Player player, String name, boolean expansion, DistrictData.BlockClaim existing, long limit) {
        cancel(player, false);
        Selection selection = new Selection(name, player.getWorld().getName(), expansion, existing, limit, null, null, -1, false);
        if (existing != null) {
            selection.pos1 = new BlockPoint(existing.minBlockX(), existing.minBlockZ());
            selection.pos2 = new BlockPoint(existing.maxBlockX(), existing.maxBlockZ());
        }
        selections.put(player.getUniqueId(), selection);
        giveWand(player);
        player.sendMessage(fmt.success((expansion ? "District expansion" : "District block claim") + " started for &e" + name + "&a."));
        player.sendMessage(fmt.info("Left-click block 1 and right-click block 2. Sneak-click that button to clear its corner."));
        player.sendMessage(fmt.info((expansion ? "Maximum" : "Required") + " horizontal area: &e" + limit + " blocks&7. Then use &e/district confirm&7."));
        updateActionbar(player, selection);
        if (selection.complete()) refreshSelection(player, selection);
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), expansion ? "DISTRICT_CLAIM_EXPAND_START" : "DISTRICT_CLAIM_START",
            "DISTRICT", name, "blockAreaLimit=" + limit);
    }

    private void startOwnedSelection(Player player, Selection selection, String message, String confirmCommand) {
        cancel(player, false);
        selections.put(player.getUniqueId(), selection);
        giveWand(player);
        player.sendMessage(fmt.success(message + " for &e" + selection.name + "&a."));
        player.sendMessage(fmt.info("Left-click block 1, right-click block 2, then use &e" + confirmCommand + "&7. Maximum area: &e" + selection.limit + " blocks&7."));
        updateActionbar(player, selection);
    }

    public void confirm(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) { player.sendMessage(fmt.error("No district block selection is active.")); return; }
        if (!selection.worldName.equals(player.getWorld().getName())) { player.sendMessage(fmt.error("Return to the selected world before confirming.")); return; }
        DistrictData.BlockClaim claim = asRectangle(selection);
        if (claim == null) { player.sendMessage(fmt.error("Select both block corners before confirming.")); return; }

        if (selection.expansion) {
            if (!claim.contains(selection.existing) || claim.areaBlocks() <= selection.existing.areaBlocks() || claim.areaBlocks() > selection.limit) {
                player.sendMessage(fmt.error("The new rectangle must contain the entire old claim and have an area between "
                    + (selection.existing.areaBlocks() + 1) + " and " + selection.limit + " blocks."));
                return;
            }
        } else if (!selection.isMarketZone() && !selection.isStationPlatform() && !selection.isRestrictedLand() && !selection.isSpawnCityClaim()
            && (claim.areaBlocks() < plugin.getConfigManager().getConfig().getLong("districts.selection.requiredAreaBlocks", 2500)
                || claim.areaBlocks() > selection.limit)) {
            long minimum = plugin.getConfigManager().getConfig().getLong("districts.selection.requiredAreaBlocks", 2500);
            player.sendMessage(fmt.error("A new district claim must contain between " + minimum + " and "
                + selection.limit + " horizontal blocks."));
            return;
        } else if ((selection.isMarketZone() || selection.isStationPlatform() || selection.isRestrictedLand() || selection.isSpawnCityClaim())
            && claim.areaBlocks() > selection.limit) {
            player.sendMessage(fmt.error("The selected area is " + claim.areaBlocks() + " blocks; the maximum is " + selection.limit + "."));
            return;
        }

        if ((selection.isMarketZone() || selection.isStationPlatform() || selection.isRestrictedLand())) {
            DistrictData.BlockClaim owner = districts.getClaim(selection.ownerDistrict().getId());
            if (owner == null || !owner.contains(claim)) {
                player.sendMessage(fmt.error("Both corners and the complete selected rectangle must be inside your district claim."));
                return;
            }
        }

        boolean completed;
        if (selection.isSpawnCityClaim()) completed = setSpawnCityClaim(player, claim);
        else if (selection.isStationPlatform()) completed = setStationPlatform(player, selection.stationId, claim);
        else if (selection.isRestrictedLand()) completed = setRestrictedLand(player, selection.restrictedDistrict, selection.name, claim);
        else if (selection.isMarketZone()) completed = createMarketZone(player, selection.marketDistrict, claim);
        else if (selection.expansion) {
            DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
            completed = district != null && districts.updateClaim(district, player.getUniqueId(), claim);
        } else completed = districts.apply(player, selection.name, claim) != null;

        if (!completed) {
            player.sendMessage(fmt.error("The block region could not be confirmed. Your selection remains active."));
            return;
        }
        if (selection.isMarketZone()) player.sendMessage(fmt.success("Market zone updated: &e" + describe(claim) + "&a."));
        else if (selection.expansion) player.sendMessage(fmt.success("District claim expanded to &e" + describe(claim) + "&a."));
        cancel(player, false);
    }

    public void cancel(Player player) {
        if (selections.containsKey(player.getUniqueId())) {
            cancel(player, true);
            player.sendMessage(fmt.info("District block selection cancelled."));
        } else {
            removeWands(player);
            player.sendMessage(fmt.info("No district selection was active."));
        }
    }

    private void cancel(Player player, boolean audited) {
        Selection previous = selections.remove(player.getUniqueId());
        removeWands(player);
        if (audited && previous != null) plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_CLAIM_CANCEL",
            "DISTRICT", previous.name, previous.complete() ? "areaBlocks=" + asRectangle(previous).areaBlocks() : "incomplete");
        visualization.hide(player.getUniqueId());
    }

    public void showStatus(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) { player.sendMessage(fmt.info("No district block selection is active.")); return; }
        DistrictData.BlockClaim claim = asRectangle(selection);
        player.sendMessage(fmt.header("District Block Selection"));
        player.sendMessage(fmt.info("District: &e" + selection.name + " &7| Position 1: " + (selection.pos1 == null ? "&cunset" : "&a" + selection.pos1)
            + " &7| Position 2: " + (selection.pos2 == null ? "&cunset" : "&a" + selection.pos2)));
        player.sendMessage(fmt.info("Area: &e" + (claim == null ? 0 : claim.areaBlocks()) + "&7/&e" + selection.limit + " blocks &7| Size: &e"
            + (claim == null ? "incomplete" : claim.widthBlocks() + "x" + claim.depthBlocks())));
    }

    public void showDistrictBorders(Player player) {
        DistrictData.District district = districts.getAllDistricts().stream()
            .filter(d -> d.getStatus() == DistrictData.DistrictStatus.ACTIVE && d.getWorldName().equals(player.getWorld().getName()))
            .filter(d -> {
                DistrictData.BlockClaim claim = districts.getClaim(d.getId());
                return claim != null && claim.contains(player.getLocation().getBlockX(), player.getLocation().getBlockZ());
            }).findFirst().orElseGet(() -> districts.getAllDistricts().stream()
                .filter(d -> d.getStatus() == DistrictData.DistrictStatus.ACTIVE && d.getWorldName().equals(player.getWorld().getName()))
                .min(Comparator.comparingDouble(d -> Math.hypot(player.getLocation().getBlockX() - d.getCenterX(), player.getLocation().getBlockZ() - d.getCenterZ())))
                .orElse(null));
        if (district == null || districts.getClaim(district.getId()) == null) {
            player.sendMessage(fmt.error("No block-based district border is nearby."));
            return;
        }
        DistrictData.BlockClaim claim = districts.getClaim(district.getId());
        visualizeClaim(player, claim, RegionData.RegionType.DISTRICT, district.getName(), RegionVisualizationSession.Mode.THIRTY_SECONDS);
        openBorderDialog(player, district.getName() + " borders", describe(claim) + " | " + claim.minBlockX() + ", " + claim.minBlockZ()
            + " to " + claim.maxBlockX() + ", " + claim.maxBlockZ(), "district borders");
    }

    public void showMarketZoneBorders(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canCreateMerchantNpc(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires the MERCHANT, CO_MAYOR, or MAYOR district role.")); return;
        }
        try {
            String regionName = "district_market_" + district.getId();
            RegionData.Region zone = plugin.getServiceRegistry().get(RegionService.class).getAllRegions().stream()
                .filter(region -> region.getName().equalsIgnoreCase(regionName)).findFirst().orElse(null);
            if (zone == null) { player.sendMessage(fmt.error("This district does not have a market zone yet.")); return; }
            if (!zone.getWorldName().equals(player.getWorld().getName())) { player.sendMessage(fmt.error("Travel to &e" + zone.getWorldName() + "&c to view this market zone.")); return; }
            visualization.showRegion(player, zone, RegionVisualizationSession.Mode.THIRTY_SECONDS, false);
            openBorderDialog(player, district.getName() + " market zone", "Dense 3D market-zone border is visible for 30 seconds.", "district marketzone borders");
        } catch (RuntimeException error) { player.sendMessage(fmt.error("Market-zone borders are unavailable right now.")); }
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
                        visualization.hide(player.getUniqueId());
                        player.sendMessage(fmt.warn("District block selection expired and its wand was removed."));
                    }
                    selections.remove(entry.getKey());
                } else if (selection.complete()) {
                    renderPortalOutline(player, asRectangle(selection));
                }
            }
        }, 20L, 20L);
    }

    public void shutdown() {
        if (overlayTask != null) overlayTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeWands(player);
            if (selections.containsKey(player.getUniqueId())) visualization.hide(player.getUniqueId());
        }
        selections.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) return;
        Player player = event.getPlayer();
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) { event.setCancelled(true); removeWands(player); return; }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (!event.getClickedBlock().getWorld().getName().equals(selection.worldName)) return;

        boolean first = event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (player.isSneaking()) {
            if (first) selection.pos1 = null; else selection.pos2 = null;
        } else {
            BlockPoint point = new BlockPoint(event.getClickedBlock().getX(), event.getClickedBlock().getZ());
            BlockPoint previous = first ? selection.pos1 : selection.pos2;
            if (first) selection.pos1 = point; else selection.pos2 = point;
            DistrictData.BlockClaim candidate = asRectangle(selection);
            if (candidate != null && candidate.areaBlocks() > selection.limit) {
                if (first) selection.pos1 = previous; else selection.pos2 = previous;
                player.sendMessage(fmt.error("That corner would create " + candidate.areaBlocks() + " blocks; the limit is " + selection.limit + "."));
                return;
            }
        }
        selection.lastTouched = System.currentTimeMillis();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, player.isSneaking() ? 0.7f : 1.3f);
        updateActionbar(player, selection);
        refreshSelection(player, selection);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer(), true); }
    @EventHandler public void onKick(PlayerKickEvent event) { cancel(event.getPlayer(), true); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        if (selections.containsKey(event.getPlayer().getUniqueId())) {
            cancel(event.getPlayer(), true);
            event.getPlayer().sendMessage(fmt.warn("District block selection reset after changing worlds."));
        }
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (!selections.containsKey(event.getEntity().getUniqueId())) return;
        event.getDrops().removeIf(this::isWand);
        cancel(event.getEntity(), true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { removeWands(event.getPlayer()); }

    private void giveWand(Player player) {
        giveWand(player, plugin.getConfigManager().getConfig().getString("districts.selection.wandMaterial", "GOLDEN_AXE"), "&b&lDistrict Block Selection Wand");
    }

    private void giveWand(Player player, String materialName, String displayName) {
        removeWands(player);
        Material material;
        try { material = Material.valueOf(materialName.toUpperCase()); } catch (IllegalArgumentException ignored) { material = Material.GOLDEN_AXE; }
        ItemStack wand = new ItemStack(material);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(fmt.deserialize(displayName));
        meta.lore(java.util.List.of(fmt.deserialize("&7Left-click: set block corner 1"), fmt.deserialize("&7Right-click: set block corner 2"),
            fmt.deserialize("&7Sneak-click: clear that corner"), fmt.deserialize("&8/district confirm to finish")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void removeWands(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) if (isWand(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        if (isWand(player.getInventory().getItemInOffHand())) player.getInventory().setItemInOffHand(null);
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private DistrictData.BlockClaim asRectangle(Selection selection) {
        if (!selection.complete()) return null;
        return new DistrictData.BlockClaim(selection.worldName, selection.pos1.x, selection.pos1.z, selection.pos2.x, selection.pos2.z);
    }

    private boolean createMarketZone(Player player, DistrictData.District district, DistrictData.BlockClaim claim) {
        if (district == null || claim == null || !districts.canCreateMerchantNpc(player.getUniqueId(), district)) return false;
        try {
            RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
            String regionName = "district_market_" + district.getId();
            regions.getAllRegions().stream().filter(region -> region.getName().equalsIgnoreCase(regionName))
                .map(RegionData.Region::getId).toList().forEach(regions::deleteRegion);
            var world = player.getWorld();
            var region = regions.createRegion(regionName, RegionData.RegionType.DISTRICT_MARKET, claim.worldName(),
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight() - 1, claim.maxBlockZ(), 30);
            if (region == null) return false;
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_MARKET_ZONE_SET", "DISTRICT",
                String.valueOf(district.getId()), "areaBlocks=" + claim.areaBlocks());
            return true;
        } catch (RuntimeException ex) { player.sendMessage(fmt.error("Region service is unavailable; market zone was not changed.")); return false; }
    }

    private boolean setStationPlatform(Player player, int stationId, DistrictData.BlockClaim claim) {
        try {
            RailService rail = plugin.getServiceRegistry().get(RailService.class);
            if (rail instanceof RailServiceImpl implementation) return implementation.setPlatformBlocks(stationId, player, claim);
        } catch (RuntimeException ignored) { }
        player.sendMessage(fmt.error("Rail service is unavailable; platform was not changed."));
        return false;
    }

    private boolean setRestrictedLand(Player player, DistrictData.District district, String name, DistrictData.BlockClaim claim) {
        try {
            DistrictRestrictedLandService service = plugin.getServiceRegistry().get(DistrictRestrictedLandService.class);
            var result = service.create(player, name, claim);
            player.sendMessage(result.success() ? fmt.success(result.message()) : fmt.error(result.message()));
            return result.success();
        } catch (RuntimeException unavailable) { player.sendMessage(fmt.error("Restricted-land service is unavailable.")); return false; }
    }

    private boolean setSpawnCityClaim(Player player, DistrictData.BlockClaim claim) {
        if (!player.hasPermission("vaultsurvival.spawncity.admin")) return false;
        try {
            RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
            regions.getAllRegions().stream().filter(region -> region.getName().equalsIgnoreCase("spawn_city_claim"))
                .map(RegionData.Region::getId).toList().forEach(regions::deleteRegion);
            var world = player.getWorld();
            var region = regions.createRegion("spawn_city_claim", RegionData.RegionType.SPAWN_CITY, claim.worldName(),
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight() - 1, claim.maxBlockZ(), 50);
            if (region == null) return false;
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "SPAWN_CITY_BLOCK_CLAIM_SET", "REGION",
                String.valueOf(region.getId()), "areaBlocks=" + claim.areaBlocks());
            player.sendMessage(fmt.success("Spawn City claim set to &e" + describe(claim) + "&a."));
            return true;
        } catch (RuntimeException unavailable) { player.sendMessage(fmt.error("Region service is unavailable; Spawn City claim was not changed.")); return false; }
    }

    private void updateActionbar(Player player, Selection selection) {
        DistrictData.BlockClaim claim = asRectangle(selection);
        player.sendActionBar(Component.text("Block corners: " + (selection.pos1 == null ? "1 missing" : "1 set") + " / "
            + (selection.pos2 == null ? "2 missing" : "2 set") + " | area " + (claim == null ? 0 : claim.areaBlocks()) + "/" + selection.limit + " | confirm"));
    }

    public boolean updateVisualization(Player player, String mode, Boolean sideGrid, Boolean floorGrid) {
        if (mode != null) {
            RegionVisualizationSession.Mode parsed = RegionVisualizationSession.Mode.parse(mode);
            if (parsed == null || !visualization.setMode(player.getUniqueId(), parsed)) return false;
        }
        if (sideGrid != null && !visualization.setSideGrid(player.getUniqueId(), sideGrid)) return false;
        return floorGrid == null || visualization.setFloorGrid(player.getUniqueId(), floorGrid);
    }

    public void hideVisualization(Player player) { visualization.hide(player.getUniqueId()); }
    public void showVisualizationControls(Player player, String status) { openBorderDialog(player, "District Border Controls", status, "district borders"); }

    private void refreshSelection(Player player, Selection selection) {
        if (!selection.complete()) { visualization.hide(player.getUniqueId()); return; }
        DistrictData.BlockClaim bounds = asRectangle(selection);
        if (plugin.getConfigManager().isDistrictSelectionOverlayEnabled())
            visualizeClaim(player, bounds, selectionType(selection), selection.name, RegionVisualizationSession.Mode.WHILE_EDITING);
        renderPortalOutline(player, bounds);
    }

    private RegionData.RegionType selectionType(Selection selection) {
        if (selection.isSpawnCityClaim()) return RegionData.RegionType.SPAWN_CITY;
        if (selection.isStationPlatform()) return RegionData.RegionType.STATION_PLATFORM;
        if (selection.isMarketZone()) return RegionData.RegionType.DISTRICT_MARKET;
        if (selection.isRestrictedLand()) return RegionData.RegionType.CUSTOM;
        return RegionData.RegionType.DISTRICT;
    }

    private void visualizeClaim(Player player, DistrictData.BlockClaim claim, RegionData.RegionType type, String name, RegionVisualizationSession.Mode mode) {
        visualization.showBounds(player, new RegionVisualizationSession.Bounds(player.getWorld(), claim.minBlockX(), player.getWorld().getMinHeight(),
            claim.minBlockZ(), claim.maxBlockX(), player.getWorld().getMaxHeight() - 1, claim.maxBlockZ()), type, name, mode, false);
    }

    /** Purple portal particles distinguish an editable selection from a confirmed district border. */
    private void renderPortalOutline(Player player, DistrictData.BlockClaim claim) {
        if (claim == null || player.getWorld() == null || !player.getWorld().getName().equals(claim.worldName())) return;
        int y = Math.max(player.getWorld().getMinHeight() + 1, Math.min(player.getLocation().getBlockY() + 1, player.getWorld().getMaxHeight() - 2));
        long perimeter = Math.max(1L, 2L * claim.widthBlocks() + 2L * claim.depthBlocks());
        int step = (int) Math.max(1L, (long) Math.ceil(perimeter / 300.0));
        for (int x = claim.minBlockX(); x <= claim.maxBlockX(); x += step) {
            portal(player, x + .5, y, claim.minBlockZ() + .5);
            portal(player, x + .5, y, claim.maxBlockZ() + .5);
        }
        for (int z = claim.minBlockZ(); z <= claim.maxBlockZ(); z += step) {
            portal(player, claim.minBlockX() + .5, y, z + .5);
            portal(player, claim.maxBlockX() + .5, y, z + .5);
        }
        for (int dy = 0; dy <= 5; dy++) {
            portal(player, claim.minBlockX() + .5, y + dy, claim.minBlockZ() + .5);
            portal(player, claim.maxBlockX() + .5, y + dy, claim.maxBlockZ() + .5);
        }
    }

    private void portal(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.PORTAL, x, y, z, 1, 0.03, 0.08, 0.03, 0.01);
    }

    private String describe(DistrictData.BlockClaim claim) {
        return claim.widthBlocks() + "x" + claim.depthBlocks() + " blocks (" + claim.areaBlocks() + " area)";
    }

    private void openBorderDialog(Player player, String title, String body, String reopenCommand) {
        if (!plugin.getServiceRegistry().has(DialogService.class)) { player.sendMessage(fmt.info(body)); return; }
        plugin.getServiceRegistry().get(DialogService.class).openResult(player, title, body, java.util.List.of(
            DialogMenuItem.item("10 seconds", "Keep this border visible for 10 seconds.", "district borders showtime 10", null, Material.CLOCK),
            DialogMenuItem.item("30 seconds", "Keep this border visible for 30 seconds.", "district borders showtime 30", null, Material.CLOCK),
            DialogMenuItem.item("Persistent", "Keep this border visible until hidden.", "district borders showtime persistent", null, Material.RECOVERY_COMPASS),
            DialogMenuItem.item("Side grid ON", "Enable wall grid lines.", "district borders grid on", null, Material.IRON_BARS),
            DialogMenuItem.item("Floor grid ON", "Enable floor grid lines.", "district borders floorgrid on", null, Material.IRON_TRAPDOOR),
            DialogMenuItem.item("Hide", "Stop the border visualization.", "district borders hide", null, Material.INK_SAC),
            DialogMenuItem.item("Refresh", "Render this border again.", reopenCommand, null, Material.ENDER_EYE)));
    }

    private record BlockPoint(int x, int z) {
        @Override public String toString() { return x + "," + z; }
    }

    private static final class Selection {
        private final String name;
        private final String worldName;
        private final boolean expansion;
        private final DistrictData.BlockClaim existing;
        private final long limit;
        private final DistrictData.District marketDistrict;
        private final DistrictData.District stationDistrict;
        private final int stationId;
        private final boolean spawnCityClaim;
        private DistrictData.District restrictedDistrict;
        private BlockPoint pos1;
        private BlockPoint pos2;
        private long lastTouched = System.currentTimeMillis();

        private Selection(String name, String worldName, boolean expansion, DistrictData.BlockClaim existing, long limit,
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

        private boolean complete() { return pos1 != null && pos2 != null; }
        private boolean isMarketZone() { return marketDistrict != null; }
        private boolean isStationPlatform() { return stationDistrict != null; }
        private boolean isSpawnCityClaim() { return spawnCityClaim; }
        private boolean isRestrictedLand() { return restrictedDistrict != null; }
        private DistrictData.District ownerDistrict() { return marketDistrict != null ? marketDistrict : stationDistrict != null ? stationDistrict : restrictedDistrict; }
    }
}
