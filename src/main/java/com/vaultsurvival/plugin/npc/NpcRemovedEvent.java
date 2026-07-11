package com.vaultsurvival.plugin.npc;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a persisted NPC is removed, allowing owning domain records to become replaceable. */
public final class NpcRemovedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NpcData.Npc npc;

    public NpcRemovedEvent(NpcData.Npc npc) {
        this.npc = npc;
    }

    public NpcData.Npc getNpc() { return npc; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
