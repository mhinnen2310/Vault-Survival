package com.vaultsurvival.plugin.vaults;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.vaults.VaultData.VaultTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of VaultService.
 * Vaults are physical blocks that store cash. They cannot be broken normally
 * and always protect at least 50% of their value from breach theft.
 */
public class VaultServiceImpl implements VaultService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final RegionService regions;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;

    // The block material used for vaults in the world
    private static final Material VAULT_MATERIAL = Material.BARREL;

    // NamespacedKey for storing vault UUID on the block
    private final NamespacedKey vaultUuidKey;

    public VaultServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
        this.vaultUuidKey = new NamespacedKey(plugin, "vs_vault_uuid");
    }

    // ========================================================================
    // Placement and Removal
    // ========================================================================

    @Override
    public UUID placeVault(Player player, Location location, VaultTier tier) {
        UUID ownerUuid = player.getUniqueId();
        UUID vaultUuid = UUID.randomUUID();

        // Check region allows block placement
        if (regions != null && !regions.isAllowed(location, RegionData.RuleFlag.BLOCK_PLACE)) {
            player.sendMessage(fmt.error("You cannot place vaults in this area."));
            return null;
        }

        // Check region restrictions (Phase 5 will enhance this)
        // For now, allow placement anywhere outside spawn protection

        // Insert database record
        String sql = "INSERT INTO vaults (vault_uuid, tier, world, x, y, z, owner_uuid, " +
                     "capacity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, vaultUuid);
            ps.setString(2, tier.name());
            ps.setString(3, location.getWorld().getName());
            ps.setInt(4, location.getBlockX());
            ps.setInt(5, location.getBlockY());
            ps.setInt(6, location.getBlockZ());
            ps.setObject(7, ownerUuid);
            ps.setLong(8, tier.getCapacity());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to place vault", e);
            return null;
        }

        // Place the physical block
        location.getBlock().setType(VAULT_MATERIAL);

        // Store vault UUID in block PDC (for post-restart lookup)
        // This is stored in the chunk data, not on the block itself
        // For v1, we rely on DB lookup by location

        // Grant owner access
        grantAccess(vaultUuid, ownerUuid, "OWNER", ownerUuid);

        audit.log(ownerUuid, player.getName(), "VAULT_PLACE", "VAULT", vaultUuid.toString(),
            "tier=" + tier.name() + " loc=" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());

        player.sendMessage(fmt.success("Vault placed! Tier: &e" + tier.getDisplayName()));
        player.sendMessage(fmt.info("Capacity: &e" + fmt.formatMoney(tier.getCapacity(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        return vaultUuid;
    }

    @Override
    public boolean removeVault(Player player, UUID vaultUuid) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) {
            player.sendMessage(fmt.error("Vault not found."));
            return false;
        }

        // Only owner or admin
        if (!vault.getOwnerUuid().equals(player.getUniqueId()) &&
            !player.hasPermission("vs.vault.admin.inspect")) {
            player.sendMessage(fmt.error("You don't own this vault."));
            return false;
        }

        long balance = getBalance(vaultUuid);
        if (balance > 0) {
            player.sendMessage(fmt.error("The vault still contains &6" + balance + "&c coins. Withdraw them first."));
            return false;
        }

        // Remove related data first, then the vault itself
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Clean up access entries
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM vault_access WHERE vault_uuid = ?")) {
                    ps.setObject(1, vaultUuid);
                    ps.executeUpdate();
                }
                // Clean up repairs
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM vault_repairs WHERE vault_uuid = ?")) {
                    ps.setObject(1, vaultUuid);
                    ps.executeUpdate();
                }
                // Remove the vault
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM vaults WHERE vault_uuid = ?")) {
                    ps.setObject(1, vaultUuid);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to remove vault", e);
            return false;
        }

        // Restore the physical block to air
        World world = plugin.getServer().getWorld(vault.getWorld());
        if (world != null) {
            Location loc = new Location(world, vault.getX(), vault.getY(), vault.getZ());
            loc.getBlock().setType(Material.AIR);
        }

        audit.log(player.getUniqueId(), player.getName(), "VAULT_REMOVE", "VAULT",
            vaultUuid.toString(), "owner removal");

        player.sendMessage(fmt.success("Vault removed."));
        return true;
    }

    // ========================================================================
    // Deposit / Withdraw
    // ========================================================================

    @Override
    public boolean depositCash(Player player, UUID vaultUuid, List<ItemStack> cashItems) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) return false;

        if (!canAccess(vaultUuid, player.getUniqueId())) {
            player.sendMessage(fmt.error("You don't have access to this vault."));
            return false;
        }

        // Calculate total deposit and check capacity
        long currentBalance = getBalance(vaultUuid);
        long depositTotal = 0;
        for (ItemStack item : cashItems) {
            if (currency.validateCash(item)) {
                depositTotal += currency.getCashAmount(item);
            }
        }

        if (currentBalance + depositTotal > vault.getCapacity()) {
            player.sendMessage(fmt.error("Vault capacity exceeded. Max: &6" +
                vault.getCapacity()));
            return false;
        }

        // Transfer each cash item into the vault
        long totalDeposited = 0;
        for (ItemStack item : cashItems) {
            if (!currency.validateCash(item)) continue;

            UUID cashUuid = currency.getCashUuid(item);
            long amount = currency.getCashAmount(item);

            // Remove from player inventory first (before modifying item PDC)
            player.getInventory().remove(item);

            // Update cash location to vault
            currency.updateCashLocation(cashUuid, "IN_VAULT", vaultUuid.toString());

            // Mark the physical cash as stored (changes state, doesn't destroy)
            // The cash still exists but is now in the vault
            totalDeposited += amount;
        }

        audit.logVaultDeposit(player.getUniqueId(), player.getName(), vaultUuid,
            null, totalDeposited);

        player.sendMessage(fmt.success("Deposited &6" + fmt.formatMoney(totalDeposited,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        return true;
    }

    @Override
    public List<ItemStack> withdrawCash(Player player, UUID vaultUuid, long amount) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) return List.of();

        if (!canAccess(vaultUuid, player.getUniqueId())) {
            player.sendMessage(fmt.error("You don't have access to this vault."));
            return List.of();
        }

        long available = getBalance(vaultUuid);
        if (amount > available) {
            player.sendMessage(fmt.error("Insufficient funds. Available: &6" +
                fmt.formatMoney(available,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
            return List.of();
        }

        // Create new physical cash for the withdrawal
        ItemStack withdrawnCash = currency.mintCash(amount, player.getUniqueId(), player.getUniqueId());

        // Check inventory space
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(fmt.error("Your inventory is full! Make space first."));
            return List.of();
        }

        player.getInventory().addItem(withdrawnCash);
        // The new cash is already in the player's inventory with state ACTIVE

        // Move vault cash back to inventory: update cash items from IN_VAULT to ACTIVE
        moveVaultCashToPlayer(vaultUuid, amount, player.getUniqueId());

        audit.logVaultWithdraw(player.getUniqueId(), player.getName(), vaultUuid,
            currency.getCashUuid(withdrawnCash), amount);

        return List.of(withdrawnCash);
    }

    /**
     * Move cash from vault state back to player inventory.
     * Updates cash_items state from IN_VAULT to ACTIVE and assigns new owner.
     */    private void moveVaultCashToPlayer(UUID vaultUuid, long amount, UUID playerUuid) {
        String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                         "WHERE state = 'IN_VAULT' AND location_id = ? ORDER BY amount ASC";

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                List<UUID> toSpend = new ArrayList<>();
                UUID partialUuid = null;
                long partialNewAmount = 0;

                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, vaultUuid.toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmount = rs.getLong("amount");

                        if (cashAmount <= remaining) {
                            toSpend.add(cashUuid);
                            remaining -= cashAmount;
                        } else {
                            // Partial spend: reduce this item's amount
                            partialUuid = cashUuid;
                            partialNewAmount = cashAmount - remaining;
                            remaining = 0;
                        }
                    }
                }

                // Mark whole items as SPENT
                if (!toSpend.isEmpty()) {
                    String spendSql = "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') " +
                                      "WHERE cash_uuid = ?";
                    try (PreparedStatement ps = conn.prepareStatement(spendSql)) {
                        for (UUID uuid : toSpend) {
                            ps.setString(1, uuid.toString());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // Reduce amount of partially-spent item
                if (partialUuid != null) {
                    String partialSql = "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') " +
                                        "WHERE cash_uuid = ?";
                    try (PreparedStatement ps = conn.prepareStatement(partialSql)) {
                        ps.setLong(1, partialNewAmount);
                        ps.setString(2, partialUuid.toString());
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to mark vault cash on withdrawal", e);
        }
    }

    // ========================================================================
    // Balance and Protection
    // ========================================================================

    @Override
    public long getBalance(UUID vaultUuid) {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items " +
                     "WHERE state = 'IN_VAULT' AND location_id = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vaultUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get vault balance", e);
        }
        return 0;
    }

    @Override
    public long getProtectedAmount(UUID vaultUuid) {
        long balance = getBalance(vaultUuid);
        return (long) Math.floor(balance * 0.50);
    }

    @Override
    public long getStealableAmount(UUID vaultUuid) {
        long balance = getBalance(vaultUuid);
        long protected_ = getProtectedAmount(vaultUuid);
        return balance - protected_;
    }

    @Override
    public long getCapacity(VaultTier tier) {
        return tier.getCapacity();
    }

    // ========================================================================
    // Lockdown
    // ========================================================================

    @Override
    public boolean isLockedDown(UUID vaultUuid) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) return true;
        if (!vault.isLockedDown()) return false;

        // Check if lockdown has expired
        if (vault.getLockoutUntil() != null &&
            vault.getLockoutUntil().before(new Timestamp(System.currentTimeMillis()))) {
            // Lockdown expired, auto-clear
            clearLockdown(vaultUuid);
            return false;
        }

        return true;
    }

    @Override
    public long getLockdownRemaining(UUID vaultUuid) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null || vault.getLockoutUntil() == null) return 0;
        long remaining = vault.getLockoutUntil().getTime() - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    @Override
    public boolean repairVault(Player player, UUID vaultUuid) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) return false;

        if (!canAccess(vaultUuid, player.getUniqueId())) {
            player.sendMessage(fmt.error("You don't have access to this vault."));
            return false;
        }

        if (!vault.isLockedDown()) {
            player.sendMessage(fmt.error("Vault is not in lockdown."));
            return false;
        }

        // Cost: 5% of vault balance to repair
        long balance = getBalance(vaultUuid);
        long repairCost = Math.max(100, balance / 20);

        // Check if player has enough cash
        long playerCash = currency.getPlayerCashTotal(player.getUniqueId());
        if (playerCash < repairCost) {
            player.sendMessage(fmt.error("Repair costs &6" + repairCost + "&c. You have &6" + playerCash));
            return false;
        }

        // Withdraw repair cost from player
        List<ItemStack> payment = currency.withdrawCash(player, repairCost);
        if (payment.isEmpty()) {
            player.sendMessage(fmt.error("Failed to collect repair payment."));
            return false;
        }

        // Mark payment as SPENT
        for (ItemStack cash : payment) {
            currency.invalidateCash(cash, "SPENT");
        }

        clearLockdown(vaultUuid);

        // Log repair
        String repairSql = "INSERT INTO vault_repairs (vault_uuid, repaired_by, repair_cost, repaired_at) " +
                           "VALUES (?, ?, ?, datetime('now'))";
        try {
            plugin.getDatabase().executeUpdate(repairSql, vaultUuid, player.getUniqueId(), repairCost);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to log vault repair", e);
        }

        player.sendMessage(fmt.success("Vault repaired for &6" + repairCost + "&a coins. Lockdown lifted."));
        return true;
    }

    private void clearLockdown(UUID vaultUuid) {
        String sql = "UPDATE vaults SET is_locked_down = FALSE, lockout_until = NULL WHERE vault_uuid = ?";
        try {
            plugin.getDatabase().executeUpdate(sql, vaultUuid);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to clear vault lockdown", e);
        }
    }

    // ========================================================================
    // Access Control
    // ========================================================================

    @Override
    public boolean grantAccess(UUID vaultUuid, UUID playerUuid, String accessLevel, UUID grantedBy) {
        String sql = "INSERT OR REPLACE INTO vault_access (vault_uuid, player_uuid, access_level, granted_by, granted_at) " +
                     "VALUES (?, ?, ?, ?, datetime('now'))";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vaultUuid.toString());
            ps.setString(2, playerUuid.toString());
            ps.setString(3, accessLevel);
            ps.setString(4, grantedBy != null ? grantedBy.toString() : null);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to grant vault access", e);
            return false;
        }
    }

    @Override
    public boolean revokeAccess(UUID vaultUuid, UUID playerUuid) {
        String sql = "DELETE FROM vault_access WHERE vault_uuid = ? AND player_uuid = ?";
        try {
            plugin.getDatabase().executeUpdate(sql, vaultUuid, playerUuid);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to revoke vault access", e);
            return false;
        }
    }

    @Override
    public boolean canAccess(UUID vaultUuid, UUID playerUuid) {
        VaultData vault = getVault(vaultUuid);
        if (vault == null) return false;
        if (vault.getOwnerUuid().equals(playerUuid)) return true;

        String sql = "SELECT COUNT(*) FROM vault_access WHERE vault_uuid = ? AND player_uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, vaultUuid);
            ps.setObject(2, playerUuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check vault access", e);
        }
        return false;
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public VaultData getVault(UUID vaultUuid) {
        String sql = "SELECT vault_uuid, tier, world, x, y, z, owner_uuid, capacity, " +
                     "is_locked_down, lockout_until, created_at FROM vaults WHERE vault_uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, vaultUuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                VaultData vault = mapToVaultData(rs);
                vault.setBalance(getBalance(vaultUuid));
                return vault;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get vault: " + vaultUuid, e);
        }
        return null;
    }

    @Override
    public List<VaultData> getPlayerVaults(UUID ownerUuid) {
        List<VaultData> vaults = new ArrayList<>();
        String sql = "SELECT vault_uuid, tier, world, x, y, z, owner_uuid, capacity, " +
                     "is_locked_down, lockout_until, created_at FROM vaults WHERE owner_uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ownerUuid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                VaultData vault = mapToVaultData(rs);
                vault.setBalance(getBalance(vault.getVaultUuid()));
                vaults.add(vault);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get player vaults", e);
        }
        return vaults;
    }

    @Override
    public VaultData getVaultAt(Location location) {
        String sql = "SELECT vault_uuid, tier, world, x, y, z, owner_uuid, capacity, " +
                     "is_locked_down, lockout_until, created_at FROM vaults " +
                     "WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, location.getWorld().getName());
            ps.setInt(2, location.getBlockX());
            ps.setInt(3, location.getBlockY());
            ps.setInt(4, location.getBlockZ());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                VaultData vault = mapToVaultData(rs);
                vault.setBalance(getBalance(vault.getVaultUuid()));
                return vault;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to lookup vault at location", e);
        }
        return null;
    }

    @Override
    public boolean isVaultBlock(Location location) {
        return getVaultAt(location) != null;
    }

    // ========================================================================
    // Breach support
    // ========================================================================

    @Override
    public boolean updateBalanceAfterBreach(UUID vaultUuid, long stolenAmount) {
        // Enforce the 50% hard cap
        long stealable = getStealableAmount(vaultUuid);
        if (stolenAmount > stealable) {
            logger.severe("BREACH HARD CAP EXCEEDED: tried to steal " + stolenAmount +
                         " but max stealable is " + stealable + " for vault " + vaultUuid);
            return false;
        }

        // Set lockdown
        int cooldownMinutes = plugin.getConfigManager().getVaultBreachCooldownMinutes();
        String lockdownSql = "UPDATE vaults SET is_locked_down = TRUE, " +
                             "lockout_until = ? WHERE vault_uuid = ?";
        try {
            String lockoutTime = new java.sql.Timestamp(System.currentTimeMillis() + cooldownMinutes * 60000L).toString();
            plugin.getDatabase().executeUpdate(lockdownSql, lockoutTime, vaultUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set vault lockdown after breach", e);
            return false;
        }

        // The breach module will handle creating stolen cash separately
        return true;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private VaultData mapToVaultData(ResultSet rs) throws SQLException {
        UUID vaultUuid = UUID.fromString(rs.getString("vault_uuid"));
        VaultTier tier = VaultTier.valueOf(rs.getString("tier"));
        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));

        VaultData vault = new VaultData(vaultUuid, tier, ownerUuid);
        vault.setWorld(rs.getString("world"));
        vault.setX(rs.getInt("x"));
        vault.setY(rs.getInt("y"));
        vault.setZ(rs.getInt("z"));
        vault.setLockedDown(rs.getInt("is_locked_down") == 1);
        String lockoutStr = rs.getString("lockout_until");
        vault.setLockoutUntil(lockoutStr != null ? parseTimestamp(lockoutStr) : null);
        String createdStr = rs.getString("created_at");
        vault.setCreatedAt(createdStr != null ? parseTimestamp(createdStr) : new java.sql.Timestamp(System.currentTimeMillis()));
        return vault;
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
