package com.vaultsurvival.plugin.area;

import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.regions.RegionData;

import java.util.List;
import java.util.Map;

public record CurrentAreaContext(
    AreaType areaType,
    String areaName,
    DistrictData.District district,
    String playerStatus,
    List<RegionData.Region> activeRegions,
    Map<RegionData.RuleFlag, Boolean> activeFlags,
    String lawSummary,
    String riskSummary,
    String marketSummary,
    String jobSummary,
    String stationSummary
) {
    public enum AreaType {
        OUTLANDS,
        SPAWN_CITY,
        AUCTION_HALL,
        MINT,
        DISTRICT,
        MARKET_ZONE,
        STATION,
        OTHER_REGION
    }

    public boolean hasDistrict() {
        return district != null;
    }
}
