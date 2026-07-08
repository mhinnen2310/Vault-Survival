package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class ContractServiceImpl implements ContractService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final Logger logger;
    private final Map<Integer, ContractData.Contract> contracts = new ConcurrentHashMap<>();

    public ContractServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.logger = plugin.getLogger();
    }

    @Override
    public ContractData.Contract createContract(UUID issuer, UUID target, String description, long amount, long deadlineHours) {
        long deadline = System.currentTimeMillis() + (deadlineHours * 3600000);
        String sql = "INSERT INTO contracts (issuer_uuid, target_uuid, description, amount, deadline, status) VALUES (?,?,?,?,?,'PENDING')";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, issuer.toString()); ps.setString(2, target.toString());
            ps.setString(3, description); ps.setLong(4, amount); ps.setLong(5, deadline);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var c = new ContractData.Contract(id, issuer, target, description, amount, deadline, ContractData.ContractStatus.PENDING);
                contracts.put(id, c); return c;
            }
        } catch (SQLException e) { logger.log(Level.WARNING, "Failed to create contract", e); }
        return null;
    }

    @Override public boolean acceptContract(int contractId, UUID acceptor) {
        var c = contracts.get(contractId);
        if (c == null || !c.getTargetUuid().equals(acceptor) || c.getStatus() != ContractData.ContractStatus.PENDING) return false;
        c.setStatus(ContractData.ContractStatus.ACCEPTED);
        try { plugin.getDatabase().executeUpdate("UPDATE contracts SET status='ACCEPTED' WHERE id=?", contractId); } catch (SQLException ignored) {}
        return true;
    }

    @Override public boolean completeContract(int contractId, UUID completer) {
        var c = contracts.get(contractId);
        if (c == null || c.getStatus() != ContractData.ContractStatus.ACCEPTED) return false;

        // Transfer payment atomically: mark issuer's cash SPENT → mint for target
        if (c.getAmount() > 0 && currency != null) {
            Player issuer = Bukkit.getPlayer(c.getIssuerUuid());
            Player target = Bukkit.getPlayer(c.getTargetUuid());
            if (issuer != null && target != null) {
                // Atomic DB transfer
                try (Connection conn = plugin.getDatabase().getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        long remaining = c.getAmount();
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT cash_uuid, amount FROM cash_items WHERE state='ACTIVE' AND owner_uuid=? ORDER BY amount ASC")) {
                            ps.setString(1, c.getIssuerUuid().toString());
                            ResultSet rs = ps.executeQuery();
                            while (rs.next() && remaining > 0) {
                                UUID cuuid = UUID.fromString(rs.getString("cash_uuid"));
                                long amt = rs.getLong("amount");
                                if (amt <= remaining) {
                                    try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET state='SPENT' WHERE cash_uuid=?")) {
                                        up.setString(1, cuuid.toString()); up.executeUpdate();
                                    }
                                    remaining -= amt;
                                } else {
                                    try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET amount=? WHERE cash_uuid=?")) {
                                        up.setLong(1, amt - remaining); up.setString(2, cuuid.toString()); up.executeUpdate();
                                    }
                                    remaining = 0;
                                }
                            }
                        }
                        if (remaining > 0) { conn.rollback(); return false; }
                        conn.commit();
                    } catch (SQLException e) { conn.rollback(); throw e; }
                    finally { conn.setAutoCommit(true); }
                } catch (SQLException e) { logger.log(Level.WARNING, "Failed to transfer contract payment", e); return false; }

                // Mint new cash for target and give to them
                var cash = currency.mintCash(c.getAmount(), c.getIssuerUuid(), c.getTargetUuid());
                target.getInventory().addItem(cash);
                // Remove physical cash from issuer inventory
                removeCashItems(issuer, c.getAmount());
            }
        }

        c.setStatus(ContractData.ContractStatus.COMPLETED);
        try { plugin.getDatabase().executeUpdate("UPDATE contracts SET status='COMPLETED' WHERE id=?", contractId); } catch (SQLException ignored) {}
        return true;
    }

    @Override public boolean cancelContract(int contractId, UUID canceller) {
        var c = contracts.get(contractId);
        if (c == null || (!c.getIssuerUuid().equals(canceller) && !c.getTargetUuid().equals(canceller))) return false;
        if (c.getStatus() == ContractData.ContractStatus.COMPLETED) return false;
        c.setStatus(ContractData.ContractStatus.CANCELLED);
        try { plugin.getDatabase().executeUpdate("UPDATE contracts SET status='CANCELLED' WHERE id=?", contractId); } catch (SQLException ignored) {}
        return true;
    }

    @Override public List<ContractData.Contract> getContracts(UUID player) {
        return contracts.values().stream()
            .filter(c -> c.getIssuerUuid().equals(player) || c.getTargetUuid().equals(player))
            .toList();
    }
    @Override public ContractData.Contract getContract(int id) { return contracts.get(id); }

    private void removeCashItems(Player player, long amount) {
        long remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            var item = player.getInventory().getItem(i);
            if (item != null && currency.isCashItem(item)) {
                long amt = currency.getCashAmount(item);
                if (amt <= remaining) { remaining -= amt; player.getInventory().setItem(i, null); }
            }
        }
    }

    @Override public void loadAll() {
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
        } catch (SQLException e) { logger.log(Level.SEVERE, "Failed to load contracts", e); }
        logger.info("Loaded " + contracts.size() + " contracts");
    }
}
