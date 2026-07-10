package com.vaultsurvival.plugin.workflow;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.security.StaffAlertService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Persistent civic workflows shared by player, district, and staff dialogs. */
public final class CivicWorkflowService {
    public record Preferences(String notifications, String menuStyle, String privacy) { }
    public record JoinRequest(int id, int districtId, UUID playerUuid, String playerName,
                              String message, String status, long createdAt) { }
    public record Report(int id, UUID reporterUuid, String reporterName, String subjectName,
                         Integer districtId, String category, String details, String world,
                         double x, double y, double z, String status, String assignedTo,
                         long createdAt) { }
    public record Diplomacy(int id, int districtA, int districtB, String relation,
                            Integer proposerDistrict, long updatedAt) { }
    public record JobHistory(int claimId, int jobId, String title, String claimant,
                             String status, long reward, long createdAt, long submittedAt) { }
    public record JobDispute(int id, int claimId, int jobId, int districtId, String openedBy,
                             String reason, String status, String handledBy, String resolution,
                             long createdAt) { }
    public record SupportRequest(int id, int districtId, Integer projectId, String requestedBy,
                                 String details, String status, String assignedTo, long createdAt) { }

    private static final List<String> NOTIFICATION_VALUES = List.of("ALL", "IMPORTANT", "OFF");
    private static final List<String> MENU_VALUES = List.of("AUTO", "NATIVE", "COMPACT");
    private static final List<String> PRIVACY_VALUES = List.of("PUBLIC", "FRIENDS", "PRIVATE");

    private final VaultSurvivalPlugin plugin;

    public CivicWorkflowService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public Preferences preferences(UUID playerUuid) {
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT notifications,menu_style,privacy FROM player_preferences WHERE player_uuid=?")) {
            statement.setString(1, playerUuid.toString());
            ResultSet result = statement.executeQuery();
            if (result.next()) {
                return new Preferences(result.getString(1), result.getString(2), result.getString(3));
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player preferences", error);
        }
        return new Preferences("ALL", "AUTO", "PUBLIC");
    }

    public Preferences setPreference(UUID playerUuid, String key, String requestedValue) {
        Preferences current = preferences(playerUuid);
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        List<String> allowed = switch (normalizedKey) {
            case "notifications" -> NOTIFICATION_VALUES;
            case "menu", "menu_style", "style" -> MENU_VALUES;
            case "privacy" -> PRIVACY_VALUES;
            default -> List.of();
        };
        if (allowed.isEmpty()) throw new IllegalArgumentException("Unknown preference: " + key);
        String currentValue = switch (normalizedKey) {
            case "notifications" -> current.notifications();
            case "menu", "menu_style", "style" -> current.menuStyle();
            default -> current.privacy();
        };
        String value = requestedValue == null || requestedValue.equalsIgnoreCase("next")
            ? allowed.get((allowed.indexOf(currentValue.toUpperCase(Locale.ROOT)) + 1) % allowed.size())
            : requestedValue.toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Allowed values: " + String.join(", ", allowed));
        }
        String notifications = normalizedKey.equals("notifications") ? value : current.notifications();
        String menu = normalizedKey.equals("menu") || normalizedKey.equals("menu_style") || normalizedKey.equals("style")
            ? value : current.menuStyle();
        String privacy = normalizedKey.equals("privacy") ? value : current.privacy();
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT INTO player_preferences(player_uuid,notifications,menu_style,privacy,updated_at) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET notifications=excluded.notifications,menu_style=excluded.menu_style,privacy=excluded.privacy,updated_at=excluded.updated_at",
                playerUuid.toString(), notifications, menu, privacy, System.currentTimeMillis());
        } catch (Exception error) {
            throw new IllegalStateException("Could not save preference", error);
        }
        return new Preferences(notifications, menu, privacy);
    }

    public boolean canViewProfile(UUID viewer, UUID target) {
        if (viewer.equals(target)) return true;
        String privacy = preferences(target).privacy();
        if (privacy.equals("PUBLIC")) return true;
        if (privacy.equals("PRIVATE")) return false;
        return count("SELECT COUNT(*) FROM friends WHERE (player_uuid=? AND friend_uuid=?) OR (player_uuid=? AND friend_uuid=?)",
            viewer.toString(), target.toString(), target.toString(), viewer.toString()) > 0;
    }

    public JoinRequest requestJoin(Player player, int districtId, String message) {
        DistrictService districts = districts();
        DistrictData.District district = districts.getDistrict(districtId);
        if (district == null || district.getStatus() != DistrictData.DistrictStatus.ACTIVE) {
            throw new IllegalArgumentException("That district is not active.");
        }
        if (districts.getPlayerDistrict(player.getUniqueId()) != null) {
            throw new IllegalStateException("Leave your current district before requesting another.");
        }
        long now = System.currentTimeMillis();
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO district_join_requests(district_id,player_uuid,player_name,message,status,created_at) VALUES(?,?,?,?,'OPEN',?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, districtId);
            statement.setString(2, player.getUniqueId().toString());
            statement.setString(3, player.getName());
            statement.setString(4, trim(message, 240));
            statement.setLong(5, now);
            statement.executeUpdate();
            ResultSet keys = statement.getGeneratedKeys();
            int id = keys.next() ? keys.getInt(1) : -1;
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "DISTRICT_JOIN_REQUEST", "DISTRICT",
                String.valueOf(districtId), "request=" + id);
            for (UUID member : district.getMembers()) {
                if (district.isCouncil(member)) notify(member, player.getName() + " requested to join " + district.getName() + ".", true);
            }
            return new JoinRequest(id, districtId, player.getUniqueId(), player.getName(), message, "OPEN", now);
        } catch (Exception error) {
            if (error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                throw new IllegalStateException("You already have an open request for this district.");
            }
            throw new IllegalStateException("Could not create join request.", error);
        }
    }

    public List<JoinRequest> joinRequests(int districtId, boolean includeClosed) {
        List<JoinRequest> rows = new ArrayList<>();
        String sql = "SELECT * FROM district_join_requests WHERE district_id=? "
            + (includeClosed ? "" : "AND status='OPEN' ") + "ORDER BY created_at DESC LIMIT 50";
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, districtId);
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(readJoinRequest(result));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to load district join requests", error);
        }
        return rows;
    }

    public boolean resolveJoinRequest(Player actor, int requestId, boolean approve) {
        JoinRequest request = findJoinRequest(requestId);
        if (request == null || !request.status().equals("OPEN")) return false;
        DistrictService districts = districts();
        DistrictData.District district = districts.getDistrict(request.districtId());
        if (district == null || !districts.canManageRoles(actor.getUniqueId(), district)) return false;
        if (approve && !districts.inviteMember(district.getId(), actor.getUniqueId(), request.playerUuid())) return false;
        String status = approve ? "APPROVED" : "DENIED";
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE district_join_requests SET status=?,handled_by=?,resolved_at=? WHERE id=? AND status='OPEN'",
                status, actor.getUniqueId().toString(), System.currentTimeMillis(), requestId);
            plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), "DISTRICT_JOIN_" + status, "DISTRICT",
                String.valueOf(district.getId()), "request=" + requestId + " player=" + request.playerUuid());
            notify(request.playerUuid(), "Your request to join " + district.getName() + " was " + status.toLowerCase(Locale.ROOT) + ".", true);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public Report createReport(Player reporter, String category, String subject, String details) {
        if (details == null || details.isBlank()) throw new IllegalArgumentException("Report details are required.");
        if (!plugin.getConfigManager().isStaffSandbox() && count(
            "SELECT COUNT(*) FROM player_reports WHERE reporter_uuid=? AND status IN ('OPEN','CLAIMED')",
            reporter.getUniqueId().toString()) >= 5) {
            throw new IllegalStateException("You already have five open reports. Wait for staff to handle one first.");
        }
        String normalizedCategory = normalizeCategory(category);
        String subjectName = subject == null || subject.equalsIgnoreCase("none") ? null : trim(subject, 32);
        String subjectUuid = null;
        if (subjectName != null) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(subjectName);
            if (target.hasPlayedBefore() || target.isOnline()) subjectUuid = target.getUniqueId().toString();
        }
        DistrictData.District district = districts().getPlayerDistrict(reporter.getUniqueId());
        if (district == null) {
            try { district = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.area.CurrentAreaService.class).resolve(reporter).district(); }
            catch (RuntimeException ignored) { }
        }
        Location location = reporter.getLocation();
        long now = System.currentTimeMillis();
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO player_reports(reporter_uuid,reporter_name,subject_uuid,subject_name,district_id,category,details,world,x,y,z,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, reporter.getUniqueId().toString());
            statement.setString(2, reporter.getName());
            statement.setString(3, subjectUuid);
            statement.setString(4, subjectName);
            if (district == null) statement.setObject(5, null); else statement.setInt(5, district.getId());
            statement.setString(6, normalizedCategory);
            statement.setString(7, trim(details, 800));
            statement.setString(8, location.getWorld().getName());
            statement.setDouble(9, location.getX());
            statement.setDouble(10, location.getY());
            statement.setDouble(11, location.getZ());
            statement.setLong(12, now);
            statement.executeUpdate();
            ResultSet keys = statement.getGeneratedKeys();
            int id = keys.next() ? keys.getInt(1) : -1;
            plugin.getAuditLogger().log(reporter.getUniqueId(), reporter.getName(), "PLAYER_REPORT_CREATE", "REPORT", String.valueOf(id),
                "category=" + normalizedCategory + " subject=" + subjectName);
            StaffAlertService alerts = service(StaffAlertService.class);
            if (alerts != null) alerts.recordAlert("REPORT", normalizedCategory.equals("POLICE_ABUSE") ? "HIGH" : "MEDIUM",
                reporter.getUniqueId(), reporter.getName(), "Report #" + id + " " + normalizedCategory + ": " + trim(details, 180), location);
            return new Report(id, reporter.getUniqueId(), reporter.getName(), subjectName,
                district == null ? null : district.getId(), normalizedCategory, details, location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), "OPEN", null, now);
        } catch (Exception error) {
            throw new IllegalStateException("Could not submit report.", error);
        }
    }

    public List<Report> reports(String category, boolean includeClosed) {
        List<Report> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM player_reports WHERE 1=1 ");
        if (!includeClosed) sql.append("AND status IN ('OPEN','CLAIMED') ");
        if (category != null && !category.equalsIgnoreCase("ALL")) sql.append("AND category=? ");
        sql.append("ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'CLAIMED' THEN 1 ELSE 2 END, created_at DESC LIMIT 100");
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            if (category != null && !category.equalsIgnoreCase("ALL")) statement.setString(1, normalizeCategory(category));
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(readReport(result));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player reports", error);
        }
        return rows;
    }

    public boolean claimReport(Player staff, int reportId) {
        return updateReport(staff, reportId, "CLAIMED", null, "status='OPEN'");
    }

    public boolean resolveReport(Player staff, int reportId, boolean dismiss, String resolution) {
        return updateReport(staff, reportId, dismiss ? "DISMISSED" : "RESOLVED", trim(resolution, 500),
            "status IN ('OPEN','CLAIMED')");
    }

    private boolean updateReport(Player staff, int reportId, String status, String resolution, String condition) {
        try {
            String sql = status.equals("CLAIMED")
                ? "UPDATE player_reports SET status='CLAIMED',assigned_to=? WHERE id=? AND " + condition
                : "UPDATE player_reports SET status=?,assigned_to=COALESCE(assigned_to,?),resolution=?,resolved_at=? WHERE id=? AND " + condition;
            int changed;
            try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                if (status.equals("CLAIMED")) {
                    statement.setString(1, staff.getUniqueId().toString()); statement.setInt(2, reportId);
                } else {
                    statement.setString(1, status); statement.setString(2, staff.getUniqueId().toString());
                    statement.setString(3, resolution); statement.setLong(4, System.currentTimeMillis()); statement.setInt(5, reportId);
                }
                changed = statement.executeUpdate();
            }
            if (changed > 0) plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(),
                "REPORT_" + status, String.valueOf(reportId), "resolution=" + resolution);
            return changed > 0;
        } catch (Exception error) {
            return false;
        }
    }

    public List<Diplomacy> diplomacyFor(int districtId) {
        List<Diplomacy> rows = new ArrayList<>();
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT * FROM district_diplomacy WHERE district_a=? OR district_b=? ORDER BY updated_at DESC")) {
            statement.setInt(1, districtId); statement.setInt(2, districtId);
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(readDiplomacy(result));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to load diplomacy", error);
        }
        return rows;
    }

    public Set<Integer> alliedDistrictIds(int districtId) {
        Set<Integer> allies = new LinkedHashSet<>();
        allies.add(districtId);
        for (Diplomacy relation : diplomacyFor(districtId)) {
            if (!relation.relation().equals("ALLIED")) continue;
            allies.add(relation.districtA() == districtId ? relation.districtB() : relation.districtA());
        }
        return allies;
    }

    public boolean changeDiplomacy(Player actor, String action, int targetDistrictId) {
        DistrictService districts = districts();
        DistrictData.District own = districts.getPlayerDistrict(actor.getUniqueId());
        DistrictData.District target = districts.getDistrict(targetDistrictId);
        if (own == null || target == null || own.getId() == target.getId() || !canDiplomat(actor.getUniqueId(), own)) return false;
        int a = Math.min(own.getId(), target.getId()); int b = Math.max(own.getId(), target.getId());
        String normalized = action.toLowerCase(Locale.ROOT);
        Diplomacy existing = diplomacyFor(own.getId()).stream()
            .filter(row -> row.districtA() == a && row.districtB() == b).findFirst().orElse(null);
        String relation;
        Integer proposer = own.getId();
        switch (normalized) {
            case "request", "ally" -> relation = "PENDING_ALLIANCE";
            case "accept" -> {
                if (existing == null || !existing.relation().equals("PENDING_ALLIANCE")
                    || existing.proposerDistrict() == null || existing.proposerDistrict() == own.getId()) return false;
                relation = "ALLIED"; proposer = null;
            }
            case "deny", "neutral" -> { relation = "NEUTRAL"; proposer = null; }
            case "hostile", "enemy" -> { relation = "HOSTILE"; proposer = null; }
            default -> { return false; }
        }
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT INTO district_diplomacy(district_a,district_b,relation,proposer_district,changed_by,updated_at) VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(district_a,district_b) DO UPDATE SET relation=excluded.relation,proposer_district=excluded.proposer_district,changed_by=excluded.changed_by,updated_at=excluded.updated_at",
                a, b, relation, proposer, actor.getUniqueId().toString(), System.currentTimeMillis());
            plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), "DISTRICT_DIPLOMACY_" + relation, "DISTRICT",
                String.valueOf(own.getId()), "target=" + target.getId());
            for (UUID member : target.getMembers()) if (canDiplomat(member, target)) {
                notify(member, own.getName() + " changed diplomacy with " + target.getName() + " to " + relation + ".", true);
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public List<JobHistory> jobHistory(Integer districtId, UUID playerUuid, int limit) {
        List<JobHistory> rows = new ArrayList<>();
        String sql = "SELECT c.id claim_id,j.id job_id,j.title,c.player_uuid,c.status,j.reward,c.created_at,c.submitted_at " +
            "FROM district_job_claims c JOIN district_jobs j ON j.id=c.job_id WHERE 1=1 " +
            (districtId == null ? "" : "AND j.district_id=? ") + (playerUuid == null ? "" : "AND c.player_uuid=? ") +
            "AND c.status IN ('APPROVED','DENIED','COMPLETED','PAID','PAYOUT_PENDING','DISPUTED') ORDER BY c.created_at DESC LIMIT ?";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (districtId != null) statement.setInt(index++, districtId);
            if (playerUuid != null) statement.setString(index++, playerUuid.toString());
            statement.setInt(index, Math.max(1, Math.min(100, limit)));
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(new JobHistory(result.getInt("claim_id"), result.getInt("job_id"),
                result.getString("title"), result.getString("player_uuid"), result.getString("status"),
                result.getLong("reward"), result.getLong("created_at"), result.getLong("submitted_at")));
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Failed to load job history", error);
        }
        return rows;
    }

    public int openJobDispute(Player actor, int claimId, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A dispute reason is required.");
        String sql = "SELECT c.player_uuid,c.status,j.id job_id,j.district_id FROM district_job_claims c JOIN district_jobs j ON j.id=c.job_id WHERE c.id=?";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, claimId); ResultSet result = statement.executeQuery();
            if (!result.next()) throw new IllegalArgumentException("Claim not found.");
            int districtId = result.getInt("district_id"); int jobId = result.getInt("job_id");
            DistrictData.District district = districts().getDistrict(districtId);
            boolean owner = actor.getUniqueId().toString().equals(result.getString("player_uuid"));
            boolean manager = district != null && districts().canApproveDistrictJob(actor.getUniqueId(), district);
            if (!owner && !manager) throw new IllegalStateException("You cannot dispute this claim.");
            String status = result.getString("status");
            if (!Set.of("APPROVED", "DENIED", "COMPLETED", "PAID", "PAYOUT_PENDING").contains(status)) {
                throw new IllegalStateException("This claim is not in a disputable state.");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO district_job_disputes(claim_id,job_id,district_id,opened_by,reason,status,created_at) VALUES(?,?,?,?,?,'OPEN',?)",
                Statement.RETURN_GENERATED_KEYS)) {
                insert.setInt(1, claimId); insert.setInt(2, jobId); insert.setInt(3, districtId);
                insert.setString(4, actor.getUniqueId().toString()); insert.setString(5, trim(reason, 600));
                insert.setLong(6, System.currentTimeMillis()); insert.executeUpdate();
                ResultSet keys = insert.getGeneratedKeys(); int id = keys.next() ? keys.getInt(1) : -1;
                plugin.getDatabase().executeUpdate("UPDATE district_job_claims SET status='DISPUTED' WHERE id=?", claimId);
                plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), "DISTRICT_JOB_DISPUTE_OPEN", "CLAIM", String.valueOf(claimId), "dispute=" + id);
                StaffAlertService alerts = service(StaffAlertService.class);
                if (alerts != null) alerts.recordAlert("JOB_DISPUTE", "MEDIUM", actor.getUniqueId(), actor.getName(),
                    "District job dispute #" + id + " claim #" + claimId, actor.getLocation());
                return id;
            }
        } catch (IllegalArgumentException | IllegalStateException expected) {
            throw expected;
        } catch (Exception error) {
            if (error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                throw new IllegalStateException("This claim already has an open dispute.");
            }
            throw new IllegalStateException("Could not open dispute.", error);
        }
    }

    public List<JobDispute> jobDisputes(Integer districtId, boolean includeClosed) {
        List<JobDispute> rows = new ArrayList<>();
        String sql = "SELECT * FROM district_job_disputes WHERE 1=1 " + (districtId == null ? "" : "AND district_id=? ")
            + (includeClosed ? "" : "AND status='OPEN' ") + "ORDER BY created_at DESC LIMIT 100";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (districtId != null) statement.setInt(1, districtId);
            ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(new JobDispute(result.getInt("id"), result.getInt("claim_id"),
                result.getInt("job_id"), result.getInt("district_id"), result.getString("opened_by"),
                result.getString("reason"), result.getString("status"), result.getString("handled_by"),
                result.getString("resolution"), result.getLong("created_at")));
        } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Failed to load job disputes", error); }
        return rows;
    }

    public boolean resolveJobDispute(Player actor, int disputeId, boolean approve, String resolution) {
        JobDispute dispute = jobDisputes(null, false).stream().filter(row -> row.id() == disputeId).findFirst().orElse(null);
        if (dispute == null) return false;
        DistrictData.District district = districts().getDistrict(dispute.districtId());
        boolean allowed = district != null && districts().canApproveDistrictJob(actor.getUniqueId(), district);
        if (!allowed && !actor.hasPermission("vs.admin")) return false;
        try {
            plugin.getDatabase().executeUpdate("UPDATE district_job_disputes SET status='RESOLVED',handled_by=?,resolution=?,resolved_at=? WHERE id=? AND status='OPEN'",
                actor.getUniqueId().toString(), trim(resolution, 500), System.currentTimeMillis(), disputeId);
            plugin.getDatabase().executeUpdate("UPDATE district_job_claims SET status=?,reviewed_by=?,review_reason=? WHERE id=?",
                approve ? "APPROVED" : "DENIED", actor.getUniqueId().toString(), trim(resolution, 500), dispute.claimId());
            plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), "DISTRICT_JOB_DISPUTE_RESOLVE", "DISPUTE", String.valueOf(disputeId),
                "outcome=" + (approve ? "APPROVED" : "DENIED"));
            return true;
        } catch (Exception error) { return false; }
    }

    public int requestKingdomSupport(Player actor, Integer projectId, String details) {
        DistrictData.District district = districts().getPlayerDistrict(actor.getUniqueId());
        if (district == null || !districts().canManageDevelopment(actor.getUniqueId(), district)) {
            throw new IllegalStateException("A district development role is required.");
        }
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO kingdom_support_requests(district_id,project_id,requested_by,details,status,created_at,updated_at) VALUES(?,?,?,?,'OPEN',?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, district.getId()); if (projectId == null) statement.setObject(2, null); else statement.setInt(2, projectId);
            statement.setString(3, actor.getUniqueId().toString()); statement.setString(4, trim(details, 600));
            long now = System.currentTimeMillis(); statement.setLong(5, now); statement.setLong(6, now); statement.executeUpdate();
            ResultSet keys = statement.getGeneratedKeys(); int id = keys.next() ? keys.getInt(1) : -1;
            plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), "KINGDOM_SUPPORT_REQUEST", "DISTRICT", String.valueOf(district.getId()), "request=" + id);
            StaffAlertService alerts = service(StaffAlertService.class);
            if (alerts != null) alerts.recordAlert("KINGDOM_SUPPORT", "LOW", actor.getUniqueId(), actor.getName(),
                "Support request #" + id + " from " + district.getName(), actor.getLocation());
            return id;
        } catch (Exception error) { throw new IllegalStateException("Could not create support request.", error); }
    }

    public List<SupportRequest> supportRequests(Integer districtId, boolean includeClosed) {
        List<SupportRequest> rows = new ArrayList<>();
        String sql = "SELECT * FROM kingdom_support_requests WHERE 1=1 " + (districtId == null ? "" : "AND district_id=? ")
            + (includeClosed ? "" : "AND status IN ('OPEN','ASSIGNED') ") + "ORDER BY created_at DESC LIMIT 100";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (districtId != null) statement.setInt(1, districtId); ResultSet result = statement.executeQuery();
            while (result.next()) rows.add(new SupportRequest(result.getInt("id"), result.getInt("district_id"),
                (Integer) result.getObject("project_id"), result.getString("requested_by"), result.getString("details"),
                result.getString("status"), result.getString("assigned_to"), result.getLong("created_at")));
        } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Failed to load support requests", error); }
        return rows;
    }

    public boolean assignSupport(Player staff, int requestId) {
        try {
            int changed;
            try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE kingdom_support_requests SET status='ASSIGNED',assigned_to=?,updated_at=? WHERE id=? AND status='OPEN'")) {
                statement.setString(1, staff.getUniqueId().toString()); statement.setLong(2, System.currentTimeMillis()); statement.setInt(3, requestId);
                changed = statement.executeUpdate();
            }
            if (changed > 0) plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "KINGDOM_SUPPORT_ASSIGN", String.valueOf(requestId), "");
            return changed > 0;
        } catch (Exception error) { return false; }
    }

    public boolean completeSupport(Player staff, int requestId, String note) {
        try {
            int changed;
            try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE kingdom_support_requests SET status='COMPLETED',assigned_to=COALESCE(assigned_to,?),completion_note=?,updated_at=? WHERE id=? AND status IN ('OPEN','ASSIGNED')")) {
                statement.setString(1, staff.getUniqueId().toString()); statement.setString(2, trim(note, 500));
                statement.setLong(3, System.currentTimeMillis()); statement.setInt(4, requestId); changed = statement.executeUpdate();
            }
            if (changed > 0) plugin.getAuditLogger().logAdminAction(staff.getUniqueId(), staff.getName(), "KINGDOM_SUPPORT_COMPLETE", String.valueOf(requestId), "note=" + trim(note, 120));
            return changed > 0;
        } catch (Exception error) { return false; }
    }

    private boolean canDiplomat(UUID playerUuid, DistrictData.District district) {
        return district != null && (district.isCouncil(playerUuid) || district.hasRole(playerUuid, DistrictData.DistrictRole.DIPLOMAT));
    }

    private void notify(UUID playerUuid, String message, boolean important) {
        Preferences preferences = preferences(playerUuid);
        if (preferences.notifications().equals("OFF") || (preferences.notifications().equals("IMPORTANT") && !important)) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) player.sendMessage(plugin.getMessageFormatter().info(message));
    }

    private JoinRequest findJoinRequest(int id) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM district_join_requests WHERE id=?")) {
            statement.setInt(1, id); ResultSet result = statement.executeQuery(); return result.next() ? readJoinRequest(result) : null;
        } catch (Exception error) { return null; }
    }

    private JoinRequest readJoinRequest(ResultSet result) throws Exception {
        return new JoinRequest(result.getInt("id"), result.getInt("district_id"), UUID.fromString(result.getString("player_uuid")),
            result.getString("player_name"), result.getString("message"), result.getString("status"), result.getLong("created_at"));
    }

    private Report readReport(ResultSet result) throws Exception {
        Object districtId = result.getObject("district_id");
        return new Report(result.getInt("id"), UUID.fromString(result.getString("reporter_uuid")), result.getString("reporter_name"),
            result.getString("subject_name"), districtId == null ? null : ((Number) districtId).intValue(), result.getString("category"),
            result.getString("details"), result.getString("world"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z"),
            result.getString("status"), result.getString("assigned_to"), result.getLong("created_at"));
    }

    private Diplomacy readDiplomacy(ResultSet result) throws Exception {
        Object proposer = result.getObject("proposer_district");
        return new Diplomacy(result.getInt("id"), result.getInt("district_a"), result.getInt("district_b"), result.getString("relation"),
            proposer == null ? null : ((Number) proposer).intValue(), result.getLong("updated_at"));
    }

    private DistrictService districts() { return plugin.getServiceRegistry().get(DistrictService.class); }
    private int count(String sql, Object... values) {
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            ResultSet result = statement.executeQuery(); return result.next() ? result.getInt(1) : 0;
        } catch (Exception ignored) { return 0; }
    }
    private <T> T service(Class<T> type) { try { return plugin.getServiceRegistry().get(type); } catch (RuntimeException ignored) { return null; } }
    private String normalizeCategory(String value) {
        String category = value == null ? "OTHER" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        return category.isBlank() ? "OTHER" : trim(category, 32);
    }
    private String trim(String value, int max) { if (value == null) return ""; String clean = value.trim(); return clean.length() <= max ? clean : clean.substring(0, max); }
}
