package com.vaultsurvival.plugin.crime;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.damage.DamageData;
import com.vaultsurvival.plugin.damage.DamageService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;

/**
 * Listener that automatically logs crimes when valuable blocks are stolen.
 * Runs at MONITOR priority (after DamageListener has already processed the break).
 */
public class CrimeListener implements Listener {

    private final VaultSurvivalPlugin plugin;
    private final DamageService damageService;
    private final CrimeService crimeService;
    private final DistrictService districtService;

    public CrimeListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.damageService = plugin.getServiceRegistry().get(DamageService.class);
        this.crimeService = plugin.getServiceRegistry().get(CrimeService.class);
        this.districtService = plugin.getServiceRegistry().get(DistrictService.class);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (damageService == null || crimeService == null) return;

        var block = event.getBlock();
        var player = event.getPlayer();

        // Only auto-log if this is tracked damage in a district (non-member breaking blocks)
        if (!damageService.shouldTrackDamage(block.getLocation(), player.getUniqueId())) return;

        int districtId = damageService.getDistrictIdForLocation(block.getLocation());
        if (districtId < 0) return;

        // Check if the block is valuable — if so, auto-log as theft
        var blockClass = DamageData.DamageRecord.classify(block.getType());
        if (blockClass == DamageData.BlockClass.VALUABLE) {
            String location = block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
            crimeService.logCrime(
                player.getUniqueId(),
                districtId,
                CrimeData.CrimeType.THEFT,
                CrimeData.CrimeSeverity.MODERATE,
                block.getType().name(),
                location
            );
        } else if (blockClass == DamageData.BlockClass.CONTAINER) {
            // Breaking into containers is also a crime
            String location = block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
            crimeService.logCrime(
                player.getUniqueId(),
                districtId,
                CrimeData.CrimeType.THEFT,
                CrimeData.CrimeSeverity.MINOR,
                block.getType().name(),
                location
            );
        }

        DistrictData.District district = districtService.getDistrict(districtId);
        if (district != null
            && !district.isMember(player.getUniqueId())
            && districtService.isLawActive(district, DistrictData.LawKey.VISITOR_BLOCK_DAMAGE_ILLEGAL)) {
            createEvidence(player, district, DistrictData.LawKey.VISITOR_BLOCK_DAMAGE_ILLEGAL,
                "BLOCK_BREAK", block.getLocation(), CrimeData.CrimeSeverity.MODERATE,
                "block=" + block.getType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        int districtId = damageService.getDistrictIdForLocation(event.getBlock().getLocation());
        if (districtId < 0) return;
        DistrictData.District district = districtService.getDistrict(districtId);
        if (district == null || district.isMember(event.getPlayer().getUniqueId())) return;
        DistrictData.LawKey law = switch (event.getBlock().getType()) {
            case FIRE, LAVA, LAVA_BUCKET -> DistrictData.LawKey.FIRE_LAVA_PLACEMENT_ILLEGAL;
            case TNT, TNT_MINECART, END_CRYSTAL -> DistrictData.LawKey.EXPLOSION_USE_ILLEGAL;
            default -> DistrictData.LawKey.VISITOR_BLOCK_PLACEMENT_ILLEGAL;
        };
        if (districtService.isLawActive(district, law)) {
            createEvidence(event.getPlayer(), district, law, "BLOCK_PLACE", event.getBlock().getLocation(),
                CrimeData.CrimeSeverity.MINOR, "block=" + event.getBlock().getType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        DistrictData.District district = findDistrict(event.getEntity().getLocation());
        if (district == null) return;
        boolean attackerVisitor = !district.isMember(attacker.getUniqueId());
        if (attackerVisitor && districtService.isLawActive(district, DistrictData.LawKey.VISITOR_PVP_ILLEGAL)) {
            createEvidence(attacker, district, DistrictData.LawKey.VISITOR_PVP_ILLEGAL, "PVP_ATTACK",
                victim.getLocation(), CrimeData.CrimeSeverity.MODERATE, "victim=" + victim.getUniqueId());
        }
        if (isMarket(event.getEntity().getLocation())
            && districtService.isLawActive(district, DistrictData.LawKey.MARKET_PVP_ILLEGAL)) {
            createEvidence(attacker, district, DistrictData.LawKey.MARKET_PVP_ILLEGAL, "MARKET_PVP",
                victim.getLocation(), CrimeData.CrimeSeverity.MODERATE, "victim=" + victim.getUniqueId());
        }
        if (district.isPolice(victim.getUniqueId())
            && districtService.isLawActive(district, DistrictData.LawKey.ASSAULT_POLICE_ILLEGAL)) {
            createEvidence(attacker, district, DistrictData.LawKey.ASSAULT_POLICE_ILLEGAL, "ASSAULT_POLICE",
                victim.getLocation(), CrimeData.CrimeSeverity.MAJOR, "victim=" + victim.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Container)) return;
        DistrictData.District district = findDistrict(event.getClickedBlock().getLocation());
        if (district == null || district.isMember(event.getPlayer().getUniqueId())) return;
        if (districtService.isLawActive(district, DistrictData.LawKey.CHEST_THEFT_ILLEGAL)) {
            createEvidence(event.getPlayer(), district, DistrictData.LawKey.CHEST_THEFT_ILLEGAL,
                "CONTAINER_OPEN", event.getClickedBlock().getLocation(), CrimeData.CrimeSeverity.MINOR,
                "container=" + event.getClickedBlock().getType().name() + " safe_detect=open_only");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!message.startsWith("/npc create") && !message.startsWith("/npc shop") && !message.startsWith("/npc market")) {
            return;
        }
        DistrictData.District district = findDistrict(event.getPlayer().getLocation());
        if (district == null) return;
        if (!districtService.isLawActive(district, DistrictData.LawKey.UNLICENSED_MERCHANT_ILLEGAL)) return;
        if (!districtService.canCreateMerchantNpc(event.getPlayer().getUniqueId(), district)) {
            createEvidence(event.getPlayer(), district, DistrictData.LawKey.UNLICENSED_MERCHANT_ILLEGAL,
                "MERCHANT_NPC_ATTEMPT", event.getPlayer().getLocation(), CrimeData.CrimeSeverity.MODERATE,
                "command=" + event.getMessage());
        }
    }

    private void createEvidence(Player player, DistrictData.District district, DistrictData.LawKey law,
                                String action, Location location, CrimeData.CrimeSeverity severity, String details) {
        crimeService.createEvidence(player.getUniqueId(), district.getId(), law.name(), action,
            location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ(),
            severity, details);
    }

    private DistrictData.District findDistrict(Location location) {
        int districtId = damageService.getDistrictIdForLocation(location);
        if (districtId >= 0) {
            return districtService.getDistrict(districtId);
        }
        for (DistrictData.District district : districtService.getAllDistricts()) {
            if (district.getStatus() == DistrictData.DistrictStatus.ACTIVE
                && district.getWorldName().equals(location.getWorld().getName())
                && Math.abs(location.getBlockX() - district.getCenterX()) <= 250
                && Math.abs(location.getBlockZ() - district.getCenterZ()) <= 250) {
                return district;
            }
        }
        return null;
    }

    private boolean isMarket(Location location) {
        try {
            RegionService regionService = plugin.getServiceRegistry().get(RegionService.class);
            return regionService.getRegionsAt(location).stream()
                .anyMatch(region -> region.getType() == RegionData.RegionType.AUCTION_HALL
                    || region.getType() == RegionData.RegionType.DISTRICT_PUBLIC
                    || region.getType() == RegionData.RegionType.DISTRICT_MARKET);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
