package com.vaultsurvival.plugin.npc;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when a player right-clicks an NPC.
 * Other modules (Market, Currency, etc.) listen to this event
 * to open their respective GUIs or execute commands.
 */
public class NpcInteractEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final NpcData.Npc npc;

    public NpcInteractEvent(Player player, NpcData.Npc npc) {
        this.player = player;
        this.npc = npc;
    }

    public Player getPlayer() { return player; }
    public NpcData.Npc getNpc() { return npc; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
