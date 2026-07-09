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

public class ContractAuditServiceImpl implements ContractAuditService {
    private final VaultSurvivalPlugin plugin;

    public ContractAuditServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void log(int contractId, UUID actorUuid, String action, long amount, String details) {
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT INTO contract_audit (contract_id, actor_uuid, action, amount, details, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                contractId, actorUuid != null ? actorUuid.toString() : null, action, amount, details, System.currentTimeMillis());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write contract audit", e);
        }
        plugin.getAuditLogger().log(actorUuid, actorUuid != null ? actorUuid.toString() : "SYSTEM",
            "CONTRACT_" + action, "CONTRACT", String.valueOf(contractId), details + " amount=" + amount);
    }

    @Override
    public List<String> recent(int limit) {
        List<String> rows = new ArrayList<>();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM contract_audit ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add("#" + rs.getInt("id") + " c=" + rs.getInt("contract_id") + " "
                    + rs.getString("action") + " amount=" + rs.getLong("amount") + " "
                    + rs.getString("details"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read contract audit", e);
        }
        return rows;
    }
}
