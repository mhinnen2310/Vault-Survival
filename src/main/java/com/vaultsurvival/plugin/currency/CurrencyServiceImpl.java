package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.DatabaseManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CashItemData.CashState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of CurrencyService.
 * Physical cash system where every coin corresponds to a server-authoritative database record.
 * No /balance. No digital bank. Every coin exists somewhere.
 */
public class CurrencyServiceImpl implements CurrencyService {

    private final VaultSurvivalPlugin plugin;
    private final DatabaseManager db;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final ConfigManager config;
    private final Logger logger;

    // NamespacedKey for PersistentDataContainer on cash items
    private final NamespacedKey cashUuidKey;
    private final NamespacedKey cashAmountKey;

    // Material used for physical cash items
    private final Material cashMaterial;
    private final int cashModelData;

    public CurrencyServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabase();
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.config = plugin.getConfigManager();
        this.logger = plugin.getLogger();

        this.cashUuidKey = new NamespacedKey(plugin, "vs_cash_uuid");
        this.cashAmountKey = new NamespacedKey(plugin, "vs_cash_amount");
        this.cashModelData = config.getCashModelData();

        // Load cash material from config, fallback to GOLD_NUGGET
        Material mat = Material.getMaterial(config.getCashMaterial().toUpperCase());
        this.cashMaterial = mat != null ? mat : Material.GOLD_NUGGET;
    }

    // ========================================================================
    // Core Operations
    // ========================================================================

    @Override
    public ItemStack mintCash(long amount, UUID creator, UUID recipient) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");

        UUID cashUuid = UUID.randomUUID();
        String locationType = recipient != null ? "INVENTORY" : "MINT";
        String locationId = recipient != null ? recipient.toString() : "MINT";

        // Insert database record in a transaction
        String sql = "INSERT INTO cash_items (cash_uuid, amount, state, created_at, last_seen_at, " +
                     "location_type, location_id, owner_uuid, created_by, issued_by, original_owner, current_holder) " +
                     "VALUES (?, ?, 'ACTIVE', datetime('now'), datetime('now'), ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cashUuid);
            ps.setLong(2, amount);
            ps.setString(3, locationType);
            ps.setString(4, locationId);
            ps.setObject(5, recipient);
            ps.setObject(6, creator);
            ps.setObject(7, creator);
            ps.setObject(8, recipient);
            ps.setObject(9, recipient);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to mint cash", e);
            throw new RuntimeException("Cash minting failed", e);
        }

        audit.logCashCreate(creator, creator != null ? creator.toString() : "MINT", cashUuid, amount, "MINT");

        return createCashItemStack(cashUuid, amount);
    }

    @Override
    public ItemStack materializeCash(UUID cashUuid) {
        CashItemData data = getCashData(cashUuid);
        if (data == null || data.getAmount() <= 0 || data.getState() != CashState.ACTIVE) return null;
        return createCashItemStack(cashUuid, data.getAmount());
    }

    @Override public ItemStack materializePlannedCash(UUID cashUuid,long amount){if(cashUuid==null||amount<=0)throw new IllegalArgumentException("Valid planned cash is required");return createCashItemStack(cashUuid,amount);}

    @Override
    public ItemStack[] splitCash(ItemStack cashItem, long splitAmount) {
        if (!validateCash(cashItem)) {
            logger.warning("Attempted to split invalid cash");
            return null;
        }

        CashItemData data = getCashData(cashItem);
        if (data == null) return null;

        long originalAmount = data.getAmount();
        UUID originalUuid = data.getCashUuid();
        UUID originalOwner = data.getOwnerUuid();

        if (splitAmount <= 0 || splitAmount >= originalAmount) {
            logger.warning("Invalid split amount: " + splitAmount + " from " + originalAmount);
            return null;
        }

        long remainingAmount = originalAmount - splitAmount;

        // Invalidate the original
        invalidateCash(cashItem, "SPLIT");

        // Create two new cash items preserving ownership
        ItemStack remaining = mintCash(remainingAmount, null, originalOwner);
        ItemStack split = mintCash(splitAmount, null, originalOwner);

        audit.logCashSplit(null, "SYSTEM", originalUuid, splitAmount, "SPLIT");

        return new ItemStack[] { remaining, split };
    }

    @Override
    public ItemStack mergeCash(ItemStack cash1, ItemStack cash2) {
        if (!validateCash(cash1) || !validateCash(cash2)) {
            logger.warning("Attempted to merge invalid cash");
            return null;
        }

        // Verify both items belong to the same owner
        CashItemData data1 = getCashData(cash1);
        CashItemData data2 = getCashData(cash2);
        if (data1 == null || data2 == null) return null;

        UUID owner1 = data1.getOwnerUuid();
        UUID owner2 = data2.getOwnerUuid();
        if (!Objects.equals(owner1, owner2)) {
            logger.warning("Cannot merge cash from different owners");
            return null;
        }

        long amount1 = data1.getAmount();
        long amount2 = data2.getAmount();
        long totalAmount = amount1 + amount2;

        // Invalidate both originals
        invalidateCash(cash1, "MERGED");
        invalidateCash(cash2, "MERGED");

        // Create merged item with the original owner
        ItemStack merged = mintCash(totalAmount, null, owner1);

        audit.logCashMerge(null, "SYSTEM", getCashUuid(merged), totalAmount);

        return merged;
    }

    // ========================================================================
    // Validation
    // ========================================================================

    @Override
    public boolean validateCash(ItemStack item) {
        if (!isCashItem(item)) return false;

        UUID cashUuid = getCashUuid(item);
        if (cashUuid == null) return false;

        CashItemData data = getCashData(cashUuid);
        if (data == null) return false;

        // Must be in a valid state
        if (!data.isValid()) return false;

        // Location consistency check: cash in vault/escrow/locker shouldn't appear in inventory
        CashState state = data.getState();
        if (state != CashState.ACTIVE && state != CashState.DROPPED) {
            return false;
        }

        // Verify the amount on the item matches the DB
        long dbAmount = data.getAmount();
        long itemAmount = getAmountFromPDC(item);

        return dbAmount == itemAmount;
    }

    @Override public CashSnapshot snapshot(ItemStack item) {
        UUID uuid=getCashUuid(item);return uuid==null?null:new CashSnapshot(uuid,getAmountFromPDC(item));
    }

    @Override public java.util.concurrent.CompletableFuture<Map<UUID,CashRecord>> validateCashSnapshots(List<CashSnapshot> snapshots) {
        List<CashSnapshot> immutable=snapshots==null?List.of():snapshots.stream().filter(Objects::nonNull).distinct().toList();
        if(immutable.isEmpty())return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
        return db.read(connection->{String placeholders=String.join(",",java.util.Collections.nCopies(immutable.size(),"?"));Map<UUID,CashRecord> records=new HashMap<>();
            try(PreparedStatement statement=connection.prepareStatement("SELECT cash_uuid,amount,state,location_type,location_id,owner_uuid,created_by FROM cash_items WHERE cash_uuid IN ("+placeholders+")")){for(int i=0;i<immutable.size();i++)statement.setString(i+1,immutable.get(i).cashUuid().toString());try(ResultSet rows=statement.executeQuery()){while(rows.next()){UUID id=UUID.fromString(rows.getString(1));String owner=rows.getString(6),creator=rows.getString(7);CashRecord record=new CashRecord(id,rows.getLong(2),CashState.valueOf(rows.getString(3)),rows.getString(4),rows.getString(5),owner==null?null:UUID.fromString(owner),creator==null?null:UUID.fromString(creator));records.put(id,record);}}}return Map.copyOf(records);});
    }

    @Override public java.util.concurrent.CompletableFuture<Void> updateCashLocations(List<UUID> cashUuids,String locationType,String locationId){
        List<UUID> immutable=cashUuids==null?List.of():cashUuids.stream().filter(Objects::nonNull).distinct().toList();
        if(immutable.isEmpty())return java.util.concurrent.CompletableFuture.completedFuture(null);
        return db.write(connection->{try(PreparedStatement statement=connection.prepareStatement("UPDATE cash_items SET location_type=?,location_id=?,last_seen_at=datetime('now') WHERE cash_uuid=?")){for(UUID uuid:immutable){statement.setString(1,locationType);statement.setString(2,locationId);statement.setString(3,uuid.toString());statement.addBatch();}statement.executeBatch();}return null;});
    }

    @Override
    public void invalidateCash(ItemStack cashItem, String reason) {
        UUID cashUuid = getCashUuid(cashItem);
        if (cashUuid == null) return;

        invalidateCashInDb(cashUuid, reason);

        // Clear PDC tags to prevent reuse
        clearCashPDC(cashItem);
    }

    @Override
    public void invalidateCashByUuid(UUID cashUuid, String reason) {
        invalidateCashInDb(cashUuid, reason);
        // No physical item to clear - the item will be caught by validation
    }

    private void invalidateCashInDb(UUID cashUuid, String reason) {
        CashState newState = "SPENT".equalsIgnoreCase(reason) ? CashState.SPENT : CashState.INVALIDATED;

        String sql = "UPDATE cash_items SET state = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newState.name());
            ps.setObject(2, cashUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to invalidate cash: " + cashUuid, e);
        }

        audit.logCashInvalidate(null, "SYSTEM", cashUuid, reason);
    }

    private void clearCashPDC(ItemStack cashItem) {
        ItemMeta meta = cashItem.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.remove(cashUuidKey);
            pdc.remove(cashAmountKey);
            cashItem.setItemMeta(meta);
        }
    }

    @Override
    public long getCashAmount(ItemStack cashItem) {
        CashItemData data = getCashData(cashItem);
        return data != null ? data.getAmount() : 0;
    }

    @Override
    public UUID getCashUuid(ItemStack cashItem) {
        if (cashItem == null || !cashItem.hasItemMeta()) return null;
        ItemMeta meta = cashItem.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uuidStr = pdc.get(cashUuidKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public CashItemData getCashData(ItemStack cashItem) {
        UUID uuid = getCashUuid(cashItem);
        return uuid != null ? getCashData(uuid) : null;
    }

    @Override
    public CashItemData getCashData(UUID cashUuid) {
        String sql = "SELECT cash_uuid, amount, state, created_at, last_seen_at, " +
                     "location_type, location_id, owner_uuid, created_by " +
                     "FROM cash_items WHERE cash_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cashUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapToCashItemData(rs);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get cash data: " + cashUuid, e);
        }
        return null;
    }

    @Override
    public boolean isCashItem(ItemStack item) {
        return getCashUuid(item) != null;
    }

    // ========================================================================
    // Inventory Operations
    // ========================================================================

    @Override
    public List<CashItemData> scanInventory(Player player) {
        List<CashItemData> validCash = new ArrayList<>();
        int invalidFound = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;

            if (isCashItem(item)) {
                if (validateCash(item)) {
                    CashItemData data = getCashData(item);
                    if (data != null) {
                        validCash.add(data);
                        // Update location
                        updateCashLocation(data.getCashUuid(), "INVENTORY", player.getUniqueId().toString());
                    }
                } else {
                    // Invalid cash found - remove it
                    invalidateCash(item, "COUNTERFEIT");
                    player.getInventory().remove(item);
                    invalidFound++;
                }
            }
        }

        if (invalidFound > 0) {
            logger.warning("Removed " + invalidFound + " invalid cash items from " + player.getName());
            player.sendMessage(fmt.warn(invalidFound + " counterfeit coin(s) were removed from your inventory."));
        }

        return validCash;
    }

    @Override
    public void updateCashLocation(UUID cashUuid, String locationType, String locationId) {
        String sql = "UPDATE cash_items SET location_type = ?, location_id = ?, last_seen_at = datetime('now') " +
                     "WHERE cash_uuid = ?";
        db.executeUpdateAsync(sql, locationType, locationId, cashUuid).exceptionally(error->{logger.log(Level.WARNING,"Failed to update cash location: "+cashUuid,error);return 0;});
    }

    @Override
    public boolean transferCash(ItemStack cashItem, UUID fromPlayer, UUID toPlayer) {
        if (!validateCash(cashItem)) return false;

        UUID cashUuid = getCashUuid(cashItem);

        String sql = "UPDATE cash_items SET owner_uuid = ?, location_type = 'INVENTORY', " +
                     "location_id = ?, last_seen_at = datetime('now'), state = 'ACTIVE' WHERE cash_uuid = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, toPlayer);
            ps.setString(2, toPlayer.toString());
            ps.setObject(3, cashUuid);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to transfer cash: " + cashUuid, e);
            return false;
        }
    }

    @Override
    public long getPlayerCashTotal(UUID playerUuid) {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items " +
                     "WHERE owner_uuid = ? AND state = 'ACTIVE'";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get cash total for " + playerUuid, e);
        }
        return 0;
    }

    @Override
    public List<ItemStack> withdrawCash(Player player, long amount) {
        List<ItemStack> withdrawn = new ArrayList<>();
        long remaining = amount;

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !validateCash(item)) continue;

            long itemAmount = getCashAmount(item);

            if (itemAmount <= remaining) {
                // Take the whole item
                player.getInventory().setItem(i, null);
                withdrawn.add(item);
                updateCashLocation(getCashUuid(item), "WITHDRAWN", player.getUniqueId().toString());
                remaining -= itemAmount;
            } else {
                // Split the item
                ItemStack[] split = splitCash(item.clone(), remaining);
                if (split != null) {
                    remaining = 0;
                    withdrawn.add(split[1]); // The split portion
                    player.getInventory().setItem(i, split[0]); // The remaining
                }
                break;
            }
        }

        return withdrawn;
    }

    @Override
    public void depositCash(Player player, List<ItemStack> cashItems) {
        for (ItemStack cashItem : cashItems) {
            if (!validateCash(cashItem)) continue;

            // Try to merge with existing cash items in inventory
            boolean merged = false;
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack existing = contents[i];
                if (existing != null && validateCash(existing)) {
                    ItemStack mergedItem = mergeCash(existing, cashItem);
                    if (mergedItem != null) {
                        // Replace the existing slot with the merged item
                        player.getInventory().setItem(i, mergedItem);
                        merged = true;
                        break;
                    }
                }
            }

            if (!merged) {
                HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(cashItem);
                // If inventory is full, drop at player's feet
                for (ItemStack overflowItem : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflowItem);
                    if (isCashItem(overflowItem)) {
                        updateCashLocation(getCashUuid(overflowItem), "DROPPED",
                            player.getUniqueId().toString());
                    }
                }
            }

            // Update location of the deposited cash
            UUID cashUuid = getCashUuid(cashItem);
            if (cashUuid != null) {
                updateCashLocation(cashUuid, "INVENTORY", player.getUniqueId().toString());
            }
        }
    }

    // ========================================================================
    // Stats
    // ========================================================================

    @Override
    public CurrencyStats getStats() {
        String sql = """
            SELECT
                IFNULL(SUM(CASE WHEN state = 'ACTIVE' THEN amount ELSE 0 END), 0) AS circulating,
                IFNULL(SUM(CASE WHEN state = 'IN_VAULT' THEN amount ELSE 0 END), 0) AS in_vaults,
                IFNULL(SUM(CASE WHEN state = 'IN_AH_ESCROW' THEN amount ELSE 0 END), 0) AS in_escrow,
                IFNULL(SUM(CASE WHEN state = 'IN_AUCTION_LOCKER' THEN amount ELSE 0 END), 0) AS in_lockers,
                IFNULL(SUM(CASE WHEN state = 'IN_DISTRICT_TREASURY' THEN amount ELSE 0 END), 0) AS in_treasuries,
                IFNULL(SUM(CASE WHEN state = 'DROPPED' THEN amount ELSE 0 END), 0) AS dropped,
                IFNULL(SUM(CASE WHEN state = 'SPENT' THEN amount ELSE 0 END), 0) AS spent,
                IFNULL(SUM(CASE WHEN state = 'INVALIDATED' THEN amount ELSE 0 END), 0) AS invalidated,
                COUNT(CASE WHEN state IN ('ACTIVE','DROPPED') THEN 1 END) AS active_count
            FROM cash_items
            """;

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new CurrencyStats(
                    rs.getLong("circulating"),
                    rs.getLong("in_vaults"),
                    rs.getLong("in_escrow"),
                    rs.getLong("in_lockers"),
                    rs.getLong("in_treasuries"),
                    rs.getLong("dropped"),
                    rs.getLong("spent"),
                    rs.getLong("invalidated"),
                    rs.getInt("active_count")
                );
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get currency stats", e);
        }

        return new CurrencyStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ========================================================================
    // ItemStack Creation
    // ========================================================================

    /**
     * Create the physical ItemStack representing cash.
     */
    private ItemStack createCashItemStack(UUID cashUuid, long amount) {
        ItemStack item = new ItemStack(cashMaterial, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Set display name
        String name = config.getCurrencyNamePlural();
        if (amount == 1) name = config.getCurrencyName();
        meta.displayName(fmt.deserialize("&6" + amount + " &e" + name));

        // Set lore
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(fmt.deserialize("&7&oPhysical currency of Vault Survival"));
        lore.add(fmt.deserialize("&8ID: " + cashUuid.toString().substring(0, 8) + "..."));
        meta.lore(lore);

        // Set custom model data
        meta.setCustomModelData(cashModelData);

        // Store cash UUID and amount in PersistentDataContainer
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(cashUuidKey, PersistentDataType.STRING, cashUuid.toString());
        pdc.set(cashAmountKey, PersistentDataType.LONG, amount);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Read the amount from the item's PDC (not the DB).
     */
    private long getAmountFromPDC(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Long amount = meta.getPersistentDataContainer().get(cashAmountKey, PersistentDataType.LONG);
        return amount != null ? amount : 0;
    }

    /**
     * Map a ResultSet row to a CashItemData object.
     */
    private CashItemData mapToCashItemData(ResultSet rs) throws SQLException {
        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
        long amount = rs.getLong("amount");
        CashItemData data = new CashItemData(cashUuid, amount);
        data.setState(CashState.valueOf(rs.getString("state")));
        data.setLastSeenAt(parseTimestamp(rs.getString("last_seen_at")));
        data.setLocationType(rs.getString("location_type"));
        data.setLocationId(rs.getString("location_id"));
        String ownerStr = rs.getString("owner_uuid");
        data.setOwnerUuid(ownerStr != null ? UUID.fromString(ownerStr) : null);
        String createdStr = rs.getString("created_by");
        data.setCreatedBy(createdStr != null ? UUID.fromString(createdStr) : null);
        return data;
    }

    private static java.sql.Timestamp parseTimestamp(String ts) {
        if (ts == null) return null;
        try {
            return java.sql.Timestamp.valueOf(ts.replace("T", " ").replace("Z", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
