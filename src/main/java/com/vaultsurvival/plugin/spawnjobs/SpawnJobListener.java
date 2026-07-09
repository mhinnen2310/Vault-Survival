package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.npc.NpcInteractEvent;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;

public class SpawnJobListener implements Listener {
    private final SpawnJobServiceImpl service;

    public SpawnJobListener(VaultSurvivalPlugin plugin, SpawnJobServiceImpl service) {
        this.service = service;
    }

    @EventHandler
    public void onNpc(NpcInteractEvent event) {
        String name = event.getNpc().getName().toLowerCase();
        String action = event.getNpc().getActionData() != null ? event.getNpc().getActionData().toLowerCase() : "";
        if (name.contains("job board") || action.contains("spawnjobs")) {
            event.getPlayer().performCommand("spawnjobs");
        } else if (name.contains("conductor") || name.contains("builder") || name.contains("mason") || name.contains("smith") || name.contains("mint")) {
            event.getPlayer().sendMessage("Use /spawnjobs active and /spawnjobs turnin <id> to turn in related jobs here.");
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        var uuid = service.getPackageUuid(event.getItemDrop().getItemStack());
        if (uuid != null) {
            service.markLost(uuid);
            event.getPlayer().sendMessage("Your transport package was dropped and marked lost.");
        }
    }

    @EventHandler
    public void onMove(InventoryMoveItemEvent event) {
        if (service.isPackage(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
