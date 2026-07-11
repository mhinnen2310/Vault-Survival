package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.social.ContractAuditService;
import com.vaultsurvival.plugin.social.PayoutLockerService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SpawnJobServiceImpl implements SpawnJobService {
    private static final int MAX_ACTIVE = 3;
    private static final long SMALL_PAYOUT = 250;
    private final VaultSurvivalPlugin plugin;
    private final PayoutLockerService payouts;
    private final ContractAuditService audit;
    private final NamespacedKey packageKey;
    private final Map<Integer, SpawnJobData.Job> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, SpawnJobData.PlayerJob> playerJobs = new ConcurrentHashMap<>();

    public SpawnJobServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        this.audit = plugin.getServiceRegistry().get(ContractAuditService.class);
        this.packageKey = new NamespacedKey(plugin, "spawn_package_uuid");
    }

    @Override public void loadAll() {
        jobs.clear(); playerJobs.clear();
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM spawn_city_jobs");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) jobs.put(rs.getInt("id"), new SpawnJobData.Job(rs.getInt("id"),
                SpawnJobData.JobType.valueOf(rs.getString("type")), rs.getString("title"), rs.getString("description"),
                rs.getLong("reward"), rs.getString("required_item"), rs.getInt("required_amount"),
                rs.getString("destination"), rs.getInt("enabled") == 1));
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to load spawn jobs", e); }
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM player_spawn_jobs");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) playerJobs.put(rs.getInt("id"), new SpawnJobData.PlayerJob(rs.getInt("id"), rs.getInt("job_id"),
                UUID.fromString(rs.getString("player_uuid")), SpawnJobData.PlayerJobStatus.valueOf(rs.getString("status"))));
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to load player spawn jobs", e); }
    }

    @Override public void seedStarterJobs() {
        seed("oak_logs", SpawnJobData.JobType.ITEM_DELIVERY, "Deliver 32 Oak Logs to Spawn Builder", 100, "oak_log", 32, "Spawn Builder");
        seed("cobblestone", SpawnJobData.JobType.ITEM_DELIVERY, "Deliver 64 Cobblestone to Spawn Mason", 120, "cobblestone", 64, "Spawn Mason");
        seed("coal", SpawnJobData.JobType.ITEM_DELIVERY, "Deliver 16 Coal to Spawn Smith", 90, "coal", 16, "Spawn Smith");
        seed("letter", SpawnJobData.JobType.TRANSPORT_PACKAGE, "Transport Sealed Letter to Spawn Conductor", 150, null, 0, "Spawn Conductor");
        seed("mint", SpawnJobData.JobType.TALK_TO_NPC, "Visit Mint NPC and return to Job Board", 75, null, 0, "Mint NPC");
        seed("district_crate", SpawnJobData.JobType.TRANSPORT_PACKAGE, "Transport crate to first configured district station", 200, null, 0, "District Station NPC");
        loadAll();
    }

    @Override public List<SpawnJobData.Job> getJobs() { return jobs.values().stream().filter(SpawnJobData.Job::isEnabled).sorted(Comparator.comparingInt(SpawnJobData.Job::getId)).toList(); }
    @Override public List<SpawnJobData.PlayerJob> getActiveJobs(Player player) { return playerJobs.values().stream().filter(j -> j.getPlayerUuid().equals(player.getUniqueId()) && j.getStatus() == SpawnJobData.PlayerJobStatus.ACTIVE).toList(); }
    @Override public SpawnJobData.Job getJob(int id) { return jobs.get(id); }

    @Override public boolean accept(Player player, int jobId) {
        SpawnJobData.Job job = jobs.get(jobId);
        if (job == null || !job.isEnabled()) return false;
        if (getActiveJobs(player).size() >= MAX_ACTIVE) return false;
        if (getActiveJobs(player).stream().anyMatch(j -> j.getJobId() == jobId)) return false;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO player_spawn_jobs (job_id, player_uuid, status, accepted_at) VALUES (?, ?, 'ACTIVE', ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, jobId); ps.setString(2, player.getUniqueId().toString()); ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            int playerJobId = keys.next() ? keys.getInt(1) : -1;
            if (job.getType() == SpawnJobData.JobType.TRANSPORT_PACKAGE) givePackage(player, playerJobId, job);
            loadAll();
            audit.log(0, player.getUniqueId(), "SPAWN_JOB_ACCEPT", job.getReward(), "job=" + jobId);
            return true;
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to accept spawn job", e); return false; }
    }

    @Override public boolean turnIn(Player player, int jobId) {
        SpawnJobData.Job job = jobs.get(jobId);
        SpawnJobData.PlayerJob pj = getActiveJobs(player).stream().filter(j -> j.getJobId() == jobId).findFirst().orElse(null);
        if (job == null || pj == null) return false;
        boolean ok = switch (job.getType()) {
            case ITEM_DELIVERY -> removeItems(player, parseItem(job.getRequiredItem()), job.getRequiredAmount());
            case TRANSPORT_PACKAGE -> consumePackage(player, pj.getId(), job.getDestination());
            case TALK_TO_NPC, EXPLORATION_CHECKPOINT -> true;
            default -> false;
        };
        if (!ok) return false;
        complete(player, pj, job);
        return true;
    }

    @Override public boolean abandon(Player player, int jobId) {
        SpawnJobData.PlayerJob pj = getActiveJobs(player).stream().filter(j -> j.getJobId() == jobId).findFirst().orElse(null);
        if (pj == null) return false;
        setPlayerJobStatus(pj, SpawnJobData.PlayerJobStatus.ABANDONED);
        markPackages(pj.getId(), "ABANDONED");
        audit.log(0, player.getUniqueId(), "SPAWN_JOB_ABANDON", 0, "job=" + jobId);
        return true;
    }

    @Override public boolean disableJob(int jobId) {
        try { plugin.getDatabase().executeUpdate("UPDATE spawn_city_jobs SET enabled=0 WHERE id=?", jobId); loadAll(); return true; }
        catch (SQLException e) { return false; }
    }

    @Override public SpawnJobData.Job createAdminJob(SpawnJobData.JobType type, String title, long reward, String item, int amount, String destination) {
        if (item != null && parseItem(item) == null) return null;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO spawn_city_jobs (type,title,description,reward,required_item,required_amount,destination,enabled,created_at) VALUES (?,?,?,?,?,?,?,1,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type.name()); ps.setString(2, title); ps.setString(3, title); ps.setLong(4, reward);
            ps.setString(5, normalizeItem(item)); ps.setInt(6, amount); ps.setString(7, destination); ps.setLong(8, System.currentTimeMillis()); ps.executeUpdate();
            loadAll(); ResultSet keys = ps.getGeneratedKeys(); return keys.next() ? jobs.get(keys.getInt(1)) : null;
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to create spawn job", e); return null; }
    }

    @Override public Material parseItem(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Material m = Material.matchMaterial(raw.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace('-', '_'));
        return m != null && m.isItem() ? m : null;
    }

    public boolean isPackage(ItemStack item) { return getPackageUuid(item) != null; }
    public UUID getPackageUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(packageKey, PersistentDataType.STRING);
        try { return raw != null ? UUID.fromString(raw) : null; } catch (Exception ignored) { return null; }
    }
    public void markLost(UUID packageUuid) { markPackage(packageUuid, "LOST"); }

    private void complete(Player player, SpawnJobData.PlayerJob pj, SpawnJobData.Job job) {
        setPlayerJobStatus(pj, SpawnJobData.PlayerJobStatus.COMPLETED);
        if (job.getReward() <= SMALL_PAYOUT && player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(plugin.getServiceRegistry().get(com.vaultsurvival.plugin.currency.CurrencyService.class).mintCash(job.getReward(), null, player.getUniqueId()));
        } else {
            payouts.storePayout(player.getUniqueId(), job.getReward(), "SPAWN_CITY_JOB", String.valueOf(job.getId()), job.getTitle());
        }
        setPlayerJobStatus(pj, SpawnJobData.PlayerJobStatus.PAID);
        audit.log(0, player.getUniqueId(), "SPAWN_JOB_COMPLETE", job.getReward(), "job=" + job.getId());
    }

    private void givePackage(Player player, int playerJobId, SpawnJobData.Job job) throws SQLException {
        UUID uuid = UUID.randomUUID();
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        meta.displayName(plugin.getMessageFormatter().deserialize("&6Sealed Package &8#" + uuid.toString().substring(0, 8)));
        meta.getPersistentDataContainer().set(packageKey, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        plugin.getDatabase().executeUpdate("INSERT INTO transport_packages (package_uuid, player_job_id, player_uuid, destination, status, created_at, expires_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
            uuid.toString(), playerJobId, player.getUniqueId().toString(), job.getDestination(), System.currentTimeMillis(), System.currentTimeMillis() + 86400000L);
    }

    private boolean consumePackage(Player player, int playerJobId, String destination) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            UUID uuid = getPackageUuid(item);
            if (uuid == null) continue;
            try (Connection conn = plugin.getDatabase().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM transport_packages WHERE package_uuid=? AND player_job_id=? AND status='ACTIVE'")) {
                ps.setString(1, uuid.toString()); ps.setInt(2, playerJobId);
                if (ps.executeQuery().next()) {
                    player.getInventory().setItem(i, null);
                    markPackage(uuid, "TURNED_IN");
                    return true;
                }
            } catch (SQLException e) { return false; }
        }
        return false;
    }

    private boolean removeItems(Player player, Material material, int amount) {
        if (material == null || amount <= 0) return false;
        int found = 0;
        for (ItemStack item : player.getInventory().getContents()) if (item != null && item.getType() == material) found += item.getAmount();
        if (found < amount) return false;
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getSize() && remaining > 0; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != material) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take); if (item.getAmount() <= 0) player.getInventory().setItem(i, null);
            remaining -= take;
        }
        return true;
    }

    private void seed(String key, SpawnJobData.JobType type, String title, long reward, String item, int amount, String destination) {
        try { plugin.getDatabase().executeUpdate("INSERT OR IGNORE INTO spawn_city_jobs (type,title,description,reward,required_item,required_amount,destination,enabled,seed_key,created_at) VALUES (?,?,?,?,?,?,?,1,?,?)",
            type.name(), title, title, reward, normalizeItem(item), amount, destination, key, System.currentTimeMillis()); }
        catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Failed to seed spawn job " + key, e); }
    }
    private void setPlayerJobStatus(SpawnJobData.PlayerJob pj, SpawnJobData.PlayerJobStatus status) {
        pj.setStatus(status);
        try { plugin.getDatabase().executeUpdate("UPDATE player_spawn_jobs SET status=?, completed_at=CASE WHEN ? IN ('COMPLETED','PAID','FAILED','ABANDONED') THEN ? ELSE completed_at END WHERE id=?", status.name(), status.name(), System.currentTimeMillis(), pj.getId()); }
        catch (SQLException ignored) {}
    }
    private void markPackages(int playerJobId, String status) {
        try { plugin.getDatabase().executeUpdate("UPDATE transport_packages SET status=? WHERE player_job_id=? AND status='ACTIVE'", status, playerJobId); } catch (SQLException ignored) {}
    }
    private void markPackage(UUID packageUuid, String status) {
        try { plugin.getDatabase().executeUpdate("UPDATE transport_packages SET status=? WHERE package_uuid=?", status, packageUuid.toString()); } catch (SQLException ignored) {}
    }
    private String normalizeItem(String raw) {
        Material m = parseItem(raw);
        return m == null ? null : m.getKey().asString();
    }
}
