package com.vaultsurvival.plugin.merchant.shop;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.social.PayoutLockerService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Inventory-backed shop editor. Player inventory remains usable; top slots persist stock. */
public final class MerchantShopEditor implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final MerchantShopService service;

    public MerchantShopEditor(VaultSurvivalPlugin plugin, MerchantShopService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public static void open(VaultSurvivalPlugin plugin, MerchantShopService service, Player player, MerchantShopData.Shop shop) {
        EditorHolder holder = new EditorHolder(shop.getId(), player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Edit shop: " + shop.getName()));
        holder.inventory = inventory;
        for (MerchantShopData.ShopItem row : service.getShopItems(shop.getId())) {
            if (row.getSlot() < 0 || row.getSlot() >= 45) continue;
            ItemStack display = service.itemStack(row);
            display.setAmount(Math.max(1, Math.min(display.getMaxStackSize(), row.getStock())));
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("Stock: " + row.getStock()));
            lore.add(Component.text("Price: " + row.getPrice()));
            lore.add(Component.text("Left-click: set price"));
            lore.add(Component.text("Right-click: take all stock"));
            meta.lore(lore); display.setItemMeta(meta); inventory.setItem(row.getSlot(), display);
        }
        inventory.setItem(45, button(Material.HOPPER, "Move items into slots above"));
        inventory.setItem(49, button(Material.GOLD_INGOT, "Collect shop earnings"));
        inventory.setItem(53, button(Material.BARRIER, "Close editor"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder) || !(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.owner.equals(player.getUniqueId())) { event.setCancelled(true); return; }
        int raw = event.getRawSlot();
        if (raw >= 45 && raw < 54) {
            event.setCancelled(true);
            if (raw == 49) {
                try { plugin.getServiceRegistry().get(PayoutLockerService.class).claimMerchantShop(player); }
                catch (RuntimeException unavailable) { player.sendMessage(plugin.getMessageFormatter().error("Payout service is unavailable.")); }
            } else if (raw == 53) player.closeInventory();
            return;
        }
        if (raw >= 0 && raw < 45) {
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                ItemStack deposit = cursor.clone();
                if (service.depositEditorStack(player, holder.shopId, raw, deposit)) {
                    event.setCursor(null);
                    Bukkit.getScheduler().runTask(plugin, () -> service.openShopEditor(player, holder.shopId));
                }
                return;
            }
            MerchantShopData.ShopItem row = service.getShopItems(holder.shopId).stream()
                .filter(item -> item.getSlot() == raw).findFirst().orElse(null);
            if (row == null) return;
            player.closeInventory();
            if (event.isRightClick()) {
                service.removeStock(player, holder.shopId, raw, row.getStock());
                Bukkit.getScheduler().runTask(plugin, () -> service.openShopEditor(player, holder.shopId));
            } else {
                player.performCommand("vsmenu form merchant_price " + holder.shopId + " " + raw);
            }
            return;
        }
        if (event.isShiftClick() && raw >= 54) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;
            int slot = firstSlot(holder.shopId, clicked);
            if (slot < 0) { player.sendMessage(plugin.getMessageFormatter().error("The shop editor is full.")); return; }
            ItemStack deposit = clicked.clone();
            if (service.depositEditorStack(player, holder.shopId, slot, deposit)) {
                event.setCurrentItem(null);
                Bukkit.getScheduler().runTask(plugin, () -> service.openShopEditor(player, holder.shopId));
            }
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EditorHolder) event.setCancelled(true);
    }

    private int firstSlot(int shopId, ItemStack stack) {
        boolean[] used = new boolean[45];
        for (MerchantShopData.ShopItem row : service.getShopItems(shopId)) {
            if (row.getSlot() >= 0 && row.getSlot() < 45) {
                used[row.getSlot()] = true;
                if (service.itemStack(row).isSimilar(stack)) return row.getSlot();
            }
        }
        for (int i = 0; i < used.length; i++) if (!used[i]) return i;
        return -1;
    }

    private static ItemStack button(Material material, String title) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title)); item.setItemMeta(meta); return item;
    }

    private static final class EditorHolder implements InventoryHolder {
        private final int shopId; private final java.util.UUID owner; private Inventory inventory;
        private EditorHolder(int shopId, java.util.UUID owner) { this.shopId = shopId; this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
