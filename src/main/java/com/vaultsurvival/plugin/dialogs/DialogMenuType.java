package com.vaultsurvival.plugin.dialogs;

import java.util.Locale;

public enum DialogMenuType {
    MAIN("main", "Vault Survival", "Choose a Vault Survival tool."),
    DISTRICTS("districts", "Districts", "District actions for your current role."),
    ADMIN("admin", "Admin", "Staff-only administration shortcuts."),
    ADMIN_RANKS("admin_ranks", "Rank Admin", "Rank and access shortcuts."),
    ADMIN_CASH("admin_cash", "Cash Admin", "Physical money administration shortcuts."),
    ADMIN_NPCS("admin_npcs", "NPC Admin", "NPC management shortcuts."),
    ADMIN_REGIONS("admin_regions", "Region Admin", "Region management shortcuts."),
    ADMIN_DAMAGE("admin_damage", "Damage Admin", "Temporary damage and restoration shortcuts."),
    ADMIN_DISPLAYS("admin_displays", "Display Admin", "Auction display shortcuts."),
    ADMIN_UPDATES("admin_updates", "Updates", "GitHub updater shortcuts."),
    STAFF("staff", "Staff", "Staff mode shortcuts."),
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
        if (normalized.equals("spawn")) {
            return SPAWNCITY;
        }
        if (normalized.equals("worldedit") || normalized.equals("vsworldedit")) {
            return VWE;
        }
        return MAIN;
    }
}
