package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.social.ContractAuditService;
import com.vaultsurvival.plugin.social.EscrowService;
import com.vaultsurvival.plugin.social.PayoutLockerService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DistrictJobServiceImpl implements DistrictJobService {
    private final VaultSurvivalPlugin plugin;
    private final DistrictService districts;
    private final PayoutLockerService payouts;
    private final EscrowService escrow;
    private final ContractAuditService audit;
    private final Map<Integer, DistrictJobData.Job> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, DistrictJobData.Claim> claims = new ConcurrentHashMap<>();

    public DistrictJobServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.districts = plugin.getServiceRegistry().get(DistrictService.class);
        this.payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        this.escrow = plugin.getServiceRegistry().get(EscrowService.class);
        this.audit = plugin.getServiceRegistry().get(ContractAuditService.class);
    }

    @Override
    public DistrictJobData.Job createJob(Player creator, DistrictData.District district, DistrictJobData.JobType type,
                                         String title, String description, long reward, long deadlineHours,
                                         String requiredItem, int requiredAmount, String origin, String destination,
                                         String checkpoint, boolean manualApproval) {
        if (!canCreate(creator, district, type)) return null;
        DistrictJobData.TrackingMode tracking = trackingMode(type);
        if (tracking == DistrictJobData.TrackingMode.AUTO_ITEM && (parseItem(requiredItem) == null || requiredAmount <= 0)) {
            creator.sendMessage(plugin.getMessageFormatter().error("Invalid required item or amount. Use names like stone, oak_log, minecraft:iron_ingot."));
            return null;
        }
        long deadline = System.currentTimeMillis() + Math.max(1, deadlineHours) * 3600000L;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO district_jobs (district_id, creator_uuid, type, title, description, reward, deadline, required_item, required_amount, origin, destination, checkpoint, tracking_mode, manual_approval, status, created_at) " +
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, district.getId());
            ps.setString(2, creator.getUniqueId().toString());
            ps.setString(3, type.name());
            ps.setString(4, title);
            ps.setString(5, description);
            ps.setLong(6, reward);
            ps.setLong(7, deadline);
            ps.setString(8, normalizeItem(requiredItem));
            ps.setInt(9, requiredAmount);
            ps.setString(10, origin);
            ps.setString(11, destination);
            ps.setString(12, checkpoint);
            ps.setString(13, tracking.name());
            ps.setInt(14, manualApproval ? 1 : 0);
            ps.setLong(15, System.currentTimeMillis());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (!keys.next()) return null;
            int id = keys.getInt(1);
            var job = new DistrictJobData.Job(id, district.getId(), creator.getUniqueId(), type, title, description,
                reward, deadline, normalizeItem(requiredItem), requiredAmount, origin, destination, checkpoint,
                tracking, manualApproval, DistrictJobData.JobStatus.DRAFT);
            jobs.put(id, job);
            if (reward > 0 && !lockTreasuryEscrow(district.getId(), id, reward, creator.getUniqueId())) {
                setJobStatus(job, DistrictJobData.JobStatus.CANCELLED);
                creator.sendMessage(plugin.getMessageFormatter().error("Treasury escrow failed. Job was not activated."));
                return null;
            }
            setJobStatus(job, DistrictJobData.JobStatus.ACTIVE);
            audit.log(-id, creator.getUniqueId(), "DISTRICT_JOB_CREATE", reward, "district=" + district.getId() + " type=" + type);
            return job;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create district job", e);
            return null;
        }
    }

    @Override public List<DistrictJobData.Job> getJobs(int districtId) {
        return jobs.values().stream().filter(j -> j.getDistrictId() == districtId).sorted(Comparator.comparingInt(DistrictJobData.Job::getId).reversed()).toList();
    }
    @Override public List<DistrictJobData.Job> getActiveJobs(int districtId) {
        return getJobs(districtId).stream().filter(j -> j.getStatus() == DistrictJobData.JobStatus.ACTIVE).toList();
    }
    @Override public List<DistrictJobData.Claim> getClaimsFor(Player player) {
        return claims.values().stream().filter(c -> c.getPlayerUuid().equals(player.getUniqueId())).toList();
    }
    @Override public List<DistrictJobData.Claim> getSubmittedClaims(int districtId) {
        return claims.values().stream().filter(c -> {
            var job = jobs.get(c.getJobId());
            return job != null && job.getDistrictId() == districtId && c.getStatus() == DistrictJobData.JobStatus.SUBMITTED;
        }).toList();
    }
    @Override public DistrictJobData.Job getJob(int id) { return jobs.get(id); }
    @Override public DistrictJobData.Claim getClaim(int id) { return claims.get(id); }

    @Override
    public boolean acceptJob(Player player, int jobId) {
        var job = jobs.get(jobId);
        if (job == null || job.getStatus() != DistrictJobData.JobStatus.ACTIVE || System.currentTimeMillis() > job.getDeadline()) return false;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO district_job_claims (job_id, player_uuid, status, created_at) VALUES (?, ?, 'IN_PROGRESS', ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, jobId);
            ps.setString(2, player.getUniqueId().toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            loadAll();
            setJobStatus(jobs.get(jobId), DistrictJobData.JobStatus.IN_PROGRESS);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to accept job", e);
            return false;
        }
    }

    @Override
    public boolean deliverJob(Player player, int jobId) {
        var job = jobs.get(jobId);
        var claim = findClaim(jobId, player.getUniqueId());
        if (job == null || claim == null || job.getStatus() != DistrictJobData.JobStatus.IN_PROGRESS || claim.getStatus() != DistrictJobData.JobStatus.IN_PROGRESS) return false;
        if (job.getTrackingMode() != DistrictJobData.TrackingMode.AUTO_ITEM) return false;
        Material material = parseItem(job.getRequiredItem());
        if (material == null || !removeItems(player, material, job.getRequiredAmount())) return false;
        return pay(job, claim, player.getUniqueId());
    }

    @Override
    public boolean submitJob(Player player, int jobId) {
        var job = jobs.get(jobId);
        var claim = findClaim(jobId, player.getUniqueId());
        if (job == null || claim == null || claim.getStatus() != DistrictJobData.JobStatus.IN_PROGRESS) return false;
        if (!job.isManualApproval() && job.getTrackingMode() == DistrictJobData.TrackingMode.AUTO_ITEM) return deliverJob(player, jobId);
        setClaimStatus(claim, DistrictJobData.JobStatus.SUBMITTED, null, null);
        return true;
    }

    @Override
    public boolean approveClaim(Player approver, int claimId) {
        var claim = claims.get(claimId);
        if (claim == null || claim.getStatus() != DistrictJobData.JobStatus.SUBMITTED) return false;
        var job = jobs.get(claim.getJobId());
        var district = job != null ? districts.getDistrict(job.getDistrictId()) : null;
        if (district == null || !districts.canApproveDistrictJob(approver.getUniqueId(), district)) return false;
        if (!pay(job, claim, claim.getPlayerUuid())) return false;
        setClaimStatus(claim, DistrictJobData.JobStatus.APPROVED, approver.getUniqueId(), null);
        return true;
    }

    @Override
    public boolean denyClaim(Player approver, int claimId, String reason) {
        var claim = claims.get(claimId);
        if (claim == null || claim.getStatus() != DistrictJobData.JobStatus.SUBMITTED) return false;
        var job = jobs.get(claim.getJobId());
        var district = job != null ? districts.getDistrict(job.getDistrictId()) : null;
        if (district == null || !districts.canApproveDistrictJob(approver.getUniqueId(), district)) return false;
        setClaimStatus(claim, DistrictJobData.JobStatus.DENIED, approver.getUniqueId(), reason);
        audit.log(-job.getId(), approver.getUniqueId(), "DISTRICT_JOB_DENY", 0, reason);
        return true;
    }

    @Override
    public Material parseItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace('-', '_');
        Material material = Material.matchMaterial(key);
        return material != null && material.isItem() ? material : null;
    }

    @Override
    public void loadAll() {
        jobs.clear(); claims.clear();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM district_jobs");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                var job = new DistrictJobData.Job(rs.getInt("id"), rs.getInt("district_id"), UUID.fromString(rs.getString("creator_uuid")),
                    DistrictJobData.JobType.valueOf(rs.getString("type")), rs.getString("title"), rs.getString("description"),
                    rs.getLong("reward"), rs.getLong("deadline"), rs.getString("required_item"), rs.getInt("required_amount"),
                    rs.getString("origin"), rs.getString("destination"), rs.getString("checkpoint"),
                    DistrictJobData.TrackingMode.valueOf(rs.getString("tracking_mode")), rs.getInt("manual_approval") == 1,
                    DistrictJobData.JobStatus.valueOf(rs.getString("status")));
                jobs.put(job.getId(), job);
            }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to load district jobs", e); }
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM district_job_claims");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) claims.put(rs.getInt("id"), new DistrictJobData.Claim(rs.getInt("id"), rs.getInt("job_id"),
                UUID.fromString(rs.getString("player_uuid")), DistrictJobData.JobStatus.valueOf(rs.getString("status"))));
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to load district job claims", e); }
    }

    private boolean canCreate(Player creator, DistrictData.District district, DistrictJobData.JobType type) {
        if (district == null) return false;
        if (districts.canCreateDistrictJob(creator.getUniqueId(), district) || districts.canRequestStation(creator.getUniqueId(), district)) return true;
        return type == DistrictJobData.JobType.MARKET_SUPPLY && districts.hasDistrictRole(creator.getUniqueId(), district, DistrictData.DistrictRole.MERCHANT);
    }

    private DistrictJobData.TrackingMode trackingMode(DistrictJobData.JobType type) {
        return switch (type) {
            case DELIVERY, GATHERING -> DistrictJobData.TrackingMode.AUTO_ITEM;
            case TRANSPORT_PACKAGE, TALK_TO_NPC -> DistrictJobData.TrackingMode.AUTO_NPC;
            case EXPLORATION_CHECKPOINT -> DistrictJobData.TrackingMode.AUTO_CHECKPOINT;
            default -> DistrictJobData.TrackingMode.MANUAL;
        };
    }

    private boolean lockTreasuryEscrow(int districtId, int jobId, long amount, UUID actor) throws SQLException {
        long remaining = amount;
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT cash_uuid, amount FROM cash_items WHERE state='IN_DISTRICT_TREASURY' AND location_id=? ORDER BY amount ASC")) {
                ps.setString(1, String.valueOf(districtId));
                ResultSet rs = ps.executeQuery();
                while (rs.next() && remaining > 0) {
                    String cashUuid = rs.getString("cash_uuid");
                    long cashAmount = rs.getLong("amount");
                    if (cashAmount <= remaining) {
                        try (PreparedStatement up = conn.prepareStatement("UPDATE cash_items SET state='IN_CONTRACT_ESCROW', location_type='CONTRACT_ESCROW', location_id=?, last_seen_at=datetime('now') WHERE cash_uuid=?")) {
                            up.setString(1, String.valueOf(-jobId)); up.setString(2, cashUuid); up.executeUpdate();
                        }
                        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO contract_escrows (contract_id, cash_uuid, amount, source_type, source_id, status, locked_by, locked_at) VALUES (?, ?, ?, 'DISTRICT_TREASURY', ?, 'LOCKED', ?, ?)")) {
                            ins.setInt(1, -jobId); ins.setString(2, cashUuid); ins.setLong(3, cashAmount); ins.setString(4, String.valueOf(districtId)); ins.setString(5, actor.toString()); ins.setLong(6, System.currentTimeMillis()); ins.executeUpdate();
                        }
                        remaining -= cashAmount;
                    }
                }
                if (remaining > 0) { conn.rollback(); return false; }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; } finally { conn.setAutoCommit(true); }
        }
        audit.log(-jobId, actor, "DISTRICT_JOB_ESCROW_LOCK", amount, "district=" + districtId);
        return true;
    }

    private boolean pay(DistrictJobData.Job job, DistrictJobData.Claim claim, UUID recipient) {
        if (!escrow.releaseToPayoutLocker(-job.getId(), recipient, "district-job=" + job.getId() + " " + job.getTitle())) return false;
        setClaimStatus(claim, DistrictJobData.JobStatus.PAYOUT_PENDING, null, null);
        setJobStatus(job, DistrictJobData.JobStatus.PAYOUT_PENDING);
        audit.log(-job.getId(), recipient, "DISTRICT_JOB_PAYOUT_PENDING", job.getReward(), "claim=" + claim.getId());
        return true;
    }

    private boolean removeItems(Player player, Material material, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) remaining -= item.getAmount();
        }
        if (remaining > 0) return false;
        remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != material) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) player.getInventory().setItem(i, null);
            remaining -= take;
        }
        return true;
    }

    private DistrictJobData.Claim findClaim(int jobId, UUID player) {
        return claims.values().stream().filter(c -> c.getJobId() == jobId && c.getPlayerUuid().equals(player)).findFirst().orElse(null);
    }
    private void setJobStatus(DistrictJobData.Job job, DistrictJobData.JobStatus status) {
        job.setStatus(status);
        try { plugin.getDatabase().executeUpdate("UPDATE district_jobs SET status=? WHERE id=?", status.name(), job.getId()); } catch (SQLException ignored) {}
    }
    private void setClaimStatus(DistrictJobData.Claim claim, DistrictJobData.JobStatus status, UUID reviewer, String reason) {
        claim.setStatus(status);
        try { plugin.getDatabase().executeUpdate("UPDATE district_job_claims SET status=?, submitted_at=CASE WHEN ?='SUBMITTED' THEN ? ELSE submitted_at END, reviewed_by=?, review_reason=? WHERE id=?",
            status.name(), status.name(), System.currentTimeMillis(), reviewer != null ? reviewer.toString() : null, reason, claim.getId()); } catch (SQLException ignored) {}
    }
    private String normalizeItem(String raw) {
        Material material = parseItem(raw);
        return material == null ? null : material.getKey().asString();
    }
}
