package com.vaultsurvival.plugin.store;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class StoreServiceImpl implements StoreService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final Logger logger;
    private final MessageFormatter fmt;
    private final Map<String, StoreData.CosmeticItem> items = new ConcurrentHashMap<>();

    public StoreServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.logger = plugin.getLogger(); this.fmt = plugin.getMessageFormatter();
    }

    @Override public List<StoreData.CosmeticItem> getItems(String category) {
        return items.values().stream()
            .filter(i -> category == null || i.getCategory().equalsIgnoreCase(category))
            .toList();
    }

    @Override
    public boolean purchaseItem(UUID playerUuid, String itemId) {
        var item = items.get(itemId);
        if (item == null) return false;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || currency == null) return false;

        long balance = currency.getPlayerCashTotal(playerUuid);
        if (balance < item.getPrice()) {
            player.sendMessage(fmt.error("You need " + fmt.formatMoney(item.getPrice(),
                plugin.getConfigManager().getCurrencyName(), plugin.getConfigManager().getCurrencyNamePlural())));
            return false;
        }

        // Deduct cash via DB
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = item.getPrice();
                try (PreparedStatement ps = conn.prepareStatement("SELECT cash_uuid, amount FROM cash_items WHERE state='ACTIVE' AND owner_uuid=? ORDER BY amount ASC")) {
                    ps.setString(1, playerUuid.toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cuuid = UUID.fromString(rs.getString("cash_uuid"));
                        long amt = rs.getLong("amount");
                        if (amt <= remaining) {
                            try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET state='SPENT' WHERE cash_uuid=?")) { up.setString(1, cuuid.toString()); up.executeUpdate(); }
                            remaining -= amt;
                        } else {
                            try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET amount=? WHERE cash_uuid=?")) { up.setLong(1, amt-remaining); up.setString(2, cuuid.toString()); up.executeUpdate(); }
                            remaining = 0;
                        }
                    }
                }
                if (remaining > 0) { conn.rollback(); return false; }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        } catch (SQLException e) { logger.log(Level.WARNING, "Store purchase failed", e); return false; }

        // Remove physical cash
        removeCashInv(player, item.getPrice());

        // Give cosmetic item
        Material mat = Material.getMaterial(item.getMaterial());
        if (mat == null) mat = Material.PAPER;
        ItemStack give = new ItemStack(mat);
        ItemMeta meta = give.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§r" + item.getDisplayName().replace('&', '§'));
            meta.setCustomModelData(item.getCustomModelData());
            meta.setLore(List.of("§7" + item.getDescription()));
            give.setItemMeta(meta);
        }
        if (player.getInventory().firstEmpty() == -1) player.getWorld().dropItemNaturally(player.getLocation(), give);
        else player.getInventory().addItem(give);

        player.sendMessage(fmt.success("Purchased &e" + item.getDisplayName()));
        return true;
    }

    private void removeCashInv(Player p, long amount) {
        long remaining = amount;
        for (int i = 0; i < p.getInventory().getSize() && remaining > 0; i++) {
            var it = p.getInventory().getItem(i);
            if (it != null && currency.isCashItem(it)) {
                long amt = currency.getCashAmount(it);
                if (amt <= remaining) { remaining -= amt; p.getInventory().setItem(i, null); }
                else { p.getInventory().setItem(i, null); remaining = 0; }
            }
        }
    }

    @Override public void reloadItems() { loadItems(); }
    public void loadItems() {
        items.clear();
        // Default cosmetic items
        addItem("gold_name", "&6Golden Name Tag", "NAME_TAG", 2001, 5000, "Chat", "A shiny golden name tag");
        addItem("crown_hat", "&6Crown Hat", "GOLDEN_HELMET", 2002, 15000, "Hats", "A regal crown for your head");
        addItem("diamond_trail", "&bDiamond Particle Trail", "DIAMOND", 2003, 25000, "Effects", "Leave a trail of sparkles");
        addItem("fire_wings", "&cFire Wings Cape", "ELYTRA", 2004, 50000, "Wings", "Cape that looks like fire wings");
        addItem("rainbow_chat", "&dRainbow Chat Color", "INK_SAC", 2005, 10000, "Chat", "Your messages cycle through colors");
        addItem("ender_particles", "&5Ender Particles", "ENDER_PEARL", 2006, 20000, "Effects", "Ender particles surround you");
        logger.info("Loaded " + items.size() + " store items");
    }

    private void addItem(String id, String name, String mat, int model, long price, String cat, String desc) {
        items.put(id, new StoreData.CosmeticItem(id, name, mat, model, price, cat, desc));
    }
}
