package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogMenuType;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.util.List;

/** Typed Town Clerk entry point. It accepts only a fixed context, never command text. */
public final class TownClerkService {
    private final VaultSurvivalPlugin plugin;
    public TownClerkService(VaultSurvivalPlugin plugin){this.plugin=plugin;}
    public void open(Player player,TownClerkContext context,Integer districtId){
        DialogService dialogs=plugin.getServiceRegistry().get(DialogService.class);
        if(context==TownClerkContext.SPAWN_CITY){
            dialogs.openResult(player,"Spawn City Town Clerk","District founding petitions require three unique accepted founders. Start or continue your petition here.",List.of(
                DialogMenuItem.locked("New Founding Petition","Choose a name, invite two players, select a claim, and return the independence contract.","Petition storage is installed; the complete invitation dialog remains unavailable in this build.",Material.WRITABLE_BOOK),
                DialogMenuItem.item("District Directory","Browse existing districts.","vsmenu district.directory",null,Material.FILLED_MAP),
                DialogMenuItem.item("Close","Close the clerk.","vsmenu close",null,Material.BARRIER)));
            return;
        }
        DistrictService districts=plugin.getServiceRegistry().get(DistrictService.class);
        DistrictData.District district=districts.getPlayerDistrict(player.getUniqueId());
        if(district==null || (districtId!=null && district.getId()!=districtId)){dialogs.openResult(player,"Town Clerk","This clerk only manages its own district.",List.of(DialogMenuItem.item("Close","Close.","vsmenu close",null,Material.BARRIER)));return;}
        dialogs.openMenu(player, DialogMenuType.DISTRICT_DEVELOPMENT);
    }
}
