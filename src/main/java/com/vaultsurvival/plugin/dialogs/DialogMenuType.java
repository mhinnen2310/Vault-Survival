package com.vaultsurvival.plugin.dialogs;

import java.util.Locale;

public enum DialogMenuType {
    MAIN("main", "Vault Survival", "Choose a Vault Survival tool."),
    CURRENT_AREA("current_area", "Current Area", "Information about the district, station, and risk around you."),
    SETTINGS("settings", "Settings", "Player settings and preferences."),
    GUIDES("guides", "Guides", "Quick guides for Vault Survival systems."),
    PLAYER_JOBS("player.jobs", "Jobs", "Spawn City and district jobs, claims, delivery, and payout tools."),
    PLAYER_ORDERS("player.orders", "Orders", "Merchant buy orders, deliveries, and Auction Hall listings."),
    PLAYER_RISK("player.risk", "Risk", "Computed local risk, evidence, wanted state, and report tools."),

    DISTRICTS("district.home", "Districts", "District actions for your current role."),
    DISTRICT_DIRECTORY("district.directory", "District Directory", "Browse active districts and request membership."),
    DISTRICT_CURRENT("district.current", "Current District", "Current district status, membership, laws, market, jobs, and station context."),
    DISTRICT_LAWS("district.laws", "District Laws", "Active district law states."),
    DISTRICT_PENDING_LAWS("district.pending_laws", "Pending Laws", "Law changes queued for daily activation."),
    DISTRICT_ROLES("district.roles", "District Roles", "Role-gated law and governance controls."),
    DISTRICT_MARKET("district.market", "District Market", "Market-zone, shops, buy orders, and supply tools."),
    DISTRICT_MERCHANT("district.merchant", "District Merchant", "Merchant NPC, shops, orders, storage, and payouts."),
    DISTRICT_TREASURY("district.treasury", "District Treasury", "Physical treasury balance, deposits, withdrawals, and oversight."),
    DISTRICT_POLICE("district.police", "District Police", "Evidence, wanted, arrest, fine, and report tools."),
    DISTRICT_EVIDENCE("district.evidence", "District Evidence", "Paginated active evidence for this district."),
    DISTRICT_STATION("district.station", "District Station", "Station application, platform, arrival, and status controls."),
    DISTRICT_DIPLOMACY("district.diplomacy", "Diplomacy", "Alliances, hostility, neutral relations, and ally chat."),
    DISTRICT_JOBS("district.jobs", "District Jobs", "Job creation, claims, completion history, disputes, and escrow."),
    DISTRICT_DEVELOPMENT("district.development", "Development", "Projects, contributions, maintenance scoring, and Kingdom Support."),

    MERCHANT_HOME("merchant.home", "Merchant", "Merchant tools and order management."),
    MERCHANT_SHOPS("merchant.shops", "Shops", "Merchant NPC shops, inventory, pricing, sales, and earnings."),
    MERCHANT_ORDERS("merchant.orders", "Merchant Orders", "Your buy-order status, fill progress, storage, and escrow."),
    MERCHANT_CREATE_ORDER("merchant.create_order", "Create Order", "Interactive held-item buy-order creation."),
    MERCHANT_EARNINGS("merchant.earnings", "Earnings", "Delivered order storage and claimable merchant payouts."),
    MERCHANT_SETTINGS("merchant.settings", "Merchant Settings", "Merchant limits, market visualization, and payout policy."),

    RAIL_HOME("rail.home", "Rail", "Rail station and travel tools."),
    RAIL_STATION("rail.station", "Station", "Active stations, platform context, and district station status."),
    RAIL_ROUTES("rail.routes", "Routes", "Active routes, destinations, prices, and travel time."),
    RAIL_TICKET("rail.ticket", "Ticket", "Select a route and buy a physical-economy rail ticket."),
    RAIL_JOURNEY("rail.journey", "Journey", "Live boarding and journey state."),

    STAFF("staff.home", "Staff", "Staff mode shortcuts."),
    STAFF_QUICK("staff.quick", "Quick Actions", "Audited staff shortcuts."),
    STAFF_PLAYERS("staff.players", "Players", "Audited player search, lists, profiles, and operational actions."),
    STAFF_PLAYER_SEARCH("staff.player.search", "Player Search", "Search by name, UUID, district, or rank."),
    STAFF_PLAYER_LIST("staff.player.list", "Player List", "Online, recent, wanted, and frozen player lists."),
    STAFF_PLAYER_PROFILE("staff.player.profile", "Player Profile", "Audited player profile and action launcher."),
    STAFF_REPORTS("staff.reports", "Reports", "Persistent player-report claim and resolution queue."),
    STAFF_SECURITY("staff.security", "Security", "Persistent alerts, scoring, anti-xray verification, and storage discovery."),
    STAFF_ECONOMY("staff.economy", "Economy", "Cash, vault, escrow, payout, and contract oversight."),
    STAFF_CASH_TRACE("staff.cash_trace", "Cash Trace", "Physical cash lookup, trail, and circulation metrics."),
    STAFF_VAULTS("staff.vaults", "Vaults", "Staff vault tools."),
    STAFF_CONTRACTS("staff.contracts", "Contracts", "Contract, escrow, dispute, job, and payout oversight."),
    STAFF_DISTRICTS("staff.districts", "Districts", "Applications, moderation, teleports, support, and join-request oversight."),
    STAFF_POLICE_ABUSE("staff.police_abuse", "Police Abuse", "Filtered police-abuse report and security review."),
    STAFF_RAIL("staff.rail", "Rail Admin", "Station administration plus revenue and travel logs."),
    STAFF_REGION_DEBUG("staff.region_debug", "Region Debug", "Region debugging shortcuts."),
    STAFF_SYSTEM("staff.system", "System", "System tools and diagnostics."),

    ADMIN("admin", "Admin", "Staff-only administration shortcuts."),
    ADMIN_RANKS("admin_ranks", "Rank Admin", "Rank and access shortcuts."),
    ADMIN_CASH("admin_cash", "Cash Admin", "Physical money administration shortcuts."),
    ADMIN_NPCS("admin_npcs", "NPC Admin", "NPC management shortcuts."),
    ADMIN_REGIONS("admin_regions", "Region Admin", "Region management shortcuts."),
    ADMIN_DAMAGE("admin_damage", "Damage Admin", "Temporary damage and restoration shortcuts."),
    ADMIN_DISPLAYS("admin_displays", "Display Admin", "Auction display shortcuts."),
    ADMIN_UPDATES("admin_updates", "Updates", "GitHub updater shortcuts."),
    SPAWNCITY("spawncity", "Spawn City", "Spawn City setup and information."),
    VWE("vwe", "VS-WorldEdit", "Internal building toolkit shortcuts."),
    AUCTIONHALL("auctionhall", "Auction Hall", "Physical Auction Hall shortcuts."),
    VAULTS("vaults", "Vaults", "Vault management shortcuts.");

    private final String id;
    private final String title;
    private final String body;

    DialogMenuType(String id, String title, String body) {
        this.id = id;
        this.title = title;
        this.body = body;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public static DialogMenuType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAIN;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (DialogMenuType type : values()) {
            if (type.id.replace("_", "").equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        if (normalized.equals("ah") || normalized.equals("auction")) {
            return AUCTIONHALL;
        }
        if (normalized.equals("current")) {
            return CURRENT_AREA;
        }
        if (normalized.equals("district") || normalized.equals("districts")) {
            return DISTRICTS;
        }
        if (normalized.equals("merchant")) {
            return MERCHANT_HOME;
        }
        if (normalized.equals("rail")) {
            return RAIL_HOME;
        }
        if (normalized.equals("jobs")) {
            return PLAYER_JOBS;
        }
        if (normalized.equals("orders")) {
            return PLAYER_ORDERS;
        }
        if (normalized.equals("players")) {
            return STAFF_PLAYERS;
        }
        if (normalized.equals("security")) {
            return STAFF_SECURITY;
        }
        if (normalized.equals("economy")) {
            return STAFF_ECONOMY;
        }
        if (normalized.equals("contracts")) {
            return STAFF_CONTRACTS;
        }
        if (normalized.equals("railadmin")) {
            return STAFF_RAIL;
        }
        if (normalized.equals("debug")) {
            return STAFF_REGION_DEBUG;
        }
        if (normalized.equals("system")) {
            return STAFF_SYSTEM;
        }
        if (normalized.equals("spawn")) {
            return SPAWNCITY;
        }
        if (normalized.equals("worldedit") || normalized.equals("vsworldedit")) {
            return VWE;
        }
        return MAIN;
    }
}
