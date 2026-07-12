package com.vaultsurvival.plugin.access;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.logging.Level;

/** Captures Bukkit join state before persisting the access/session snapshot off-thread. */
public final class AccessListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final AccessServiceImpl accessService;

    public AccessListener(VaultSurvivalPlugin plugin, AccessServiceImpl accessService) {
        this.plugin=plugin;this.accessService=accessService;
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        Player player=event.getPlayer();UUID uuid=player.getUniqueId();String name=player.getName();
        String ip=player.getAddress()==null?"unknown":player.getAddress().getAddress().getHostAddress();
        plugin.getDatabase().write(connection->{
            try(PreparedStatement profile=connection.prepareStatement("INSERT INTO players(uuid,username,first_seen,last_seen) VALUES(?,?,datetime('now'),datetime('now')) ON CONFLICT(uuid) DO UPDATE SET username=excluded.username,last_seen=datetime('now')")){profile.setString(1,uuid.toString());profile.setString(2,name);profile.executeUpdate();}
            try(PreparedStatement session=connection.prepareStatement("INSERT INTO player_sessions(player_uuid,login_time,ip_address) VALUES(?,datetime('now'),?)")){session.setString(1,uuid.toString());session.setString(2,ip);session.executeUpdate();}
            try(PreparedStatement defaults=connection.prepareStatement("INSERT OR IGNORE INTO access_player_groups(player_uuid,group_id,granted_by) SELECT ?,id,NULL FROM access_groups WHERE lower(name)='default' AND NOT EXISTS(SELECT 1 FROM access_player_groups WHERE player_uuid=?)")){defaults.setString(1,uuid.toString());defaults.setString(2,uuid.toString());defaults.executeUpdate();}
            return null;
        }).whenComplete((ignored,failure)->plugin.getScheduler().runSync(()->{
            if(failure!=null)plugin.getLogger().log(Level.WARNING,"Failed to initialize player access for "+name,failure);
            else if(player.isOnline())accessService.refreshPlayerPermissions(player);
        }));
        try {
            var spawnCity=plugin.getServiceRegistry().get(com.vaultsurvival.plugin.spawncity.SpawnCityService.class);
            event.joinMessage(plugin.getMessageFormatter().deserialize("&e"+name+" &7arrived in &6"+spawnCity.getCityName()+"&7."));
        } catch(IllegalStateException ignored){ }
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent event){accessService.clearPlayerPermissions(event.getPlayer().getUniqueId());}
}
