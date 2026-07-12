package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogMenuType;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Typed Town Clerk entry point. It accepts only a fixed context, never command text. */
public final class TownClerkService {
    private final VaultSurvivalPlugin plugin;
    private final DistrictFoundingService founding;
    public TownClerkService(VaultSurvivalPlugin plugin,DistrictFoundingService founding){this.plugin=plugin;this.founding=founding;}
    public void open(Player player,TownClerkContext context,Integer districtId){
        DialogService dialogs=plugin.getServiceRegistry().get(DialogService.class);
        if(context==TownClerkContext.SPAWN_CITY){
            openSpawn(player,"Welcome to the Spawn City Town Clerk.");
            return;
        }
        DistrictService districts=plugin.getServiceRegistry().get(DistrictService.class);
        DistrictData.District district=districts.getPlayerDistrict(player.getUniqueId());
        if(district==null || (districtId!=null && district.getId()!=districtId)){dialogs.openResult(player,"Town Clerk","This clerk only manages its own district.",List.of(DialogMenuItem.item("Close","Close.","vsmenu close",null,Material.BARRIER)));return;}
        boolean mayor=district.hasRole(player.getUniqueId(),DistrictData.DistrictRole.MAYOR)||district.hasRole(player.getUniqueId(),DistrictData.DistrictRole.CO_MAYOR);
        List<DialogMenuItem> items=new ArrayList<>();
        items.add(DialogMenuItem.item("District Overview","Level, members, claim and facility status.","vsmenu action district_stats",null,Material.NETHER_STAR));
        if(mayor){
            items.add(DialogMenuItem.item("Facilities & Upgrades","Level Town Hall, Market, Station, Jail and Auction House.","vsmenu action district_facilities",null,Material.SMITHING_TABLE));
            items.add(DialogMenuItem.item("Select Market Zone","Select exact market-zone blocks inside the district.","district marketzone start",null,Material.EMERALD_BLOCK));
            items.add(DialogMenuItem.item("Station","Request, configure and upgrade the district station.","vsmenu district.station",null,Material.RAIL));
            items.add(DialogMenuItem.item("Laws & Settings","Edit readable laws and district settings.","vsmenu district.laws",null,Material.LECTERN));
            items.add(DialogMenuItem.item("Set District Home","Set the shared district teleport point.","district sethome",null,Material.RESPAWN_ANCHOR));
            items.add(DialogMenuItem.item("Restricted Land","Create and manage role/player access areas.","district restricted list",null,Material.IRON_DOOR));
            items.add(DialogMenuItem.item("Farms & Workers","Mayor defines farm zones; farmers configure worker areas and supply/output chests.","vsmenu action district_farms",null,Material.WHEAT));
            items.add(DialogMenuItem.item("Treasury","Manage the physical upgrade treasury.","vsmenu district.treasury",null,Material.GOLD_BLOCK));
        }
        items.add(DialogMenuItem.item("Close","Close the clerk.","vsmenu close",null,Material.BARRIER));
        dialogs.openResult(player,district.getName()+" Town Clerk",mayor?"All mayor governance, facility and region controls are grouped here.":"District overview. Mayor controls are visible only to MAYOR and CO_MAYOR.",items);
    }

    public void openSpawn(Player player,String status){
        founding.current(player.getUniqueId()).thenCombine(founding.invitations(player.getUniqueId()),View::new).whenComplete((view,failure)->Bukkit.getScheduler().runTask(plugin,()->{
            DialogService dialogs=plugin.getServiceRegistry().get(DialogService.class);if(failure!=null){dialogs.openResult(player,"Spawn City Town Clerk","Founding records are temporarily unavailable: "+failure.getMessage(),List.of(DialogMenuItem.item("Close","Close.","vsmenu close",null,Material.BARRIER)));return;}
            List<DialogMenuItem> items=new ArrayList<>();StringBuilder body=new StringBuilder(status).append("\n\nThree unique accepted UUIDs, including the founder, are mandatory.");
            DistrictFoundingPetition petition=view.current();
            if(petition==null)items.add(DialogMenuItem.item("Create Petition","Choose a district name.","vsmenu form district_founding",null,Material.WRITABLE_BOOK));
            else{body.append("\nPetition: ").append(petition.districtName()).append("\nAccepted: ").append(petition.acceptedCount()).append("/3\nClaim: ").append(petition.claim()==null?"not selected":petition.claim().areaBlocks()+" blocks").append("\nContract: ").append(petition.contractUuid()==null?"not issued":"issued");items.add(DialogMenuItem.item("Invite Founder","Invite an online player by exact name and choose a starting role.","vsmenu form district_founding_invite",null,Material.PLAYER_HEAD));if(petition.acceptedCount()>=3)items.add(DialogMenuItem.item("Select District Claim","Select 2,500–level-limit surface blocks remotely.","vsmenu action district_founding_select",null,Material.GOLDEN_AXE));else items.add(DialogMenuItem.locked("Select District Claim","Select the founding land.","Requires at least three accepted unique founders.",Material.GOLDEN_AXE));if(petition.claim()!=null&&petition.contractUuid()!=null)items.add(DialogMenuItem.item("Submit Application","Hand in the matching independence contract for staff review.","vsmenu action district_founding_submit",null,Material.EMERALD));}
            for(DistrictFoundingPetition invite:view.invitations()){items.add(DialogMenuItem.item("Accept: "+invite.districtName(),"Join this founding petition.","vsmenu action district_founding_accept "+invite.petitionUuid(),null,Material.LIME_DYE));items.add(DialogMenuItem.item("Decline: "+invite.districtName(),"Decline this invitation.","vsmenu action district_founding_decline "+invite.petitionUuid(),null,Material.RED_DYE));}
            items.add(DialogMenuItem.item("District Directory","Browse existing districts.","vsmenu district.directory",null,Material.FILLED_MAP));items.add(DialogMenuItem.item("Close","Close the clerk.","vsmenu close",null,Material.BARRIER));dialogs.openResult(player,"Spawn City Town Clerk",body.toString(),items);
        }));
    }
    private record View(DistrictFoundingPetition current,List<DistrictFoundingPetition> invitations){}
}
