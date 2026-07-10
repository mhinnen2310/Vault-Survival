package com.vaultsurvival.plugin.rail;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.districts.DistrictData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RailServiceImpl implements RailService {

    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;
    private final ConcurrentHashMap<Integer, RailData.Station> stations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, RailData.Route> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RailJourneyData.Journey> journeys = new ConcurrentHashMap<>();

    // Configurable journey timing (ticks)
    private final int boardingWindowTicks;
    private final int minBoardingTicks;
    private final double trainCarYOffset;

    public RailServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();

        this.boardingWindowTicks = plugin.getConfigManager().getConfig().getInt("rail.journey.boardingWindowTicks", 200);
        this.minBoardingTicks = plugin.getConfigManager().getConfig().getInt("rail.journey.minBoardingTicks", 60);
        this.trainCarYOffset = plugin.getConfigManager().getConfig().getDouble("rail.journey.trainCarYOffset", 5.0);
    }

    // ========================================================================
    // Station Application
    // ========================================================================

    /**
     * Create a new station application. Platform and arrival are set in subsequent steps.
     * Creates a PENDING station with staging coordinates that are replaced during setup.
     */
    public RailData.Station createApplication(Player requester, String name) {
        DistrictService districtService;
        try {
            districtService = plugin.getServiceRegistry().get(DistrictService.class);
        } catch (Exception e) {
            requester.sendMessage(fmt.error("District service is not available."));
            return null;
        }

        DistrictData.District district = districtService.getPlayerDistrict(requester.getUniqueId());
        if (district == null || district.getStatus() != DistrictData.DistrictStatus.ACTIVE) {
            requester.sendMessage(fmt.error("You must be in an active district to request a station."));
            return null;
        }

        if (!districtService.canRequestStation(requester.getUniqueId(), district)) {
            requester.sendMessage(fmt.error("Only MAYOR, CO_MAYOR, or DIPLOMAT can request a station."));
            return null;
        }

        // Check if district already has a station
        var existing = getStationByDistrict(district.getId());
        if (existing != null && existing.getStatus() != RailData.StationStatus.DENIED) {
            requester.sendMessage(fmt.error("Your district already has a station (#" + existing.getId() +
                " - " + existing.getStatus().name() + ")."));
            return null;
        }

        // Check application fee
        long appFee = plugin.getConfigManager().getConfig().getLong("rail.applicationFee", 10000);
        long treasuryBalance = 0;
        try {
            treasuryBalance = getDistrictTreasury(district.getId());
        } catch (Exception ignored) {}
        if (treasuryBalance < appFee) {
            requester.sendMessage(fmt.error("District treasury needs &6" + fmt.formatMoney(appFee,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural()) +
                " &cfor the application fee. Current balance: &6" +
                fmt.formatMoney(treasuryBalance,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            return null;
        }

        // Deduct application fee from district treasury
        if (appFee > 0 && districtService != null) {
            deductFromTreasury(district.getId(), appFee);
        }

        // Create a PENDING station with staging coordinates.
        Location loc = requester.getLocation();
        long now = System.currentTimeMillis();
        long defaultPrice = plugin.getConfigManager().getConfig().getLong("rail.defaultTicketPrice", 100);
        long defaultUpkeep = plugin.getConfigManager().getConfig().getLong("rail.defaultUpkeepCost", 5000);
        int defaultTax = plugin.getConfigManager().getConfig().getInt("rail.defaultKingdomTaxPercent", 10);

        String sql = "INSERT INTO rail_stations (district_id, requester_uuid, name, world_name, " +
                     "plat_min_x, plat_min_y, plat_min_z, plat_max_x, plat_max_y, plat_max_z, " +
                     "arr_x, arr_y, arr_z, arr_yaw, arr_pitch, " +
                     "ticket_price, upkeep_cost, kingdom_tax_percent, status, total_revenue, created_at) " +
                     "VALUES (?, ?, ?, ?, 0,0,0,0,0,0, ?,?,?,?,?, ?, ?, ?, 'PENDING', 0, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, district.getId());
            ps.setString(2, requester.getUniqueId().toString());
            ps.setString(3, name);
            ps.setString(4, loc.getWorld().getName());
            ps.setDouble(5, loc.getX()); ps.setDouble(6, loc.getY()); ps.setDouble(7, loc.getZ());
            ps.setFloat(8, loc.getYaw()); ps.setFloat(9, loc.getPitch());
            ps.setLong(10, defaultPrice);
            ps.setLong(11, defaultUpkeep);
            ps.setInt(12, defaultTax);
            ps.setLong(13, now);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                RailData.Station station = new RailData.Station(
                    id, district.getId(), requester.getUniqueId(), name,
                    loc.getWorld().getName(), 0, 0, 0, 0, 0, 0,
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                    defaultPrice, defaultUpkeep, defaultTax,
                    RailData.StationStatus.PENDING, 0, now);
                stations.put(id, station);

                audit.log(requester.getUniqueId(), requester.getName(), "RAIL_STATION_REQUEST",
                    "STATION", String.valueOf(id),
                    "name=" + name + " district=" + district.getId() + " fee=" + appFee);

                requester.sendMessage(fmt.success("Station application created! ID: #" + id));
                requester.sendMessage(fmt.info("Fee paid: &6" + fmt.formatMoney(appFee,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural())));
                requester.sendMessage(fmt.info("Use &e/district station setplatform " + id +
                    " &7to set the platform area."));
                requester.sendMessage(fmt.info("Use &e/district station setarrival " + id +
                    " &7to set the arrival point."));

                return station;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create station application", e);
            requester.sendMessage(fmt.error("Failed to create application."));
        }
        return null;
    }

    @Override
    public RailData.Station requestStation(Player requester, String name) {
        return createApplication(requester, name);
    }

    /**
     * Set the platform region for a PENDING station using VWE selection or player location.
     */
    public boolean setPlatform(int stationId, Player player, int radius) {
        RailData.Station station = stations.get(stationId);
        if (station == null || !station.getRequesterUuid().equals(player.getUniqueId())) {
            player.sendMessage(fmt.error("Station not found or not yours."));
            return false;
        }
        if (station.getStatus() != RailData.StationStatus.PENDING) {
            player.sendMessage(fmt.error("Station is not in pending state."));
            return false;
        }

        Location loc = player.getLocation();
        int r = Math.max(radius, plugin.getConfigManager().getConfig().getInt("rail.minPlatformRadius", 3));

        int minX = loc.getBlockX() - r, minY = loc.getBlockY() - 1, minZ = loc.getBlockZ() - r;
        int maxX = loc.getBlockX() + r, maxY = loc.getBlockY() + 3, maxZ = loc.getBlockZ() + r;

        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET plat_min_x=?,plat_min_y=?,plat_min_z=?," +
                "plat_max_x=?,plat_max_y=?,plat_max_z=? WHERE id=?",
                minX, minY, minZ, maxX, maxY, maxZ, stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set platform", e);
            return false;
        }

        player.sendMessage(fmt.success("Platform set: &e" + (r*2) + "x" + (r*2) +
            " &aarea centered at your location."));
        return true;
    }

    /** Set a pending station platform from a validated whole-chunk district selection. */
    public boolean setPlatformChunks(int stationId, Player player, DistrictData.ChunkClaim claim) {
        RailData.Station station = stations.get(stationId);
        if (station == null || !station.getRequesterUuid().equals(player.getUniqueId())
            || station.getStatus() != RailData.StationStatus.PENDING || claim == null
            || !station.getWorldName().equals(claim.worldName())) return false;
        var world = player.getWorld();
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET plat_min_x=?,plat_min_y=?,plat_min_z=?,plat_max_x=?,plat_max_y=?,plat_max_z=? WHERE id=?",
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight(), claim.maxBlockZ(), stationId);
            RailData.Station updated = new RailData.Station(station.getId(), station.getDistrictId(), station.getRequesterUuid(), station.getName(), station.getWorldName(),
                claim.minBlockX(), world.getMinHeight(), claim.minBlockZ(), claim.maxBlockX(), world.getMaxHeight(), claim.maxBlockZ(),
                station.getArrX(), station.getArrY(), station.getArrZ(), station.getArrYaw(), station.getArrPitch(), station.getTicketPrice(),
                station.getUpkeepCost(), station.getKingdomTaxPercent(), station.getStatus(), station.getTotalRevenue(), station.getCreatedAt());
            stations.put(stationId, updated);
            player.sendMessage(fmt.success("Station platform set to &e" + claim.chunkCount() + " chunks&a."));
            audit.log(player.getUniqueId(), player.getName(), "RAIL_PLATFORM_CHUNKS_SET", "STATION", String.valueOf(stationId), "chunks=" + claim.chunkCount());
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set chunk station platform", e);
            return false;
        }
    }

    @Override
    public boolean setPlatform(int stationId, Player player) {
        return setPlatform(stationId, player, 5);
    }

    /**
     * Set the arrival point for a PENDING station.
     */
    public boolean setArrival(int stationId, Player player, Location arrivalLoc) {
        RailData.Station station = stations.get(stationId);
        if (station == null || !station.getRequesterUuid().equals(player.getUniqueId())) {
            player.sendMessage(fmt.error("Station not found or not yours."));
            return false;
        }
        if (station.getStatus() != RailData.StationStatus.PENDING) {
            player.sendMessage(fmt.error("Station is not in pending state."));
            return false;
        }

        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET arr_x=?,arr_y=?,arr_z=?,arr_yaw=?,arr_pitch=? WHERE id=?",
                arrivalLoc.getX(), arrivalLoc.getY(), arrivalLoc.getZ(),
                arrivalLoc.getYaw(), arrivalLoc.getPitch(), stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set arrival", e);
            return false;
        }

        player.sendMessage(fmt.success("Arrival point set at your location."));
        return true;
    }

    @Override
    public boolean setArrival(int stationId, Player player) {
        return setArrival(stationId, player, player.getLocation());
    }

    @Override
    public boolean setTicketPrice(int stationId, Player player, long price) {
        RailData.Station station = stations.get(stationId);
        if (station == null || !station.getRequesterUuid().equals(player.getUniqueId())) {
            player.sendMessage(fmt.error("Station not found or not yours to manage."));
            return false;
        }
        if (price < 0) {
            player.sendMessage(fmt.error("Ticket price cannot be negative."));
            return false;
        }
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET ticket_price = ? WHERE id = ?", price, stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set ticket price", e);
            return false;
        }
        player.sendMessage(fmt.success("Ticket price set to &6" + fmt.formatMoney(price,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        return true;
    }

    @Override
    public boolean setUpkeep(int stationId, long upkeep) {
        RailData.Station station = stations.get(stationId);
        if (station == null) return false;
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET upkeep_cost = ? WHERE id = ?", upkeep, stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to set upkeep", e);
            return false;
        }
        return true;
    }

    @Override
    public RailData.Station getStationStatus(Player player) {
        DistrictService districtService;
        try {
            districtService = plugin.getServiceRegistry().get(DistrictService.class);
        } catch (Exception e) {
            player.sendMessage(fmt.error("District service unavailable."));
            return null;
        }
        DistrictData.District district = districtService.getPlayerDistrict(player.getUniqueId());
        if (district == null) {
            player.sendMessage(fmt.info("You are not in a district."));
            return null;
        }

        RailData.Station station = getStationByDistrict(district.getId());
        if (station == null) {
            player.sendMessage(fmt.info("Your district has no train station application."));
            if (districtService.canRequestStation(player.getUniqueId(), district)) {
                player.sendMessage(fmt.info("Request one with: &e/district station request <name>"));
            }
            return null;
        }

        player.sendMessage(fmt.header("District Station: " + station.getName()));
        String statusColor = switch (station.getStatus()) {
            case ACTIVE -> "&a";
            case PENDING -> "&e";
            case SUSPENDED -> "&c";
            case DENIED -> "&8";
        };
        player.sendMessage(fmt.info("Status: " + statusColor + station.getStatus().name()));
        player.sendMessage(fmt.info("Ticket Price: &6" + fmt.formatMoney(station.getTicketPrice(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        player.sendMessage(fmt.info("Upkeep: &6" + fmt.formatMoney(station.getUpkeepCost(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        player.sendMessage(fmt.info("Total Revenue: &6" + fmt.formatMoney(station.getTotalRevenue(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        return station;
    }

    // ========================================================================
    // Admin Actions
    // ========================================================================

    @Override
    public boolean approveStation(int stationId, UUID adminUuid) {
        RailData.Station station = stations.get(stationId);
        if (station == null || station.getStatus() != RailData.StationStatus.PENDING) return false;

        station.setStatus(RailData.StationStatus.ACTIVE);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET status = 'ACTIVE' WHERE id = ?", stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to approve station", e);
            return false;
        }

        audit.log(adminUuid, "ADMIN", "RAIL_STATION_APPROVE", "STATION",
            String.valueOf(stationId), "name=" + station.getName());

        // Notify requester if online
        Player requester = Bukkit.getPlayer(station.getRequesterUuid());
        if (requester != null) {
            requester.sendMessage(fmt.success("Your station '" + station.getName() +
                "' has been approved!"));
        }
        return true;
    }

    @Override
    public boolean denyStation(int stationId, UUID adminUuid, String reason) {
        RailData.Station station = stations.get(stationId);
        if (station == null || station.getStatus() != RailData.StationStatus.PENDING) return false;

        station.setStatus(RailData.StationStatus.DENIED);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET status = 'DENIED' WHERE id = ?", stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to deny station", e);
            return false;
        }

        audit.log(adminUuid, "ADMIN", "RAIL_STATION_DENY", "STATION",
            String.valueOf(stationId), "reason=" + reason);

        Player requester = Bukkit.getPlayer(station.getRequesterUuid());
        if (requester != null) {
            requester.sendMessage(fmt.error("Your station '" + station.getName() +
                "' was denied: " + reason));
        }
        return true;
    }

    @Override
    public boolean suspendStation(int stationId, UUID adminUuid) {
        RailData.Station station = stations.get(stationId);
        if (station == null || station.getStatus() != RailData.StationStatus.ACTIVE) return false;

        station.setStatus(RailData.StationStatus.SUSPENDED);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET status = 'SUSPENDED' WHERE id = ?", stationId);
            // Suspend all routes from/to this station
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_routes SET status = 'SUSPENDED' WHERE from_station_id = ? OR to_station_id = ?",
                stationId, stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to suspend station", e);
            return false;
        }                for (RailData.Route r : routes.values()) {
                    if (r.getFromStationId() == stationId || r.getToStationId() == stationId) {
                        r.setStatus(RailData.RouteStatus.SUSPENDED);
                    }
                }

        audit.log(adminUuid, "ADMIN", "RAIL_STATION_SUSPEND", "STATION", String.valueOf(stationId), "");
        return true;
    }

    @Override
    public boolean unsuspendStation(int stationId, UUID adminUuid) {
        RailData.Station station = stations.get(stationId);
        if (station == null || station.getStatus() != RailData.StationStatus.SUSPENDED) return false;

        station.setStatus(RailData.StationStatus.ACTIVE);
        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_stations SET status = 'ACTIVE' WHERE id = ?", stationId);
            plugin.getDatabase().executeUpdate(
                "UPDATE rail_routes SET status = 'ACTIVE' WHERE from_station_id = ? OR to_station_id = ?",
                stationId, stationId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to unsuspend station", e);
            return false;
        }                for (RailData.Route r : routes.values()) {
                    if (r.getFromStationId() == stationId || r.getToStationId() == stationId) {
                        r.setStatus(RailData.RouteStatus.ACTIVE);
                    }
                }

        audit.log(adminUuid, "ADMIN", "RAIL_STATION_UNSUSPEND", "STATION", String.valueOf(stationId), "");
        return true;
    }

    // ========================================================================
    // Routes
    // ========================================================================

    @Override
    public RailData.Route createRoute(int fromStationId, int toStationId, long ticketPrice,
                                       int kingdomTaxPercent, int travelTimeTicks) {
        RailData.Station from = stations.get(fromStationId);
        RailData.Station to = stations.get(toStationId);
        if (from == null || to == null) return null;
        if (from.getStatus() != RailData.StationStatus.ACTIVE ||
            to.getStatus() != RailData.StationStatus.ACTIVE) return null;

        long now = System.currentTimeMillis();
        String sql = "INSERT INTO rail_routes (from_station_id, to_station_id, ticket_price, " +
                     "kingdom_tax_percent, travel_time_ticks, status, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, fromStationId);
            ps.setInt(2, toStationId);
            ps.setLong(3, ticketPrice);
            ps.setInt(4, kingdomTaxPercent);
            ps.setInt(5, travelTimeTicks);
            ps.setLong(6, now);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                RailData.Route route = new RailData.Route(id, fromStationId, toStationId,
                    ticketPrice, kingdomTaxPercent, travelTimeTicks,
                    RailData.RouteStatus.ACTIVE, now);
                routes.put(id, route);
                return route;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to create route", e);
        }
        return null;
    }

    // ========================================================================
    // Travel
    // ========================================================================

    @Override
    public boolean buyTicket(Player player, int routeId) {
        return startJourney(player, routeId);
    }

    // ========================================================================
    // Journey System
    // ========================================================================

    @Override
    public boolean startJourney(Player player, int routeId) {
        RailData.Route route = routes.get(routeId);
        if (route == null || route.getStatus() != RailData.RouteStatus.ACTIVE) {
            player.sendMessage(fmt.error("This route is not available."));
            return false;
        }

        RailData.Station fromStation = stations.get(route.getFromStationId());
        RailData.Station toStation = stations.get(route.getToStationId());
        if (fromStation == null || toStation == null ||
            fromStation.getStatus() != RailData.StationStatus.ACTIVE ||
            toStation.getStatus() != RailData.StationStatus.ACTIVE) {
            player.sendMessage(fmt.error("One of the stations is not active."));
            return false;
        }

        // Check player is on the correct departure platform
        Location loc = player.getLocation();
        if (!fromStation.getWorldName().equals(loc.getWorld().getName()) ||
            !fromStation.isInsidePlatform(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            player.sendMessage(fmt.error("You must be on the platform at " + fromStation.getName() + "."));
            return false;
        }

        // Verify this route actually departs from the station whose platform the player is on
        var playerStation = stations.values().stream()
            .filter(s -> s.getWorldName().equals(loc.getWorld().getName()) &&
                         s.isInsidePlatform(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))
            .findFirst();
        if (playerStation.isEmpty() || playerStation.get().getId() != route.getFromStationId()) {
            player.sendMessage(fmt.error("This route departs from " + fromStation.getName() +
                ", but you are at " + (playerStation.map(RailData.Station::getName).orElse("an unknown location")) + "."));
            return false;
        }

        // Check no existing active journey
        if (journeys.containsKey(player.getUniqueId())) {
            var existing = journeys.get(player.getUniqueId());
            if (existing != null && !existing.isEnded()) {
                player.sendMessage(fmt.error("You already have an active journey. Use &e/station journey&7 to check."));
                return false;
            }
        }

        // Blocked checks
        if (!canPlayerTravel(player)) return false;

        long price = route.getTicketPrice();
        if (price > 0) {
            long playerCash = currency.getPlayerCashTotal(player.getUniqueId());
            if (playerCash < price) {
                player.sendMessage(fmt.error("You need &6" + fmt.formatMoney(price,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()) +
                    " &cfor a ticket."));
                return false;
            }

            var withdrawn = currency.withdrawCash(player, price);
            long paid = withdrawn.stream().mapToLong(currency::getCashAmount).sum();
            if (paid < price) {
                currency.depositCash(player, withdrawn);
                player.sendMessage(fmt.error("Payment failed. Cash returned."));
                return false;
            }

            // Process payment
            try (Connection conn = plugin.getDatabase().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    for (var cashItem : withdrawn) {
                        UUID cashUuid = currency.getCashUuid(cashItem);
                        plugin.getDatabase().executeUpdate(
                            "UPDATE cash_items SET state = 'SPENT' WHERE cash_uuid = ?",
                            cashUuid.toString());
                    }

                    long kingdomCut = (price * route.getKingdomTaxPercent()) / 100;
                    long districtRevenue = price - kingdomCut;

                    if (districtRevenue > 0) {
                        var districtCash = currency.mintCash(districtRevenue, player.getUniqueId(), null);
                        UUID dcUuid = currency.getCashUuid(districtCash);
                        plugin.getDatabase().executeUpdate(
                            "UPDATE cash_items SET state = 'IN_DISTRICT_TREASURY', " +
                            "location_type = 'TREASURY', location_id = ?, owner_uuid = NULL " +
                            "WHERE cash_uuid = ?",
                            String.valueOf(fromStation.getDistrictId()), dcUuid.toString());
                    }

                    if (kingdomCut > 0) {
                        var kingdomCash = currency.mintCash(kingdomCut, player.getUniqueId(), null);
                        UUID kcUuid = currency.getCashUuid(kingdomCash);
                        plugin.getDatabase().executeUpdate(
                            "UPDATE cash_items SET state = 'IN_DISTRICT_TREASURY', " +
                            "location_type = 'TREASURY', location_id = 'SPAWN_CITY', owner_uuid = NULL " +
                            "WHERE cash_uuid = ?",
                            kcUuid.toString());
                    }

                    plugin.getDatabase().executeUpdate(
                        "UPDATE rail_stations SET total_revenue = total_revenue + ? WHERE id = ?",
                        districtRevenue, fromStation.getId());

                    conn.commit();

                    audit.log(player.getUniqueId(), player.getName(), "RAIL_TICKET_BUY",
                        "ROUTE", String.valueOf(routeId),
                        "from=" + fromStation.getName() + " to=" + toStation.getName() +
                        " price=" + price);

                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to process ticket", e);
                currency.depositCash(player, withdrawn);
                return false;
            }
        }

        // Create journey and start boarding
        RailJourneyData.Journey journey = new RailJourneyData.Journey(
            player.getUniqueId(), player.getName(), routeId,
            fromStation.getId(), toStation.getId(),
            fromStation.getName(), toStation.getName(),
            price, route.getTravelTimeTicks());

        journeys.put(player.getUniqueId(), journey);

        // Schedule boarding announcement after a short delay
        int boardingDelay = Math.max(minBoardingTicks, boardingWindowTicks / 4);
        int boardingTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            RailJourneyData.Journey j = journeys.get(player.getUniqueId());
            if (j == null || j.isEnded()) return;

            // Open boarding window
            j.setState(RailJourneyData.JourneyState.BOARDING);
            player.sendMessage(fmt.info("&eBoarding now open for " +
                fromStation.getName() + " &8→ &f" + toStation.getName() + "&e!"));
            player.sendMessage(fmt.info("Use &e/station board " + routeId +
                " &7to board the train. Boarding closes in " +
                ((boardingWindowTicks - boardingDelay) / 20) + "s."));
            player.sendActionBar(Component.text(
                fmt.colorize("&6🚂 Boarding open! Use /station board " + routeId)));

            // Schedule boarding close → auto-departure
            int closeTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                RailJourneyData.Journey j2 = journeys.get(player.getUniqueId());
                if (j2 == null) return;

                if (j2.getState() == RailJourneyData.JourneyState.BOARDING ||
                    j2.getState() == RailJourneyData.JourneyState.TICKETED) {
                    // Boarding is always an explicit player action. Never move a player into a
                    // journey they did not confirm, even when the timer elapses.
                    journeys.remove(player.getUniqueId());
                    j2.setState(RailJourneyData.JourneyState.CANCELLED);
                    logJourneyEvent(j2, "BOARDING_EXPIRED");
                    player.sendMessage(fmt.warn("Boarding closed before you boarded. Your journey was cancelled."));
                }
            }, boardingWindowTicks - boardingDelay);
            j.setBoardingTaskId(closeTaskId);

        }, boardingDelay);
        journey.setBoardingTaskId(boardingTaskId);

        player.sendMessage(fmt.success("Ticket purchased for &6" + fmt.formatMoney(price,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        player.sendMessage(fmt.info("Boarding opens in &e" + (boardingDelay / 20) +
            "s&7. Stand by on the platform!"));

        // Log journey start to DB
        logJourneyEvent(journey, "TICKET_PURCHASED");

        return true;
    }

    @Override
    public boolean boardTrain(Player player, int routeId) {
        RailJourneyData.Journey journey = journeys.get(player.getUniqueId());
        if (journey == null) {
            player.sendMessage(fmt.error("You don't have an active journey. Buy a ticket first."));
            return false;
        }

        if (!journey.canBoard()) {
            player.sendMessage(fmt.error("Boarding is not available (state: " +
                journey.getState().name() + ")."));
            return false;
        }

        if (journey.getRouteId() != routeId) {
            player.sendMessage(fmt.error("Your journey is for route #" + journey.getRouteId() +
                ", not #" + routeId + "."));
            return false;
        }

        // Re-verify player is still on the departure platform
        RailData.Station fromStation = stations.get(journey.getFromStationId());
        if (fromStation == null) {
            player.sendMessage(fmt.error("Departure station not found."));
            cancelJourneyInternal(player, RailJourneyData.JourneyState.FAILED);
            return false;
        }

        Location loc = player.getLocation();
        if (!fromStation.getWorldName().equals(loc.getWorld().getName()) ||
            !fromStation.isInsidePlatform(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            player.sendMessage(fmt.error("You must be on the platform at " + fromStation.getName() + " to board."));
            return false;
        }

        if (!canPlayerTravel(player)) return false;

        doBoardTrain(player, journey);
        return true;
    }

    private void doBoardTrain(Player player, RailJourneyData.Journey journey) {
        // Cancel any pending boarding task
        if (journey.getBoardingTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getBoardingTaskId());
            journey.setBoardingTaskId(-1);
        }

        journey.setState(RailJourneyData.JourneyState.BOARDED);

        // Save player's current location
        journey.setPreviousLocation(player.getLocation().clone());

        // Teleport to train car (above the platform center)
        RailData.Station fromStation = stations.get(journey.getFromStationId());
        if (fromStation != null) {
            var world = Bukkit.getWorld(fromStation.getWorldName());
            if (world != null) {
                double carX = (fromStation.getPlatMinX() + fromStation.getPlatMaxX()) / 2.0 + 0.5;
                double carY = Math.max(fromStation.getPlatMinY(), fromStation.getPlatMaxY()) + trainCarYOffset;
                double carZ = (fromStation.getPlatMinZ() + fromStation.getPlatMaxZ()) / 2.0 + 0.5;
                Location carLoc = new Location(world, carX, carY, carZ);
                journey.setTrainCarLocation(carLoc);
                player.teleport(carLoc);
            }
        }

        player.sendMessage(fmt.success("&6🚂 All aboard! &aYou are now on the train to &e" +
            journey.getToStationName() + "&a."));
        player.sendTitle(
            fmt.colorize("&6🚂 All Aboard!"),
            fmt.colorize("&eDestination: " + journey.getToStationName()),
            10, 60, 10);

        logJourneyEvent(journey, "BOARDED");

        // Start journey countdown after a short delay
        int departDelay = 40; // 2 seconds
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            RailJourneyData.Journey j = journeys.get(player.getUniqueId());
            if (j == null || j.getState() != RailJourneyData.JourneyState.BOARDED) return;

            j.setState(RailJourneyData.JourneyState.DEPARTED);
            player.sendMessage(fmt.info("&6🚂 Train departing! &e" +
                j.getFromStationName() + " &8→ &f" + j.getToStationName()));

            logJourneyEvent(j, "DEPARTED");

            // Start transit countdown
            j.setState(RailJourneyData.JourneyState.IN_TRANSIT);
            startTransitCountdown(player, j);
        }, departDelay);
    }

    private void startTransitCountdown(Player player, RailJourneyData.Journey journey) {
        int totalTicks = journey.getTotalTravelTicks();
        journey.setTicksRemaining(totalTicks);

        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            int tick = 0;

            @Override
            public void run() {
                RailJourneyData.Journey j = journeys.get(player.getUniqueId());
                if (j == null || j.getState() != RailJourneyData.JourneyState.IN_TRANSIT) {
                    Bukkit.getScheduler().cancelTask(journey.getJourneyTaskId());
                    return;
                }

                tick++;
                int remaining = totalTicks - tick;
                j.setTicksRemaining(Math.max(0, remaining));

                // Show actionbar countdown
                if (tick % 20 == 0) { // Every second
                    String timeStr = j.getTimeRemaining();
                    player.sendActionBar(Component.text(
                        fmt.colorize("&6🚂 &e" + j.getFromStationName() +
                        " &8→ &f" + j.getToStationName() +
                        " &7| &a" + timeStr)));
                }

                // Title countdown at milestones
                if (remaining <= 200 && remaining > 190) { // ~10 seconds
                    player.sendTitle(
                        fmt.colorize("&e10 seconds"),
                        fmt.colorize("&7Arriving at " + j.getToStationName()),
                        0, 40, 10);
                } else if (remaining <= 100 && remaining > 90) { // ~5 seconds
                    player.sendTitle(
                        fmt.colorize("&e5 seconds"),
                        fmt.colorize("&7Get ready to disembark..."),
                        0, 40, 10);
                } else if (remaining <= 60 && remaining > 50) { // ~3 seconds
                    player.sendTitle(
                        fmt.colorize("&e3"),
                        fmt.colorize("&7..."),
                        0, 16, 0);
                } else if (remaining <= 40 && remaining > 30) { // ~2 seconds
                    player.sendTitle(
                        fmt.colorize("&62"),
                        fmt.colorize("&7.."),
                        0, 16, 0);
                } else if (remaining <= 20 && remaining > 10) { // ~1 second
                    player.sendTitle(
                        fmt.colorize("&c1"),
                        fmt.colorize("&7."),
                        0, 16, 0);
                }

                // Arrival
                if (remaining <= 0) {
                    arriveAtDestination(player, j);
                }
            }
        }, 0L, 1L); // Run every tick

        journey.setJourneyTaskId(taskId);
    }

    private void arriveAtDestination(Player player, RailJourneyData.Journey journey) {
        // Cancel the countdown task
        if (journey.getJourneyTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getJourneyTaskId());
        }

        journey.setState(RailJourneyData.JourneyState.ARRIVED);

        RailData.Station toStation = stations.get(journey.getToStationId());
        if (toStation != null) {
            Location arrival = toStation.getArrivalLocation();
            if (arrival != null) {
                player.teleport(arrival);
            } else {
                // Fallback: teleport back to previous location
                if (journey.getPreviousLocation() != null) {
                    player.teleport(journey.getPreviousLocation());
                }
                player.sendMessage(fmt.warn("Arrival point not found. Returning to departure area."));
            }
        }

        player.sendTitle(
            fmt.colorize("&a🚂 Arrived!"),
            fmt.colorize("&fWelcome to " + journey.getToStationName()),
            10, 60, 20);

        player.sendMessage(fmt.success("&a🚂 Arrived at &e" + journey.getToStationName() + "&a!"));

        logJourneyEvent(journey, "ARRIVED");

        // Clean up after a delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            journeys.remove(player.getUniqueId());
        }, 100L); // 5 seconds
    }

    @Override
    public boolean cancelJourney(Player player) {
        return cancelJourneyInternal(player, RailJourneyData.JourneyState.CANCELLED);
    }

    private boolean cancelJourneyInternal(Player player, RailJourneyData.JourneyState endState) {
        RailJourneyData.Journey journey = journeys.get(player.getUniqueId());
        if (journey == null || journey.isEnded()) return false;

        // Cancel any running tasks
        if (journey.getBoardingTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getBoardingTaskId());
        }
        if (journey.getJourneyTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getJourneyTaskId());
        }

        journey.setState(endState);

        // Teleport back if in train car
        if (journey.getPreviousLocation() != null && journey.isActive()) {
            player.teleport(journey.getPreviousLocation());
        }

        player.sendMessage(fmt.info("Your journey has been " +
            (endState == RailJourneyData.JourneyState.CANCELLED ? "cancelled." : "ended.")));

        logJourneyEvent(journey, endState.name());

        journeys.remove(player.getUniqueId());
        return true;
    }

    @Override
    public RailJourneyData.Journey getActiveJourney(UUID playerUuid) {
        RailJourneyData.Journey j = journeys.get(playerUuid);
        if (j != null && !j.isEnded()) return j;
        return null;
    }

    @Override
    public boolean isPlayerInTransit(UUID playerUuid) {
        RailJourneyData.Journey j = journeys.get(playerUuid);
        return j != null && j.isActive();
    }

    @Override
    public void handlePlayerRejoin(Player player) {
        RailJourneyData.Journey journey = journeys.get(player.getUniqueId());
        if (journey == null) return;

        if (journey.isEnded()) {
            journeys.remove(player.getUniqueId());
            return;
        }

        // If they were in TICKETED/BOARDING state, cancel (boarding window expired)
        if (journey.getState() == RailJourneyData.JourneyState.TICKETED ||
            journey.getState() == RailJourneyData.JourneyState.BOARDING) {
            player.sendMessage(fmt.warn("Your boarding window expired while you were offline."));
            cancelJourneyInternal(player, RailJourneyData.JourneyState.CANCELLED);
            return;
        }

        // If they were IN_TRANSIT, cancel the journey (can't resume mid-transit safely)
        if (journey.isActive()) {
            // Cancel and return to departure area
            if (journey.getJourneyTaskId() >= 0) {
                Bukkit.getScheduler().cancelTask(journey.getJourneyTaskId());
            }
            if (journey.getPreviousLocation() != null) {
                player.teleport(journey.getPreviousLocation());
            }
            player.sendMessage(fmt.warn("Your train journey was cancelled because you logged out."));
            journey.setState(RailJourneyData.JourneyState.CANCELLED);
            logJourneyEvent(journey, "CANCELLED_LOGOUT");
            journeys.remove(player.getUniqueId());
        }
    }

    @Override
    public void handlePlayerQuit(Player player) {
        RailJourneyData.Journey journey = journeys.get(player.getUniqueId());
        if (journey == null) return;

        if (journey.isEnded()) {
            journeys.remove(player.getUniqueId());
            return;
        }

        // Cancel journey on quit
        if (journey.getJourneyTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getJourneyTaskId());
        }
        if (journey.getBoardingTaskId() >= 0) {
            Bukkit.getScheduler().cancelTask(journey.getBoardingTaskId());
        }

        // Teleport back if in train car (player is quitting so this may not matter, but be safe)
        if (journey.getPreviousLocation() != null && journey.isActive()) {
            player.teleport(journey.getPreviousLocation());
        }

        journey.setState(RailJourneyData.JourneyState.CANCELLED);
        logJourneyEvent(journey, "CANCELLED_QUIT");
        // Don't remove from map yet — handle on rejoin
    }

    @Override
    public List<String> getJourneyLog(int stationId, int limit) {
        List<String> log = new ArrayList<>();
        String sql = "SELECT * FROM rail_journey_log WHERE from_station_id = ? OR to_station_id = ? " +
                     "ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, stationId);
            ps.setInt(2, stationId);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                log.add(String.format("#%d %s → %s | %s @ %d",
                    rs.getInt("id"),
                    rs.getString("from_station"),
                    rs.getString("to_station"),
                    rs.getString("event"),
                    rs.getLong("created_at")));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load journey log", e);
        }
        return log;
    }

    /**
     * Check if the player can travel (combat, breach, frozen, etc.).
     */
    private boolean canPlayerTravel(Player player) {
        // Check combat tag
        if (player.hasMetadata("combat_tagged")) {
            player.sendMessage(fmt.error("You cannot travel while in combat."));
            return false;
        }
        // Check if frozen (staff)
        if (player.hasMetadata("staffmode_frozen")) {
            player.sendMessage(fmt.error("You cannot travel while frozen."));
            return false;
        }
        return true;
    }

    /**
     * Log a journey event to the database.
     */
    private void logJourneyEvent(RailJourneyData.Journey journey, String event) {
        String sql = "INSERT INTO rail_journey_log (player_uuid, player_name, route_id, " +
                     "from_station_id, to_station_id, from_station, to_station, " +
                     "ticket_price, event, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, journey.getPlayerUuid().toString());
            ps.setString(2, journey.getPlayerName());
            ps.setInt(3, journey.getRouteId());
            ps.setInt(4, journey.getFromStationId());
            ps.setInt(5, journey.getToStationId());
            ps.setString(6, journey.getFromStationName());
            ps.setString(7, journey.getToStationName());
            ps.setLong(8, journey.getTicketPrice());
            ps.setString(9, event);
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to log journey event", e);
        }
    }

    // ========================================================================
    // Treasury Helpers
    // ========================================================================

    private long getDistrictTreasury(int districtId) {
        String sql = "SELECT IFNULL(SUM(amount), 0) FROM cash_items " +
                     "WHERE state = 'IN_DISTRICT_TREASURY' AND location_id = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(districtId));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get treasury balance", e);
        }
        return 0;
    }

    private void deductFromTreasury(int districtId, long amount) {
        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                                 "WHERE state = 'IN_DISTRICT_TREASURY' AND location_id = ? ORDER BY amount ASC";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, String.valueOf(districtId));
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        String cashUuid = rs.getString("cash_uuid");
                        long cashAmount = rs.getLong("amount");
                        if (cashAmount <= remaining) {
                            plugin.getDatabase().executeUpdate(
                                "UPDATE cash_items SET state = 'SPENT' WHERE cash_uuid = ?", cashUuid);
                            remaining -= cashAmount;
                        } else {
                            plugin.getDatabase().executeUpdate(
                                "UPDATE cash_items SET amount = ? WHERE cash_uuid = ?",
                                cashAmount - remaining, cashUuid);
                            remaining = 0;
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to deduct from treasury", e);
        }
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public List<RailData.Station> getAllStations() {
        return new ArrayList<>(stations.values());
    }

    @Override
    public List<RailData.Station> getActiveStations() {
        return stations.values().stream()
            .filter(s -> s.getStatus() == RailData.StationStatus.ACTIVE)
            .toList();
    }

    @Override
    public List<RailData.Station> getPendingStations() {
        return stations.values().stream()
            .filter(s -> s.getStatus() == RailData.StationStatus.PENDING)
            .toList();
    }

    @Override
    public List<RailData.Station> getSuspendedStations() {
        return stations.values().stream()
            .filter(s -> s.getStatus() == RailData.StationStatus.SUSPENDED)
            .toList();
    }

    @Override
    public RailData.Station getStation(int stationId) {
        return stations.get(stationId);
    }

    @Override
    public RailData.Station getStationByDistrict(int districtId) {
        return stations.values().stream()
            .filter(s -> s.getDistrictId() == districtId &&
                s.getStatus() != RailData.StationStatus.DENIED)
            .findFirst().orElse(null);
    }

    @Override
    public List<RailData.Route> getAllRoutes() {
        return new ArrayList<>(routes.values());
    }

    @Override
    public List<RailData.Route> getRoutesFrom(int stationId) {
        return routes.values().stream()
            .filter(r -> r.getFromStationId() == stationId &&
                r.getStatus() == RailData.RouteStatus.ACTIVE)
            .toList();
    }

    @Override
    public void loadAll() {
        stations.clear();
        routes.clear();

        // Load stations
        String stationSql = "SELECT * FROM rail_stations";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(stationSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RailData.Station station = new RailData.Station(
                    rs.getInt("id"),
                    rs.getInt("district_id"),
                    UUID.fromString(rs.getString("requester_uuid")),
                    rs.getString("name"),
                    rs.getString("world_name"),
                    rs.getInt("plat_min_x"), rs.getInt("plat_min_y"), rs.getInt("plat_min_z"),
                    rs.getInt("plat_max_x"), rs.getInt("plat_max_y"), rs.getInt("plat_max_z"),
                    rs.getDouble("arr_x"), rs.getDouble("arr_y"), rs.getDouble("arr_z"),
                    rs.getFloat("arr_yaw"), rs.getFloat("arr_pitch"),
                    rs.getLong("ticket_price"),
                    rs.getLong("upkeep_cost"),
                    rs.getInt("kingdom_tax_percent"),
                    RailData.StationStatus.valueOf(rs.getString("status")),
                    rs.getLong("total_revenue"),
                    rs.getLong("created_at")
                );
                stations.put(station.getId(), station);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load rail stations", e);
        }
        logger.info("Loaded " + stations.size() + " rail stations");

        // Load routes
        String routeSql = "SELECT * FROM rail_routes";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(routeSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RailData.Route route = new RailData.Route(
                    rs.getInt("id"),
                    rs.getInt("from_station_id"),
                    rs.getInt("to_station_id"),
                    rs.getLong("ticket_price"),
                    rs.getInt("kingdom_tax_percent"),
                    rs.getInt("travel_time_ticks"),
                    RailData.RouteStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at")
                );
                routes.put(route.getId(), route);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load rail routes", e);
        }
        logger.info("Loaded " + routes.size() + " rail routes");
    }
}
