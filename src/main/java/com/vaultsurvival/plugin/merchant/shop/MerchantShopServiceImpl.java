package com.vaultsurvival.plugin.merchant.shop;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.GUIFramework;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictTreasuryService;
import com.vaultsurvival.plugin.npc.NpcData;
import com.vaultsurvival.plugin.npc.NpcService;
import com.vaultsurvival.plugin.social.PayoutLockerService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MerchantShopServiceImpl implements MerchantShopService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final NpcService npcService;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;
    private final ConcurrentHashMap<Integer, MerchantShopData.Shop> shops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> pendingEditors = new ConcurrentHashMap<>();

    public MerchantShopServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.npcService = plugin.getServiceRegistry().get(NpcService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
    }

    // ========================================================================
    // Create Shop
    // ========================================================================

    @Override
    public MerchantShopData.Shop createShop(Player merchant, String shopName) {
        // Check MERCHANT role via district
        DistrictService districtService;
        try {
            districtService = plugin.getServiceRegistry().get(DistrictService.class);
        } catch (Exception e) {
            merchant.sendMessage(fmt.error("District service is not available."));
            return null;
        }

        DistrictData.District district = districtService.getPlayerDistrict(merchant.getUniqueId());
        if (district == null) {
            merchant.sendMessage(fmt.error("You must be a member of a district with MERCHANT role to create a shop."));
            return null;
        }

        if (!districtService.canCreateMerchantNpc(merchant.getUniqueId(), district)) {
            merchant.sendMessage(fmt.error("You need the MERCHANT, CO_MAYOR, or MAYOR role in your district."));
            return null;
        }

        if (plugin.getConfigManager().getConfig().getBoolean("districtMarket.requireMarketZone", true)) {
            try {
                RegionService regions = plugin.getServiceRegistry().get(RegionService.class);
                boolean market = regions.getRegionsAt(merchant.getLocation()).stream().anyMatch(region ->
                    region.getType() == RegionData.RegionType.AUCTION_HALL
                        || ((region.getType() == RegionData.RegionType.DISTRICT_PUBLIC
                            || region.getType() == RegionData.RegionType.DISTRICT_MARKET)
                            && region.getName().equalsIgnoreCase("district_market_" + district.getId())));
                if (!market) {
                    merchant.sendMessage(fmt.error("This district requires merchant NPCs to be placed inside a market zone."));
                    return null;
                }
            } catch (RuntimeException e) {
                merchant.sendMessage(fmt.error("Market-zone service is unavailable; shop creation is blocked safely."));
                return null;
            }
        }

        // Check limits - per merchant
        int maxPerMerchant = plugin.getConfigManager().getConfig()
            .getInt("districtMarket.maxNpcPerMerchant", 3);
        int myShopCount = getMerchantShops(merchant.getUniqueId()).size();
        if (myShopCount >= maxPerMerchant) {
            merchant.sendMessage(fmt.error("You already have " + myShopCount + " shops (max: " + maxPerMerchant + ")."));
            return null;
        }

        // Check limits - per district
        int maxPerDistrict = plugin.getConfigManager().getConfig()
            .getInt("districtMarket.maxNpcPerDistrict", 25);
        long districtShopCount = shops.values().stream()
            .filter(s -> s.getDistrictId() == district.getId())
            .count();
        if (districtShopCount >= maxPerDistrict) {
            merchant.sendMessage(fmt.error("This district already has " + districtShopCount +
                " shops (max: " + maxPerDistrict + ")."));
            return null;
        }

        // Create the NPC
        Location loc = merchant.getLocation();
        NpcData.Npc npc = npcService.createNpc(
            shopName,
            merchant.getName(), // Use merchant's skin
            loc,
            NpcData.ActionType.MERCHANT_SHOP,
            ""
        );

        if (npc == null) {
            merchant.sendMessage(fmt.error("Failed to create NPC."));
            return null;
        }

        // Insert shop record
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO merchant_shops (owner_uuid, npc_id, district_id, name, world_name, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, merchant.getUniqueId().toString());
            ps.setInt(2, npc.getId());
            ps.setInt(3, district.getId());
            ps.setString(4, shopName);
            ps.setString(5, loc.getWorld().getName());
            ps.setLong(6, now);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int shopId = keys.getInt(1);
                MerchantShopData.Shop shop = new MerchantShopData.Shop(
                    shopId, merchant.getUniqueId(), npc.getId(), district.getId(),
                    shopName, loc.getWorld().getName(), now);
                shops.put(shopId, shop);

                audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_SHOP_CREATE",
                    "SHOP", String.valueOf(shopId),
                    "name=" + shopName + " npc=" + npc.getId());

                merchant.sendMessage(fmt.success("Shop '" + shopName + "' created! NPC #" + npc.getId()));
                merchant.sendMessage(fmt.info("Shop ID: &e" + shopId + " &7| Add stock with &e/merchant shop stock " + shopId + " <slot> <qty>"));
                merchant.sendMessage(fmt.info("Set prices with &e/merchant shop prices " + shopId + " <slot> <price>"));

                return shop;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create shop", e);
            // Clean up NPC
            npcService.removeNpc(npc.getId());
        }

        merchant.sendMessage(fmt.error("Failed to create shop record."));
        return null;
    }

    // ========================================================================
    // Stock Management
    // ========================================================================

    @Override
    public boolean addStock(Player merchant, int shopId, int slot, int quantity) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null || !shop.getOwnerUuid().equals(merchant.getUniqueId())) {
            merchant.sendMessage(fmt.error("Shop not found or not yours."));
            return false;
        }

        if (slot < 0 || slot > 53) {
            merchant.sendMessage(fmt.error("Slot must be between 0 and 53."));
            return false;
        }
        if (quantity <= 0) {
            merchant.sendMessage(fmt.error("Quantity must be greater than 0."));
            return false;
        }

        // Get the held item as the stock template
        ItemStack heldItem = merchant.getInventory().getItemInMainHand();
        if (heldItem.getType().isAir()) {
            merchant.sendMessage(fmt.error("Hold the item you want to stock in your main hand."));
            return false;
        }

        // Check player has enough of this item
        int available = countMatchingItems(merchant, heldItem.getType());
        if (available < quantity) {
            merchant.sendMessage(fmt.error("You only have " + available + " of that item. Need " + quantity + "."));
            return false;
        }

        // Check if this slot already has an item and it matches
        var existingItems = getShopItems(shopId);
        var existing = existingItems.stream().filter(i -> i.getSlot() == slot).findFirst();
        if (existing.isPresent()) {
            if (!isSameMaterial(existing.get().getItemData(), heldItem.getType())) {
                merchant.sendMessage(fmt.error("Slot " + slot + " already has a different item. Clear it first."));
                return false;
            }
        }

        // Consume items from player
        int removed = removeMatchingItems(merchant, heldItem.getType(), quantity);
        if (removed <= 0) {
            merchant.sendMessage(fmt.error("Failed to remove items from your inventory."));
            return false;
        }

        ItemStack template = heldItem.clone();
        template.setAmount(1);
        String itemData = serializeItem(template);
        String itemDisplay = getItemDisplay(template);

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (existing.isPresent()) {
                    // Update existing stock
                    plugin.getDatabase().executeUpdate(
                        "UPDATE merchant_shop_items SET stock = stock + ?, item_data = ? WHERE id = ?",
                        removed, itemData, existing.get().getId());
                } else {
                    // Insert new item row
                    plugin.getDatabase().executeUpdate(
                        "INSERT INTO merchant_shop_items (shop_id, slot, item_data, item_display, stock, price) " +
                        "VALUES (?, ?, ?, ?, ?, 0)",
                        shopId, slot, itemData, itemDisplay, removed);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add stock", e);
            // Return items
            returnItems(merchant, template, removed);
            merchant.sendMessage(fmt.error("Failed to add stock. Items returned."));
            return false;
        }

        audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_SHOP_STOCK",
            "SHOP", String.valueOf(shopId),
            "slot=" + slot + " item=" + itemDisplay + " qty=" + removed);

        merchant.sendMessage(fmt.success("Added &e" + removed + "x " + itemDisplay +
            " &ato shop slot " + slot + "."));
        merchant.sendMessage(fmt.info("Don't forget to set a price! &e/merchant shop prices " + shopId + " " + slot + " <price>"));

        return true;
    }

    @Override
    public boolean setPrice(Player merchant, int shopId, int slot, long price) {
        return setPriceInternal(merchant, shopId, slot, price, true);
    }

    @Override public boolean setPriceSilently(Player merchant, int shopId, int slot, long price) {
        return setPriceInternal(merchant, shopId, slot, price, false);
    }

    private boolean setPriceInternal(Player merchant, int shopId, int slot, long price, boolean notify) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null || !shop.getOwnerUuid().equals(merchant.getUniqueId())) {
            if (notify) merchant.sendMessage(fmt.error("Shop not found or not yours."));
            return false;
        }
        if (price < 0) {
            if (notify) merchant.sendMessage(fmt.error("Price cannot be negative."));
            return false;
        }

        try {
            int rows = 0;
            try (Connection conn = plugin.getDatabase().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE merchant_shop_items SET price = ? WHERE shop_id = ? AND slot = ?")) {
                ps.setLong(1, price);
                ps.setInt(2, shopId);
                ps.setInt(3, slot);
                rows = ps.executeUpdate();
            }
            if (rows == 0) {
                if (notify) merchant.sendMessage(fmt.error("No item in slot " + slot + ". Add stock first."));
                return false;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set price", e);
            return false;
        }

        if (notify) merchant.sendMessage(fmt.success("Price for slot " + slot + " set to &6" +
            fmt.formatMoney(price, plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
        return true;
    }

    @Override
    public boolean removeStock(Player merchant, int shopId, int slot, int quantity) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null || !shop.getOwnerUuid().equals(merchant.getUniqueId())) {
            merchant.sendMessage(fmt.error("Shop not found or not yours."));
            return false;
        }

        var items = getShopItems(shopId);
        var existing = items.stream().filter(i -> i.getSlot() == slot).findFirst();
        if (existing.isEmpty()) {
            merchant.sendMessage(fmt.error("No item in slot " + slot + "."));
            return false;
        }

        MerchantShopData.ShopItem shopItem = existing.get();
        int toRemove = Math.min(quantity, shopItem.getStock());

        ItemStack item = deserializeItem(shopItem.getItemData());
        item.setAmount(toRemove);

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                int newStock = shopItem.getStock() - toRemove;
                if (newStock <= 0) {
                    plugin.getDatabase().executeUpdate(
                        "DELETE FROM merchant_shop_items WHERE id = ?", shopItem.getId());
                } else {
                    plugin.getDatabase().executeUpdate(
                        "UPDATE merchant_shop_items SET stock = ? WHERE id = ?",
                        newStock, shopItem.getId());
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remove stock", e);
            return false;
        }

        // Give items to merchant
        var overflow = merchant.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                merchant.getWorld().dropItemNaturally(merchant.getLocation(), drop);
            }
        }

        merchant.sendMessage(fmt.success("Removed &e" + toRemove + "x " + shopItem.getItemDisplay() +
            " &afrom slot " + slot + "."));
        return true;
    }

    // ========================================================================
    // Buy Item
    // ========================================================================

    @Override
    public boolean buyItem(Player buyer, int shopId, int slot, int quantity) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null) {
            buyer.sendMessage(fmt.error("Shop not found."));
            return false;
        }

        var items = getShopItems(shopId);
        var existing = items.stream().filter(i -> i.getSlot() == slot).findFirst();
        if (existing.isEmpty()) {
            buyer.sendMessage(fmt.error("No item in this slot."));
            return false;
        }

        MerchantShopData.ShopItem shopItem = existing.get();
        if (shopItem.getPrice() <= 0) {
            buyer.sendMessage(fmt.error("This item has no price set."));
            return false;
        }
        if (shopItem.getStock() <= 0) {
            buyer.sendMessage(fmt.error("This item is out of stock."));
            return false;
        }

        int toBuy = Math.min(quantity, shopItem.getStock());
        long totalPrice = shopItem.getPrice() * toBuy;

        // Check buyer has enough cash
        long buyerCash = currency.getPlayerCashTotal(buyer.getUniqueId());
        if (buyerCash < totalPrice) {
            buyer.sendMessage(fmt.error("You need &6" + fmt.formatMoney(totalPrice,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) +
                " &cbut only have &6" + fmt.formatMoney(buyerCash,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            return false;
        }

        // Calculate tax
        int taxPercent = plugin.getConfigManager().getConfig()
            .getInt("districtMarket.defaultTaxPercent", 10);
        long taxAmount = Math.max(0, Math.round(totalPrice * (taxPercent / 100.0)));
        long netEarnings = totalPrice - taxAmount;

        DistrictTreasuryService treasury = null;
        if (taxAmount > 0) {
            try { treasury = plugin.getServiceRegistry().get(DistrictTreasuryService.class); }
            catch (RuntimeException ignored) { }
            if (treasury == null || treasury.getVaults(shop.getDistrictId()).isEmpty()) {
                buyer.sendMessage(fmt.error("This shop's district has no registered physical treasury vault for tax."));
                return false;
            }
        }

        PayoutLockerService payouts;
        try {
            payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        } catch (Exception e) {
            buyer.sendMessage(fmt.error("Payout system unavailable."));
            return false;
        }

        // Withdraw cash from buyer
        List<ItemStack> withdrawn = currency.withdrawCash(buyer, totalPrice);
        long totalPaid = withdrawn.stream().mapToLong(currency::getCashAmount).sum();
        if (totalPaid < totalPrice) {
            currency.depositCash(buyer, withdrawn);
            buyer.sendMessage(fmt.error("Payment failed. Cash returned."));
            return false;
        }

        // Update DB first - mark cash as spent, update stock, record sale, handle tax
        long now = System.currentTimeMillis();
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Mark withdrawn cash as spent
                try (PreparedStatement spend = conn.prepareStatement(
                        "UPDATE cash_items SET state='SPENT',location_type='MERCHANT_PURCHASE',location_id=?,owner_uuid=NULL,last_seen_at=datetime('now') WHERE cash_uuid=?")) {
                    for (ItemStack cashItem : withdrawn) {
                        spend.setString(1, String.valueOf(shopId));
                        spend.setString(2, currency.getCashUuid(cashItem).toString());
                        if (spend.executeUpdate() != 1) throw new SQLException("Purchase cash changed concurrently");
                    }
                }

                // Update stock
                int newStock = shopItem.getStock() - toBuy;
                if (newStock <= 0) {
                    plugin.getDatabase().executeUpdate(
                        "DELETE FROM merchant_shop_items WHERE id = ?", shopItem.getId());
                } else {
                    plugin.getDatabase().executeUpdate(
                        "UPDATE merchant_shop_items SET stock = ? WHERE id = ?",
                        newStock, shopItem.getId());
                }

                // Record sale
                plugin.getDatabase().executeUpdate(
                    "INSERT INTO merchant_shop_sales (shop_id, buyer_uuid, item_data, quantity, price_each, tax_amount, timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    shopId, buyer.getUniqueId().toString(), shopItem.getItemData(),
                    toBuy, shopItem.getPrice(), taxAmount, now);

                conn.commit();

                if (taxAmount > 0) {
                    var taxCredit = treasury.creditSystem(shop.getDistrictId(), taxAmount,
                        "MERCHANT_SHOP_TAX", buyer.getUniqueId());
                    if (!taxCredit.success()) {
                        logger.severe("Committed shop tax could not be credited: " + taxCredit.message());
                    }
                }

                // Create payout locker entry for merchant
                payouts.storePayout(shop.getOwnerUuid(), netEarnings,
                    "MERCHANT_SHOP", String.valueOf(shopId),
                    "sold=" + toBuy + "x " + shopItem.getItemDisplay() + " tax=" + taxAmount);

                audit.log(buyer.getUniqueId(), buyer.getName(), "MERCHANT_SHOP_BUY",
                    "SHOP", String.valueOf(shopId),
                    "item=" + shopItem.getItemDisplay() + " qty=" + toBuy +
                    " price=" + totalPrice + " tax=" + taxAmount);

                // Give item to buyer AFTER successful commit
                ItemStack item = deserializeItem(shopItem.getItemData());
                item.setAmount(toBuy);
                var overflowItems = buyer.getInventory().addItem(item);
                if (!overflowItems.isEmpty()) {
                    for (ItemStack drop : overflowItems.values()) {
                        buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop);
                    }
                    buyer.sendMessage(fmt.warn("Some items dropped at your feet (inventory full)."));
                }

                buyer.sendMessage(fmt.success("Bought &e" + toBuy + "x " + shopItem.getItemDisplay() +
                    " &afor &6" + fmt.formatMoney(totalPrice,
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())));
                if (taxAmount > 0) {
                    buyer.sendMessage(fmt.info("Tax paid: &6" + fmt.formatMoney(taxAmount,
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural()) +
                        " &7(to district treasury)"));
                }

                // Notify merchant if online
                Player merchant = Bukkit.getPlayer(shop.getOwnerUuid());
                if (merchant != null) {
                    merchant.sendMessage(fmt.info("&e" + buyer.getName() + " &7bought &e" +
                        toBuy + "x " + shopItem.getItemDisplay() +
                        " &7from your shop '" + shop.getName() + "'!"));
                    merchant.sendMessage(fmt.info("Earnings: &6" + fmt.formatMoney(netEarnings,
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural()) +
                        " &7- Collect it by interacting with your shop NPC."));
                }

                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to process purchase", e);
            // Refund cash - DB didn't commit so cash items are still valid
            currency.depositCash(buyer, withdrawn);
            buyer.sendMessage(fmt.error("Purchase failed. Your cash has been returned."));
            return false;
        }
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public MerchantShopData.Shop getShop(int shopId) {
        return shops.get(shopId);
    }

    @Override
    public MerchantShopData.Shop getShopByNpcId(int npcId) {
        return shops.values().stream()
            .filter(s -> s.getNpcId() == npcId)
            .findFirst().orElse(null);
    }

    @Override
    public List<MerchantShopData.Shop> getMerchantShops(UUID merchantUuid) {
        return shops.values().stream()
            .filter(s -> s.getOwnerUuid().equals(merchantUuid))
            .toList();
    }

    @Override
    public List<MerchantShopData.ShopItem> getShopItems(int shopId) {
        List<MerchantShopData.ShopItem> items = new ArrayList<>();
        String sql = "SELECT * FROM merchant_shop_items WHERE shop_id = ? ORDER BY slot";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                items.add(new MerchantShopData.ShopItem(
                    rs.getInt("id"),
                    rs.getInt("shop_id"),
                    rs.getInt("slot"),
                    rs.getString("item_data"),
                    rs.getString("item_display"),
                    rs.getInt("stock"),
                    rs.getLong("price")
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get shop items", e);
        }
        return items;
    }

    @Override
    public List<MerchantShopData.Sale> getSales(int shopId) {
        List<MerchantShopData.Sale> sales = new ArrayList<>();
        String sql = "SELECT * FROM merchant_shop_sales WHERE shop_id = ? ORDER BY timestamp DESC LIMIT 50";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sales.add(new MerchantShopData.Sale(
                    rs.getInt("id"),
                    rs.getInt("shop_id"),
                    UUID.fromString(rs.getString("buyer_uuid")),
                    rs.getString("item_data"),
                    rs.getInt("quantity"),
                    rs.getLong("price_each"),
                    rs.getLong("tax_amount"),
                    rs.getLong("timestamp")
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get sales", e);
        }
        return sales;
    }

    @Override
    public List<MerchantShopData.Shop> getAllShops() {
        return new ArrayList<>(shops.values());
    }

    @Override
    public void openShopGui(Player player, int npcId) {
        MerchantShopData.Shop shop = getShopByNpcId(npcId);
        if (shop == null) {
            player.sendMessage(fmt.error("This shop is not configured."));
            return;
        }

        if (shop.getOwnerUuid().equals(player.getUniqueId())) {
            Long pendingUntil = pendingEditors.remove(player.getUniqueId());
            if (pendingUntil != null && pendingUntil >= System.currentTimeMillis()) {
                openShopEditor(player, shop.getId());
                return;
            }
            try {
                var dialogs = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.dialogs.DialogService.class);
                dialogs.openResult(player, shop.getName(), "Choose how you want to use your shop NPC.", List.of(
                    com.vaultsurvival.plugin.dialogs.DialogMenuItem.item("Open Shop", "Browse the customer inventory.", "merchant shop browse " + shop.getId(), null, Material.CHEST),
                    com.vaultsurvival.plugin.dialogs.DialogMenuItem.item("Edit Shop", "Move inventory items into shop slots and set prices.", "merchant shop edit " + shop.getId(), null, Material.CRAFTING_TABLE),
                    com.vaultsurvival.plugin.dialogs.DialogMenuItem.item("Collect Earnings", "Collect pending proceeds as physical cash here.", "merchant shop collect " + shop.getId(), null, Material.GOLD_INGOT)
                ));
                return;
            } catch (RuntimeException ignored) { }
        }

        openCustomerShop(player, shop.getId());
    }

    @Override
    public void openCustomerShop(Player player, int shopId) {
        MerchantShopData.Shop shop = getShop(shopId);
        if (shop == null) {
            player.sendMessage(fmt.error("Shop not found."));
            return;
        }
        List<MerchantShopData.ShopItem> items = getShopItems(shop.getId());
        if (items.isEmpty()) {
            player.closeInventory();
            try {
                plugin.getServiceRegistry().get(com.vaultsurvival.plugin.dialogs.DialogService.class).openResult(player,
                    shop.getName(), "This shop is currently sold out.", List.of());
            } catch (RuntimeException fallback) { player.sendMessage(fmt.info("This shop has no items in stock.")); }
            return;
        }

        var guiItems = new ArrayList<GUIFramework.GUIItem>();
        for (MerchantShopData.ShopItem shopItem : items) {
            ItemStack display = deserializeItem(shopItem.getItemData());

            var meta = display.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = meta.lore() != null ?
                    new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(MessageFormatter.deserializeLegacy("&7Price: &6" + fmt.formatMoney(shopItem.getPrice(),
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
                lore.add(MessageFormatter.deserializeLegacy("&7Stock: &e" + shopItem.getStock()));
                lore.add(MessageFormatter.deserializeLegacy("&eClick to buy 1!"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }

            guiItems.add(new GUIFramework.GUIItem(shopItem.getSlot(), display, (p, e) -> {
                int buyQty = e.isShiftClick() ? Math.min(64, shopItem.getStock()) : 1;
                if (buyItem(p, shop.getId(), shopItem.getSlot(), buyQty)) {
                    Bukkit.getScheduler().runTask(plugin, () -> openCustomerShop(p, shop.getId()));
                }
            }));
        }

        plugin.getGuiFramework().openGUI(player,
            "&8" + shop.getName() + " (Shop #" + shop.getId() + ")", 4, guiItems);

        player.sendMessage(fmt.info("&eShift+click &7to buy a stack (up to 64)."));
    }

    @Override
    public void beginShopEdit(Player merchant) {
        if (getMerchantShops(merchant.getUniqueId()).isEmpty()) {
            merchant.sendMessage(fmt.error("You do not own a merchant shop."));
            return;
        }
        pendingEditors.put(merchant.getUniqueId(), System.currentTimeMillis() + 60_000L);
        merchant.sendMessage(fmt.info("Right-click the shop NPC you want to edit within 60 seconds."));
    }

    @Override
    public void openShopEditor(Player merchant, int shopId) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null || !shop.getOwnerUuid().equals(merchant.getUniqueId())) {
            merchant.sendMessage(fmt.error("Shop not found or not yours."));
            return;
        }
        MerchantShopEditor.open(plugin, this, merchant, shop);
    }

    @Override
    public boolean depositEditorStack(Player merchant, int shopId, int slot, ItemStack supplied) {
        MerchantShopData.Shop shop = shops.get(shopId);
        if (shop == null || !shop.getOwnerUuid().equals(merchant.getUniqueId()) || slot < 0 || slot >= 45
            || supplied == null || supplied.getType().isAir() || supplied.getAmount() <= 0) return false;
        ItemStack template = supplied.clone(); template.setAmount(1);
        MerchantShopData.ShopItem existing = getShopItems(shopId).stream().filter(row -> row.getSlot() == slot).findFirst().orElse(null);
        if (existing != null && !deserializeItem(existing.getItemData()).isSimilar(template)) {
            merchant.sendMessage(fmt.error("That shop slot contains a different item."));
            return false;
        }
        try (Connection connection = plugin.getDatabase().getConnection()) {
            if (existing == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO merchant_shop_items(shop_id,slot,item_data,item_display,stock,price) VALUES(?,?,?,?,?,0)")) {
                    insert.setInt(1, shopId); insert.setInt(2, slot); insert.setString(3, serializeItem(template));
                    insert.setString(4, getItemDisplay(template)); insert.setInt(5, supplied.getAmount()); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("UPDATE merchant_shop_items SET stock=stock+? WHERE id=?")) {
                    update.setInt(1, supplied.getAmount()); update.setInt(2, existing.getId()); update.executeUpdate();
                }
            }
        } catch (SQLException error) {
            logger.log(Level.WARNING, "Failed editor stock deposit", error);
            return false;
        }
        audit.log(merchant.getUniqueId(), merchant.getName(), "MERCHANT_SHOP_EDITOR_STOCK", "SHOP", String.valueOf(shopId),
            "slot=" + slot + " qty=" + supplied.getAmount() + " item=" + getItemDisplay(template));
        return true;
    }

    @Override public ItemStack itemStack(MerchantShopData.ShopItem item) { return deserializeItem(item.getItemData()); }

    @Override
    public void loadAll() {
        shops.clear();
        String sql = "SELECT * FROM merchant_shops";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MerchantShopData.Shop shop = new MerchantShopData.Shop(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("owner_uuid")),
                    rs.getInt("npc_id"),
                    rs.getInt("district_id"),
                    rs.getString("name"),
                    rs.getString("world_name"),
                    rs.getLong("created_at")
                );
                shops.put(shop.getId(), shop);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load shops", e);
        }
        logger.info("Loaded " + shops.size() + " merchant shops");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private int countMatchingItems(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int removeMatchingItems(Player player, Material material, int quantity) {
        int remaining = quantity;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == material) {
                int stackAmount = item.getAmount();
                if (stackAmount <= remaining) {
                    remaining -= stackAmount;
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }
        return quantity - remaining;
    }

    private void returnItems(Player player, ItemStack templateItem, int count) {
        templateItem.setAmount(count);
        var overflow = player.getInventory().addItem(templateItem);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    private boolean isSameMaterial(String base64Item, Material material) {
        try {
            ItemStack item = deserializeItem(base64Item);
            return item.getType() == material;
        } catch (Exception e) {
            return false;
        }
    }

    private String getItemDisplay(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return formatMaterialName(item.getType().name());
    }

    private String formatMaterialName(String name) {
        String lower = name.replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : lower.toCharArray()) {
            if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else if (c == ' ') { sb.append(' '); cap = true; }
            else sb.append(c);
        }
        return sb.toString();
    }

    // Serialization

    private static String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
            boos.writeObject(item);
            boos.close();
            return Base64Coder.encodeLines(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    private static ItemStack deserializeItem(String data) {
        try {
            byte[] bytes = Base64Coder.decodeLines(data);
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
            return (ItemStack) bois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }
}
