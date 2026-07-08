package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DialogService {

    private final VaultSurvivalPlugin plugin;
    private final DialogProvider nativeProvider;
    private final DialogProvider fallbackProvider;
    private final Map<String, DialogInputDefinition> inputs;
    private String lastProviderName = "none";

    public DialogService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.nativeProvider = new NativePaperDialogProvider();
        this.fallbackProvider = new FallbackDialogProvider(plugin, this);
        this.inputs = buildInputs();
    }

    public boolean isNativeSupported() {
        return nativeProvider.isAvailable();
    }

    public String getLastProviderName() {
        return lastProviderName;
    }

    public void openMenu(Player player, DialogMenuType menuType) {
        if (!plugin.getConfigManager().areDialogsEnabled()) {
            player.sendMessage(plugin.getMessageFormatter().error("Vault Survival menus are disabled."));
            return;
        }
        if (!hasVsPermission(player, "vs.menu")) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }

        List<DialogMenuItem> items = visibleItems(player, buildItems(player, menuType));
        boolean opened = false;
        if (plugin.getConfigManager().preferNativeDialogs() && nativeProvider.isAvailable()) {
            try {
                opened = nativeProvider.open(player, menuType, items);
                if (opened) {
                    lastProviderName = nativeProvider.getName();
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Native dialog failed, using fallback: " + t.getMessage());
            }
        }
        if (!opened) {
            fallbackProvider.open(player, menuType, items);
            lastProviderName = fallbackProvider.getName();
        }
    }

    public void runItemCommand(Player player, DialogMenuItem item) {
        if (!isAllowed(player, item)) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }
        if (item.adminSensitive()) {
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                "DIALOG_ACTION", item.command(), "menu_button=" + item.label());
        }
        player.performCommand(item.command());
    }

    public void openInput(Player player, String inputId) {
        DialogInputDefinition input = inputs.get(inputId.toLowerCase(Locale.ROOT));
        if (input == null) {
            player.sendMessage(plugin.getMessageFormatter().error("Unknown menu input: " + inputId));
            return;
        }
        if (!hasVsPermission(player, "vs.menu") || !hasVsPermission(player, input.permission())) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }
        if (input.adminSensitive()) {
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                "DIALOG_INPUT_OPEN", input.commandTemplate(), "input=" + input.id());
        }

        boolean opened = false;
        if (plugin.getConfigManager().preferNativeDialogs() && nativeProvider.isAvailable()) {
            try {
                opened = nativeProvider.openInput(player, input);
                if (opened) {
                    lastProviderName = nativeProvider.getName();
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Native dialog input failed, using fallback: " + t.getMessage());
            }
        }
        if (!opened) {
            fallbackProvider.openInput(player, input);
            lastProviderName = fallbackProvider.getName();
        }
    }

    public List<String> inputIds() {
        return inputs.keySet().stream().sorted().toList();
    }

    private List<DialogMenuItem> buildItems(Player player, DialogMenuType menuType) {
        return switch (menuType) {
            case MAIN -> mainMenu();
            case DISTRICTS -> districtMenu(player);
            case ADMIN -> adminMenu();
            case ADMIN_RANKS -> adminRanksMenu();
            case ADMIN_CASH -> adminCashMenu();
            case ADMIN_NPCS -> adminNpcsMenu();
            case ADMIN_REGIONS -> adminRegionsMenu();
            case ADMIN_DAMAGE -> adminDamageMenu();
            case ADMIN_DISPLAYS -> adminDisplaysMenu();
            case ADMIN_UPDATES -> adminUpdatesMenu();
            case STAFF -> staffMenu();
            case SPAWNCITY -> spawnCityMenu();
            case VWE -> vweMenu();
            case AUCTIONHALL -> auctionHallMenu();
            case VAULTS -> vaultMenu();
        };
    }

    private List<DialogMenuItem> mainMenu() {
        return List.of(
            DialogMenuItem.item("Districts", "Open district tools for your role.", "vsmenu districts", null, Material.MAP),
            DialogMenuItem.adminItem("Admin", "Open staff administration shortcuts.", "vsmenu admin", "vs.admin", Material.REDSTONE_BLOCK),
            DialogMenuItem.adminItem("Staff", "Open staff mode shortcuts.", "vsmenu staff", "vs.staffmode.use", Material.COMPASS),
            DialogMenuItem.item("Spawn City", "Open Spawn City setup and info.", "vsmenu spawncity", "vaultsurvival.spawncity.info", Material.BEACON),
            DialogMenuItem.adminItem("VS-WorldEdit", "Open internal build tools.", "vsmenu vwe", "vaultsurvival.vwe.use", Material.WOODEN_AXE),
            DialogMenuItem.item("Auction Hall", "Open physical Auction Hall shortcuts.", "vsmenu auctionhall", "vs.market.buy", Material.GOLD_INGOT),
            DialogMenuItem.item("Vaults", "Open vault management shortcuts.", "vsmenu vaults", "vs.vault.use", Material.BARREL)
        );
    }

    private List<DialogMenuItem> districtMenu(Player player) {
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.item("My District", "Show your district information.", "district info", null, Material.OAK_SIGN));
        items.add(DialogMenuItem.item("District List", "List official districts.", "district list", null, Material.FILLED_MAP));
        items.add(DialogMenuItem.item("Apply", "Apply for a new district name.", "vsmenu input district_apply", null, Material.WRITABLE_BOOK));

        DistrictData.District district = getDistrict(player);
        if (district != null) {
            if (district.isCouncil(player.getUniqueId())) {
                items.add(DialogMenuItem.item("Invite Member", "Invite an online player to your district.", "vsmenu input district_invite", null, Material.PLAYER_HEAD));
                items.add(DialogMenuItem.item("Set Law", "Toggle a local district law.", "vsmenu input district_law", null, Material.LECTERN));
                items.add(DialogMenuItem.item("Kick Member", "Remove a member from your district.", "vsmenu input district_kick", null, Material.LEATHER_BOOTS));
            }
            if (district.isPolice(player.getUniqueId())) {
                items.add(DialogMenuItem.item("Wanted", "List wanted players in your district.", "crime wanted", null, Material.CROSSBOW));
                items.add(DialogMenuItem.item("Crime Record", "Open a player crime record.", "vsmenu input crime_record", null, Material.PAPER));
                items.add(DialogMenuItem.item("Arrest", "Arrest a wanted nearby player.", "vsmenu input crime_arrest", null, Material.IRON_BARS));
                items.add(DialogMenuItem.item("Fine", "Fine a wanted player.", "vsmenu input crime_fine", null, Material.GOLD_NUGGET));
                items.add(DialogMenuItem.item("Release", "Release a jailed player.", "vsmenu input crime_release", null, Material.TRIPWIRE_HOOK));
                items.add(DialogMenuItem.item("Jailed", "List district jail records.", "crime jailed", null, Material.IRON_DOOR));
            }
            if (district.isMayor(player.getUniqueId())) {
                items.add(DialogMenuItem.adminItem("Set Role", "Set a member district role.", "vsmenu input district_role", null, Material.NAME_TAG));
                items.add(DialogMenuItem.adminItem("Set Jail", "Set district jail to your location.", "crime setjail", null, Material.IRON_BLOCK));
                items.add(DialogMenuItem.adminItem("Disband", "Usage help for district disband command.", "district disband", null, Material.TNT));
            }
            if (district.isTreasurer(player.getUniqueId())) {
                items.add(DialogMenuItem.item("Deposit Treasury", "Deposit physical cash into treasury.", "vsmenu input district_deposit", null, Material.GOLD_NUGGET));
                items.add(DialogMenuItem.item("Withdraw Treasury", "Withdraw physical cash from treasury.", "vsmenu input district_withdraw", null, Material.CHEST));
            }
        }
        items.add(DialogMenuItem.adminItem("Applications", "Review pending applications.", "district applications", "vs.district.admin", Material.PAPER));
        items.add(backItem());
        return items;
    }

    private List<DialogMenuItem> adminMenu() {
        return List.of(
            DialogMenuItem.adminItem("Dashboard", "Open the admin dashboard.", "vsadmin dashboard", "vs.admin", Material.COMMAND_BLOCK),
            DialogMenuItem.adminItem("Analytics", "Open analytics.", "analytics", "vs.admin", Material.COMPARATOR),
            DialogMenuItem.adminItem("Modules", "List loaded modules.", "vsadmin modules", "vs.admin", Material.BOOKSHELF),
            DialogMenuItem.adminItem("Ranks", "Open rank and access tools.", "vsmenu admin_ranks", "vs.admin.rank", Material.NAME_TAG),
            DialogMenuItem.adminItem("Cash", "Open physical cash admin tools.", "vsmenu admin_cash", "vs.cash.admin", Material.GOLD_NUGGET),
            DialogMenuItem.adminItem("NPCs", "Open NPC admin tools.", "vsmenu admin_npcs", "vs.npc.admin", Material.VILLAGER_SPAWN_EGG),
            DialogMenuItem.adminItem("Regions", "Open region admin tools.", "vsmenu admin_regions", "vs.region.admin", Material.WOODEN_AXE),
            DialogMenuItem.adminItem("Damage", "Open damage restoration admin tools.", "vsmenu admin_damage", "vs.damage.admin", Material.CRACKED_STONE_BRICKS),
            DialogMenuItem.adminItem("Displays", "Open Auction Hall display tools.", "vsmenu admin_displays", "vs.display.admin", Material.ITEM_FRAME),
            DialogMenuItem.adminItem("Updates", "Open GitHub update tools.", "vsmenu admin_updates", "vs.update", Material.CHEST_MINECART),
            DialogMenuItem.adminItem("Reload Config", "Reload Vault Survival config.", "vs reload", "vs.admin.reload", Material.REPEATER),
            DialogMenuItem.adminItem("Optimize DB", "Run database checkpoint.", "vsadmin optimize", "vs.admin.reload", Material.ANVIL),
            backItem()
        );
    }

    private List<DialogMenuItem> adminRanksMenu() {
        return List.of(
            DialogMenuItem.adminItem("Rank Info", "View a player's rank data.", "vsmenu input rank_info", "vs.admin.rank", Material.PAPER),
            DialogMenuItem.adminItem("Set Group", "Set/add/remove player access groups.", "vsmenu input rank_set", "vs.admin.rank", Material.NAME_TAG),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminCashMenu() {
        return List.of(
            DialogMenuItem.adminItem("Stats", "Show physical cash statistics.", "cash stats", "vs.cash.admin", Material.COMPARATOR),
            DialogMenuItem.adminItem("Scan", "Scan physical cash in the world.", "cash scan", "vs.cash.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Inspect Player", "Inspect a player's physical money.", "vsmenu input cash_inspect", "vs.cash.admin", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Mint", "Mint physical cash for testing/admin use.", "vsmenu input cash_mint", "vs.cash.admin", Material.GOLD_NUGGET),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminNpcsMenu() {
        return List.of(
            DialogMenuItem.adminItem("List", "List configured NPCs.", "npc list", "vs.npc.admin", Material.BOOK),
            DialogMenuItem.adminItem("Create", "Create an NPC at your location.", "vsmenu input npc_create", "vs.npc.admin", Material.VILLAGER_SPAWN_EGG),
            DialogMenuItem.adminItem("Remove", "Remove an NPC by id.", "vsmenu input npc_remove", "vs.npc.admin", Material.BARRIER),
            DialogMenuItem.adminItem("Move Here", "Move an NPC to you.", "vsmenu input npc_movehere", "vs.npc.admin", Material.ENDER_PEARL),
            DialogMenuItem.adminItem("Skin", "Change an NPC skin name.", "vsmenu input npc_skin", "vs.npc.admin", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Command", "Set an NPC command action.", "vsmenu input npc_command", "vs.npc.admin", Material.COMMAND_BLOCK),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminRegionsMenu() {
        return List.of(
            DialogMenuItem.adminItem("Wand", "Get the region wand.", "region wand", "vs.region.admin", Material.WOODEN_AXE),
            DialogMenuItem.adminItem("List", "List regions.", "region list", "vs.region.admin", Material.FILLED_MAP),
            DialogMenuItem.adminItem("Here", "Show regions at your location.", "region here", "vs.region.admin", Material.COMPASS),
            DialogMenuItem.adminItem("Create", "Create a region from selection.", "vsmenu input region_create", "vs.region.admin", Material.EMERALD_BLOCK),
            DialogMenuItem.adminItem("Delete", "Delete a region.", "vsmenu input region_delete", "vs.region.admin", Material.TNT),
            DialogMenuItem.adminItem("Flag", "Set a region flag.", "vsmenu input region_flag", "vs.region.admin", Material.LEVER),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminDamageMenu() {
        return List.of(
            DialogMenuItem.adminItem("Info", "Show damage info for target block.", "damage info", "vs.damage.use", Material.STONE_BRICKS),
            DialogMenuItem.adminItem("List", "List pending temporary damage.", "damage list", "vs.damage.admin", Material.PAPER),
            DialogMenuItem.adminItem("Restore", "Restore a pending damage record.", "vsmenu input damage_restore", "vs.damage.admin", Material.ANVIL),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminDisplaysMenu() {
        return List.of(
            DialogMenuItem.adminItem("List", "List display slots.", "displays list", "vs.display.admin", Material.ITEM_FRAME),
            DialogMenuItem.adminItem("Refresh", "Refresh display slots.", "displays refresh", "vs.display.admin", Material.CLOCK),
            DialogMenuItem.adminItem("Add", "Add display slot at target frame.", "vsmenu input displays_add", "vs.display.admin", Material.GLOW_ITEM_FRAME),
            DialogMenuItem.adminItem("Remove", "Remove a display slot.", "vsmenu input displays_remove", "vs.display.admin", Material.BARRIER),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> adminUpdatesMenu() {
        return List.of(
            DialogMenuItem.adminItem("Status", "Show updater status.", "vs update status", "vs.update", Material.PAPER),
            DialogMenuItem.adminItem("Check", "Check GitHub releases.", "vs update check", "vs.update", Material.SPYGLASS),
            DialogMenuItem.adminItem("Stage", "Download latest GitHub release jar.", "vs update stage", "vs.update", Material.CHEST_MINECART),
            DialogMenuItem.adminItem("Install", "Install the staged update on next restart.", "vs update install", "vs.update", Material.ANVIL),
            DialogMenuItem.adminItem("Back", "Return to admin menu.", "vsmenu admin", null, Material.ARROW)
        );
    }

    private List<DialogMenuItem> staffMenu() {
        return List.of(
            DialogMenuItem.adminItem("Toggle Staffmode", "Enter or leave staff mode.", "staffmode", "vs.staffmode.use", Material.ENDER_EYE),
            DialogMenuItem.adminItem("Toggle Bypass", "Toggle staffmode bypass if allowed.", "staffmode *", "vs.staffmode.bypass", Material.NETHER_STAR),
            backItem()
        );
    }

    private List<DialogMenuItem> spawnCityMenu() {
        return List.of(
            DialogMenuItem.item("Info", "Show Spawn City information.", "spawncity info", "vaultsurvival.spawncity.info", Material.BEACON),
            DialogMenuItem.item("Teleport", "Teleport to Spawn City.", "spawncity teleport", "vaultsurvival.spawncity.info", Material.ENDER_PEARL),
            DialogMenuItem.item("Regions", "List Spawn City regions.", "spawncity regions", "vaultsurvival.spawncity.info", Material.FILLED_MAP),
            DialogMenuItem.adminItem("Rename", "Rename Spawn City.", "vsmenu input spawncity_setname", "vaultsurvival.spawncity.admin", Material.NAME_TAG),
            DialogMenuItem.adminItem("Set Spawn", "Set Spawn City spawn here.", "spawncity setspawn", "vaultsurvival.spawncity.admin", Material.RESPAWN_ANCHOR),
            DialogMenuItem.adminItem("Set Capital Region", "Save VWE selection as capital.", "spawncity setcapitalregion", "vaultsurvival.spawncity.admin", Material.EMERALD_BLOCK),
            DialogMenuItem.adminItem("Set Auction Hall Region", "Save VWE selection as Auction Hall.", "spawncity setauctionhallregion", "vaultsurvival.spawncity.admin", Material.GOLD_BLOCK),
            DialogMenuItem.adminItem("Set Mint Region", "Save VWE selection as Mint.", "spawncity setmintregion", "vaultsurvival.spawncity.admin", Material.IRON_BLOCK),
            backItem()
        );
    }

    private List<DialogMenuItem> vweMenu() {
        return List.of(
            DialogMenuItem.adminItem("Wand", "Get the VS-WorldEdit wand.", "vwe wand", "vaultsurvival.vwe.use", Material.WOODEN_AXE),
            DialogMenuItem.adminItem("Set Pos 1", "Set selection position 1 here.", "vwe pos1", "vaultsurvival.vwe.use", Material.LIME_WOOL),
            DialogMenuItem.adminItem("Set Pos 2", "Set selection position 2 here.", "vwe pos2", "vaultsurvival.vwe.use", Material.RED_WOOL),
            DialogMenuItem.adminItem("Selection", "Show current selection.", "vwe selection", "vaultsurvival.vwe.use", Material.SPYGLASS),
            DialogMenuItem.adminItem("Fill", "Fill selection with a block.", "vsmenu input vwe_fill", "vaultsurvival.vwe.use", Material.BRICKS),
            DialogMenuItem.adminItem("Replace", "Replace blocks in selection.", "vsmenu input vwe_replace", "vaultsurvival.vwe.use", Material.STONECUTTER),
            DialogMenuItem.adminItem("Walls", "Build walls with a block.", "vsmenu input vwe_walls", "vaultsurvival.vwe.use", Material.COBBLESTONE_WALL),
            DialogMenuItem.adminItem("Outline", "Build an outline with a block.", "vsmenu input vwe_outline", "vaultsurvival.vwe.use", Material.GLASS),
            DialogMenuItem.adminItem("Floor", "Build a floor with a block.", "vsmenu input vwe_floor", "vaultsurvival.vwe.use", Material.SMOOTH_STONE),
            DialogMenuItem.adminItem("Ceiling", "Build a ceiling with a block.", "vsmenu input vwe_ceiling", "vaultsurvival.vwe.use", Material.STONE_SLAB),
            DialogMenuItem.adminItem("Hollow", "Hollow selection with walls.", "vsmenu input vwe_hollow", "vaultsurvival.vwe.use", Material.GLASS),
            DialogMenuItem.adminItem("Cylinder", "Build a vertical cylinder at you.", "vsmenu input vwe_cylinder", "vaultsurvival.vwe.use", Material.COPPER_BLOCK),
            DialogMenuItem.adminItem("Circle", "Build a flat circle at you.", "vsmenu input vwe_circle", "vaultsurvival.vwe.use", Material.CUT_COPPER),
            DialogMenuItem.adminItem("Sphere", "Build a solid sphere at you.", "vsmenu input vwe_sphere", "vaultsurvival.vwe.use", Material.SLIME_BLOCK),
            DialogMenuItem.adminItem("Hollow Sphere", "Build a hollow sphere at you.", "vsmenu input vwe_hsphere", "vaultsurvival.vwe.use", Material.HONEYCOMB_BLOCK),
            DialogMenuItem.adminItem("Line", "Draw a line from pos1 to pos2.", "vsmenu input vwe_line", "vaultsurvival.vwe.use", Material.STRING),
            DialogMenuItem.adminItem("Undo", "Undo last VWE operation.", "vwe undo", "vaultsurvival.vwe.use", Material.ARROW),
            DialogMenuItem.adminItem("Confirm", "Confirm pending operation.", "vwe confirm", "vaultsurvival.vwe.use", Material.EMERALD),
            DialogMenuItem.adminItem("Cancel", "Cancel pending operation.", "vwe cancel", "vaultsurvival.vwe.use", Material.BARRIER),
            backItem()
        );
    }

    private List<DialogMenuItem> auctionHallMenu() {
        return List.of(
            DialogMenuItem.item("Listings", "Browse active listings.", "ah listings", "vs.market.buy", Material.GOLD_INGOT),
            DialogMenuItem.item("Collect", "Collect Auction Locker earnings.", "ah collect", "vs.market.sell", Material.CHEST),
            DialogMenuItem.item("Sell", "List held item inside the Auction Hall.", "vsmenu input ah_sell", "vs.market.sell", Material.WRITABLE_BOOK),
            DialogMenuItem.item("Buy", "Buy an Auction Hall listing by UUID.", "vsmenu input ah_buy", "vs.market.buy", Material.EMERALD),
            DialogMenuItem.item("Cancel", "Cancel one of your Auction Hall listings.", "vsmenu input ah_cancel", "vs.market.sell", Material.BARRIER),
            DialogMenuItem.adminItem("Inspect", "Inspect an Auction Hall listing.", "vsmenu input ah_inspect", "vs.market.admin", Material.SPYGLASS),
            backItem()
        );
    }

    private List<DialogMenuItem> vaultMenu() {
        return List.of(
            DialogMenuItem.item("Info", "Inspect the vault you are looking at.", "vault info", "vs.vault.use", Material.BARREL),
            DialogMenuItem.item("Deposit", "Deposit held cash into a vault.", "vault deposit", "vs.vault.use", Material.HOPPER),
            DialogMenuItem.item("Withdraw", "Withdraw physical cash from the target vault.", "vsmenu input vault_withdraw", "vs.vault.use", Material.DROPPER),
            DialogMenuItem.item("Access", "Add or remove vault access.", "vsmenu input vault_access", "vs.vault.use", Material.IRON_DOOR),
            DialogMenuItem.item("Repair", "Repair the target vault.", "vault repair", "vs.vault.use", Material.ANVIL),
            DialogMenuItem.item("List", "List your vaults.", "vault list", "vs.vault.use", Material.BOOK),
            DialogMenuItem.item("Place", "Place a vault tier.", "vsmenu input vault_place", "vs.vault.place", Material.CHEST),
            DialogMenuItem.adminItem("Inspect", "Inspect a vault UUID.", "vsmenu input vault_inspect", "vs.vault.admin.inspect", Material.SPYGLASS),
            backItem()
        );
    }

    private DialogMenuItem backItem() {
        return DialogMenuItem.item("Back", "Return to the main Vault Survival menu.", "vsmenu", null, Material.ARROW);
    }

    private DistrictData.District getDistrict(Player player) {
        try {
            DistrictService districtService = plugin.getServiceRegistry().get(DistrictService.class);
            return districtService.getPlayerDistrict(player.getUniqueId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<DialogMenuItem> visibleItems(Player player, List<DialogMenuItem> items) {
        return items.stream()
            .filter(item -> isAllowed(player, item))
            .toList();
    }

    private boolean isAllowed(Player player, DialogMenuItem item) {
        return hasVsPermission(player, item.permission());
    }

    private boolean hasVsPermission(Player player, String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (player.hasPermission(permission)) {
            return true;
        }
        try {
            AccessService accessService = plugin.getServiceRegistry().get(AccessService.class);
            return accessService != null && accessService.hasPermission(player.getUniqueId(), permission);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Map<String, DialogInputDefinition> buildInputs() {
        List<DialogInputDefinition> definitions = List.of(
            input("district_apply", "District Application", "Enter the name for your new district.", "District name", "district apply $(value)", null, false),
            input("district_invite", "Invite Member", "Enter the online player name to invite.", "Player", "district invite $(value)", null, false),
            input("district_kick", "Kick Member", "Enter the district member name to remove.", "Player", "district kick $(value)", null, true),
            input("district_law", "Set District Law", "Enter law name and true/false.", "lawName true|false", "district law $(value)", null, true),
            input("district_role", "Set District Role", "Enter member name and role: CITIZEN, POLICE, TREASURER, COUNCIL, MAYOR.", "player role", "district role $(value)", null, true),
            input("district_deposit", "Deposit Treasury", "Enter physical cash amount to deposit.", "Amount", "district deposit $(value)", null, false),
            input("district_withdraw", "Withdraw Treasury", "Enter physical cash amount to withdraw.", "Amount", "district withdraw $(value)", null, true),
            input("crime_record", "Crime Record", "Enter player name for a crime record.", "Player", "crime record $(value)", null, false),
            input("crime_arrest", "Arrest", "Enter the nearby wanted player name.", "Player", "crime arrest $(value)", null, true),
            input("crime_fine", "Fine", "Enter player and amount.", "player amount", "crime fine $(value)", null, true),
            input("crime_release", "Release", "Enter jailed player name.", "Player", "crime release $(value)", null, true),
            input("rank_info", "Rank Info", "Enter player name to inspect rank data.", "Player", "rank $(value) info", "vs.admin.rank", true),
            input("rank_set", "Set Rank", "Enter player, action, and group. Example: Player set owner.", "player set|add|remove group", "rank $(value)", "vs.admin.rank", true),
            input("cash_inspect", "Inspect Cash", "Enter player name to inspect physical cash.", "Player", "cash inspect $(value)", "vs.cash.admin", true),
            input("cash_mint", "Mint Cash", "Enter amount to mint as physical cash.", "Amount", "cash mint $(value)", "vs.cash.admin", true),
            input("npc_create", "Create NPC", "Enter name, skin username, optional action and data.", "name skin [COMMAND|SHOP|MARKET|NONE] [data]", "npc create $(value)", "vs.npc.admin", true),
            input("npc_remove", "Remove NPC", "Enter NPC id to remove.", "NPC id", "npc remove $(value)", "vs.npc.admin", true),
            input("npc_movehere", "Move NPC Here", "Enter NPC id to move to you.", "NPC id", "npc movehere $(value)", "vs.npc.admin", true),
            input("npc_skin", "NPC Skin", "Enter NPC id and skin username.", "id skinUsername", "npc skin $(value)", "vs.npc.admin", true),
            input("npc_command", "NPC Command", "Enter NPC id and command without slash.", "id command", "npc command $(value)", "vs.npc.admin", true),
            input("region_create", "Create Region", "Enter region id/name.", "Region id", "region create $(value)", "vs.region.admin", true),
            input("region_delete", "Delete Region", "Enter region id/name.", "Region id", "region delete $(value)", "vs.region.admin", true),
            input("region_flag", "Set Region Flag", "Enter region, flag, and true/false.", "region flag true|false", "region flag $(value)", "vs.region.admin", true),
            input("damage_restore", "Restore Damage", "Enter damage id to restore.", "Damage id", "damage restore $(value)", "vs.damage.admin", true),
            input("displays_add", "Add Display", "Enter display id/name for the targeted frame.", "Display id", "displays add $(value)", "vs.display.admin", true),
            input("displays_remove", "Remove Display", "Enter display id/name to remove.", "Display id", "displays remove $(value)", "vs.display.admin", true),
            input("spawncity_setname", "Rename Spawn City", "Enter the new Spawn City name.", "City name", "spawncity setname $(value)", "vaultsurvival.spawncity.admin", true),
            input("vwe_fill", "VWE Fill", "Enter block id for fill.", "Block", "vwe fill $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_replace", "VWE Replace", "Enter from-block and to-block.", "fromBlock toBlock", "vwe replace $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_walls", "VWE Walls", "Enter block id for walls.", "Block", "vwe walls $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_outline", "VWE Outline", "Enter block id for outline.", "Block", "vwe outline $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_floor", "VWE Floor", "Enter block id for floor.", "Block", "vwe floor $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_ceiling", "VWE Ceiling", "Enter block id for ceiling.", "Block", "vwe ceiling $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_hollow", "VWE Hollow", "Enter wall block and interior block. Use AIR for empty interior.", "wallBlock airBlock", "vwe hollow $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_cylinder", "VWE Cylinder", "Enter radius, height, and block.", "radius height block", "vwe cylinder $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_circle", "VWE Circle", "Enter radius and block.", "radius block", "vwe circle $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_sphere", "VWE Sphere", "Enter radius and block.", "radius block", "vwe sphere $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_hsphere", "VWE Hollow Sphere", "Enter radius and block.", "radius block", "vwe hsphere $(value)", "vaultsurvival.vwe.use", true),
            input("vwe_line", "VWE Line", "Enter block id for a line from pos1 to pos2.", "Block", "vwe line $(value)", "vaultsurvival.vwe.use", true),
            input("ah_sell", "Auction Sell", "Hold the item and enter price, optional category, optional hours.", "price [category] [hours]", "ah sell $(value)", "vs.market.sell", false),
            input("ah_buy", "Auction Buy", "Enter listing UUID.", "Listing UUID", "ah buy $(value)", "vs.market.buy", false),
            input("ah_cancel", "Auction Cancel", "Enter listing UUID.", "Listing UUID", "ah cancel $(value)", "vs.market.sell", false),
            input("ah_inspect", "Auction Inspect", "Enter listing UUID.", "Listing UUID", "ah inspect $(value)", "vs.market.admin", true),
            input("vault_withdraw", "Vault Withdraw", "Look at a vault and enter amount.", "Amount", "vault withdraw $(value)", "vs.vault.use", false),
            input("vault_access", "Vault Access", "Look at a vault and enter add/remove plus player.", "add|remove player", "vault access $(value)", "vs.vault.use", true),
            input("vault_place", "Vault Place", "Enter tier: small, iron, reinforced, treasury, decoy.", "Tier", "vault place $(value)", "vs.vault.place", false),
            input("vault_inspect", "Vault Inspect", "Enter vault UUID.", "Vault UUID", "vault inspect $(value)", "vs.vault.admin.inspect", true)
        );

        return definitions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            DialogInputDefinition::id,
            definition -> definition
        ));
    }

    private DialogInputDefinition input(String id, String title, String description, String label,
                                        String commandTemplate, String permission, boolean adminSensitive) {
        return new DialogInputDefinition(id, title, description, label, commandTemplate, permission, adminSensitive);
    }
}
