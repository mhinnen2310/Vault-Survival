package com.vaultsurvival.plugin.rail;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface RailService {

    /**
     * Submit a station application for a district.
     * Only MAYOR, CO_MAYOR, or DIPLOMAT can do this.
     */
    RailData.Station requestStation(Player requester, String name);
    /** Dialog-safe application path; returns the station without command chat output. */
    RailData.Station requestStationSilently(Player requester, String name);

    /**
     * Set the platform region for a pending station.
     */
    boolean setPlatform(int stationId, Player player);

    /**
     * Set the arrival point for a pending station.
     */
    boolean setArrival(int stationId, Player player);

    /**
     * Set ticket price for a station.
     */
    boolean setTicketPrice(int stationId, Player player, long price);

    /** Dialog-safe variant that returns structured status without emitting chat output. */
    boolean setTicketPriceSilently(int stationId, Player player, long price);

    /**
     * Set upkeep cost for a station (staff only).
     */
    boolean setUpkeep(int stationId, long upkeep);

    /**
     * Get station status.
     */
    RailData.Station getStationStatus(Player player);

    /**
     * Admin: approve a pending station application.
     */
    boolean approveStation(int stationId, UUID adminUuid);

    /**
     * Admin: deny a station application.
     */
    boolean denyStation(int stationId, UUID adminUuid, String reason);

    /**
     * Admin: suspend an active station.
     */
    boolean suspendStation(int stationId, UUID adminUuid);

    /**
     * Admin: unsuspend a station.
     */
    boolean unsuspendStation(int stationId, UUID adminUuid);

    /**
     * Create a route between two active stations.
     */
    RailData.Route createRoute(int fromStationId, int toStationId, long ticketPrice,
                                int kingdomTaxPercent, int travelTimeTicks);

    /**
     * Get all stations.
     */
    List<RailData.Station> getAllStations();

    /**
     * Get active stations.
     */
    List<RailData.Station> getActiveStations();

    /**
     * Get pending applications.
     */
    List<RailData.Station> getPendingStations();

    /**
     * Get suspended stations.
     */
    List<RailData.Station> getSuspendedStations();

    /**
     * Get station by ID.
     */
    RailData.Station getStation(int stationId);

    /**
     * Get station by district ID.
     */
    RailData.Station getStationByDistrict(int districtId);

    /**
     * Get all routes.
     */
    List<RailData.Route> getAllRoutes();

    /**
     * Get routes from a station.
     */
    List<RailData.Route> getRoutesFrom(int stationId);

    /**
     * Buy a ticket. Starts the boarding process.
     * The player must then use /station board to board the train.
     */
    boolean buyTicket(Player player, int routeId);

    /**
     * Start a journey: open the boarding window after ticket purchase.
     */
    boolean startJourney(Player player, int routeId);

    /**
     * Board the train for an active journey.
     */
    boolean boardTrain(Player player, int routeId);

    /**
     * Cancel an active or pending journey.
     */
    boolean cancelJourney(Player player);

    /**
     * Get the player's active journey, if any.
     */
    RailJourneyData.Journey getActiveJourney(UUID playerUuid);

    /**
     * Check if a player is currently in transit (on a train).
     */
    boolean isPlayerInTransit(UUID playerUuid);

    /**
     * Called when a player rejoins to restore or cancel their journey.
     */
    void handlePlayerRejoin(Player player);

    /**
     * Called when a player quits to save journey state.
     */
    void handlePlayerQuit(Player player);

    /**
     * Get the journey log for a station (recent arrivals).
     */
    List<String> getJourneyLog(int stationId, int limit);

    /**
     * Load all from database.
     */
    void loadAll();
}
