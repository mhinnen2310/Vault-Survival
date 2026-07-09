package com.vaultsurvival.plugin.dialogs;

import java.util.Locale;

public enum DialogMenuType {
    MAIN("main", "Vault Survival", "Choose a Vault Survival tool."),
    CURRENT_AREA("current_area", "Current Area", "Information about the district, station, and risk around you."),
    SETTINGS("settings", "Settings", "Player settings and preferences."),
    GUIDES("guides", "Guides", "Quick guides for Vault Survival systems."),
    PLAYER_JOBS("player.jobs", "Jobs", "Player job board placeholder."),
    PLAYER_ORDERS("player.orders", "Orders", "Player orders placeholder."),
    PLAYER_RISK("player.risk", "Risk", "Local risk and crime status placeholder."),

    DISTRICTS("district.home", "Districts", "District actions for your current role."),
    DISTRICT_CURRENT("district.current", "Current District", "Current district placeholder."),
    DISTRICT_LAWS("district.laws", "District Laws", "District laws placeholder."),
    DISTRICT_PENDING_LAWS("district.pending_laws", "Pending Laws", "Pending district law placeholder."),
    DISTRICT_ROLES("district.roles", "District Roles", "District role placeholder."),
    DISTRICT_MARKET("district.market", "District Market", "District market placeholder."),
    DISTRICT_MERCHANT("district.merchant", "District Merchant", "District merchant placeholder."),
    DISTRICT_TREASURY("district.treasury", "District Treasury", "District treasury placeholder."),
    DISTRICT_POLICE("district.police", "District Police", "District police placeholder."),
    DISTRICT_STATION("district.station", "District Station", "District station placeholder."),
    DISTRICT_DIPLOMACY("district.diplomacy", "Diplomacy", "District diplomacy placeholder."),
    DISTRICT_JOBS("district.jobs", "District Jobs", "District job placeholder."),
    DISTRICT_DEVELOPMENT("district.development", "Development", "District development placeholder."),

    MERCHANT_HOME("merchant.home", "Merchant", "Merchant tools and order management."),
    MERCHANT_SHOPS("merchant.shops", "Shops", "Merchant shop placeholder."),
    MERCHANT_ORDERS("merchant.orders", "Merchant Orders", "Merchant order placeholder."),
    MERCHANT_CREATE_ORDER("merchant.create_order", "Create Order", "Create order placeholder."),
    MERCHANT_EARNINGS("merchant.earnings", "Earnings", "Merchant earnings placeholder."),

    RAIL_HOME("rail.home", "Rail", "Rail station and travel tools."),
    RAIL_STATION("rail.station", "Station", "Station placeholder."),
    RAIL_ROUTES("rail.routes", "Routes", "Rail routes placeholder."),
    RAIL_TICKET("rail.ticket", "Ticket", "Ticket placeholder."),
    RAIL_JOURNEY("rail.journey", "Journey", "Journey placeholder."),

    STAFF("staff.home", "Staff", "Staff mode shortcuts."),
    STAFF_QUICK("staff.quick", "Quick Actions", "Audited staff shortcuts."),
    STAFF_PLAYERS("staff.players", "Players", "Staff player tools placeholder."),
    STAFF_PLAYER_SEARCH("staff.player.search", "Player Search", "Player search placeholder."),
    STAFF_PLAYER_LIST("staff.player.list", "Player List", "Player list placeholder."),
    STAFF_PLAYER_PROFILE("staff.player.profile", "Player Profile", "Player profile placeholder."),
    STAFF_REPORTS("staff.reports", "Reports", "Reports placeholder."),
    STAFF_SECURITY("staff.security", "Security", "Security shortcuts and placeholders."),
    STAFF_ECONOMY("staff.economy", "Economy", "Economy shortcuts and placeholders."),
    STAFF_CASH_TRACE("staff.cash_trace", "Cash Trace", "Cash trace placeholder."),
    STAFF_VAULTS("staff.vaults", "Vaults", "Staff vault tools."),
    STAFF_CONTRACTS("staff.contracts", "Contracts", "Staff contract tools placeholder."),
    STAFF_DISTRICTS("staff.districts", "Districts", "Staff district tools placeholder."),
    STAFF_POLICE_ABUSE("staff.police_abuse", "Police Abuse", "Police abuse review placeholder."),
    STAFF_RAIL("staff.rail", "Rail Admin", "Rail administration placeholder."),
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
