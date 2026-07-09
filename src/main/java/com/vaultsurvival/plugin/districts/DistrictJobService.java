package com.vaultsurvival.plugin.districts;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public interface DistrictJobService {
    DistrictJobData.Job createJob(Player creator, DistrictData.District district, DistrictJobData.JobType type,
                                  String title, String description, long reward, long deadlineHours,
                                  String requiredItem, int requiredAmount, String origin, String destination,
                                  String checkpoint, boolean manualApproval);
    List<DistrictJobData.Job> getJobs(int districtId);
    List<DistrictJobData.Job> getActiveJobs(int districtId);
    List<DistrictJobData.Claim> getClaimsFor(Player player);
    List<DistrictJobData.Claim> getSubmittedClaims(int districtId);
    DistrictJobData.Job getJob(int id);
    DistrictJobData.Claim getClaim(int id);
    boolean acceptJob(Player player, int jobId);
    boolean deliverJob(Player player, int jobId);
    boolean submitJob(Player player, int jobId);
    boolean approveClaim(Player approver, int claimId);
    boolean denyClaim(Player approver, int claimId, String reason);
    Material parseItem(String raw);
    void loadAll();
}
