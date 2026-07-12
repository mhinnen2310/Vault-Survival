package com.vaultsurvival.plugin.regions;

import java.util.EnumMap;
import java.util.Map;

/** Canonical list used by the shared selector/visualizer regression test. */
public final class RegionWorkflowCoverage {
    public enum Workflow { DISTRICT_CLAIM, MARKET_ZONE, STATION_PLATFORM, STATION_ARRIVAL, MINT, TOWN_HALL, TOWN_CLERK, JAIL, POLICE_STATION, TREASURY, AUCTION_HALL, VAULT_ZONE, REPAIR_ZONE, PROJECT_SITE, BLACK_MARKET, TRAIN_INTERIOR, FARM_ZONE }
    private static final Map<Workflow,RegionData.RegionType> TYPES;
    static {var types=new EnumMap<Workflow,RegionData.RegionType>(Workflow.class);types.put(Workflow.DISTRICT_CLAIM,RegionData.RegionType.DISTRICT);types.put(Workflow.MARKET_ZONE,RegionData.RegionType.DISTRICT_MARKET);types.put(Workflow.STATION_PLATFORM,RegionData.RegionType.STATION_PLATFORM);types.put(Workflow.STATION_ARRIVAL,RegionData.RegionType.STATION_ARRIVAL);types.put(Workflow.MINT,RegionData.RegionType.MINT);types.put(Workflow.TOWN_HALL,RegionData.RegionType.TOWN_HALL);types.put(Workflow.TOWN_CLERK,RegionData.RegionType.TOWN_HALL);types.put(Workflow.JAIL,RegionData.RegionType.JAIL);types.put(Workflow.POLICE_STATION,RegionData.RegionType.POLICE_STATION);types.put(Workflow.TREASURY,RegionData.RegionType.TREASURY);types.put(Workflow.AUCTION_HALL,RegionData.RegionType.AUCTION_HALL);types.put(Workflow.VAULT_ZONE,RegionData.RegionType.VAULT_ZONE);types.put(Workflow.REPAIR_ZONE,RegionData.RegionType.REPAIR_ZONE);types.put(Workflow.PROJECT_SITE,RegionData.RegionType.PROJECT_REGION);types.put(Workflow.BLACK_MARKET,RegionData.RegionType.BLACK_MARKET);types.put(Workflow.TRAIN_INTERIOR,RegionData.RegionType.TRAIN_INTERIOR);types.put(Workflow.FARM_ZONE,RegionData.RegionType.FARM_ZONE);TYPES=Map.copyOf(types);}
    private RegionWorkflowCoverage(){}
    public static Map<Workflow,RegionData.RegionType> all(){return TYPES;}
    public static RegionData.RegionType type(Workflow workflow){return TYPES.get(workflow);}
}
