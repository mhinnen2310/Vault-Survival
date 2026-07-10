package com.vaultsurvival.plugin.core;

import java.io.File;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the SQLite database connection.
 * SQLite uses a single file-based database - no external server needed.
 * WAL mode is enabled for better concurrent read performance.
 */
public class DatabaseManager {

    private final Logger logger;
    private final File dataFolder;
    private Connection connection;
    private String dbUrl;

    public DatabaseManager(Logger logger, File dataFolder) {
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    /**
     * Initialize the SQLite connection using ConfigManager values.
     */
    public void connect(ConfigManager config) throws SQLException {
        dataFolder.mkdirs();
        String dbFile = config.getDatabaseFile();
        File dbPath = new File(dataFolder, dbFile);

        dbUrl = "jdbc:sqlite:" + dbPath.getAbsolutePath();

        // Load SQLite driver
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }

        connection = DriverManager.getConnection(dbUrl);

        // Enable WAL mode for better concurrent read performance
        // and enable foreign keys
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("PRAGMA busy_timeout=5000");
        }

        logger.info("SQLite database connected: " + dbPath.getAbsolutePath());
    }

    /**
     * Get a connection. SQLite uses a single shared connection;
     * we return a no-close proxy wrapper so that try-with-resources
     * patterns in service code don't accidentally close the shared connection.
     * SQLite serializes writes internally.
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database connection is not available");
        }
        // Return a proxy that intercepts close() as a no-op,
        // preventing try-with-resources from killing the shared connection.
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) return null;
                try {
                    return method.invoke(connection, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        );
    }

    /**
     * Initialize all required database tables.
     * Uses IF NOT EXISTS so it's safe to run on every startup.
     */
    public void initializeSchema() {
        String[] statements = {
            // === Player tables ===
            "CREATE TABLE IF NOT EXISTS players (" +
                "uuid TEXT PRIMARY KEY," +
                "username TEXT NOT NULL," +
                "first_seen TEXT NOT NULL DEFAULT (datetime('now'))," +
                "last_seen TEXT NOT NULL DEFAULT (datetime('now'))," +
                "playtime_seconds INTEGER NOT NULL DEFAULT 0," +
                "is_banned INTEGER NOT NULL DEFAULT 0," +
                "ban_reason TEXT," +
                "ban_expiry TEXT," +
                "metadata TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS player_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL REFERENCES players(uuid)," +
                "login_time TEXT NOT NULL DEFAULT (datetime('now'))," +
                "logout_time TEXT," +
                "ip_address TEXT" +
            ")",

            // === Access / Permissions tables ===
            "CREATE TABLE IF NOT EXISTS access_groups (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE NOT NULL," +
                "weight INTEGER NOT NULL DEFAULT 0," +
                "prefix TEXT DEFAULT ''," +
                "color TEXT DEFAULT '&7'," +
                "is_staff INTEGER NOT NULL DEFAULT 0," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",

            "CREATE TABLE IF NOT EXISTS access_group_permissions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "group_id INTEGER NOT NULL REFERENCES access_groups(id) ON DELETE CASCADE," +
                "permission_node TEXT NOT NULL," +
                "UNIQUE(group_id, permission_node)" +
            ")",

            "CREATE TABLE IF NOT EXISTS access_group_inheritance (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "group_id INTEGER NOT NULL REFERENCES access_groups(id) ON DELETE CASCADE," +
                "parent_group_id INTEGER NOT NULL REFERENCES access_groups(id) ON DELETE CASCADE," +
                "UNIQUE(group_id, parent_group_id)" +
            ")",

            "CREATE TABLE IF NOT EXISTS access_player_groups (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL," +
                "group_id INTEGER NOT NULL REFERENCES access_groups(id) ON DELETE CASCADE," +
                "granted_by TEXT," +
                "granted_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "expires_at TEXT," +
                "UNIQUE(player_uuid, group_id)" +
            ")",

            "CREATE TABLE IF NOT EXISTS access_player_permissions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL," +
                "permission_node TEXT NOT NULL," +
                "value INTEGER NOT NULL DEFAULT 1," +
                "granted_by TEXT," +
                "granted_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "expires_at TEXT," +
                "UNIQUE(player_uuid, permission_node)" +
            ")",

            // === Admin Audit Log ===
            "CREATE TABLE IF NOT EXISTS admin_audit_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp TEXT NOT NULL DEFAULT (datetime('now'))," +
                "actor_uuid TEXT," +
                "actor_name TEXT," +
                "action_type TEXT NOT NULL," +
                "target_type TEXT," +
                "target_id TEXT," +
                "details TEXT," +
                "ip_address TEXT" +
            ")",

            // === Cash tables ===
            "CREATE TABLE IF NOT EXISTS cash_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "cash_uuid TEXT UNIQUE NOT NULL," +
                "amount INTEGER NOT NULL," +
                "state TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "last_seen_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "location_type TEXT," +
                "location_id TEXT," +
                "owner_uuid TEXT," +
                "created_by TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS cash_transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp TEXT NOT NULL DEFAULT (datetime('now'))," +
                "cash_uuid TEXT NOT NULL REFERENCES cash_items(cash_uuid) ON DELETE CASCADE," +
                "transaction_type TEXT NOT NULL," +
                "amount INTEGER NOT NULL," +
                "actor_uuid TEXT," +
                "source_location TEXT," +
                "target_location TEXT," +
                "details TEXT" +
            ")",

            // === Vault tables ===
            "CREATE TABLE IF NOT EXISTS vaults (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vault_uuid TEXT UNIQUE NOT NULL," +
                "tier TEXT NOT NULL DEFAULT 'SMALL'," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "owner_uuid TEXT NOT NULL," +
                "capacity INTEGER NOT NULL DEFAULT 100000," +
                "is_locked_down INTEGER NOT NULL DEFAULT 0," +
                "lockout_until TEXT," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "metadata TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS vault_access (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vault_uuid TEXT NOT NULL REFERENCES vaults(vault_uuid) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "access_level TEXT NOT NULL DEFAULT 'USER'," +
                "granted_by TEXT," +
                "granted_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "UNIQUE(vault_uuid, player_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS vault_breaches (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vault_uuid TEXT NOT NULL REFERENCES vaults(vault_uuid) ON DELETE CASCADE," +
                "thief_uuid TEXT NOT NULL," +
                "started_at TEXT," +
                "completed_at TEXT," +
                "success INTEGER NOT NULL DEFAULT 0," +
                "stolen_amount INTEGER NOT NULL DEFAULT 0," +
                "vault_balance_before INTEGER NOT NULL," +
                "vault_balance_after INTEGER NOT NULL," +
                "breach_score REAL," +
                "failed_reason TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS vault_repairs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vault_uuid TEXT NOT NULL REFERENCES vaults(vault_uuid) ON DELETE CASCADE," +
                "repaired_by TEXT," +
                "repair_cost INTEGER NOT NULL DEFAULT 0," +
                "repaired_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",

            // === Market tables (Phase 4 - pre-create) ===
            "CREATE TABLE IF NOT EXISTS auction_listings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "listing_uuid TEXT UNIQUE NOT NULL," +
                "seller_uuid TEXT NOT NULL," +
                "category TEXT NOT NULL DEFAULT 'MISC'," +
                "item_data TEXT NOT NULL," +
                "price INTEGER NOT NULL," +
                "currency_type TEXT NOT NULL DEFAULT 'CASH'," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "expires_at TEXT," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "sold_to TEXT," +
                "sold_at TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS auction_escrow_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "listing_uuid TEXT UNIQUE NOT NULL," +
                "item_data TEXT NOT NULL," +
                "stored_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "returned_to_seller INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS auction_lockers (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT UNIQUE NOT NULL," +
                "balance INTEGER NOT NULL DEFAULT 0," +
                "last_collected_at TEXT" +
            ")",

            "CREATE TABLE IF NOT EXISTS auction_transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "listing_uuid TEXT NOT NULL REFERENCES auction_listings(listing_uuid) ON DELETE CASCADE," +
                "buyer_uuid TEXT NOT NULL," +
                "seller_uuid TEXT NOT NULL," +
                "price INTEGER NOT NULL," +
                "tax INTEGER NOT NULL," +
                "completed_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",

            // === NPC tables ===
            "CREATE TABLE IF NOT EXISTS npcs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "skin_username TEXT NOT NULL," +
                "world TEXT NOT NULL," +
                "x REAL NOT NULL," +
                "y REAL NOT NULL," +
                "z REAL NOT NULL," +
                "yaw REAL NOT NULL DEFAULT 0," +
                "pitch REAL NOT NULL DEFAULT 0," +
                "action_type TEXT NOT NULL DEFAULT 'NONE'," +
                "action_data TEXT DEFAULT ''," +
                "look_at_players INTEGER NOT NULL DEFAULT 1" +
            ")",

            "CREATE TABLE IF NOT EXISTS npc_shop_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "npc_id INTEGER NOT NULL REFERENCES npcs(id) ON DELETE CASCADE," +
                "slot INTEGER NOT NULL," +
                "item_data TEXT NOT NULL," +
                "price INTEGER NOT NULL," +
                "command_on_purchase TEXT DEFAULT ''," +
                "UNIQUE(npc_id, slot)" +
            ")",

            // === Region tables ===
            "CREATE TABLE IF NOT EXISTS regions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "type TEXT NOT NULL DEFAULT 'CUSTOM'," +
                "world TEXT NOT NULL," +
                "x1 INTEGER NOT NULL," +
                "y1 INTEGER NOT NULL," +
                "z1 INTEGER NOT NULL," +
                "x2 INTEGER NOT NULL," +
                "y2 INTEGER NOT NULL," +
                "z2 INTEGER NOT NULL," +
                "priority INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS region_flags (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "region_id INTEGER NOT NULL REFERENCES regions(id) ON DELETE CASCADE," +
                "flag_name TEXT NOT NULL," +
                "flag_value INTEGER NOT NULL DEFAULT 1," +
                "UNIQUE(region_id, flag_name)" +
            ")",

            // === District tables ===
            "CREATE TABLE IF NOT EXISTS districts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "founder_uuid TEXT NOT NULL," +
                "world TEXT NOT NULL," +
                "center_x INTEGER NOT NULL," +
                "center_z INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'APPLICATION'," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_members (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "role TEXT NOT NULL DEFAULT 'MEMBER'," +
                "joined_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "UNIQUE(district_id, player_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_member_roles (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "role TEXT NOT NULL," +
                "granted_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "UNIQUE(district_id, player_uuid, role)" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_laws (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "law_name TEXT NOT NULL," +
                "enabled INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(district_id, law_name)" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_pending_laws (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "law_name TEXT NOT NULL," +
                "enabled INTEGER NOT NULL DEFAULT 0," +
                "proposed_by TEXT NOT NULL," +
                "proposed_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "applies_at TEXT," +
                "applied INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(district_id, law_name)" +
            ")",

            // === Temporary Damage tables (Phase 7) ===
            "CREATE TABLE IF NOT EXISTS temporary_damage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "original_block TEXT NOT NULL," +
                "original_block_data TEXT DEFAULT ''," +
                "damage_type TEXT NOT NULL DEFAULT 'BREAK'," +
                "actor_uuid TEXT NOT NULL," +
                "timestamp INTEGER NOT NULL," +
                "scheduled_restore INTEGER NOT NULL," +
                "restored INTEGER NOT NULL DEFAULT 0" +
            ")",

            // === Repair states table (Phase 8) ===
            "CREATE TABLE IF NOT EXISTS repair_states (" +
                "district_id INTEGER PRIMARY KEY," +
                "repair_points INTEGER NOT NULL DEFAULT 500," +
                "last_wage_paid INTEGER NOT NULL DEFAULT 0," +
                "last_points_reset INTEGER NOT NULL DEFAULT 0," +
                "is_exhausted INTEGER NOT NULL DEFAULT 0" +
            ")",

            // === Crime & Police tables (Phase 9) ===
            "CREATE TABLE IF NOT EXISTS crime_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL," +
                "criminal_uuid TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "severity TEXT NOT NULL DEFAULT 'MODERATE'," +
                "block_type TEXT DEFAULT ''," +
                "location TEXT DEFAULT ''," +
                "timestamp INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS wanted_players (" +
                "criminal_uuid TEXT NOT NULL," +
                "district_id INTEGER NOT NULL," +
                "bounty INTEGER NOT NULL DEFAULT 0," +
                "crime_count INTEGER NOT NULL DEFAULT 0," +
                "last_crime_time INTEGER NOT NULL DEFAULT 0," +
                "arrested INTEGER NOT NULL DEFAULT 0," +
                "jail_until INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (criminal_uuid, district_id)" +
            ")",

            "CREATE TABLE IF NOT EXISTS jail_locations (" +
                "district_id INTEGER PRIMARY KEY," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_evidence (" +
                "evidence_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "law_key TEXT NOT NULL," +
                "action_type TEXT NOT NULL," +
                "location TEXT NOT NULL," +
                "timestamp INTEGER NOT NULL," +
                "severity TEXT NOT NULL," +
                "details TEXT," +
                "status TEXT NOT NULL DEFAULT 'UNHANDLED'," +
                "expires_at INTEGER NOT NULL," +
                "handled_by TEXT" +
            ")",

            // === Display Auction Hall tables (Phase 10) ===
            "CREATE TABLE IF NOT EXISTS display_slots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "category TEXT NOT NULL DEFAULT 'MISC'" +
            ")",

            // === Social tables (Phase 11) ===
            "CREATE TABLE IF NOT EXISTS friends (" +
                "player_uuid TEXT NOT NULL," +
                "friend_uuid TEXT NOT NULL," +
                "since INTEGER NOT NULL," +
                "PRIMARY KEY (player_uuid, friend_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS player_groups (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "owner_uuid TEXT NOT NULL," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",

            "CREATE TABLE IF NOT EXISTS group_members (" +
                "group_id INTEGER NOT NULL REFERENCES player_groups(id) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "PRIMARY KEY (group_id, player_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS stations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "route_type TEXT NOT NULL DEFAULT 'FREE'," +
                "cost INTEGER NOT NULL DEFAULT 0," +
                "owner_uuid TEXT NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS contracts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "issuer_uuid TEXT NOT NULL," +
                "target_uuid TEXT NOT NULL," +
                "description TEXT DEFAULT ''," +
                "amount INTEGER NOT NULL DEFAULT 0," +
                "deadline INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'PENDING'" +
            ")",

            "CREATE TABLE IF NOT EXISTS contract_escrows (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "contract_id INTEGER NOT NULL," +
                "cash_uuid TEXT," +
                "amount INTEGER NOT NULL," +
                "source_type TEXT NOT NULL DEFAULT 'PLAYER_CASH'," +
                "source_id TEXT," +
                "status TEXT NOT NULL DEFAULT 'LOCKED'," +
                "locked_by TEXT," +
                "locked_at INTEGER NOT NULL," +
                "released_at INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS payout_lockers (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL," +
                "amount INTEGER NOT NULL," +
                "source_type TEXT NOT NULL," +
                "source_id TEXT," +
                "details TEXT," +
                "status TEXT NOT NULL DEFAULT 'PENDING'," +
                "created_at INTEGER NOT NULL," +
                "claimed_at INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS contract_audit (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "contract_id INTEGER NOT NULL DEFAULT 0," +
                "actor_uuid TEXT," +
                "action TEXT NOT NULL," +
                "amount INTEGER NOT NULL DEFAULT 0," +
                "details TEXT," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS contract_disputes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "contract_id INTEGER NOT NULL," +
                "opened_by TEXT NOT NULL," +
                "reason TEXT," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "created_at INTEGER NOT NULL," +
                "resolved_at INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL," +
                "creator_uuid TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "description TEXT DEFAULT ''," +
                "reward INTEGER NOT NULL," +
                "deadline INTEGER NOT NULL DEFAULT 0," +
                "required_item TEXT," +
                "required_amount INTEGER NOT NULL DEFAULT 0," +
                "origin TEXT," +
                "destination TEXT," +
                "checkpoint TEXT," +
                "tracking_mode TEXT NOT NULL DEFAULT 'MANUAL'," +
                "manual_approval INTEGER NOT NULL DEFAULT 1," +
                "status TEXT NOT NULL DEFAULT 'DRAFT'," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_job_claims (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "job_id INTEGER NOT NULL," +
                "player_uuid TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'CLAIMED'," +
                "submitted_at INTEGER NOT NULL DEFAULT 0," +
                "reviewed_by TEXT," +
                "review_reason TEXT," +
                "created_at INTEGER NOT NULL," +
                "UNIQUE(job_id, player_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS spawn_city_jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "description TEXT DEFAULT ''," +
                "reward INTEGER NOT NULL," +
                "required_item TEXT," +
                "required_amount INTEGER NOT NULL DEFAULT 0," +
                "destination TEXT," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "seed_key TEXT UNIQUE," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS player_spawn_jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "job_id INTEGER NOT NULL," +
                "player_uuid TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "accepted_at INTEGER NOT NULL," +
                "completed_at INTEGER NOT NULL DEFAULT 0," +
                "cooldown_until INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(job_id, player_uuid, status)" +
            ")",

            "CREATE TABLE IF NOT EXISTS transport_packages (" +
                "package_uuid TEXT PRIMARY KEY," +
                "player_job_id INTEGER NOT NULL," +
                "player_uuid TEXT NOT NULL," +
                "destination TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "created_at INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL" +
            ")",

            // === Merchant Buy Orders (Sprint 9) ===
            "CREATE TABLE IF NOT EXISTS merchant_orders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "merchant_uuid TEXT NOT NULL," +
                "item_template TEXT NOT NULL," +
                "item_display TEXT NOT NULL," +
                "price_per_item INTEGER NOT NULL," +
                "req_quantity INTEGER NOT NULL," +
                "filled_quantity INTEGER NOT NULL DEFAULT 0," +
                "partial_delivery INTEGER NOT NULL DEFAULT 0," +
                "escrow_amount INTEGER NOT NULL DEFAULT 0," +
                "remaining_escrow INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS merchant_order_storage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "order_id INTEGER NOT NULL," +
                "item_data TEXT NOT NULL," +
                "claimed INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS merchant_deliveries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "order_id INTEGER NOT NULL," +
                "supplier_uuid TEXT NOT NULL," +
                "quantity_delivered INTEGER NOT NULL," +
                "payout_amount INTEGER NOT NULL," +
                "timestamp INTEGER NOT NULL" +
            ")",

            // === Merchant Shops (Sprint 10) ===
            "CREATE TABLE IF NOT EXISTS merchant_shops (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "owner_uuid TEXT NOT NULL," +
                "npc_id INTEGER NOT NULL," +
                "district_id INTEGER NOT NULL," +
                "name TEXT NOT NULL," +
                "world_name TEXT NOT NULL," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS merchant_shop_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "shop_id INTEGER NOT NULL," +
                "slot INTEGER NOT NULL," +
                "item_data TEXT NOT NULL," +
                "item_display TEXT NOT NULL," +
                "stock INTEGER NOT NULL DEFAULT 0," +
                "price INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS merchant_shop_sales (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "shop_id INTEGER NOT NULL," +
                "buyer_uuid TEXT NOT NULL," +
                "item_data TEXT NOT NULL," +
                "quantity INTEGER NOT NULL," +
                "price_each INTEGER NOT NULL," +
                "tax_amount INTEGER NOT NULL DEFAULT 0," +
                "timestamp INTEGER NOT NULL" +
            ")",

            // === Rail / Train Stations (Sprint 11) ===
            "CREATE TABLE IF NOT EXISTS rail_stations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL," +
                "requester_uuid TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "world_name TEXT NOT NULL," +
                "plat_min_x INTEGER NOT NULL, plat_min_y INTEGER NOT NULL, plat_min_z INTEGER NOT NULL," +
                "plat_max_x INTEGER NOT NULL, plat_max_y INTEGER NOT NULL, plat_max_z INTEGER NOT NULL," +
                "arr_x REAL NOT NULL, arr_y REAL NOT NULL, arr_z REAL NOT NULL," +
                "arr_yaw REAL NOT NULL DEFAULT 0, arr_pitch REAL NOT NULL DEFAULT 0," +
                "ticket_price INTEGER NOT NULL DEFAULT 0," +
                "upkeep_cost INTEGER NOT NULL DEFAULT 0," +
                "kingdom_tax_percent INTEGER NOT NULL DEFAULT 10," +
                "status TEXT NOT NULL DEFAULT 'PENDING'," +
                "total_revenue INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS rail_routes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "from_station_id INTEGER NOT NULL," +
                "to_station_id INTEGER NOT NULL," +
                "ticket_price INTEGER NOT NULL DEFAULT 0," +
                "kingdom_tax_percent INTEGER NOT NULL DEFAULT 10," +
                "travel_time_ticks INTEGER NOT NULL DEFAULT 100," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "created_at INTEGER NOT NULL" +
            ")",

            // === Rail Journey Log (Sprint 12) ===
            "CREATE TABLE IF NOT EXISTS rail_journey_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL," +
                "player_name TEXT NOT NULL," +
                "route_id INTEGER NOT NULL," +
                "from_station_id INTEGER NOT NULL," +
                "to_station_id INTEGER NOT NULL," +
                "from_station TEXT NOT NULL," +
                "to_station TEXT NOT NULL," +
                "ticket_price INTEGER NOT NULL DEFAULT 0," +
                "event TEXT NOT NULL," +
                "created_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_settings (district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE, setting_key TEXT NOT NULL, setting_value TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT (datetime('now')), PRIMARY KEY(district_id, setting_key))",

            "CREATE TABLE IF NOT EXISTS district_claims (" +
                "district_id INTEGER PRIMARY KEY REFERENCES districts(id) ON DELETE CASCADE," +
                "world TEXT NOT NULL," +
                "min_chunk_x INTEGER NOT NULL," +
                "min_chunk_z INTEGER NOT NULL," +
                "max_chunk_x INTEGER NOT NULL," +
                "max_chunk_z INTEGER NOT NULL," +
                "updated_at TEXT NOT NULL DEFAULT (datetime('now'))" +
            ")",
            "CREATE TABLE IF NOT EXISTS district_development (district_id INTEGER PRIMARY KEY, level INTEGER NOT NULL DEFAULT 0, economy INTEGER NOT NULL DEFAULT 0, infrastructure INTEGER NOT NULL DEFAULT 0, security INTEGER NOT NULL DEFAULT 0, community INTEGER NOT NULL DEFAULT 0, trade_score INTEGER NOT NULL DEFAULT 0, law_and_order INTEGER NOT NULL DEFAULT 0, maintenance INTEGER NOT NULL DEFAULT 0, maintenance_state TEXT NOT NULL DEFAULT 'STABLE', updated_at INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS district_projects (id INTEGER PRIMARY KEY AUTOINCREMENT, district_id INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'DRAFT', cash_required INTEGER NOT NULL DEFAULT 0, cash_contributed INTEGER NOT NULL DEFAULT 0, item_required INTEGER NOT NULL DEFAULT 0, item_contributed INTEGER NOT NULL DEFAULT 0, created_by TEXT NOT NULL, created_at INTEGER NOT NULL, approved_by TEXT)",
            "CREATE TABLE IF NOT EXISTS district_development_contributions (id INTEGER PRIMARY KEY AUTOINCREMENT, district_id INTEGER NOT NULL, project_id INTEGER, player_uuid TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL, amount INTEGER NOT NULL, contributed_at INTEGER NOT NULL, details TEXT)",
            "CREATE TABLE IF NOT EXISTS district_npc_plans (id INTEGER PRIMARY KEY AUTOINCREMENT, district_id INTEGER NOT NULL, npc_type TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, minimum_level INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PLANNED', npc_id INTEGER, planned_by TEXT NOT NULL, planned_at INTEGER NOT NULL, UNIQUE(district_id, npc_type))",
            "CREATE TABLE IF NOT EXISTS anticheat_flags (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, check_type TEXT NOT NULL, score REAL NOT NULL, details TEXT, created_at INTEGER NOT NULL)",

            // === Civic workflows and player preferences ===
            "CREATE TABLE IF NOT EXISTS player_preferences (" +
                "player_uuid TEXT PRIMARY KEY," +
                "notifications TEXT NOT NULL DEFAULT 'ALL'," +
                "menu_style TEXT NOT NULL DEFAULT 'AUTO'," +
                "privacy TEXT NOT NULL DEFAULT 'PUBLIC'," +
                "updated_at INTEGER NOT NULL" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_join_requests (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "player_uuid TEXT NOT NULL," +
                "player_name TEXT NOT NULL," +
                "message TEXT," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "handled_by TEXT," +
                "created_at INTEGER NOT NULL," +
                "resolved_at INTEGER NOT NULL DEFAULT 0" +
            ")",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_open_district_join_request ON district_join_requests(district_id, player_uuid) WHERE status='OPEN'",

            "CREATE TABLE IF NOT EXISTS player_reports (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "reporter_uuid TEXT NOT NULL," +
                "reporter_name TEXT NOT NULL," +
                "subject_uuid TEXT," +
                "subject_name TEXT," +
                "district_id INTEGER," +
                "category TEXT NOT NULL," +
                "details TEXT NOT NULL," +
                "world TEXT NOT NULL," +
                "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "assigned_to TEXT," +
                "resolution TEXT," +
                "created_at INTEGER NOT NULL," +
                "resolved_at INTEGER NOT NULL DEFAULT 0" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_diplomacy (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_a INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "district_b INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "relation TEXT NOT NULL DEFAULT 'NEUTRAL'," +
                "proposer_district INTEGER," +
                "changed_by TEXT," +
                "updated_at INTEGER NOT NULL," +
                "UNIQUE(district_a, district_b)" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_job_disputes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "claim_id INTEGER NOT NULL REFERENCES district_job_claims(id) ON DELETE CASCADE," +
                "job_id INTEGER NOT NULL REFERENCES district_jobs(id) ON DELETE CASCADE," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "opened_by TEXT NOT NULL," +
                "reason TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "handled_by TEXT," +
                "resolution TEXT," +
                "created_at INTEGER NOT NULL," +
                "resolved_at INTEGER NOT NULL DEFAULT 0" +
            ")",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_open_district_job_dispute ON district_job_disputes(claim_id) WHERE status='OPEN'",

            "CREATE TABLE IF NOT EXISTS kingdom_support_requests (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "project_id INTEGER," +
                "requested_by TEXT NOT NULL," +
                "details TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "assigned_to TEXT," +
                "completion_note TEXT," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL" +
            ")",

            // === Persistent, actionable staff alert queue ===
            "CREATE TABLE IF NOT EXISTS staff_alerts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "alert_type TEXT NOT NULL," +
                "severity TEXT NOT NULL," +
                "player_uuid TEXT," +
                "player_name TEXT," +
                "details TEXT NOT NULL," +
                "world TEXT," +
                "x REAL, y REAL, z REAL," +
                "status TEXT NOT NULL DEFAULT 'OPEN'," +
                "assigned_to TEXT," +
                "resolution TEXT," +
                "created_at INTEGER NOT NULL," +
                "resolved_at INTEGER NOT NULL DEFAULT 0" +
            ")",
            "CREATE INDEX IF NOT EXISTS idx_staff_alerts_queue ON staff_alerts(status, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_player_reports_queue ON player_reports(status, created_at DESC)"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
            logger.info("Database schema initialized successfully (" + statements.length + " tables)");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to initialize database schema", e);
            throw new RuntimeException("Database schema initialization failed", e);
        }
    }

    /**
     * Execute a SQL update (INSERT, UPDATE, DELETE).
     */
    public void executeUpdate(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Close the database connection.
     */
    public void shutdown() {
        if (connection != null) {
            try {
                // Checkpoint WAL before closing
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {}
                connection.close();
                logger.info("Database connection closed");
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Error closing database connection", e);
            }
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
