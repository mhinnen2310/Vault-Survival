package com.vaultsurvival.plugin.spawnjobs;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public interface SpawnJobService {
    void loadAll();
    void seedStarterJobs();
    List<SpawnJobData.Job> getJobs();
    List<SpawnJobData.PlayerJob> getActiveJobs(Player player);
    SpawnJobData.Job getJob(int id);
    boolean accept(Player player, int jobId);
    boolean turnIn(Player player, int jobId);
    boolean abandon(Player player, int jobId);
    boolean disableJob(int jobId);
    SpawnJobData.Job createAdminJob(SpawnJobData.JobType type, String title, long reward, String item, int amount, String destination);
    Material parseItem(String raw);
}
