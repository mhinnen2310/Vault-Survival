package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/** Exact-block mayor-owned restricted district land with explicit allow/deny access. */
public final class DistrictRestrictedLandService implements Listener {
    public enum AccessMode { PUBLIC, MEMBERS, ALLOWLIST }
    public record Land(int id,int districtId,String name,String world,int minX,int minZ,int maxX,int maxZ,AccessMode mode) {
        boolean contains(Location l){return l!=null&&l.getWorld()!=null&&world.equals(l.getWorld().getName())&&l.getBlockX()>=minX&&l.getBlockX()<=maxX&&l.getBlockZ()>=minZ&&l.getBlockZ()<=maxZ;}
    }
    public record Result(boolean success,String message,Land land){static Result error(String m){return new Result(false,m,null);}static Result ok(String m,Land l){return new Result(true,m,l);}}
    private final VaultSurvivalPlugin plugin; private final DistrictService districts; private volatile List<Land> cache=List.of();
    public DistrictRestrictedLandService(VaultSurvivalPlugin plugin,DistrictService districts){this.plugin=plugin;this.districts=districts;refresh();}

    public Result create(Player mayor,String name,DistrictData.BlockClaim claim){
        DistrictData.District district=districts.getPlayerDistrict(mayor.getUniqueId());
        if(district==null||!district.hasRole(mayor.getUniqueId(),DistrictData.DistrictRole.MAYOR))return Result.error("Only the district MAYOR can create restricted land.");
        DistrictData.BlockClaim owner=districts.getClaim(district.getId());
        if(claim==null||owner==null||!owner.contains(claim))return Result.error("Restricted land must be completely inside your district claim.");
        String clean=name==null?"":name.trim();if(!clean.matches("[A-Za-z0-9 _-]{3,32}"))return Result.error("Use a restricted-land name of 3-32 letters, numbers, spaces, _ or -.");
        int maximum=Math.max(1,plugin.getConfigManager().getConfig().getInt("districts.restrictedLand.maxAreasPerDistrict",8));
        if(list(district.getId()).size()>=maximum)return Result.error("This district already has its maximum of "+maximum+" restricted areas.");
        try(Connection c=plugin.getDatabase().getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO district_restricted_lands(district_id,name,world,min_x,min_z,max_x,max_z,access_mode,created_by,created_at) VALUES(?,?,?,?,?,?,?,'MEMBERS',?,?)")){s.setInt(1,district.getId());s.setString(2,clean);s.setString(3,claim.worldName());s.setInt(4,claim.minBlockX());s.setInt(5,claim.minBlockZ());s.setInt(6,claim.maxBlockX());s.setInt(7,claim.maxBlockZ());s.setString(8,mayor.getUniqueId().toString());s.setLong(9,System.currentTimeMillis());s.executeUpdate();}catch(SQLException e){return Result.error("Restricted land could not be saved; choose a unique name.");}
        refresh(); Land land=list(district.getId()).stream().filter(l->l.name().equalsIgnoreCase(clean)).findFirst().orElse(null);
        plugin.getAuditLogger().log(mayor.getUniqueId(),mayor.getName(),"DISTRICT_RESTRICTED_LAND_CREATE","DISTRICT",String.valueOf(district.getId()),"name="+clean+" area="+claim.areaBlocks());return Result.ok("Restricted land '"+clean+"' created in MEMBERS mode.",land);
    }

    public Result setAccess(Player mayor,int landId,OfflinePlayer target,Boolean allowed){Land land=get(landId);if(!canManage(mayor,land))return Result.error("Only this district's MAYOR can change restricted-land access.");if(target==null||target.getUniqueId().equals(mayor.getUniqueId()))return Result.error("Choose another player.");try{if(allowed==null)plugin.getDatabase().executeUpdate("DELETE FROM district_restricted_access WHERE land_id=? AND player_uuid=?",landId,target.getUniqueId().toString());else plugin.getDatabase().executeUpdate("INSERT INTO district_restricted_access(land_id,player_uuid,allowed,changed_by,changed_at) VALUES(?,?,?,?,?) ON CONFLICT(land_id,player_uuid) DO UPDATE SET allowed=excluded.allowed,changed_by=excluded.changed_by,changed_at=excluded.changed_at",landId,target.getUniqueId().toString(),allowed?1:0,mayor.getUniqueId().toString(),System.currentTimeMillis());}catch(SQLException e){return Result.error("Access could not be saved.");}plugin.getAuditLogger().log(mayor.getUniqueId(),mayor.getName(),"DISTRICT_RESTRICTED_ACCESS","LAND",String.valueOf(landId),"player="+target.getUniqueId()+" allowed="+allowed);return Result.ok("Access updated for "+(target.getName()==null?target.getUniqueId():target.getName())+".",land);}
    public Result setMode(Player mayor,int landId,String raw){Land land=get(landId);if(!canManage(mayor,land))return Result.error("Only this district's MAYOR can change access mode.");AccessMode mode;try{mode=AccessMode.valueOf(raw.toUpperCase(Locale.ROOT));}catch(Exception e){return Result.error("Mode must be PUBLIC, MEMBERS, or ALLOWLIST.");}try{plugin.getDatabase().executeUpdate("UPDATE district_restricted_lands SET access_mode=? WHERE id=?",mode.name(),landId);}catch(SQLException e){return Result.error("Mode could not be saved.");}refresh();plugin.getAuditLogger().log(mayor.getUniqueId(),mayor.getName(),"DISTRICT_RESTRICTED_MODE","LAND",String.valueOf(landId),"mode="+mode);return Result.ok("Access mode is now "+mode+".",get(landId));}
    public Result delete(Player mayor,int landId){Land land=get(landId);if(!canManage(mayor,land))return Result.error("Only this district's MAYOR can delete restricted land.");try{plugin.getDatabase().executeUpdate("DELETE FROM district_restricted_lands WHERE id=?",landId);}catch(SQLException e){return Result.error("Restricted land could not be deleted.");}refresh();return Result.ok("Restricted land deleted.",land);}
    public List<Land> list(int districtId){return cache.stream().filter(l->l.districtId()==districtId).toList();}
    public Land get(int id){return cache.stream().filter(l->l.id()==id).findFirst().orElse(null);}
    private void refresh(){List<Land> result=new ArrayList<>();try(Connection c=plugin.getDatabase().getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM district_restricted_lands ORDER BY id");ResultSet r=s.executeQuery()){while(r.next())result.add(map(r));cache=List.copyOf(result);}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"Restricted land query failed",e);}}
    public boolean allowed(Player player,Land land){if(land==null||player.hasPermission("vs.district.admin"))return true;DistrictData.District district=districts.getDistrict(land.districtId());if(district!=null&&district.hasRole(player.getUniqueId(),DistrictData.DistrictRole.MAYOR))return true;Boolean explicit=explicit(land.id(),player.getUniqueId());if(explicit!=null)return explicit;return land.mode()==AccessMode.PUBLIC||(land.mode()==AccessMode.MEMBERS&&district!=null&&district.isMember(player.getUniqueId()));}
    private Boolean explicit(int landId,UUID player){try(Connection c=plugin.getDatabase().getConnection();PreparedStatement s=c.prepareStatement("SELECT allowed FROM district_restricted_access WHERE land_id=? AND player_uuid=?")){s.setInt(1,landId);s.setString(2,player.toString());ResultSet r=s.executeQuery();return r.next()?r.getInt(1)==1:null;}catch(SQLException e){return null;}}
    private boolean canManage(Player p,Land l){if(l==null)return false;DistrictData.District d=districts.getPlayerDistrict(p.getUniqueId());return d!=null&&d.getId()==l.districtId()&&d.hasRole(p.getUniqueId(),DistrictData.DistrictRole.MAYOR);}
    private Land map(ResultSet r)throws SQLException{return new Land(r.getInt("id"),r.getInt("district_id"),r.getString("name"),r.getString("world"),r.getInt("min_x"),r.getInt("min_z"),r.getInt("max_x"),r.getInt("max_z"),AccessMode.valueOf(r.getString("access_mode")));}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void onMove(PlayerMoveEvent e){if(e.getTo()==null||sameBlock(e.getFrom(),e.getTo()))return;for(Land land:cache)if(land.contains(e.getTo())&&!land.contains(e.getFrom())&&!allowed(e.getPlayer(),land)){e.setCancelled(true);e.getPlayer().sendMessage(plugin.getMessageFormatter().error("Restricted land: "+land.name()+". The mayor has not granted you access."));return;}}
    private boolean sameBlock(Location a,Location b){return a.getWorld()==b.getWorld()&&a.getBlockX()==b.getBlockX()&&a.getBlockY()==b.getBlockY()&&a.getBlockZ()==b.getBlockZ();}
}
