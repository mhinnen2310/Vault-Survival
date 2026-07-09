package com.vaultsurvival.plugin.merchant;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopService;
import com.vaultsurvival.plugin.npc.NpcService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/** Merchant shops can be killed without ever touching their persisted stock or earnings. */
public final class MerchantNpcLifecycleListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final NpcService npcs;
    private final MerchantShopService shops;

    public MerchantNpcLifecycleListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.npcs = plugin.getServiceRegistry().get(NpcService.class);
        this.shops = plugin.getServiceRegistry().get(MerchantShopService.class);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        var npc = npcs.getNpcByVisualUuid(event.getEntity().getUniqueId());
        if (npc == null || shops.getShopByNpcId(npc.getId()) == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        npcs.despawnNpcVisual(npc.getId());
        long delay = Math.max(1, plugin.getConfigManager().getConfig().getLong("districtMarket.merchantNpc.respawnMinutes", 15));
        plugin.getAuditLogger().log(null, "SYSTEM", "MERCHANT_NPC_KILLED", "NPC", String.valueOf(npc.getId()), "respawnMinutes=" + delay);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            npcs.respawnNpc(npc.getId());
            plugin.getAuditLogger().log(null, "SYSTEM", "MERCHANT_NPC_RESPAWN", "NPC", String.valueOf(npc.getId()), "stockPreserved=true");
        }, delay * 60L * 20L);
    }
}
