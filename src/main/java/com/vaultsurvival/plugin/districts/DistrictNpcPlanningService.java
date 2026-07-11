package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.npc.NpcData;
import com.vaultsurvival.plugin.npc.NpcService;
import com.vaultsurvival.plugin.npc.NpcRemovedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** District founders place facing marker blocks, then confirm their permanent NPC plan. */
public final class DistrictNpcPlanningService implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final MessageFormatter fmt;
    private final NamespacedKey markerKey;
    private final Map<UUID, PlanSession> sessions = new ConcurrentHashMap<>();

    public DistrictNpcPlanningService(VaultSurvivalPlugin plugin, DistrictService districts) {
        this.plugin = plugin;
        this.districts = districts;
        this.fmt = plugin.getMessageFormatter();
        this.markerKey = new NamespacedKey(plugin, "district_npc_marker");
    }

    public void start(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canManageDevelopment(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires an active district and MAYOR, CO_MAYOR, or BUILDER role."));
            return;
        }
        cancel(player, false);
        Set<NpcPlanType> requiredTypes;
        try {
            requiredTypes = missingUnlockedTypes(district);
        } catch (Exception error) {
            player.sendMessage(fmt.error("Could not load this district's NPC placements. Try again later."));
            return;
        }
        if (requiredTypes.isEmpty()) {
            player.sendMessage(fmt.info("All unlocked district NPCs already have placements. Use &e/district npcs activate&7 if one still needs to spawn."));
            return;
        }
        PlanSession session = new PlanSession(district, requiredTypes);
        sessions.put(player.getUniqueId(), session);
        for (NpcPlanType type : requiredTypes) giveMarker(player, type);
        player.sendMessage(fmt.success(requiredTypes.size() + " missing unlocked district NPC marker(s) added. Place each block facing the desired NPC direction, then use &e/district npcs confirm&a."));
        player.sendMessage(fmt.info("Already placed and still-locked NPCs were skipped."));
    }

    public void confirm(Player player) {
        PlanSession session = sessions.get(player.getUniqueId());
        if (session == null) { player.sendMessage(fmt.error("No district NPC planning session is active.")); return; }
        Set<NpcPlanType> missing = EnumSet.copyOf(session.requiredTypes);
        missing.removeAll(session.markers.keySet());
        if (!missing.isEmpty()) {
            String names = missing.stream().map(type -> type.displayName).collect(Collectors.joining(", "));
            player.sendMessage(fmt.error("Place every marker from this session before confirming. Missing: " + names + "."));
            return;
        }
        try {
            for (Map.Entry<NpcPlanType, PlacedMarker> entry : session.markers.entrySet()) persistPlan(session.district, player, entry.getKey(), entry.getValue().location);
            restoreMarkers(session);
            sessions.remove(player.getUniqueId());
            removeMarkers(player);
            activateEligible(session.district, player.getName());
            player.sendMessage(fmt.success("District NPC plan confirmed. Eligible NPCs have spawned."));
        } catch (Exception error) {
            player.sendMessage(fmt.error("Could not save the NPC plan. Your markers remain active."));
        }
    }

    public void activate(Player player) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canManageDevelopment(player.getUniqueId(), district)) { player.sendMessage(fmt.error("Requires district development permission.")); return; }
        int created = activateEligible(district, player.getName());
        player.sendMessage(fmt.info(created == 0 ? "No planned NPCs are unlocked yet." : "Activated " + created + " district NPC(s)."));
    }

    public void move(Player player, int npcId) {
        DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts.canManageDevelopment(player.getUniqueId(), district)) {
            player.sendMessage(fmt.error("Requires MAYOR, CO_MAYOR, or BUILDER in this NPC's district.")); return;
        }
        DistrictData.BlockClaim claim = districts.getClaim(district.getId()); Location location = player.getLocation();
        if (claim == null || !claim.worldName().equals(location.getWorld().getName()) || !claim.contains(location.getBlockX(), location.getBlockZ())) {
            player.sendMessage(fmt.error("Stand at the new location inside your district claim.")); return;
        }
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement check = connection.prepareStatement(
                "SELECT id FROM district_npc_plans WHERE district_id=? AND npc_id=? AND status='ACTIVE'")) {
            check.setInt(1, district.getId()); check.setInt(2, npcId); ResultSet row = check.executeQuery();
            if (!row.next()) { player.sendMessage(fmt.error("That is not an active NPC owned by your district.")); return; }
            NpcService npcs = plugin.getServiceRegistry().get(NpcService.class);
            if (!npcs.moveNpc(npcId, location)) { player.sendMessage(fmt.error("NPC could not be moved.")); return; }
            plugin.getDatabase().executeUpdate("UPDATE district_npc_plans SET world=?,x=?,y=?,z=?,yaw=?,pitch=?,planned_by=?,planned_at=? WHERE id=?",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                player.getUniqueId().toString(), System.currentTimeMillis(), row.getInt(1));
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_NPC_MOVE", "NPC", String.valueOf(npcId),
                "district=" + district.getId() + " world=" + location.getWorld().getName() + " x=" + location.getX() + " y=" + location.getY() + " z=" + location.getZ());
            player.sendMessage(fmt.success("District NPC moved to your current location."));
        } catch (Exception error) { player.sendMessage(fmt.error("NPC move could not be saved.")); }
    }

    public void cancel(Player player) { cancel(player, true); }
    private void cancel(Player player, boolean message) {
        PlanSession session = sessions.remove(player.getUniqueId());
        if (session != null) restoreMarkers(session);
        removeMarkers(player);
        if (message) player.sendMessage(fmt.info("District NPC planning cancelled; marker blocks were restored."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        PlanSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getItemInHand().getItemMeta() == null) return;
        String raw = event.getItemInHand().getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        if (raw == null) return;
        NpcPlanType type;
        try { type = NpcPlanType.valueOf(raw); } catch (IllegalArgumentException ignored) { event.setCancelled(true); return; }
        if (session.markers.containsKey(type)) { event.setCancelled(true); event.getPlayer().sendMessage(fmt.error("That NPC marker is already placed.")); return; }
        if (!event.getBlockPlaced().getWorld().getName().equals(session.district.getWorldName())) { event.setCancelled(true); return; }
        DistrictData.BlockClaim claim = districts.getClaim(session.district.getId());
        if (claim == null || !claim.contains(event.getBlockPlaced().getX(), event.getBlockPlaced().getZ())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(fmt.error("District NPC markers must be placed inside the exact district border."));
            return;
        }
        float yaw = yawFor(event.getBlockPlaced().getBlockData());
        Location location = event.getBlockPlaced().getLocation().add(.5, 0, .5);
        location.setYaw(yaw);
        session.markers.put(type, new PlacedMarker(location, event.getBlockReplacedState()));
        event.getPlayer().sendMessage(fmt.success(type.displayName + " marker placed. It will face the same direction."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        PlanSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        Map.Entry<NpcPlanType, PlacedMarker> match = session.markers.entrySet().stream()
            .filter(entry -> entry.getValue().location.getWorld() != null
                && entry.getValue().location.getWorld().equals(event.getBlock().getWorld())
                && entry.getValue().location.getBlockX() == event.getBlock().getX()
                && entry.getValue().location.getBlockY() == event.getBlock().getY()
                && entry.getValue().location.getBlockZ() == event.getBlock().getZ())
            .findFirst().orElse(null);
        if (match == null) return;
        event.setCancelled(true);
        session.markers.remove(match.getKey());
        match.getValue().original.update(true, false);
        giveMarker(event.getPlayer(), match.getKey());
        event.getPlayer().sendMessage(fmt.info(match.getKey().displayName + " marker removed. Its placement item was returned."));
    }

    @EventHandler
    public void onNpcRemoved(NpcRemovedEvent event) {
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE district_npc_plans SET status='MISSING',npc_id=NULL WHERE npc_id=?",
                event.getNpc().getId());
        } catch (Exception error) {
            plugin.getLogger().warning("Could not mark removed district NPC #" + event.getNpc().getId() + " as replaceable: " + error.getMessage());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer(), false); }

    private void giveMarker(Player player, NpcPlanType type) {
        ItemStack item = new ItemStack(type.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(fmt.deserialize("&bDistrict NPC: &f" + type.displayName));
        meta.lore(java.util.List.of(fmt.deserialize("&7Place facing the desired NPC direction"), fmt.deserialize("&7Unlocks at district level " + type.minimumLevel)));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        player.getInventory().addItem(item).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void removeMarkers(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.STRING)) player.getInventory().setItem(slot, null);
        }
    }

    private void restoreMarkers(PlanSession session) { session.markers.values().forEach(marker -> marker.original.update(true, false)); }

    private void persistPlan(DistrictData.District district, Player player, NpcPlanType type, Location location) throws Exception {
        plugin.getDatabase().executeUpdate("INSERT INTO district_npc_plans (district_id,npc_type,world,x,y,z,yaw,pitch,minimum_level,status,planned_by,planned_at) VALUES (?,?,?,?,?,?,?,?,?,'PLANNED',?,?) ON CONFLICT(district_id,npc_type) DO UPDATE SET world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,minimum_level=excluded.minimum_level,status='PLANNED',npc_id=NULL,planned_by=excluded.planned_by,planned_at=excluded.planned_at",
            district.getId(), type.name(), location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), type.minimumLevel, player.getUniqueId().toString(), System.currentTimeMillis());
    }

    private Set<NpcPlanType> missingUnlockedTypes(DistrictData.District district) throws Exception {
        Set<NpcPlanType> placedTypes = EnumSet.noneOf(NpcPlanType.class);
        List<Integer> staleActivePlans = new ArrayList<>();
        NpcService npcService = plugin.getServiceRegistry().get(NpcService.class);
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id,npc_type,status,npc_id FROM district_npc_plans WHERE district_id=?")) {
            statement.setInt(1, district.getId());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    try {
                        NpcPlanType type = NpcPlanType.valueOf(results.getString("npc_type"));
                        String status = results.getString("status");
                        Object rawNpcId = results.getObject("npc_id");
                        Integer npcId = rawNpcId instanceof Number number ? number.intValue() : null;
                        if ("PLANNED".equalsIgnoreCase(status)
                            || ("ACTIVE".equalsIgnoreCase(status) && npcId != null && npcService.getNpc(npcId) != null)) {
                            placedTypes.add(type);
                        } else if ("ACTIVE".equalsIgnoreCase(status)) {
                            staleActivePlans.add(results.getInt("id"));
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        for (int planId : staleActivePlans) {
            plugin.getDatabase().executeUpdate("UPDATE district_npc_plans SET status='MISSING',npc_id=NULL WHERE id=?", planId);
        }
        int level = developmentLevel(district.getId());
        Set<NpcPlanType> missing = EnumSet.noneOf(NpcPlanType.class);
        for (NpcPlanType type : NpcPlanType.values()) {
            if (type.minimumLevel <= level && !placedTypes.contains(type)) missing.add(type);
        }
        return missing;
    }

    private int activateEligible(DistrictData.District district, String skin) {
        int level = developmentLevel(district.getId());
        int count = 0;
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM district_npc_plans WHERE district_id=? AND status='PLANNED' AND minimum_level<=?")) {
            statement.setInt(1, district.getId()); statement.setInt(2, level); ResultSet results = statement.executeQuery();
            NpcService npcs = plugin.getServiceRegistry().get(NpcService.class);
            while (results.next()) {
                NpcPlanType type = NpcPlanType.valueOf(results.getString("npc_type"));
                var world = Bukkit.getWorld(results.getString("world")); if (world == null) continue;
                Location location = new Location(world, results.getDouble("x"), results.getDouble("y"), results.getDouble("z"), results.getFloat("yaw"), results.getFloat("pitch"));
                NpcData.Npc npc = npcs.createNpc(district.getName() + " " + type.displayName, skin, location, type.actionType, type.command);
                if (npc == null) continue;
                plugin.getDatabase().executeUpdate("UPDATE district_npc_plans SET status='ACTIVE',npc_id=? WHERE id=?", npc.getId(), results.getInt("id"));
                count++;
            }
        } catch (Exception ignored) { }
        return count;
    }

    private int developmentLevel(int districtId) { try (Connection c=plugin.getDatabase().getConnection(); PreparedStatement s=c.prepareStatement("SELECT level FROM district_development WHERE district_id=?")){s.setInt(1,districtId);ResultSet r=s.executeQuery();return r.next()?r.getInt(1):0;}catch(Exception ignored){return 0;} }
    private float yawFor(org.bukkit.block.data.BlockData data) { if (!(data instanceof Directional directional)) return 0; return switch(directional.getFacing()){case NORTH->180f;case SOUTH->0f;case EAST->-90f;case WEST->90f;default->0f;}; }

    private enum NpcPlanType {
        CLERK("Town Clerk", Material.LECTERN, 0, NpcData.ActionType.COMMAND, "vsmenu district"),
        JOB_BOARD("Job Board", Material.LOOM, 0, NpcData.ActionType.COMMAND, "spawnjobs"),
        MARKET_STEWARD("Market Steward", Material.BARREL, 1, NpcData.ActionType.COMMAND, "merchant order list"),
        CONDUCTOR("Conductor", Material.BLAST_FURNACE, 2, NpcData.ActionType.NONE, "");
        private final String displayName; private final Material material; private final int minimumLevel; private final NpcData.ActionType actionType; private final String command;
        NpcPlanType(String displayName, Material material, int minimumLevel, NpcData.ActionType actionType, String command){this.displayName=displayName;this.material=material;this.minimumLevel=minimumLevel;this.actionType=actionType;this.command=command;}
    }
    private static final class PlanSession {
        private final DistrictData.District district;
        private final Set<NpcPlanType> requiredTypes;
        private final Map<NpcPlanType,PlacedMarker> markers = new EnumMap<>(NpcPlanType.class);
        private PlanSession(DistrictData.District district, Set<NpcPlanType> requiredTypes) {
            this.district = district;
            this.requiredTypes = EnumSet.copyOf(requiredTypes);
        }
    }
    private record PlacedMarker(Location location, BlockState original) { }
}
