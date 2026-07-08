package com.vaultsurvival.plugin.monitor;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.breach.BreachService;
import com.vaultsurvival.plugin.crime.CrimeService;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.damage.DamageService;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.market.MarketService;
import com.vaultsurvival.plugin.npc.NpcService;
import com.vaultsurvival.plugin.repair.RepairService;
import com.vaultsurvival.plugin.social.FriendService;
import com.vaultsurvival.plugin.social.GroupService;
import com.vaultsurvival.plugin.vaults.VaultService;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service that aggregates statistics from all modules.
 */
public class AnalyticsServiceImpl implements AnalyticsService {
    private final VaultSurvivalPlugin plugin;

    public AnalyticsServiceImpl(VaultSurvivalPlugin plugin) { this.plugin = plugin; }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Server
        stats.put("server_name", plugin.getConfigManager().getServerName());
        stats.put("online_players", Bukkit.getOnlinePlayers().size());
        stats.put("max_players", Bukkit.getMaxPlayers());

        // TPS & Memory
        double[] tps = Bukkit.getTPS();
        stats.put("tps_1m", String.format("%.1f", tps[0]));
        stats.put("tps_5m", String.format("%.1f", tps[1]));
        Runtime rt = Runtime.getRuntime();
        stats.put("memory_used_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        stats.put("memory_max_mb", rt.maxMemory() / 1048576);

        // Currency
        var cs = plugin.getServiceRegistry().get(CurrencyService.class);
        stats.put("cash_in_circulation", cs != null ? getCirculation() : "N/A");

        // Vaults
        var vs = plugin.getServiceRegistry().get(VaultService.class);
        stats.put("active_vaults", vs != null ? "Available" : "N/A");

        // Breaches
        stats.put("breaches", "See /breach log");

        // Districts
        var ds = plugin.getServiceRegistry().get(DistrictService.class);
        stats.put("districts_active", ds != null ? ds.getAllDistricts().stream().filter(d -> d.getStatus().name().equals("ACTIVE")).count() : 0);
        stats.put("districts_applications", ds != null ? ds.getApplications().size() : 0);

        // Market
        var ms = plugin.getServiceRegistry().get(MarketService.class);
        stats.put("active_listings", ms != null ? ms.getActiveListings(null).size() : 0);

        // Damage
        var dmg = plugin.getServiceRegistry().get(DamageService.class);
        stats.put("pending_restores", dmg != null ? dmg.getAllDamage().stream().filter(r -> !r.isRestored()).count() : 0);

        // Crime
        var crim = plugin.getServiceRegistry().get(CrimeService.class);
        long wanted = 0;
        if (ds != null && crim != null)
            for (var d : ds.getAllDistricts()) wanted += crim.getWantedPlayers(d.getId()).size();
        stats.put("wanted_players", wanted);

        // NPCs
        var npc = plugin.getServiceRegistry().get(NpcService.class);
        stats.put("npcs", npc != null ? npc.getAllNpcs().size() : 0);

        // Repair
        var rep = plugin.getServiceRegistry().get(RepairService.class);
        stats.put("repair_states", rep != null ? rep.getAllStates().size() : 0);

        // Social
        var fr = plugin.getServiceRegistry().get(FriendService.class);
        stats.put("friends", fr != null ? "Active" : "N/A");
        var gr = plugin.getServiceRegistry().get(GroupService.class);
        stats.put("groups", gr != null ? gr.getAllGroups().size() : 0);

        stats.put("modules_loaded", plugin.getModuleManager().getModuleNames().size());
        stats.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());

        return stats;
    }

    private long getCirculation() {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items WHERE state = 'ACTIVE'";
        try (var conn = plugin.getDatabase().getConnection();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {}
        return 0;
    }
}
