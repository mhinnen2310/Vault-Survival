package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class ContractDisputeServiceImpl implements ContractDisputeService {
    private final VaultSurvivalPlugin plugin;
    private final EscrowService escrow;
    private final ContractAuditService audit;

    public ContractDisputeServiceImpl(VaultSurvivalPlugin plugin, EscrowService escrow, ContractAuditService audit) {
        this.plugin = plugin;
        this.escrow = escrow;
        this.audit = audit;
    }

    @Override
    public boolean openDispute(int contractId, UUID actorUuid, String reason) {
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT INTO contract_disputes (contract_id, opened_by, reason, status, created_at) VALUES (?, ?, ?, 'OPEN', ?)",
                contractId, actorUuid.toString(), reason, System.currentTimeMillis());
            escrow.markDisputed(contractId);
            audit.log(contractId, actorUuid, "DISPUTE_OPEN", 0, reason);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to open contract dispute", e);
            return false;
        }
    }

    @Override
    public List<Integer> getOpenDisputeContractIds() {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT contract_id FROM contract_disputes WHERE status = 'OPEN'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt("contract_id"));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read contract disputes", e);
        }
        return ids;
    }
}
