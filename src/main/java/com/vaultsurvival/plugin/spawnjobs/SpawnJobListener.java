package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.npc.NpcInteractEvent;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
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
            for (var active : service.getActiveJobs(event.getPlayer())) {
                var job = service.getJob(active.getJobId());
                if (job != null && job.getDestination().toLowerCase().contains(name.contains("conductor") ? "conductor" : name.contains("mint") ? "mint" : name)) service.turnIn(event.getPlayer(), job.getId());
            }
            event.getPlayer().performCommand("spawnjobs active");
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

    @EventHandler
    public void onContainerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player)) return;
        boolean normalContainer = event.getView().getTopInventory().getType() != InventoryType.CRAFTING && event.getView().getTopInventory().getType() != InventoryType.PLAYER;
        if (!normalContainer) return;
        if (service.isPackage(event.getCurrentItem()) || service.isPackage(event.getCursor())) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage("Transport packages cannot be stored in containers.");
        }
    }
}
