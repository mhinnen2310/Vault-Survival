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
                "role TEXT NOT NULL DEFAULT 'CITIZEN'," +
                "joined_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "UNIQUE(district_id, player_uuid)" +
            ")",

            "CREATE TABLE IF NOT EXISTS district_laws (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "district_id INTEGER NOT NULL REFERENCES districts(id) ON DELETE CASCADE," +
                "law_name TEXT NOT NULL," +
                "enabled INTEGER NOT NULL DEFAULT 0," +
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
            ")"
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
