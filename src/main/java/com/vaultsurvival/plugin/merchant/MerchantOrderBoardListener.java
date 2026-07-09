package com.vaultsurvival.plugin.merchant;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;

/** Opens public buy orders from a persistent, server-owned merchant sign. */
public final class MerchantOrderBoardListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final MerchantOrderService orders;
    private final NamespacedKey boardKey;

    public MerchantOrderBoardListener(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.orders = plugin.getServiceRegistry().get(MerchantOrderService.class);
        this.boardKey = new NamespacedKey(plugin, "merchant_order_board");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getClickedBlock() == null
            || !(event.getClickedBlock().getState() instanceof Sign sign)
            || !sign.getPersistentDataContainer().has(boardKey, PersistentDataType.INTEGER)) return;
        event.setCancelled(true);
        try {
            DialogService dialogs = plugin.getServiceRegistry().get(DialogService.class);
            var items = new ArrayList<DialogMenuItem>();
            for (MerchantOrderData.Order order : orders.getActiveOrders()) {
                items.add(DialogMenuItem.item("#" + order.getId() + " " + order.getItemDisplay(),
                    "Reward " + order.getPricePerItem() + " | Remaining " + order.getRemainingQuantity(),
                    "merchant order deliver " + order.getId(), null, Material.CHEST));
            }
            dialogs.openResult(event.getPlayer(), "Merchant Buy Orders",
                items.isEmpty() ? "No active buy orders." : "Select an order to deliver matching items.", items);
        } catch (RuntimeException unavailable) {
            event.getPlayer().performCommand("merchant order list");
        }
    }
}
