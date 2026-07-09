package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ContractServiceImpl implements ContractService {
    private final VaultSurvivalPlugin plugin;
    private final Logger logger;
    private final EscrowService escrow;
    private final ContractAuditService audit;
    private final ContractDisputeService disputes;
    private final ConcurrentHashMap<Integer, ContractData.Contract> contracts = new ConcurrentHashMap<>();

    public ContractServiceImpl(VaultSurvivalPlugin plugin, EscrowService escrow, ContractAuditService audit,
                               ContractDisputeService disputes) {
        this.plugin = plugin;
        this.escrow = escrow;
        this.audit = audit;
        this.disputes = disputes;
        this.logger = plugin.getLogger();
    }

    @Override
    public ContractData.Contract createContract(UUID issuer, UUID target, String description, long amount, long deadlineHours) {
        long deadline = System.currentTimeMillis() + (deadlineHours * 3600000);
        String sql = "INSERT INTO contracts (issuer_uuid, target_uuid, description, amount, deadline, status) VALUES (?,?,?,?,?,'PENDING')";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, issuer.toString());
            ps.setString(2, target.toString());
            ps.setString(3, description);
            ps.setLong(4, amount);
            ps.setLong(5, deadline);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var c = new ContractData.Contract(id, issuer, target, description, amount, deadline,
                    ContractData.ContractStatus.PENDING);
                contracts.put(id, c);
                audit.log(id, issuer, "CREATE_PENDING", amount, description);
                return c;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to create contract", e);
        }
        return null;
    }

    @Override
    public ContractData.Contract createPaidContract(Player issuer, UUID target, String description, long amount,
                                                    long deadlineHours, ContractData.ContractSource source) {
        var contract = createContract(issuer.getUniqueId(), target, description, amount, deadlineHours);
        if (contract == null) return null;
        if (amount > 0 && !escrow.lockPlayerCash(issuer, contract.getId(), amount)) {
            setStatus(contract, ContractData.ContractStatus.CANCELLED);
            audit.log(contract.getId(), issuer.getUniqueId(), "ESCROW_LOCK_FAILED", amount, source.name());
            return null;
        }
        audit.log(contract.getId(), issuer.getUniqueId(), "SOURCE", amount, source.name());
        return contract;
    }

    @Override
    public boolean acceptContract(int contractId, UUID acceptor) {
        var c = contracts.get(contractId);
        if (c == null || !c.getTargetUuid().equals(acceptor) || c.getStatus() != ContractData.ContractStatus.PENDING) return false;
        if (c.getAmount() > 0 && !escrow.hasLockedEscrow(contractId, c.getAmount())) return false;
        setStatus(c, ContractData.ContractStatus.ACCEPTED);
        audit.log(contractId, acceptor, "ACCEPT", c.getAmount(), "");
        return true;
    }

    @Override
    public boolean completeContract(int contractId, UUID completer) {
        var c = contracts.get(contractId);
        if (c == null || c.getStatus() != ContractData.ContractStatus.ACCEPTED) return false;
        if (!c.getTargetUuid().equals(completer) && !c.getIssuerUuid().equals(completer)) return false;
        if (c.getAmount() > 0 && !escrow.releaseToPayoutLocker(contractId, c.getTargetUuid(), "contract complete")) {
            return false;
        }
        setStatus(c, ContractData.ContractStatus.COMPLETED);
        audit.log(contractId, completer, "COMPLETE", c.getAmount(), "");
        Player target = Bukkit.getPlayer(c.getTargetUuid());
        if (target != null) {
            target.sendMessage(plugin.getMessageFormatter().info("Contract payout ready. Use &e/payouts claim&7."));
        }
        return true;
    }

    @Override
    public boolean cancelContract(int contractId, UUID canceller) {
        var c = contracts.get(contractId);
        if (c == null || (!c.getIssuerUuid().equals(canceller) && !c.getTargetUuid().equals(canceller))) return false;
        if (c.getStatus() == ContractData.ContractStatus.COMPLETED || c.getStatus() == ContractData.ContractStatus.DISPUTED) return false;
        if (c.getAmount() > 0 && !escrow.refundToPayoutLocker(contractId, c.getIssuerUuid(), "contract cancelled")) return false;
        setStatus(c, ContractData.ContractStatus.CANCELLED);
        audit.log(contractId, canceller, "CANCEL", c.getAmount(), "");
        return true;
    }

    @Override
    public boolean disputeContract(int contractId, UUID actorUuid, String reason) {
        var c = contracts.get(contractId);
        if (c == null || (!c.getIssuerUuid().equals(actorUuid) && !c.getTargetUuid().equals(actorUuid))) return false;
        if (c.getStatus() != ContractData.ContractStatus.ACCEPTED && c.getStatus() != ContractData.ContractStatus.PENDING) return false;
        if (!disputes.openDispute(contractId, actorUuid, reason)) return false;
        setStatus(c, ContractData.ContractStatus.DISPUTED);
        return true;
    }

    @Override
    public List<ContractData.Contract> getContracts(UUID player) {
        return contracts.values().stream()
            .filter(c -> c.getIssuerUuid().equals(player) || c.getTargetUuid().equals(player))
            .toList();
    }

    @Override
    public List<ContractData.Contract> getAllContracts() {
        return new ArrayList<>(contracts.values());
    }

    @Override
    public ContractData.Contract getContract(int id) {
        return contracts.get(id);
    }

    @Override
    public void loadAll() {
        contracts.clear();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM contracts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                var c = new ContractData.Contract(rs.getInt("id"), UUID.fromString(rs.getString("issuer_uuid")),
                    UUID.fromString(rs.getString("target_uuid")), rs.getString("description"),
                    rs.getLong("amount"), rs.getLong("deadline"),
                    ContractData.ContractStatus.valueOf(rs.getString("status")));
                contracts.put(c.getId(), c);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load contracts", e);
        }
        logger.info("Loaded " + contracts.size() + " contracts");
    }

    private void setStatus(ContractData.Contract contract, ContractData.ContractStatus status) {
        contract.setStatus(status);
        try {
            plugin.getDatabase().executeUpdate("UPDATE contracts SET status=? WHERE id=?", status.name(), contract.getId());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set contract status", e);
        }
    }
}
