package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class DistrictTreasuryServiceImpl implements DistrictTreasuryService {
    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final CurrencyService currency;

    public DistrictTreasuryServiceImpl(VaultSurvivalPlugin plugin, DistrictService districts) {
        this.plugin = plugin;
        this.districts = districts;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
    }

    @Override
    public Result create(Player actor, Block block) {
        if (!plugin.getConfigManager().getConfig().getBoolean("districtTreasury.enabled", true)) return Result.error("Physical district treasuries are disabled.");
        DistrictData.District district = districts.getPlayerDistrict(actor.getUniqueId());
        if (district == null || !districts.canManageTreasury(actor.getUniqueId(), district)) return Result.error("Requires MAYOR, CO_MAYOR, or TREASURER.");
        if (block == null || block.getType().isAir() || !isVaultMaterial(block.getType())) return Result.error("Look at a barrel, chest, trapped chest, iron block, or vault block.");
        if (getVault(block) != null) return Result.error("That block is already a district treasury vault.");
        if (plugin.getConfigManager().getConfig().getBoolean("districtTreasury.requireInsideDistrict", true)) {
            DistrictData.BlockClaim claim = districts.getClaim(district.getId());
            if (claim == null || !claim.worldName().equals(block.getWorld().getName())
                || !claim.contains(block.getX(), block.getZ())) {
                return Result.error("Treasury vaults must be inside your own district claim.");
            }
        }
        int maximum = plugin.getConfigManager().getConfig().getInt("districtTreasury.maxVaultsPerDistrict", 3);
        if (getVaults(district.getId()).size() >= maximum) return Result.error("This district already has its maximum of " + maximum + " treasury vaults.");
        UUID uuid = UUID.randomUUID();
        try {
            plugin.getDatabase().executeUpdate("INSERT INTO district_treasury_vaults(vault_uuid,district_id,world,x,y,z,created_by,created_at,tier,locked,breached_until) VALUES(?,?,?,?,?,?,?,?, 'BASIC',1,0)",
                uuid.toString(), String.valueOf(district.getId()), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), actor.getUniqueId().toString(), System.currentTimeMillis());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to create district treasury vault", exception);
            return Result.error("Treasury vault registration failed.");
        }
        TreasuryVault vault = getVault(uuid);
        audit(actor, "DISTRICT_TREASURY_VAULT_CREATE", district.getId(), uuid, 0, vault);
        return Result.ok("Physical treasury vault created.", 0, vault);
    }

    @Override
    public Result remove(Player actor, UUID vaultUuid) {
        TreasuryVault vault = getVault(vaultUuid);
        if (vault == null) return Result.error("Treasury vault not found.");
        if (!canManage(actor, vault)) return Result.error("Requires MAYOR, CO_MAYOR, or TREASURER.");
        if (!isNear(actor, vault)) return Result.error("Stand next to the physical treasury vault.");
        if (getVaultBalance(vaultUuid) != 0) return Result.error("The treasury vault must be empty before removal.");
        try { plugin.getDatabase().executeUpdate("DELETE FROM district_treasury_vaults WHERE vault_uuid=?", vaultUuid.toString()); }
        catch (SQLException exception) { return Result.error("Could not remove the treasury vault."); }
        audit(actor, "DISTRICT_TREASURY_VAULT_REMOVE", vault.districtId(), vaultUuid, 0, vault);
        return Result.ok("Treasury vault unregistered. The block was left in place.", 0, vault);
    }

    @Override public Result depositHeld(Player actor, UUID vaultUuid) {
        return depositSlot(actor, vaultUuid, actor.getInventory().getHeldItemSlot());
    }

    @Override
    public Result depositAll(Player actor, UUID vaultUuid) {
        TreasuryVault vault = getVault(vaultUuid);
        Result access = validatePhysicalAccess(actor, vault);
        if (!access.success()) return access;
        long total = 0;
        for (int slot = 0; slot < actor.getInventory().getSize(); slot++) {
            ItemStack item = actor.getInventory().getItem(slot);
            if (item == null || !currency.isCashItem(item)) continue;
            Result deposited = depositSlot(actor, vaultUuid, slot);
            if (deposited.success()) total += deposited.amount();
        }
        if (total <= 0) return Result.error("No valid physical cash was found in your inventory.");
        return Result.ok("Deposited all valid physical cash: " + total + ".", total, vault);
    }

    private Result depositSlot(Player actor, UUID vaultUuid, int slot) {
        TreasuryVault vault = getVault(vaultUuid);
        Result access = validatePhysicalAccess(actor, vault);
        if (!access.success()) return access;
        ItemStack item = actor.getInventory().getItem(slot);
        if (item == null || !currency.validateCash(item)) return Result.error("Hold valid physical cash to deposit it.");
        UUID cashUuid = currency.getCashUuid(item);
        long amount = currency.getCashAmount(item);
        if (cashUuid == null || amount <= 0) return Result.error("Invalid cash item.");
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement update = connection.prepareStatement(
            "UPDATE cash_items SET state='IN_DISTRICT_TREASURY',location_type='DISTRICT_TREASURY_VAULT',location_id=?,owner_uuid=NULL,last_seen_at=datetime('now') WHERE cash_uuid=? AND state='ACTIVE' AND owner_uuid=? AND amount>0")) {
            update.setString(1, vaultUuid.toString());
            update.setString(2, cashUuid.toString());
            update.setString(3, actor.getUniqueId().toString());
            if (update.executeUpdate() != 1) return Result.error("Cash state changed before deposit; nothing was removed.");
        } catch (SQLException exception) {
            return Result.error("Deposit failed; your cash is safe.");
        }
        actor.getInventory().setItem(slot, null);
        audit(actor, "DISTRICT_TREASURY_DEPOSIT", vault.districtId(), vaultUuid, amount, vault);
        return Result.ok("Deposited " + amount + " physical cash.", amount, vault);
    }

    @Override
    public Result withdraw(Player actor, UUID vaultUuid, long amount) {
        TreasuryVault vault = getVault(vaultUuid);
        Result access = validatePhysicalAccess(actor, vault);
        if (!access.success()) return access;
        if (amount <= 0) return Result.error("Withdrawal amount must be positive.");
        if (actor.getInventory().firstEmpty() < 0) return Result.error("Your inventory is full; nothing was withdrawn.");
        if (getVaultBalance(vaultUuid) < amount) return Result.error("This physical vault does not contain that much cash.");
        UUID outputUuid = UUID.randomUUID();
        try (Connection connection = plugin.getDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try {
                long remaining = amount;
                try (PreparedStatement select = connection.prepareStatement("SELECT cash_uuid,amount FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND location_type='DISTRICT_TREASURY_VAULT' AND location_id=? ORDER BY amount ASC")) {
                    select.setString(1, vaultUuid.toString());
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next() && remaining > 0) {
                            String cashUuid = rows.getString(1);
                            long stored = rows.getLong(2);
                            if (stored <= remaining) {
                                try (PreparedStatement spend = connection.prepareStatement("UPDATE cash_items SET state='SPENT',last_seen_at=datetime('now') WHERE cash_uuid=?")) {
                                    spend.setString(1, cashUuid); spend.executeUpdate();
                                }
                                remaining -= stored;
                            } else {
                                try (PreparedStatement reduce = connection.prepareStatement("UPDATE cash_items SET amount=?,last_seen_at=datetime('now') WHERE cash_uuid=?")) {
                                    reduce.setLong(1, stored - remaining); reduce.setString(2, cashUuid); reduce.executeUpdate();
                                }
                                remaining = 0;
                            }
                        }
                    }
                }
                if (remaining != 0) throw new SQLException("Treasury balance changed during withdrawal");
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,created_at,last_seen_at,location_type,location_id,owner_uuid,created_by) VALUES(?,?,'ACTIVE',datetime('now'),datetime('now'),'INVENTORY',?,?,?)")) {
                    insert.setString(1, outputUuid.toString()); insert.setLong(2, amount);
                    insert.setString(3, actor.getUniqueId().toString()); insert.setString(4, actor.getUniqueId().toString()); insert.setString(5, actor.getUniqueId().toString()); insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback(); throw exception;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Physical treasury withdrawal failed", exception);
            return Result.error("Withdrawal failed; treasury cash was not moved.");
        }
        ItemStack cash = currency.materializeCash(outputUuid);
        if (cash == null || !actor.getInventory().addItem(cash).isEmpty()) {
            try { plugin.getDatabase().executeUpdate("UPDATE cash_items SET state='IN_DISTRICT_TREASURY',location_type='DISTRICT_TREASURY_VAULT',location_id=?,owner_uuid=NULL WHERE cash_uuid=?", vaultUuid.toString(), outputUuid.toString()); }
            catch (SQLException ignored) { }
            return Result.error("Inventory changed during withdrawal; value was returned to the physical vault.");
        }
        audit(actor, "DISTRICT_TREASURY_WITHDRAW", vault.districtId(), vaultUuid, amount, vault);
        return Result.ok("Withdrew " + amount + " physical cash.", amount, vault);
    }

    @Override public Result breach(Player thief, UUID vaultUuid) {
        TreasuryVault vault=getVault(vaultUuid);if(!thief.isOnline()||vault==null||!isNear(thief,vault))return Result.error("Stay online and next to the treasury until the breach completes.");
        if(canManage(thief,vault))return Result.error("Authorized treasury roles should use the normal withdrawal controls.");
        long now=System.currentTimeMillis();if(vault.breachedUntil()>now)return Result.error("This treasury is still in breach lockdown.");
        long balance=getVaultBalance(vaultUuid);if(balance<=0)return Result.error("This treasury vault is empty.");
        double fraction=Math.max(.01,Math.min(.50,plugin.getConfigManager().getConfig().getDouble("districtTreasury.breach.stealPercent",20.0)/100.0));
        long amount=Math.max(1,(long)Math.floor(balance*fraction));UUID output=UUID.randomUUID();
        try(Connection c=plugin.getDatabase().getConnection()){c.setAutoCommit(false);try{long remaining=amount;try(PreparedStatement s=c.prepareStatement("SELECT cash_uuid,amount FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND location_type='DISTRICT_TREASURY_VAULT' AND location_id=? ORDER BY amount ASC")){s.setString(1,vaultUuid.toString());ResultSet r=s.executeQuery();while(r.next()&&remaining>0){String id=r.getString(1);long stored=r.getLong(2);if(stored<=remaining){try(PreparedStatement u=c.prepareStatement("UPDATE cash_items SET state='SPENT',location_type='TREASURY_BREACH',location_id=?,last_seen_at=datetime('now') WHERE cash_uuid=?")){u.setString(1,vaultUuid.toString());u.setString(2,id);u.executeUpdate();}remaining-=stored;}else{try(PreparedStatement u=c.prepareStatement("UPDATE cash_items SET amount=amount-?,last_seen_at=datetime('now') WHERE cash_uuid=?")){u.setLong(1,remaining);u.setString(2,id);u.executeUpdate();}remaining=0;}}}if(remaining!=0)throw new SQLException("Treasury changed during breach");try(PreparedStatement i=c.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,created_at,last_seen_at,location_type,location_id,owner_uuid,created_by) VALUES(?,?,'ACTIVE',datetime('now'),datetime('now'),'INVENTORY',?,?,?)")){i.setString(1,output.toString());i.setLong(2,amount);i.setString(3,thief.getUniqueId().toString());i.setString(4,thief.getUniqueId().toString());i.setString(5,thief.getUniqueId().toString());i.executeUpdate();}long lockdown=now+Math.max(60,plugin.getConfigManager().getConfig().getLong("districtTreasury.breach.lockdownSeconds",1800))*1000L;try(PreparedStatement u=c.prepareStatement("UPDATE district_treasury_vaults SET breached_until=? WHERE vault_uuid=?")){u.setLong(1,lockdown);u.setString(2,vaultUuid.toString());u.executeUpdate();}c.commit();}catch(SQLException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"District treasury breach failed",e);return Result.error("The breach failed; treasury cash stayed safe.");}
        ItemStack cash=currency.materializeCash(output);if(cash==null)return Result.error("Breach value could not be materialized; contact staff.");thief.getInventory().addItem(cash).values().forEach(item->thief.getWorld().dropItemNaturally(thief.getLocation(),item));audit(thief,"DISTRICT_TREASURY_BREACH",vault.districtId(),vaultUuid,amount,vault);return Result.ok("Breach succeeded. Stolen physical cash: "+amount+".",amount,vault);
    }

    private Result validatePhysicalAccess(Player actor, TreasuryVault vault) {
        if (vault == null) return Result.error("Treasury vault not found.");
        if (!canManage(actor, vault)) return Result.error("Requires MAYOR, CO_MAYOR, or TREASURER.");
        if (!isNear(actor, vault)) return Result.error("Stand next to the physical treasury vault.");
        return Result.ok("Access granted.", 0, vault);
    }

    @Override public long getDistrictBalance(int districtId) {
        long balance = sum("SELECT IFNULL(SUM(c.amount),0) FROM cash_items c JOIN district_treasury_vaults v ON v.vault_uuid=c.location_id WHERE c.state='IN_DISTRICT_TREASURY' AND c.location_type='DISTRICT_TREASURY_VAULT' AND v.district_id=?", String.valueOf(districtId));
        DistrictData.District district = districts.getDistrict(districtId);
        if (district != null) district.setTreasuryBalance(balance);
        return balance;
    }
    @Override public long getVaultBalance(UUID vaultUuid) { return sum("SELECT IFNULL(SUM(amount),0) FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND location_type='DISTRICT_TREASURY_VAULT' AND location_id=?", vaultUuid.toString()); }

    private long sum(String sql, String value) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value); try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException exception) { plugin.getLogger().log(Level.WARNING, "Treasury balance query failed", exception); return 0; }
    }

    @Override public List<TreasuryVault> getVaults(int districtId) { return queryVaults("WHERE district_id=?", String.valueOf(districtId)); }
    @Override public TreasuryVault getVault(UUID vaultUuid) { return queryVaults("WHERE vault_uuid=?", vaultUuid.toString()).stream().findFirst().orElse(null); }
    @Override public TreasuryVault getVault(Block block) { return queryVaults("WHERE world=? AND x=? AND y=? AND z=?", block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).stream().findFirst().orElse(null); }

    private List<TreasuryVault> queryVaults(String where, Object... parameters) {
        List<TreasuryVault> result = new ArrayList<>();
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM district_treasury_vaults " + where)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(new TreasuryVault(UUID.fromString(rs.getString("vault_uuid")), Integer.parseInt(rs.getString("district_id")), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getString("tier"), rs.getInt("locked") != 0, rs.getLong("breached_until")));
            }
        } catch (SQLException exception) { plugin.getLogger().log(Level.WARNING, "Treasury vault query failed", exception); }
        return result;
    }

    @Override public boolean canManage(Player player, TreasuryVault vault) {
        if (player.hasPermission("vs.district.admin")) return true;
        DistrictData.District district = districts.getDistrict(vault.districtId());
        return district != null && districts.canManageTreasury(player.getUniqueId(), district);
    }
    @Override public boolean isNear(Player player, TreasuryVault vault) {
        var location = vault.location(plugin.getServer());
        return location != null && player.getWorld() == location.getWorld() && player.getLocation().distanceSquared(location) <= 36;
    }

    @Override
    public Result migrateLegacy(Player admin, int districtId, UUID vaultUuid) {
        if (!admin.hasPermission("vs.admin")) return Result.error("Requires vs.admin.");
        TreasuryVault vault = getVault(vaultUuid);
        if (vault == null || vault.districtId() != districtId || !isNear(admin, vault)) return Result.error("Stand next to a physical treasury vault belonging to that district.");
        long amount = sum("SELECT IFNULL(SUM(amount),0) FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND location_id=? AND (location_type IS NULL OR location_type!='DISTRICT_TREASURY_VAULT')", String.valueOf(districtId));
        try {
            plugin.getDatabase().executeUpdate("UPDATE cash_items SET location_type='DISTRICT_TREASURY_VAULT',location_id=?,owner_uuid=NULL,last_seen_at=datetime('now') WHERE state='IN_DISTRICT_TREASURY' AND location_id=? AND (location_type IS NULL OR location_type!='DISTRICT_TREASURY_VAULT')", vaultUuid.toString(), String.valueOf(districtId));
        } catch (SQLException exception) { return Result.error("Legacy treasury migration failed."); }
        audit(admin, "DISTRICT_TREASURY_MIGRATE", districtId, vaultUuid, amount, vault);
        return Result.ok("Migrated " + amount + " legacy treasury cash into the physical vault.", amount, vault);
    }

    @Override public void reportLegacyBalances() {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT location_id,SUM(amount) FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND (location_type IS NULL OR location_type!='DISTRICT_TREASURY_VAULT') GROUP BY location_id HAVING SUM(amount)>0"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) plugin.getLogger().warning("Legacy abstract district treasury cash: district/location " + rs.getString(1) + " amount=" + rs.getLong(2) + ". Use /vsadmin treasury migrate-district while standing at a physical treasury vault.");
        } catch (SQLException exception) { plugin.getLogger().log(Level.WARNING, "Failed to audit legacy district treasury cash", exception); }
    }

    @Override
    public Result creditSystem(int districtId, long amount, String source, UUID actorUuid) {
        if (amount <= 0) return Result.error("Treasury credit must be positive.");
        List<TreasuryVault> vaults = getVaults(districtId);
        if (vaults.isEmpty()) return Result.error("This district has no registered physical treasury vault.");
        TreasuryVault vault = vaults.getFirst();
        UUID cashUuid = UUID.randomUUID();
        String normalizedSource = source == null || source.isBlank() ? "SYSTEM_REVENUE" : source;
        try (Connection connection = plugin.getDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement cash = connection.prepareStatement(
                    "INSERT INTO cash_items(cash_uuid,amount,state,location_type,location_id,owner_uuid,created_by) VALUES(?,?,'IN_DISTRICT_TREASURY','DISTRICT_TREASURY_VAULT',?,NULL,?)");
                 PreparedStatement transaction = connection.prepareStatement(
                    "INSERT INTO cash_transactions(cash_uuid,transaction_type,amount,actor_uuid,source_location,target_location,details) VALUES(?,'TREASURY_CREDIT',?,?,?,?,?)")) {
                cash.setString(1, cashUuid.toString()); cash.setLong(2, amount);
                cash.setString(3, vault.vaultUuid().toString());
                cash.setString(4, actorUuid == null ? null : actorUuid.toString());
                cash.executeUpdate();
                transaction.setString(1, cashUuid.toString()); transaction.setLong(2, amount);
                transaction.setString(3, actorUuid == null ? null : actorUuid.toString());
                transaction.setString(4, normalizedSource);
                transaction.setString(5, "DISTRICT_TREASURY_VAULT:" + vault.vaultUuid());
                transaction.setString(6, normalizedSource);
                transaction.executeUpdate();
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Failed physical treasury credit", error);
            return Result.error("Physical treasury credit failed.");
        }
        plugin.getAuditLogger().log(actorUuid, actorUuid == null ? "SYSTEM" : org.bukkit.Bukkit.getOfflinePlayer(actorUuid).getName(),
            "DISTRICT_TREASURY_SYSTEM_CREDIT", "DISTRICT_TREASURY", String.valueOf(districtId),
            "vault=" + vault.vaultUuid() + " cash=" + cashUuid + " amount=" + amount + " source=" + normalizedSource);
        return Result.ok("Credited " + amount + " to the physical district treasury.", amount, vault);
    }

    @Override
    public Result debitSystem(int districtId, long amount, String reason, UUID actorUuid) {
        if (amount <= 0) return Result.error("Treasury debit must be positive.");
        if (getDistrictBalance(districtId) < amount) return Result.error("The physical district treasury has insufficient cash.");
        String normalizedReason = reason == null || reason.isBlank() ? "SYSTEM_FEE" : reason;
        try (Connection connection = plugin.getDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try {
                long remaining = amount;
                try (PreparedStatement select = connection.prepareStatement(
                    "SELECT c.cash_uuid,c.amount,c.location_id FROM cash_items c JOIN district_treasury_vaults v ON v.vault_uuid=c.location_id " +
                        "WHERE c.state='IN_DISTRICT_TREASURY' AND c.location_type='DISTRICT_TREASURY_VAULT' AND v.district_id=? ORDER BY c.amount ASC")) {
                    select.setString(1, String.valueOf(districtId));
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next() && remaining > 0) {
                            String cashUuid = rows.getString("cash_uuid");
                            long cashAmount = rows.getLong("amount");
                            long consumed = Math.min(remaining, cashAmount);
                            if (consumed == cashAmount) {
                                try (PreparedStatement update = connection.prepareStatement(
                                    "UPDATE cash_items SET state='SPENT',location_type='SYSTEM_FEE',location_id=NULL,owner_uuid=NULL,last_seen_at=datetime('now') WHERE cash_uuid=? AND state='IN_DISTRICT_TREASURY'")) {
                                    update.setString(1, cashUuid); if (update.executeUpdate() != 1) throw new SQLException("Treasury cash changed concurrently");
                                }
                            } else {
                                try (PreparedStatement reduce = connection.prepareStatement("UPDATE cash_items SET amount=amount-?,last_seen_at=datetime('now') WHERE cash_uuid=? AND amount>=?");
                                     PreparedStatement spent = connection.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,location_type,location_id,owner_uuid,created_by) VALUES(?,?,'SPENT','SYSTEM_FEE',NULL,NULL,?)")) {
                                    reduce.setLong(1, consumed); reduce.setString(2, cashUuid); reduce.setLong(3, consumed);
                                    if (reduce.executeUpdate() != 1) throw new SQLException("Treasury cash changed concurrently");
                                    UUID spentUuid = UUID.randomUUID();
                                    spent.setString(1, spentUuid.toString()); spent.setLong(2, consumed);
                                    spent.setString(3, actorUuid == null ? null : actorUuid.toString()); spent.executeUpdate();
                                    cashUuid = spentUuid.toString();
                                }
                            }
                            try (PreparedStatement transaction = connection.prepareStatement(
                                "INSERT INTO cash_transactions(cash_uuid,transaction_type,amount,actor_uuid,source_location,target_location,details) VALUES(?,'TREASURY_DEBIT',?,?,?,?,?)")) {
                                transaction.setString(1, cashUuid); transaction.setLong(2, consumed);
                                transaction.setString(3, actorUuid == null ? null : actorUuid.toString());
                                transaction.setString(4, "DISTRICT_TREASURY:" + districtId);
                                transaction.setString(5, "SYSTEM_FEE"); transaction.setString(6, normalizedReason); transaction.executeUpdate();
                            }
                            remaining -= consumed;
                        }
                    }
                }
                if (remaining != 0) throw new SQLException("Physical treasury changed during debit");
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Failed physical treasury debit", error);
            return Result.error("Physical treasury debit failed; no fee was taken.");
        }
        plugin.getAuditLogger().log(actorUuid, actorUuid == null ? "SYSTEM" : org.bukkit.Bukkit.getOfflinePlayer(actorUuid).getName(),
            "DISTRICT_TREASURY_SYSTEM_DEBIT", "DISTRICT_TREASURY", String.valueOf(districtId),
            "amount=" + amount + " reason=" + normalizedReason);
        return Result.ok("Debited " + amount + " from the physical district treasury.", amount, null);
    }

    private void audit(Player actor, String action, int districtId, UUID vaultUuid, long amount, TreasuryVault vault) {
        plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), action, "DISTRICT_TREASURY", String.valueOf(districtId),
            "vault=" + vaultUuid + " amount=" + amount + " world=" + vault.world() + " x=" + vault.x() + " y=" + vault.y() + " z=" + vault.z());
    }
    private static boolean isVaultMaterial(Material material) {
        return material == Material.BARREL || material == Material.CHEST || material == Material.TRAPPED_CHEST
            || material == Material.IRON_BLOCK || material.name().equals("VAULT");
    }
}
