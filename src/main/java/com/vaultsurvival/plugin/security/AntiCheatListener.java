package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Conservative server-side signal collection. Flags and scores only; never auto-bans. */
public final class AntiCheatListener implements Listener {
    private final VaultSurvivalPlugin plugin; private final Map<UUID, Long> breaks = new HashMap<>();
    public AntiCheatListener(VaultSurvivalPlugin plugin){this.plugin=plugin;}
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){if(!plugin.getConfigManager().getConfig().getBoolean("security.anticheat.enabled",true))return;Player p=e.getPlayer();if(e.getTo()==null||p.getAllowFlight()||p.isGliding()||p.isInsideVehicle()||plugin.isStaffModeActive(p.getUniqueId()))return; double horizontal=e.getFrom().distanceSquared(e.getTo());if(horizontal>plugin.getConfigManager().getConfig().getDouble("security.anticheat.movementMaxDistanceSquared",36))flag(p,"MOVEMENT",4,"distanceSquared="+horizontal); if(!p.isOnGround()&&e.getTo().getY()-e.getFrom().getY()>.9)flag(p,"FLY",3,"vertical ascent");}
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!plugin.getConfigManager().getConfig().getBoolean("security.anticheat.enabled",true))return;long now=System.currentTimeMillis();Long previous=breaks.put(e.getPlayer().getUniqueId(),now);if(previous!=null&&now-previous<plugin.getConfigManager().getConfig().getLong("security.anticheat.fastBreakMinIntervalMs",45))flag(e.getPlayer(),"FAST_BREAK",1,"interval="+(now-previous));}
    private void flag(Player p,String check,double score,String details){try{plugin.getDatabase().executeUpdate("INSERT INTO anticheat_flags (player_uuid,check_type,score,details,created_at) VALUES (?,?,?,?,?)",p.getUniqueId().toString(),check,score,details,System.currentTimeMillis());plugin.getAuditLogger().log(p.getUniqueId(),p.getName(),"ANTICHEAT_FLAG","PLAYER",p.getUniqueId().toString(),check+" score="+score+" "+details);if(score>=plugin.getConfigManager().getConfig().getDouble("security.anticheat.liveAlertMinimumScore",1)){try{plugin.getServiceRegistry().get(StaffAlertService.class).alertStaff("AntiCheat",p.getName()+" flagged "+check+" ("+details+")");}catch(RuntimeException ignored){}}}catch(Exception ignored){}}
}
