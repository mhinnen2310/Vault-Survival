package com.vaultsurvival.plugin.districts;

import org.bukkit.entity.Player;

/** Strict parser for persisted typed NPC context. Arbitrary command data is rejected. */
public final class TownClerkNpcHandler {
    private final TownClerkService service;
    public TownClerkNpcHandler(TownClerkService service){this.service=service;}
    public void handle(Player player,String data){
        if("SPAWN_CITY".equals(data)){service.open(player,TownClerkContext.SPAWN_CITY,null);return;}
        if(data!=null && data.startsWith("DISTRICT:")){try{service.open(player,TownClerkContext.DISTRICT,Integer.parseInt(data.substring(9)));return;}catch(NumberFormatException ignored){}}
        throw new IllegalArgumentException("Rejected invalid Town Clerk action data");
    }
}
