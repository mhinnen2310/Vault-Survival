package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class PayoutLockerServiceImpl implements PayoutLockerService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final ContractAuditService audit;
    private final MessageFormatter fmt;

    public PayoutLockerServiceImpl(VaultSurvivalPlugin plugin, ContractAuditService audit) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.audit = audit;
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public int storePayout(UUID playerUuid, long amount, String sourceType, String sourceId, String details) {
        if (amount <= 0) return -1;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO payout_lockers (player_uuid, amount, source_type, source_id, details, status, created_at) " +
                 "VALUES (?, ?, ?, ?, ?, 'PENDING', ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, amount);
            ps.setString(3, sourceType);
            ps.setString(4, sourceId);
            ps.setString(5, details);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            int id = keys.next() ? keys.getInt(1) : -1;
            audit.log(parseContractId(sourceId), playerUuid, "PAYOUT_LOCKER_STORE", amount, details);
            return id;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to store payout", e);
            return -1;
        }
    }

    @Override
    public List<ContractData.PayoutLockerEntry> getPending(UUID playerUuid) {
        return queryPending("WHERE player_uuid = ? AND status = 'PENDING'", playerUuid.toString());
    }

    @Override
    public List<ContractData.PayoutLockerEntry> getAllPending() {
        return queryPending("WHERE status = 'PENDING'", null);
    }

    @Override
    public long getPendingTotal(UUID playerUuid) {
        return getPending(playerUuid).stream().mapToLong(ContractData.PayoutLockerEntry::getAmount).sum();
    }

    @Override
    public boolean claim(Player player) {
        if (currency == null) {
            player.sendMessage(fmt.error("Currency service is unavailable. Your payout remains locked safely."));
            return false;
        }
        List<ContractData.PayoutLockerEntry> pending = getPending(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage(fmt.info("No pending payouts."));
            return true;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(fmt.error("Your inventory is full. Payout stays in your locker."));
            return false;
        }
        long total = pending.stream().mapToLong(ContractData.PayoutLockerEntry::getAmount).sum();
        ItemStack cash = currency.mintCash(total, null, player.getUniqueId());
        var overflow = player.getInventory().addItem(cash);
        if (!overflow.isEmpty()) {
            player.sendMessage(fmt.error("Your inventory filled up. Payout stays in your locker."));
            currency.invalidateCash(cash, "PAYOUT_OVERFLOW_ROLLBACK");
            return false;
        }
        try {
            for (ContractData.PayoutLockerEntry entry : pending) {
                plugin.getDatabase().executeUpdate(
                    "UPDATE payout_lockers SET status = 'CLAIMED', claimed_at = ? WHERE id = ?",
                    System.currentTimeMillis(), entry.getId());
            }
            audit.log(0, player.getUniqueId(), "PAYOUT_LOCKER_CLAIM", total, "entries=" + pending.size());
            player.sendMessage(fmt.success("Claimed payout: &6" + fmt.formatMoney(total,
                plugin.getConfigManager().getCurrencyName(), plugin.getConfigManager().getCurrencyNamePlural())));
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to mark payout claimed", e);
            player.getInventory().removeItem(cash);
            currency.invalidateCash(cash, "PAYOUT_CLAIM_DATABASE_ROLLBACK");
            player.sendMessage(fmt.error("Claim could not be completed. Your payout remains in the locker."));
            return false;
        }
    }

    private List<ContractData.PayoutLockerEntry> queryPending(String where, String playerUuid) {
        List<ContractData.PayoutLockerEntry> rows = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM payout_lockers " + where + " ORDER BY created_at DESC")) {
            if (playerUuid != null) ps.setString(1, playerUuid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new ContractData.PayoutLockerEntry(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("player_uuid")),
                    rs.getLong("amount"),
                    rs.getString("source_type"),
                    rs.getString("source_id"),
                    rs.getString("details"),
                    ContractData.PayoutStatus.valueOf(rs.getString("status"))));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query payouts", e);
        }
        return rows;
    }

    private int parseContractId(String sourceId) {
        try { return Integer.parseInt(sourceId); } catch (Exception ignored) { return 0; }
    }
}
