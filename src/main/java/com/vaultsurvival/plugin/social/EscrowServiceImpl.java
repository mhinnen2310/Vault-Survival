package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
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

public class EscrowServiceImpl implements EscrowService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final PayoutLockerService payouts;
    private final ContractAuditService audit;

    public EscrowServiceImpl(VaultSurvivalPlugin plugin, PayoutLockerService payouts, ContractAuditService audit) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.payouts = payouts;
        this.audit = audit;
    }

    @Override
    public boolean lockPlayerCash(Player payer, int contractId, long amount) {
        if (amount <= 0) return true;
        if (currency.getPlayerCashTotal(payer.getUniqueId()) < amount) return false;
        List<ItemStack> withdrawn = currency.withdrawCash(payer, amount);
        long locked = withdrawn.stream().mapToLong(currency::getCashAmount).sum();
        if (locked < amount) {
            currency.depositCash(payer, withdrawn);
            return false;
        }
        long now = System.currentTimeMillis();
        try {
            for (ItemStack cash : withdrawn) {
                UUID cashUuid = currency.getCashUuid(cash);
                long cashAmount = currency.getCashAmount(cash);
                plugin.getDatabase().executeUpdate(
                    "UPDATE cash_items SET state = 'IN_CONTRACT_ESCROW', owner_uuid = NULL, location_type = 'CONTRACT_ESCROW', location_id = ?, last_seen_at = datetime('now') WHERE cash_uuid = ?",
                    String.valueOf(contractId), cashUuid.toString());
                plugin.getDatabase().executeUpdate(
                    "INSERT INTO contract_escrows (contract_id, cash_uuid, amount, source_type, source_id, status, locked_by, locked_at) VALUES (?, ?, ?, 'PLAYER_CASH', ?, 'LOCKED', ?, ?)",
                    contractId, cashUuid.toString(), cashAmount, payer.getUniqueId().toString(), payer.getUniqueId().toString(), now);
            }
            audit.log(contractId, payer.getUniqueId(), "ESCROW_LOCK", amount, "source=PLAYER_CASH");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to lock escrow", e);
            return false;
        }
    }

    @Override
    public boolean hasLockedEscrow(int contractId, long amount) {
        return getLockedAmount(contractId) >= amount;
    }

    @Override
    public long getLockedAmount(int contractId) {
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT IFNULL(SUM(amount), 0) FROM contract_escrows WHERE contract_id = ? AND status = 'LOCKED'")) {
            ps.setInt(1, contractId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read escrow", e);
            return 0;
        }
    }

    @Override
    public boolean releaseToPayoutLocker(int contractId, UUID recipientUuid, String details) {
        long amount = getLockedAmount(contractId);
        if (amount <= 0) return false;
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') WHERE location_type = 'CONTRACT_ESCROW' AND location_id = ?",
                String.valueOf(contractId));
            plugin.getDatabase().executeUpdate(
                "UPDATE contract_escrows SET status = 'RELEASED', released_at = ? WHERE contract_id = ? AND status = 'LOCKED'",
                System.currentTimeMillis(), contractId);
            payouts.storePayout(recipientUuid, amount, "CONTRACT", String.valueOf(contractId), details);
            audit.log(contractId, recipientUuid, "ESCROW_RELEASE", amount, details);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to release escrow", e);
            return false;
        }
    }

    @Override
    public boolean refundToPayoutLocker(int contractId, UUID payerUuid, String details) {
        long amount = getLockedAmount(contractId);
        if (amount <= 0) return true;
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') WHERE location_type = 'CONTRACT_ESCROW' AND location_id = ?",
                String.valueOf(contractId));
            plugin.getDatabase().executeUpdate(
                "UPDATE contract_escrows SET status = 'REFUNDED', released_at = ? WHERE contract_id = ? AND status = 'LOCKED'",
                System.currentTimeMillis(), contractId);
            payouts.storePayout(payerUuid, amount, "CONTRACT_REFUND", String.valueOf(contractId), details);
            audit.log(contractId, payerUuid, "ESCROW_REFUND", amount, details);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to refund escrow", e);
            return false;
        }
    }

    @Override
    public boolean markDisputed(int contractId) {
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE contract_escrows SET status = 'DISPUTED' WHERE contract_id = ? AND status = 'LOCKED'",
                contractId);
            audit.log(contractId, null, "ESCROW_DISPUTE_LOCK", getLockedAmount(contractId), "dispute opened");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to dispute-lock escrow", e);
            return false;
        }
    }

    @Override
    public List<ContractData.EscrowRecord> getEscrows(int contractId) {
        return query("WHERE contract_id = ?", contractId);
    }

    @Override
    public List<ContractData.EscrowRecord> getAllEscrows() {
        return query("", null);
    }

    private List<ContractData.EscrowRecord> query(String where, Integer contractId) {
        List<ContractData.EscrowRecord> rows = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM contract_escrows " + where + " ORDER BY id DESC")) {
            if (contractId != null) ps.setInt(1, contractId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new ContractData.EscrowRecord(rs.getInt("id"), rs.getInt("contract_id"),
                    rs.getLong("amount"), rs.getString("status"), rs.getString("source_type")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query escrow", e);
        }
        return rows;
    }
}
