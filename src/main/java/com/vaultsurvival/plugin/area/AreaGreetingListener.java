package com.vaultsurvival.plugin.area;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sends configured area greetings when a player crosses a district or Spawn City boundary. */
public final class AreaGreetingListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final CurrentAreaService areas;
    private final Map<UUID, AreaKey> lastAreas = new ConcurrentHashMap<>();

    public AreaGreetingListener(VaultSurvivalPlugin plugin, CurrentAreaService areas) {
        this.plugin = plugin;
        this.areas = areas;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { lastAreas.put(event.getPlayer().getUniqueId(), key(areas.resolve(event.getPlayer()))); }
    @EventHandler public void onTeleport(PlayerTeleportEvent event) { plugin.getServer().getScheduler().runTask(plugin, () -> check(event.getPlayer())); }
    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ() && event.getFrom().getWorld().equals(event.getTo().getWorld()))) return;
        check(event.getPlayer());
    }

    private void check(Player player) {
        AreaKey next = key(areas.resolve(player));
        AreaKey previous = lastAreas.put(player.getUniqueId(), next);
        if (previous == null || previous.equals(next)) return;
        DistrictService districts;
        try { districts = plugin.getServiceRegistry().get(DistrictService.class); } catch (RuntimeException unavailable) { return; }
        if (previous.districtId != null) {
            DistrictData.District district = districts.getDistrict(previous.districtId);
            String message = districts.getDistrictMessage(district, false);
            if (!message.isBlank()) player.sendMessage(plugin.getMessageFormatter().deserialize(message));
        } else if (previous.spawnCity) {
            player.sendMessage(plugin.getMessageFormatter().deserialize(plugin.getConfigManager().getSpawnLeaveMessage()));
        }
        if (next.districtId != null) {
            DistrictData.District district = districts.getDistrict(next.districtId);
            String message = districts.getDistrictMessage(district, true);
            if (!message.isBlank()) player.sendMessage(plugin.getMessageFormatter().deserialize(message));
        } else if (next.spawnCity) {
            player.sendMessage(plugin.getMessageFormatter().deserialize(plugin.getConfigManager().getSpawnWelcomeMessage()));
        }
    }

    private AreaKey key(CurrentAreaContext context) {
        return new AreaKey(context.district() == null ? null : context.district().getId(), context.areaType() == CurrentAreaContext.AreaType.SPAWN_CITY);
    }
    private record AreaKey(Integer districtId, boolean spawnCity) { }
}
