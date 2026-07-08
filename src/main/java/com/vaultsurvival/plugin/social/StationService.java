package com.vaultsurvival.plugin.social;

import java.util.List;

public interface StationService {
    StationData.Station createStation(String name, org.bukkit.Location loc, StationData.RouteType type, long cost, java.util.UUID owner);
    boolean removeStation(int id);
    boolean travel(int fromStationId, int toStationId, java.util.UUID playerUuid);
    List<StationData.Station> getAllStations();
    StationData.Station getStation(int id);
    void loadAll();
}
