package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.area.CurrentAreaContext;
import com.vaultsurvival.plugin.area.CurrentAreaService;
import com.vaultsurvival.plugin.chat.ChatChannel;
import com.vaultsurvival.plugin.chat.ChatChannelService;
import com.vaultsurvival.plugin.crime.CrimeService;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.currency.CurrencyStats;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.workflow.CivicWorkflowService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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

        if (!canOpenRoute(player, menuType)) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }

        List<DialogMenuItem> items = resolveLockedItems(player, buildItems(player, menuType));
        boolean opened = false;
        if (preferNative(player) && nativeProvider.isAvailable()) {
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
        if (item.locked()) {
            showLocked(player, item.lockedExplanation());
            return;
        }
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

    public void showLocked(Player player, String explanation) {
        String body = explanation == null || explanation.isBlank()
            ? "This menu item is locked."
            : explanation;
        openCustomMenu(player, "Locked", body, List.of(backItem(), homeItem(), closeItem()));
    }

    public void openConfirmation(Player player, String title, String description, String confirmCommand, String cancelRoute) {
        DialogMenuItem confirm = DialogMenuItem.adminItem("Confirm", description, confirmCommand, null, Material.EMERALD);
        DialogMenuItem cancel = DialogMenuItem.item("Cancel", "Return without making changes.",
            "vsmenu " + (cancelRoute == null || cancelRoute.isBlank() ? "main" : cancelRoute), null, Material.BARRIER);
        openCustomMenu(player, title, description, List.of(confirm, cancel, homeItem(), closeItem()));
    }

    private void openCustomMenu(Player player, String title, String body, List<DialogMenuItem> items) {
        DialogMenuType shell = DialogMenuType.MAIN;
        List<DialogMenuItem> resolved = resolveLockedItems(player, items);
        boolean opened = false;
        if (preferNative(player) && nativeProvider.isAvailable()) {
            try {
                opened = nativeProvider.open(player, shell, title, body, resolved);
                if (opened) {
                    lastProviderName = nativeProvider.getName();
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Native dialog failed, using fallback: " + t.getMessage());
            }
        }
        if (!opened) {
            fallbackProvider.open(player, shell, title, body, resolved);
            lastProviderName = fallbackProvider.getName();
        }
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
        if (preferNative(player) && nativeProvider.isAvailable()) {
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
            case MAIN -> mainMenu(player);
            case CURRENT_AREA -> currentAreaMenu(player);
            case SETTINGS -> settingsMenu(player);
            case GUIDES -> guidesMenu(player);
            case PLAYER_JOBS -> playerJobsMenu(player);
            case PLAYER_ORDERS -> playerOrdersMenu(player);
            case PLAYER_RISK -> playerRiskMenu(player);
            case DISTRICTS -> districtMenu(player);
            case DISTRICT_CURRENT -> currentDistrictMenu(player);
            case DISTRICT_LAWS -> activeLawsMenu(player);
            case DISTRICT_PENDING_LAWS -> pendingLawsMenu(player);
            case DISTRICT_ROLES -> lawEditorMenu(player);
            case DISTRICT_MARKET -> districtMarketMenu(player);
            case DISTRICT_MERCHANT -> merchantMenu();
            case DISTRICT_TREASURY -> districtTreasuryMenu(player);
            case DISTRICT_POLICE -> policeDeskMenu(player);
            case DISTRICT_STATION -> districtStationMenu(player);
            case DISTRICT_DIPLOMACY -> diplomacyMenu(player);
            case DISTRICT_JOBS -> districtJobsMenu(player);
            case DISTRICT_DEVELOPMENT -> List.of(
                DialogMenuItem.item("Development Report", "View level and category progress.", "district development", null, Material.NETHER_STAR),
                DialogMenuItem.item("Project Board", "View active district projects.", "district projects", null, Material.WRITABLE_BOOK),
                DialogMenuItem.item("Maintenance", "View district maintenance state.", "district maintenance", null, Material.ANVIL),
                DialogMenuItem.item("Contributors", "View district contributors.", "district contributors", null, Material.PLAYER_HEAD),
                DialogMenuItem.item("Create Project", "Create a project with cash and material requirements.", "vsmenu input district_project_create", null, Material.EMERALD_BLOCK),
                DialogMenuItem.item("Contribute", "Contribute physical cash or held materials.", "vsmenu input district_project_contribute", null, Material.HOPPER),
                DialogMenuItem.item("Kingdom Support", "Request or track staff-assigned development support.", "civic support list", null, Material.BELL),
                backItem("district"), homeItem(), closeItem());
            case MERCHANT_HOME -> merchantMenu();
            case MERCHANT_SHOPS -> merchantShopsMenu(player);
            case MERCHANT_ORDERS -> merchantOrdersMenu(player);
            case MERCHANT_CREATE_ORDER -> merchantCreateOrderMenu(player);
            case MERCHANT_EARNINGS -> merchantEarningsMenu(player);
            case RAIL_HOME -> railMenu();
            case RAIL_STATION -> railStationMenu(player);
            case RAIL_ROUTES -> railRoutesMenu(player, false);
            case RAIL_TICKET -> railRoutesMenu(player, true);
            case RAIL_JOURNEY -> journeyMenu(player);
            case ADMIN -> adminMenu(player);
            case ADMIN_RANKS -> adminRanksMenu();
            case ADMIN_CASH -> adminCashMenu();
            case ADMIN_NPCS -> adminNpcsMenu();
            case ADMIN_REGIONS -> adminRegionsMenu();
            case ADMIN_DAMAGE -> adminDamageMenu();
            case ADMIN_DISPLAYS -> adminDisplaysMenu();
            case ADMIN_UPDATES -> adminUpdatesMenu();
            case STAFF -> staffMenu(player);
            case STAFF_QUICK -> staffQuickMenu();
            case STAFF_PLAYERS -> staffPlayersMenu();
            case STAFF_PLAYER_SEARCH -> staffPlayerSearchMenu();
            case STAFF_PLAYER_LIST -> staffPlayerListMenu();
            case STAFF_PLAYER_PROFILE -> staffPlayerProfileMenu();
            case STAFF_REPORTS -> staffReportsMenu();
            case STAFF_SECURITY -> staffSecurityMenu(player);
            case STAFF_ECONOMY -> staffEconomyMenu(player);
            case STAFF_CASH_TRACE -> staffCashTraceMenu();
            case STAFF_VAULTS -> vaultMenu(player);
            case STAFF_CONTRACTS -> staffContractsMenu(player);
            case STAFF_DISTRICTS -> staffDistrictsMenu();
            case STAFF_POLICE_ABUSE -> staffPoliceAbuseMenu();
            case STAFF_RAIL -> staffRailMenu();
            case STAFF_REGION_DEBUG -> staffRegionDebugMenu();
            case STAFF_SYSTEM -> staffSystemMenu();
            case SPAWNCITY -> spawnCityMenu();
            case VWE -> vweMenu();
            case AUCTIONHALL -> auctionHallMenu();
            case VAULTS -> vaultMenu(player);
        };
    }

    private List<DialogMenuItem> mainMenu(Player player) {
        List<DialogMenuItem> items = new ArrayList<>(List.of(
            DialogMenuItem.item("Current Area", "Show current area information.", "vsmenu current", null, Material.COMPASS),
            DialogMenuItem.item("Rail", "Browse stations, routes, tickets, and journeys.", "vsmenu rail", null, Material.RAIL),
            DialogMenuItem.item("Jobs", "Browse Spawn City and district jobs.", "vsmenu jobs", null, Material.IRON_PICKAXE),
            DialogMenuItem.item("Orders", "Browse merchant orders and Auction Hall listings.", "vsmenu orders", null, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Settings", "Open player settings.", "vsmenu settings", null, Material.COMPARATOR),
            DialogMenuItem.item("Guides", "Open guides.", "vsmenu guides", null, Material.BOOK),
            DialogMenuItem.item("Vaults", "Open vault management shortcuts.", "vsmenu vaults", "vs.vault.use", Material.BARREL)
        ));

        if (plugin.isStaffModeActive(player.getUniqueId())) {
            if (hasVsPermission(player, "vs.staffmode.use")) items.add(DialogMenuItem.adminItem("Staff Tools", "Open the staff control room.", "vsmenu staff", "vs.staffmode.use", Material.COMPASS));
            if (hasVsPermission(player, "vs.admin")) items.add(DialogMenuItem.adminItem("Admin", "Open staff administration tools.", "vsmenu admin", "vs.admin", Material.REDSTONE_BLOCK));
        }

        DistrictData.District district = getDistrict(player);
        if (district == null) {
            items.add(1, DialogMenuItem.item("Start District", "Apply to found a new district.", "vsmenu input district_apply", null, Material.WRITABLE_BOOK));
            items.add(2, DialogMenuItem.item("Join District", "Browse official districts and ask for an invite.", "district list", null, Material.FILLED_MAP));
        } else {
            items.add(1, DialogMenuItem.item("My District", "Open tools for " + district.getName() + ".", "vsmenu district", null, Material.MAP));
            DistrictService service = getDistrictService();
            if (service != null && service.canCreateMerchantNpc(player.getUniqueId(), district)) {
                items.add(2, DialogMenuItem.item("Merchant", "Open your merchant orders and shops.", "vsmenu merchant", null, Material.EMERALD));
            }
        }
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> districtMenu(Player player) {
        List<DialogMenuItem> items = new ArrayList<>();
        DistrictData.District district = getDistrict(player);
        DistrictService districtService = getDistrictService();
        if (district == null) {
            return List.of(
                DialogMenuItem.item("Start District", "Apply to found a new district.", "vsmenu input district_apply", null, Material.WRITABLE_BOOK),
                DialogMenuItem.item("Join District", "Browse official districts and ask for an invite.", "district list", null, Material.FILLED_MAP),
                backItem(), homeItem(), closeItem());
        }
        items.add(DialogMenuItem.item("Current", "Show live district, law, market, job, and station context.", "vsmenu district.current", null, Material.COMPASS));
        items.add(DialogMenuItem.item("My District", "Show your district information.", "district info", null, Material.OAK_SIGN));
        items.add(DialogMenuItem.item("Member Overview", "List district members and roles.", "district members", null, Material.PLAYER_HEAD));
        if (districtService != null && districtService.canManageLaws(player.getUniqueId(), district)) {
            items.add(DialogMenuItem.item("Laws", "View and propose district laws.", "vsmenu district.laws", null, Material.LECTERN));
        }
        if (districtService != null && districtService.canManageTreasury(player.getUniqueId(), district)) {
            items.add(DialogMenuItem.item("Treasury", "Manage physical district treasury cash.", "vsmenu district.treasury", null, Material.GOLD_BLOCK));
        }
        if (districtService != null && districtService.canPolice(player.getUniqueId(), district)) {
            items.add(DialogMenuItem.item("Police", "Open district police tools.", "vsmenu district.police", null, Material.CROSSBOW));
        }
        if (districtService != null && districtService.canRequestStation(player.getUniqueId(), district)) {
            items.add(DialogMenuItem.item("Station", "Manage your district station application.", "vsmenu district.station", null, Material.RAIL));
        }
        if (districtService != null && districtService.canManageDevelopment(player.getUniqueId(), district)) items.add(DialogMenuItem.item("Builder Tools", "Development planning.", "vsmenu district.development", null, Material.BRICKS));
        items.add(DialogMenuItem.item("District Market", "Open the market-zone, shop, and order dashboard.", "vsmenu district.market", null, Material.EMERALD_BLOCK));
        items.add(DialogMenuItem.item("Diplomacy", "View alliances, requests, hostility, and ally chat.", "vsmenu district.diplomacy", null, Material.WHITE_BANNER));
        if (districtService != null && districtService.canCreateMerchantNpc(player.getUniqueId(), district)) items.add(DialogMenuItem.item("Merchant Tools", "Merchant NPC and market tools.", "vsmenu merchant", null, Material.EMERALD));
        if (districtService != null && districtService.canCreateDistrictJob(player.getUniqueId(), district)) items.add(DialogMenuItem.item("District Jobs", "Create and manage district jobs.", "vsmenu district.jobs", null, Material.IRON_PICKAXE));
        items.add(DialogMenuItem.item("District Chat", "Switch to your district member channel.", "chat district", null, Material.WRITABLE_BOOK));
        if (districtService != null && districtService.canPolice(player.getUniqueId(), district)) items.add(DialogMenuItem.item("Police Chat", "Switch to local police channel.", "chat police", null, Material.SHIELD));
        if (districtService != null && (districtService.canCreateMerchantNpc(player.getUniqueId(), district) || districtService.canManageTreasury(player.getUniqueId(), district))) items.add(DialogMenuItem.item("Merchant Chat", "Switch to merchant channel.", "chat merchant", null, Material.EMERALD));

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
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> currentAreaMenu(Player player) {
        CurrentAreaContext context = getCurrentArea(player);
        String districtName = context.hasDistrict() ? context.district().getName() : "None";
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.locked("Area Type", "Current area type.",
            context.areaType().name().replace('_', ' ') + " - " + context.areaName(), Material.COMPASS));
        items.add(DialogMenuItem.locked("District", "Current district.",
            districtName, Material.MAP));
        items.add(DialogMenuItem.locked("Your Status", "Your status in this district.",
            context.playerStatus(), Material.PLAYER_HEAD));
        items.add(DialogMenuItem.locked("Active Flags", "Resolved region flags.",
            summarizeFlags(context), Material.REDSTONE_TORCH));
        items.add(DialogMenuItem.locked("Law Summary", "Current law state.",
            context.lawSummary(), Material.LECTERN));
        items.add(DialogMenuItem.locked("Risk Summary", "Current risk state.",
            context.riskSummary(), Material.CROSSBOW));
        items.add(DialogMenuItem.locked("Market Summary", "Current trade context.",
            context.marketSummary(), Material.EMERALD));
        items.add(DialogMenuItem.locked("Job Summary", "Current local and Spawn City work.",
            context.jobSummary(), Material.IRON_PICKAXE));
        items.add(DialogMenuItem.locked("Station Summary", "Current rail service context.",
            context.stationSummary(), Material.RAIL));

        if (context.hasDistrict()) {
            items.add(DialogMenuItem.item("View Active Laws", "Illegal actions remain possible; active laws create evidence.", "vsmenu district.laws", null, Material.LECTERN));
            items.add(DialogMenuItem.item("View Pending Laws", "View law changes queued for daily activation.", "vsmenu district.pending_laws", null, Material.PAPER));
        }

        if (context.hasDistrict()) {
            items.add(DialogMenuItem.item("District Info", "Show the current district details.",
                "district info " + context.district().getId(), null, Material.OAK_SIGN));
            items.add(DialogMenuItem.item("Request Join", "Send a persistent request to this district's council.",
                "civic join " + context.district().getId(), null, Material.WRITABLE_BOOK));
        } else {
            items.add(DialogMenuItem.locked("District Info", "Show current district details.",
                "No district applies here.", Material.OAK_SIGN));
            items.add(DialogMenuItem.locked("Request Join", "Request district membership.",
                "No district applies here.", Material.WRITABLE_BOOK));
        }

        items.add(DialogMenuItem.item("Report Crime", "Submit category, player/none, and details with this location attached.",
            "vsmenu input player_report", null, Material.BELL));
        items.add(DialogMenuItem.locked("What Happens If I Steal Here?", "Explain local illegal action behavior.",
            "Illegal actions are not automatically blocked. They create evidence if district law/evidence systems are active.",
            Material.IRON_BARS));
        items.add(backItem());
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> playerJobsMenu(Player player) {
        return List.of(
            DialogMenuItem.item("Spawn Job Board", "Open starter and transport jobs.", "spawnjobs", null, Material.BELL),
            DialogMenuItem.item("Spawn Active Jobs", "Show active Spawn City jobs.", "spawnjobs active", null, Material.MAP),
            DialogMenuItem.item("Jobs in Current District", "List active district jobs.", "district jobs", null, Material.IRON_PICKAXE),
            DialogMenuItem.item("My Accepted Jobs", "List your district job claims.", "district job list", null, Material.BOOK),
            DialogMenuItem.item("Deliver Items", "Enter a job id to deliver required items.", "vsmenu input district_job_deliver", null, Material.CHEST),
            DialogMenuItem.item("Submit Manual Job", "Enter a job id for manual submission.", "vsmenu input district_job_submit", null, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Claim Payout", "Claim pending payout locker cash.", "payouts claim", null, Material.GOLD_NUGGET),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> districtJobsMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        DistrictService districtService = getDistrictService();
        boolean canManage = district != null && districtService != null
            && (districtService.canCreateDistrictJob(player.getUniqueId(), district)
                || districtService.canRequestStation(player.getUniqueId(), district));
        return List.of(
            canManage
                ? DialogMenuItem.item("Create Job", "Create a treasury-funded district job.", "vsmenu input district_job_create", null, Material.WRITABLE_BOOK)
                : DialogMenuItem.locked("Create Job", "Create a treasury-funded district job.", "Requires MAYOR, CO_MAYOR, TREASURER, DIPLOMAT, or configured MERCHANT MARKET_SUPPLY.", Material.WRITABLE_BOOK),
            DialogMenuItem.item("Active Jobs", "List active jobs.", "district jobs", null, Material.IRON_PICKAXE),
            DialogMenuItem.item("Submitted Jobs", "Submitted manual jobs.", "vsmenu input district_job_approve", null, Material.PAPER),
            DialogMenuItem.item("Escrow", "Show escrow debug.", "escrow debug", "vs.admin", Material.CHEST),
            DialogMenuItem.item("Completed Jobs", "Browse completed and reviewed claim history.", "civic jobs history", null, Material.EMERALD),
            DialogMenuItem.item("Disputed Jobs", "Open or review district-job disputes.", "civic jobs disputes", null, Material.REDSTONE_TORCH),
            backItem("district"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> activeLawsMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        if (district == null) district = getCurrentArea(player).district();
        if (district == null) {
            return unavailableMenu("Active Laws", "You are not a member of a district.", "district");
        }
        List<DialogMenuItem> items = new ArrayList<>();
        for (DistrictData.LawKey law : DistrictData.LawKey.values()) {
            boolean enabled = Boolean.TRUE.equals(district.getLaws().get(law.name()));
            items.add(DialogMenuItem.locked(law.name(), "Active law state.",
                enabled ? "ACTIVE" : "inactive", enabled ? Material.LIME_DYE : Material.GRAY_DYE));
        }
        items.add(DialogMenuItem.item("Pending Laws", "Show pending law changes.", "vsmenu district.pending_laws", null, Material.PAPER));
        items.add(DialogMenuItem.item("Law Editor", "Open law proposal commands.", "vsmenu district.roles", null, Material.LECTERN));
        items.add(backItem("district"));
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> pendingLawsMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        if (district == null) district = getCurrentArea(player).district();
        if (district == null) {
            return unavailableMenu("Pending Laws", "You are not a member of a district.", "district");
        }
        List<DialogMenuItem> items = new ArrayList<>();
        if (district.getPendingLaws().isEmpty()) {
            items.add(DialogMenuItem.locked("No Pending Laws", "No law changes are queued.",
                "Pending laws apply at the daily law reload.", Material.PAPER));
        } else {
            district.getPendingLaws().forEach((law, enabled) -> items.add(DialogMenuItem.locked(law,
                "Pending law change visible to visitors.", "Will become " + enabled + " at daily reload.",
                enabled ? Material.LIME_DYE : Material.RED_DYE)));
        }
        items.add(DialogMenuItem.item("Active Laws", "Show active district laws.", "vsmenu district.laws", null, Material.LECTERN));
        items.add(backItem("district"));
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> lawEditorMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        DistrictService districtService = getDistrictService();
        if (district == null || districtService == null || !districtService.canManageLaws(player.getUniqueId(), district)) {
            return List.of(
                DialogMenuItem.locked("Law Editor", "Propose district law changes.",
                    "Requires CO_MAYOR or MAYOR in your district.", Material.LECTERN),
                backItem("district"), homeItem(), closeItem()
            );
        }
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.item("Propose Law", "Use: LAW true|false.", "vsmenu input district_law", null, Material.WRITABLE_BOOK));
        items.add(DialogMenuItem.item("Active Laws", "Review active laws.", "vsmenu district.laws", null, Material.LECTERN));
        items.add(DialogMenuItem.item("Pending Laws", "Review queued changes.", "vsmenu district.pending_laws", null, Material.PAPER));
        items.add(DialogMenuItem.locked("Daily Limit", "Configured max pending changes per day.",
            String.valueOf(plugin.getConfigManager().getDistrictMaxLawChangesPerDay()), Material.CLOCK));
        items.add(backItem("district"));
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> policeDeskMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        DistrictService districtService = getDistrictService();
        if (district == null || districtService == null || !districtService.canPolice(player.getUniqueId(), district)) {
            return List.of(
                DialogMenuItem.locked("Police Desk", "Review and handle active evidence.",
                    "Requires POLICE, WARDEN, CO_MAYOR, or MAYOR.", Material.SHIELD),
                backItem("district"), homeItem(), closeItem()
            );
        }
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.item("Evidence List", "List active district evidence.", "crime evidence", null, Material.PAPER));
        items.add(DialogMenuItem.item("Wanted List", "List wanted players.", "crime wanted", null, Material.CROSSBOW));
        items.add(DialogMenuItem.item("Fine Evidence", "Enter evidence id and amount.", "vsmenu input crime_fine", null, Material.GOLD_NUGGET));
        items.add(DialogMenuItem.item("Mark Wanted", "Enter evidence id.", "vsmenu input crime_wanted", null, Material.TARGET));
        items.add(DialogMenuItem.item("Dismiss Evidence", "Enter evidence id.", "vsmenu input crime_dismiss", null, Material.BARRIER));
        CrimeService crimeService = getCrimeService();
        if (crimeService != null) {
            items.add(DialogMenuItem.locked("Evidence Count", "Current district evidence records.",
                String.valueOf(crimeService.getDistrictEvidence(district.getId()).size()), Material.SPYGLASS));
        }
        items.add(backItem("district"));
        items.add(homeItem());
        items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> settingsMenu(Player player) {
        ChatChannel active = getChatChannelService() != null
            ? getChatChannelService().getActiveChannel(player)
            : ChatChannel.GLOBAL;
        CivicWorkflowService.Preferences preferences = getCivicWorkflow() == null
            ? new CivicWorkflowService.Preferences("ALL", "AUTO", "PUBLIC")
            : getCivicWorkflow().preferences(player.getUniqueId());
        return List.of(
            DialogMenuItem.locked("Current Channel", "Your active chat channel.",
                active.displayName(), Material.PAPER),
            DialogMenuItem.item("Global Channel", "Switch active chat to global.", "chat global", null, Material.GLOBE_BANNER_PATTERN),
            DialogMenuItem.item("Local Channel", "Switch active chat to nearby players.", "chat local", null, Material.COMPASS),
            chatItem(player, ChatChannel.DISTRICT, "District Channel", "Switch active chat to district members.", Material.MAP),
            chatItem(player, ChatChannel.POLICE, "Police Channel", "Switch active chat to district police.", Material.SHIELD),
            chatItem(player, ChatChannel.MERCHANT, "Merchant Channel", "Switch active chat to district merchants.", Material.EMERALD),
            chatItem(player, ChatChannel.STAFF, "Staff Channel", "Switch active chat to staff.", Material.REDSTONE_TORCH),
            DialogMenuItem.item("Chat Preview", "Preview your current chat channel.", "chatpreview", null, Material.SPYGLASS),
            DialogMenuItem.item("Notification Settings", "Show current chat settings.", "chatsettings", null, Material.BELL),
            DialogMenuItem.item("Notifications: " + preferences.notifications(), "Control routine and important workflow notifications.", "civic preferences notifications next", null, Material.BELL),
            DialogMenuItem.item("Menu Style: " + preferences.menuStyle(), "Choose automatic, native Paper dialogs, or compact fallback menus.", "civic preferences menu next", null, Material.COMPARATOR),
            DialogMenuItem.item("Privacy: " + preferences.privacy(), "Control public, friend-only, or private profile visibility.", "civic preferences privacy next", null, Material.ENDER_EYE),
            DialogMenuItem.item("Public Profile Preview", "Preview the profile other players can request.", "civic profile " + player.getName(), null, Material.PLAYER_HEAD),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> guidesMenu(Player player) {
        return List.of(
            DialogMenuItem.item("Vaults", "Learn placement, access, deposits, breaches, lockdown, and repair.", "civic guide vaults", null, Material.BARREL),
            DialogMenuItem.item("Districts", "Learn claims, roles, laws, treasury, jobs, diplomacy, stations, and development.", "civic guide districts", null, Material.MAP),
            DialogMenuItem.item("Auction Hall", "Learn physical listings, purchases, cancellation, and locker collection.", "civic guide auction", null, Material.GOLD_INGOT),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> merchantMenu() {
        return List.of(
            DialogMenuItem.item("Show Market Zone", "Display your district's market-zone chunk borders.", "district marketzone borders", null, Material.LIME_DYE),
            DialogMenuItem.item("My Buy Orders", "View your merchant buy orders.", "merchant orders", null, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Create Buy Order", "Create a new buy order (hold the item).", "vsmenu input merchant_create", null, Material.EMERALD),
            DialogMenuItem.item("Active Buy Orders", "Browse all active buy orders.", "merchant order list", null, Material.BOOKSHELF),
            DialogMenuItem.item("Deliver Items", "Deliver items to an active order.", "vsmenu input merchant_deliver", null, Material.CHEST),
            DialogMenuItem.item("Cancel Order", "Cancel your buy order.", "vsmenu input merchant_cancel", null, Material.BARRIER),
            DialogMenuItem.item("Order Storage", "View delivered items in storage.", "vsmenu merchant.earnings", null, Material.BARREL),
            DialogMenuItem.item("My NPC Shops", "Manage your merchant NPC shops.", "vsmenu merchant.shops", null, Material.VILLAGER_SPAWN_EGG),
            DialogMenuItem.item("Claim Payout", "Claim pending payout locker cash.", "payouts claim", null, Material.GOLD_NUGGET),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> merchantCreateOrderMenu(Player player) {
        int limit = Math.max(1, plugin.getConfigManager().getConfig().getInt("merchant.max_active_orders", 10));
        long active = count("SELECT COUNT(*) FROM merchant_orders WHERE merchant_uuid='" + player.getUniqueId()
            + "' AND status IN ('ACTIVE','PARTIALLY_FILLED')");
        return List.of(
            DialogMenuItem.locked("Held Item", "The item in your main hand becomes the order template.",
                player.getInventory().getItemInMainHand().getType().isAir() ? "Hold the item suppliers must deliver." : player.getInventory().getItemInMainHand().getType().name(), Material.ITEM_FRAME),
            DialogMenuItem.locked("Active Order Limit", "Your active order usage.", active + "/" + limit, Material.COMPARATOR),
            DialogMenuItem.item("Create Order", "Enter price, quantity, and optional 'partial'. Escrow is withdrawn atomically.", "vsmenu input merchant_create", null, Material.EMERALD),
            DialogMenuItem.item("My Orders", "Review order state, fills, escrow, and storage.", "vsmenu merchant.orders", null, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Browse Demand", "Compare active buy orders.", "merchant order list", null, Material.BOOKSHELF),
            backItem("merchant"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> merchantShopsMenu(Player player) {
        try {
            com.vaultsurvival.plugin.merchant.shop.MerchantShopService shopService =
                plugin.getServiceRegistry().get(com.vaultsurvival.plugin.merchant.shop.MerchantShopService.class);
            var shops = shopService.getMerchantShops(player.getUniqueId());
            List<DialogMenuItem> items = new ArrayList<>();
            for (var shop : shops) {
                var shopItems = shopService.getShopItems(shop.getId());
                int totalStock = shopItems.stream().mapToInt(com.vaultsurvival.plugin.merchant.shop.MerchantShopData.ShopItem::getStock).sum();
                items.add(DialogMenuItem.locked(
                    shop.getName() + " (#" + shop.getId() + ")",
                    "NPC #" + shop.getNpcId() + " | Items: " + shopItems.size() + " | Stock: " + totalStock,
                    "Use commands to manage: /merchant shop stock|prices " + shop.getId(),
                    Material.EMERALD));
            }
            if (items.isEmpty()) {
                items.add(DialogMenuItem.locked("No Shops", "You have no merchant shops.",
                    "Create one with /merchant shop create <name>.", Material.BARRIER));
            }
            items.add(DialogMenuItem.item("Create Shop NPC", "Create a new merchant shop NPC at your location.",
                "vsmenu input merchant_shop_create", null, Material.VILLAGER_SPAWN_EGG));
            items.add(DialogMenuItem.item("Claim Payout", "Claim pending payout locker cash.",
                "payouts claim", null, Material.GOLD_NUGGET));
            items.add(backItem("merchant"));
            items.add(homeItem());
            items.add(closeItem());
            return items;
        } catch (RuntimeException e) {
            return unavailableMenu("Shops", "Merchant shop service is not available.", "merchant");
        }
    }

    private List<DialogMenuItem> merchantOrdersMenu(Player player) {
        try {
            com.vaultsurvival.plugin.merchant.MerchantOrderService merchantService =
                plugin.getServiceRegistry().get(com.vaultsurvival.plugin.merchant.MerchantOrderService.class);
            var orders = merchantService.getMerchantOrders(player.getUniqueId());
            List<DialogMenuItem> items = new ArrayList<>();
            for (var order : orders) {
                String statusColor = switch (order.getStatus()) {
                    case ACTIVE -> "&a";
                    case PARTIALLY_FILLED -> "&e";
                    case FILLED -> "&6";
                    case CANCELLED -> "&c";
                    case EXPIRED -> "&8";
                    case DISPUTED -> "&4";
                };
                items.add(DialogMenuItem.locked(
                    "Order #" + order.getId(),
                    order.getItemDisplay() + " @ " + order.getPricePerItem() + " each",
                    statusColor + order.getStatus().name() + " | " +
                    order.getFilledQuantity() + "/" + order.getRequiredQuantity() +
                    " | Escrow: " + order.getRemainingEscrow(),
                    Material.WRITABLE_BOOK));
            }
            if (items.isEmpty()) {
                items.add(DialogMenuItem.locked("No Orders", "You have no buy orders.",
                    "Create one with /merchant order create.", Material.BARRIER));
            }
            items.add(backItem("merchant"));
            items.add(homeItem());
            items.add(closeItem());
            return items;
        } catch (RuntimeException e) {
            return unavailableMenu("Merchant Orders", "Merchant order service is not available.", "merchant");
        }
    }

    private List<DialogMenuItem> merchantEarningsMenu(Player player) {
        try {
            com.vaultsurvival.plugin.merchant.MerchantOrderService merchantService =
                plugin.getServiceRegistry().get(com.vaultsurvival.plugin.merchant.MerchantOrderService.class);
            var orders = merchantService.getMerchantOrders(player.getUniqueId());
            List<DialogMenuItem> items = new ArrayList<>();
            for (var order : orders) {
                if (order.getStatus() == com.vaultsurvival.plugin.merchant.MerchantOrderData.OrderStatus.FILLED ||
                    order.getStatus() == com.vaultsurvival.plugin.merchant.MerchantOrderData.OrderStatus.PARTIALLY_FILLED) {
                    items.add(DialogMenuItem.item(
                        "Collect Order #" + order.getId(),
                        "Collect " + (order.getFilledQuantity()) + "x " + order.getItemDisplay() + " from storage.",
                        "merchant order collect " + order.getId(),
                        null, Material.CHEST));
                }
            }
            if (items.isEmpty()) {
                items.add(DialogMenuItem.locked("No Storage", "No orders have items to collect.",
                    "Filled orders will appear here.", Material.BARRIER));
            }
            items.add(DialogMenuItem.item("Claim Payout", "Claim pending payout locker cash.",
                "payouts claim", null, Material.GOLD_NUGGET));
            items.add(backItem("merchant"));
            items.add(homeItem());
            items.add(closeItem());
            return items;
        } catch (RuntimeException e) {
            return unavailableMenu("Earnings", "Merchant order service is not available.", "merchant");
        }
    }

    private List<DialogMenuItem> railMenu() {
        return List.of(
            DialogMenuItem.item("Stations", "Browse active stations and district status.", "vsmenu rail.station", null, Material.RAIL),
            DialogMenuItem.item("Routes", "View destinations, prices, and travel time.", "vsmenu rail.routes", null, Material.MINECART),
            DialogMenuItem.item("Buy Ticket", "Select an active route from its departure platform.", "vsmenu rail.ticket", null, Material.PAPER),
            DialogMenuItem.item("Journey Status", "View your active train journey.", "vsmenu rail.journey", null, Material.CLOCK),
            DialogMenuItem.item("Station Info", "View your district station status.", "district station", null, Material.COMPASS),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> railStationMenu(Player player) {
        try {
            var rail = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.rail.RailService.class);
            List<DialogMenuItem> items = new ArrayList<>();
            for (var station : rail.getAllStations()) {
                items.add(DialogMenuItem.locked(station.getName() + " (#" + station.getId() + ")",
                    "District #" + station.getDistrictId() + " | " + station.getStatus(),
                    rail.getRoutesFrom(station.getId()).size() + " route(s) | revenue " + station.getTotalRevenue() + " | upkeep " + station.getUpkeepCost(),
                    station.getStatus() == com.vaultsurvival.plugin.rail.RailData.StationStatus.ACTIVE ? Material.RAIL : Material.BARRIER));
            }
            if (items.isEmpty()) items.add(DialogMenuItem.locked("No Stations", "No station applications exist yet.", "District diplomats and councils can submit an application.", Material.RAIL));
            items.add(DialogMenuItem.item("My District Station", "Open station application and platform controls.", "vsmenu district.station", null, Material.COMPASS));
            items.add(DialogMenuItem.item("Routes", "Browse active routes.", "vsmenu rail.routes", null, Material.MINECART));
            items.add(backItem("rail")); items.add(homeItem()); items.add(closeItem());
            return items;
        } catch (RuntimeException unavailable) { return unavailableMenu("Stations", "Rail service is unavailable.", "rail"); }
    }

    private List<DialogMenuItem> railRoutesMenu(Player player, boolean ticketMode) {
        try {
            var rail = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.rail.RailService.class);
            List<DialogMenuItem> items = new ArrayList<>();
            for (var route : rail.getAllRoutes()) {
                var from = rail.getStation(route.getFromStationId()); var to = rail.getStation(route.getToStationId());
                String names = (from == null ? "Station #" + route.getFromStationId() : from.getName()) + " -> "
                    + (to == null ? "Station #" + route.getToStationId() : to.getName());
                if (ticketMode && route.getStatus() == com.vaultsurvival.plugin.rail.RailData.RouteStatus.ACTIVE) {
                    items.add(DialogMenuItem.item("Buy Route #" + route.getId(), names + " | price " + route.getTicketPrice()
                        + " | " + (route.getTravelTimeTicks() / 20) + "s", "rail travel " + route.getId(), null, Material.PAPER));
                } else {
                    items.add(DialogMenuItem.locked("Route #" + route.getId(), names,
                        route.getStatus() + " | price " + route.getTicketPrice() + " | tax " + route.getKingdomTaxPercent() + "% | " + (route.getTravelTimeTicks() / 20) + "s",
                        route.getStatus() == com.vaultsurvival.plugin.rail.RailData.RouteStatus.ACTIVE ? Material.MINECART : Material.BARRIER));
                }
            }
            if (items.isEmpty()) items.add(DialogMenuItem.locked("No Routes", "No rail routes are configured.", "Staff can create routes after stations become active.", Material.RAIL));
            if (ticketMode) items.add(DialogMenuItem.item("Enter Route ID", "Open the route-id ticket form.", "vsmenu input rail_travel", null, Material.WRITABLE_BOOK));
            items.add(backItem("rail")); items.add(homeItem()); items.add(closeItem());
            return items;
        } catch (RuntimeException unavailable) { return unavailableMenu(ticketMode ? "Tickets" : "Routes", "Rail service is unavailable.", "rail"); }
    }

    private List<DialogMenuItem> journeyMenu(Player player) {
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            var journey = railService.getActiveJourney(player.getUniqueId());
            if (journey == null) {
                return unavailableMenu("Journey", "You have no active journey. Buy a ticket first!", "rail");
            }
            List<DialogMenuItem> items = new ArrayList<>();
            items.add(DialogMenuItem.locked("Route", journey.getFromStationName() + " → " + journey.getToStationName(),
                "Route #" + journey.getRouteId() + " | Ticket: " + journey.getTicketPrice(), Material.MINECART));
            items.add(DialogMenuItem.locked("Status", journey.getState().name(),
                "State: " + journey.getState().name(), Material.CLOCK));
            if (journey.getState() == com.vaultsurvival.plugin.rail.RailJourneyData.JourneyState.IN_TRANSIT) {
                items.add(DialogMenuItem.locked("Time Remaining", journey.getTimeRemaining(),
                    "The train is moving!", Material.COMPASS));
            }
            if (journey.canBoard()) {
                items.add(DialogMenuItem.item("Board Now", "Board the train!",
                    "station board " + journey.getRouteId(), null, Material.MINECART));
            }
            if (!journey.isEnded()) {
                items.add(DialogMenuItem.item("Cancel Journey", "Cancel your train journey.",
                    "vsmenu locked Journey cancellation is handled automatically.", null, Material.BARRIER));
            }
            items.add(DialogMenuItem.item("Refresh", "Refresh journey status.",
                "station journey", null, Material.CLOCK));
            items.add(backItem("rail"));
            items.add(homeItem());
            items.add(closeItem());
            return items;
        } catch (RuntimeException e) {
            return unavailableMenu("Journey", "Rail service is not available.", "rail");
        }
    }

    private List<DialogMenuItem> districtStationMenu(Player player) {
        try {
            var railService = plugin.getServiceRegistry().get(
                com.vaultsurvival.plugin.rail.RailService.class);
            var station = railService.getStationStatus(player);
            List<DialogMenuItem> items = new ArrayList<>();
            if (station == null) {
                items.add(DialogMenuItem.item("Request Station", "Apply for a district train station.",
                    "vsmenu input station_request", null, Material.RAIL));
                items.add(DialogMenuItem.locked("Requirements", "District must be active and you need MAYOR/CO_MAYOR/DIPLOMAT role.",
                    "Active district + council/diplomat role required.", Material.PAPER));
            }
            items.add(DialogMenuItem.item("View Status", "View your district station status.",
                "district station status", null, Material.COMPASS));
            items.add(DialogMenuItem.item("Set Platform", "Set station platform at your location.",
                "vsmenu input station_setplatform", null, Material.OAK_PLANKS));
            items.add(DialogMenuItem.item("Set Arrival", "Set arrival point at your location.",
                "vsmenu input station_setarrival", null, Material.ENDER_PEARL));
            items.add(DialogMenuItem.item("Submit Application", "Submit station application.",
                "vsmenu input station_request", null, Material.WRITABLE_BOOK));
            items.add(backItem("district"));
            items.add(homeItem());
            items.add(closeItem());
            return items;
        } catch (RuntimeException e) {
            return unavailableMenu("District Station", "Rail service is not available.", "district");
        }
    }

    private List<DialogMenuItem> unavailableMenu(String title, String reason, String backRoute) {
        return List.of(
            DialogMenuItem.locked(title, reason, reason, Material.BARRIER),
            DialogMenuItem.item("Back", "Return to the previous menu.", "vsmenu " + backRoute, null, Material.ARROW),
            homeItem(),
            closeItem()
        );
    }

    private List<DialogMenuItem> adminMenu(Player player) {
        return List.of(
            DialogMenuItem.adminItem("Players", "Open audited player search, lists, and profiles.", "vsmenu staff.players", "vs.admin", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Security", "Open security dashboard.", "vsmenu security", "vs.admin", Material.SHIELD),
            DialogMenuItem.adminItem("Economy", "Open economy dashboard.", "vsmenu economy", "vs.admin", Material.GOLD_INGOT),
            DialogMenuItem.adminItem("Vaults", "Open vault administration shortcuts.", "vsmenu vaults", "vs.vault.admin.inspect", Material.BARREL),
            DialogMenuItem.adminItem("Contracts", "Open contract, escrow, dispute, and payout oversight.", "vsmenu contracts", "vs.admin", Material.WRITABLE_BOOK),
            DialogMenuItem.adminItem("Districts", "Open applications, moderation, support, and teleport tools.", "vsmenu staff.districts", "vs.district.admin", Material.MAP),
            DialogMenuItem.adminItem("Rail Admin", "Manage station applications and routes.", "rail applications", "vs.admin", Material.POWERED_RAIL),
            DialogMenuItem.adminItem("Debug", "Open debug shortcuts.", "vsmenu debug", "vs.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("System", "Open system shortcuts.", "vsmenu system", "vs.admin", Material.COMMAND_BLOCK),
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
            backItem(), homeItem(), closeItem()
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

    private List<DialogMenuItem> staffMenu(Player player) {
        return List.of(
            DialogMenuItem.adminItem("Staff Test World", "Transfer to the fully isolated staff sandbox.", "staffmode test", "vs.staffmode.use", Material.GRASS_BLOCK),
            DialogMenuItem.adminItem("Quick Actions", "Freeze, inspect, debug, and return shortcuts.", "vsmenu staff.quick", "vs.staffmode.use", Material.LIGHTNING_ROD),
            DialogMenuItem.adminItem("Player Inspector", "Search players and open audited profiles.", "vsmenu staff.players", "vs.staffinspect", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Reports", "Open report queues.", "vsmenu staff.reports", "vs.staffmode.use", Material.PAPER),
            DialogMenuItem.adminItem("Security Alerts", "Open security and abuse tools.", "vsmenu security", "vs.staffmode.use", Material.SHIELD),
            DialogMenuItem.adminItem("Economy Tools", "Cash, vault, and contract oversight.", "vsmenu economy", "vs.cash.admin", Material.GOLD_INGOT),
            DialogMenuItem.adminItem("Vault Tools", "Inspect vault administration tools.", "vsmenu vaults", "vs.vault.admin.inspect", Material.BARREL),
            DialogMenuItem.adminItem("District Tools", "Open district moderation tools.", "vsmenu staff.districts", "vs.district.admin", Material.MAP),
            DialogMenuItem.adminItem("Contract Oversight", "Inspect contracts, escrow, and payouts.", "vsmenu contracts", "vs.admin", Material.WRITABLE_BOOK),
            DialogMenuItem.adminItem("Region Debug", "Inspect this location's regions and flags.", "vsmenu debug", "vs.region.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("System Tools", "Modules, config, updates, and diagnostics.", "vsmenu system", "vs.staffmode.use", Material.COMPARATOR),
            DialogMenuItem.adminItem("Leave Staffmode", "Return to normal player mode.", "staffmode", "vs.staffmode.use", Material.ENDER_EYE),
            backItem(), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffPlayersMenu() {
        return List.of(
            DialogMenuItem.adminItem("Search Player", "Search name, partial name, UUID, district, or rank.", "vsmenu input staffinspect_search", "vs.staffinspect", Material.SPYGLASS),
            DialogMenuItem.adminItem("Open Profile", "Open an audited staff profile for a player.", "vsmenu input staffinspect_profile", "vs.staffinspect", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Online Players", "Show online players with pagination.", "staffinspect online", "vs.staffinspect", Material.LIME_DYE),
            DialogMenuItem.adminItem("Recently Joined", "Show recently active players with pagination.", "staffinspect recent", "vs.staffinspect", Material.CLOCK),
            DialogMenuItem.adminItem("Wanted Players", "Show wanted players with pagination.", "staffinspect wanted", "vs.staffinspect", Material.CROSSBOW),
            DialogMenuItem.adminItem("Frozen Players", "Show players currently frozen by staff.", "staffinspect frozen", "vs.staffinspect", Material.ICE),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffPlayerSearchMenu() {
        return List.of(
            DialogMenuItem.adminItem("Search", "Search partial name, UUID prefix, district, or rank.", "vsmenu input staffinspect_search", "vs.staffinspect", Material.SPYGLASS),
            DialogMenuItem.adminItem("Open Exact Profile", "Open a player by exact name or UUID.", "vsmenu input staffinspect_profile", "vs.staffinspect", Material.PLAYER_HEAD),
            DialogMenuItem.item("Player Lists", "Browse online, recent, wanted, and frozen players.", "vsmenu staff.player.list", null, Material.BOOK),
            backItem("staff.players"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffPlayerListMenu() {
        return List.of(
            DialogMenuItem.adminItem("Online Players", "Open paginated online players.", "staffinspect online", "vs.staffinspect", Material.LIME_DYE),
            DialogMenuItem.adminItem("Recently Active", "Open paginated recently active players.", "staffinspect recent", "vs.staffinspect", Material.CLOCK),
            DialogMenuItem.adminItem("Wanted Players", "Open players with active wanted records.", "staffinspect wanted", "vs.staffinspect", Material.CROSSBOW),
            DialogMenuItem.adminItem("Frozen Players", "Open the current movement-freeze set.", "staffinspect frozen", "vs.staffinspect", Material.ICE),
            DialogMenuItem.item("Search", "Search across all known players.", "vsmenu staff.player.search", null, Material.SPYGLASS),
            backItem("staff.players"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffPlayerProfileMenu() {
        return List.of(
            DialogMenuItem.adminItem("Open Profile", "Enter a player name or UUID for an audited profile.", "vsmenu input staffinspect_profile", "vs.staffinspect", Material.PLAYER_HEAD),
            DialogMenuItem.item("Search First", "Find a player by partial name, district, or rank.", "vsmenu staff.player.search", null, Material.SPYGLASS),
            backItem("staff.players"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffReportsMenu() {
        return List.of(
            metric("Open Reports", count("SELECT COUNT(*) FROM player_reports WHERE status IN ('OPEN','CLAIMED')"), Material.PAPER),
            metric("Unclaimed Reports", count("SELECT COUNT(*) FROM player_reports WHERE status='OPEN'"), Material.BELL),
            metric("Police Abuse", count("SELECT COUNT(*) FROM player_reports WHERE category='POLICE_ABUSE' AND status IN ('OPEN','CLAIMED')"), Material.IRON_BARS),
            DialogMenuItem.adminItem("Open Queue", "Claim or resolve all player reports.", "civic reports ALL", "vs.staffmode.use", Material.PAPER),
            DialogMenuItem.adminItem("Police Abuse Queue", "Open the filtered high-priority queue.", "civic reports POLICE_ABUSE", "vs.staffmode.use", Material.IRON_BARS),
            backItem("staff"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffCashTraceMenu() {
        return List.of(
            metric("Tracked Cash Items", count("SELECT COUNT(*) FROM cash_items"), Material.GOLD_NUGGET),
            metric("Cash Transactions", count("SELECT COUNT(*) FROM cash_transactions"), Material.BOOK),
            metric("Invalidated Cash", count("SELECT COUNT(*) FROM cash_items WHERE state='INVALIDATED'"), Material.REDSTONE),
            DialogMenuItem.adminItem("Trace Cash UUID", "Enter a physical cash UUID.", "vsmenu input cash_trace", "vs.cash.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Inspect Player", "Inspect carried physical cash for a player.", "vsmenu input cash_inspect", "vs.cash.admin", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Global Scan", "Scan tracked physical cash state.", "cash scan", "vs.cash.admin", Material.COMPASS),
            backItem("economy"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffDistrictsMenu() {
        return List.of(
            metric("Active Districts", count("SELECT COUNT(*) FROM districts WHERE status='ACTIVE'"), Material.MAP),
            metric("Applications", count("SELECT COUNT(*) FROM districts WHERE status='APPLICATION'"), Material.PAPER),
            metric("Open Join Requests", count("SELECT COUNT(*) FROM district_join_requests WHERE status='OPEN'"), Material.PLAYER_HEAD),
            metric("Kingdom Support", count("SELECT COUNT(*) FROM kingdom_support_requests WHERE status IN ('OPEN','ASSIGNED')"), Material.BELL),
            DialogMenuItem.adminItem("District Applications", "Review pending district applications.", "district applications", "vs.district.admin", Material.WRITABLE_BOOK),
            DialogMenuItem.adminItem("District List", "List every official district.", "district list", "vs.district.admin", Material.FILLED_MAP),
            DialogMenuItem.adminItem("Teleport to District", "Enter an id or exact district name.", "vsmenu input district_staff_tp", "vs.district.teleport", Material.ENDER_PEARL),
            DialogMenuItem.adminItem("Kingdom Support Queue", "Assign and complete district support requests.", "civic support list", "vs.staffmode.use", Material.EMERALD),
            DialogMenuItem.adminItem("Police Abuse", "Review district-police abuse reports.", "vsmenu staff.police_abuse", "vs.staffmode.use", Material.IRON_BARS),
            backItem("staff"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> staffPoliceAbuseMenu() {
        return List.of(
            metric("Open Police-Abuse Reports", count("SELECT COUNT(*) FROM player_reports WHERE category='POLICE_ABUSE' AND status IN ('OPEN','CLAIMED')"), Material.IRON_BARS),
            metric("Police-Related Alerts", count("SELECT COUNT(*) FROM staff_alerts WHERE alert_type LIKE '%POLICE%' AND status IN ('OPEN','CLAIMED')"), Material.BELL),
            DialogMenuItem.adminItem("Review Reports", "Claim and resolve police-abuse reports.", "civic reports POLICE_ABUSE", "vs.staffmode.use", Material.PAPER),
            DialogMenuItem.adminItem("Security Alerts", "Review persistent police-related operational alerts.", "staffalerts list POLICE", "vs.staff.alerts", Material.REDSTONE_TORCH),
            DialogMenuItem.adminItem("Player Inspector", "Inspect the involved officer or reporter.", "vsmenu staff.player.search", "vs.staffinspect", Material.PLAYER_HEAD),
            backItem("security"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> playerOrdersMenu(Player player) {
        long availableOrders = count("SELECT COUNT(*) FROM merchant_orders WHERE status IN ('ACTIVE','PARTIALLY_FILLED')");
        long auctionListings = count("SELECT COUNT(*) FROM auction_listings WHERE status='ACTIVE'");
        long ownOrders = count("SELECT COUNT(*) FROM merchant_orders WHERE merchant_uuid='" + player.getUniqueId() + "'");
        return List.of(
            metric("Available Buy Orders", availableOrders, Material.CHEST),
            metric("Auction Listings", auctionListings, Material.GOLD_INGOT),
            metric("Your Merchant Orders", ownOrders, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Browse Buy Orders", "Deliver requested items for physical-cash payouts.", "merchant order list", null, Material.BOOKSHELF),
            DialogMenuItem.item("My Merchant Orders", "View status, escrow, fills, and storage.", "vsmenu merchant.orders", null, Material.EMERALD),
            DialogMenuItem.item("Create Buy Order", "Hold the target item and enter price and quantity.", "vsmenu merchant.create_order", null, Material.WRITABLE_BOOK),
            DialogMenuItem.item("Auction Hall Listings", "Browse active player listings.", "ah listings", "vs.market.buy", Material.GOLD_BLOCK),
            DialogMenuItem.item("Claim Payouts", "Collect pending merchant and job payouts.", "payouts claim", null, Material.GOLD_NUGGET),
            backItem(), homeItem(), closeItem());
    }

    private List<DialogMenuItem> playerRiskMenu(Player player) {
        CurrentAreaContext context = getCurrentArea(player);
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.locked("Local Risk", context.areaName(), context.riskSummary(), Material.CROSSBOW));
        items.add(DialogMenuItem.locked("Law Context", "Active law state at this location.", context.lawSummary(), Material.LECTERN));
        items.add(DialogMenuItem.locked("Region Rules", "Effective gameplay flags.", summarizeFlags(context), Material.REDSTONE_TORCH));
        items.add(DialogMenuItem.item("My Crime Record", "View your wanted, evidence, bounty, and jail state.", "crime record " + player.getName(), null, Material.PAPER));
        items.add(DialogMenuItem.item("Submit Report", "Attach this location to a persistent staff report.", "vsmenu input player_report", null, Material.BELL));
        if (context.hasDistrict()) items.add(DialogMenuItem.item("District Police Desk", "Open local evidence and wanted tools when your role allows it.", "vsmenu district.police", null, Material.SHIELD));
        items.add(backItem()); items.add(homeItem()); items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> currentDistrictMenu(Player player) {
        CurrentAreaContext context = getCurrentArea(player);
        DistrictData.District district = context.district() != null ? context.district() : getDistrict(player);
        if (district == null) return List.of(
            DialogMenuItem.locked("No District", "No active district applies to you or this location.", "Browse /district list or start an application.", Material.MAP),
            DialogMenuItem.item("Browse Districts", "List all official districts.", "district list", null, Material.FILLED_MAP),
            backItem("district"), homeItem(), closeItem());
        DistrictService districtService = getDistrictService();
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.locked(district.getName(), "District #" + district.getId(), district.getStatus() + " | " + district.getMemberCount() + " member(s) | your roles " + (districtService == null ? district.getRoles(player.getUniqueId()) : districtService.getDistrictRoles(player.getUniqueId(), district)), Material.MAP));
        items.add(DialogMenuItem.locked("Treasury", "Physical district treasury balance.", String.valueOf(district.getTreasuryBalance()), Material.GOLD_BLOCK));
        items.add(DialogMenuItem.locked("Market", "Live current-area market context.", context.marketSummary(), Material.EMERALD));
        items.add(DialogMenuItem.locked("Jobs", "Live current-area job context.", context.jobSummary(), Material.IRON_PICKAXE));
        items.add(DialogMenuItem.locked("Station", "Live rail context.", context.stationSummary(), Material.RAIL));
        items.add(DialogMenuItem.item("Active Laws", "Review active district laws.", "vsmenu district.laws", null, Material.LECTERN));
        items.add(DialogMenuItem.item("Member Overview", "List members and role assignments.", "district members", null, Material.PLAYER_HEAD));
        if (district.isCouncil(player.getUniqueId())) items.add(DialogMenuItem.item("Join Requests", "Review persistent membership requests.", "civic joins", null, Material.PAPER));
        items.add(backItem("district")); items.add(homeItem()); items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> districtMarketMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        if (district == null) return unavailableMenu("District Market", "Join a district to open its market dashboard.", "district");
        return List.of(
            metric("District Shops", count("SELECT COUNT(*) FROM merchant_shops WHERE district_id=" + district.getId()), Material.VILLAGER_SPAWN_EGG),
            metric("Network Buy Orders", count("SELECT COUNT(*) FROM merchant_orders WHERE status IN ('ACTIVE','PARTIALLY_FILLED')"), Material.WRITABLE_BOOK),
            DialogMenuItem.item("Show Market Border", "Display market-zone chunk borders.", "district marketzone borders", null, Material.LIME_DYE),
            DialogMenuItem.item("Merchant Dashboard", "Manage shops, orders, storage, and payouts.", "vsmenu district.merchant", null, Material.EMERALD),
            DialogMenuItem.item("Browse Buy Orders", "Browse and fulfill active orders.", "merchant order list", null, Material.BOOKSHELF),
            DialogMenuItem.item("Market Supply Jobs", "Browse active district supply jobs.", "district jobs", null, Material.CHEST),
            backItem("district"), homeItem(), closeItem());
    }

    private List<DialogMenuItem> districtTreasuryMenu(Player player) {
        DistrictData.District district = getDistrict(player); DistrictService service = getDistrictService();
        if (district == null) return unavailableMenu("District Treasury", "Join a district to view treasury controls.", "district");
        boolean allowed = service != null && service.canManageTreasury(player.getUniqueId(), district);
        List<DialogMenuItem> items = new ArrayList<>();
        items.add(DialogMenuItem.locked("Balance", "Physical cash stored in the district treasury.", String.valueOf(district.getTreasuryBalance()), Material.GOLD_BLOCK));
        items.add(allowed ? DialogMenuItem.item("Deposit", "Deposit physical cash.", "vsmenu input district_deposit", null, Material.HOPPER)
            : DialogMenuItem.locked("Deposit", "Deposit physical cash.", "Requires MAYOR, CO_MAYOR, or TREASURER.", Material.HOPPER));
        items.add(allowed ? DialogMenuItem.adminItem("Withdraw", "Withdraw physical treasury cash.", "vsmenu input district_withdraw", null, Material.DROPPER)
            : DialogMenuItem.locked("Withdraw", "Withdraw physical treasury cash.", "Requires MAYOR, CO_MAYOR, or TREASURER.", Material.DROPPER));
        items.add(DialogMenuItem.item("Development Projects", "View treasury-backed development work.", "vsmenu district.development", null, Material.BRICKS));
        items.add(DialogMenuItem.item("District Jobs", "View treasury-funded job escrow.", "vsmenu district.jobs", null, Material.IRON_PICKAXE));
        items.add(backItem("district")); items.add(homeItem()); items.add(closeItem());
        return items;
    }

    private List<DialogMenuItem> diplomacyMenu(Player player) {
        DistrictData.District district = getDistrict(player);
        if (district == null) return unavailableMenu("Diplomacy", "Join a district to use diplomacy.", "district");
        return List.of(
            metric("Recorded Relations", count("SELECT COUNT(*) FROM district_diplomacy WHERE district_a=" + district.getId() + " OR district_b=" + district.getId()), Material.WHITE_BANNER),
            metric("Allies", count("SELECT COUNT(*) FROM district_diplomacy WHERE relation='ALLIED' AND (district_a=" + district.getId() + " OR district_b=" + district.getId() + ")"), Material.LIME_BANNER),
            metric("Pending Alliances", count("SELECT COUNT(*) FROM district_diplomacy WHERE relation='PENDING_ALLIANCE' AND (district_a=" + district.getId() + " OR district_b=" + district.getId() + ")"), Material.PAPER),
            DialogMenuItem.item("Open Diplomacy Board", "View relations and perform role-gated transitions.", "civic diplomacy list", null, Material.MAP),
            DialogMenuItem.item("Ally Chat", "Message your district and accepted allied districts.", "chat ally", null, Material.WRITABLE_BOOK),
            backItem("district"), homeItem(), closeItem());
    }

    /** Opens a command result as a follow-up dialog instead of dumping it into chat. */
    public void openResult(Player player, String title, String body, List<DialogMenuItem> items) {
        openCustomMenu(player, title, body, items);
    }

    private List<DialogMenuItem> staffQuickMenu() {
        return List.of(
            DialogMenuItem.adminItem("Freeze Nearest Player", "Freeze the nearest online player.", "staffinspect freeze", "vs.staffinspect.freeze", Material.ICE),
            DialogMenuItem.adminItem("Inspect Player", "Search a player and open their profile.", "vsmenu input staffinspect_profile", "vs.staffinspect", Material.PLAYER_HEAD),
            DialogMenuItem.adminItem("Open Reports", "Open the persistent claim and resolution queue.", "civic reports ALL", "vs.staffmode.use", Material.PAPER),
            DialogMenuItem.adminItem("Security Alerts", "Open the persistent actionable alert queue.", "staffalerts list", "vs.staff.alerts", Material.BELL),
            DialogMenuItem.adminItem("Region Debug Here", "Show regions and flags at your location.", "region here", "vs.region.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Teleport to Last Alert", "Teleport to the newest open alert with a location.", "staffalerts last", "vs.staff.alerts", Material.ENDER_PEARL),
            DialogMenuItem.adminItem("Return to Previous Location", "Pop the audited staff teleport history stack.", "staffalerts return", "vs.staff.alerts", Material.COMPASS),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffSecurityMenu(Player player) {
        return List.of(
            metric("Invalid Cash", count("SELECT COUNT(*) FROM cash_items WHERE state='INVALIDATED'"), Material.REDSTONE),
            metric("Cash Duplicate Signals", count("SELECT COUNT(*) FROM admin_audit_log WHERE action_type LIKE '%DUPLICATE%'"), Material.TRIPWIRE_HOOK),
            metric("Vault Breaches", count("SELECT COUNT(*) FROM vault_breaches WHERE success=1"), Material.TNT),
            metric("Contract Abuse Signals", count("SELECT COUNT(*) FROM contract_disputes"), Material.WRITABLE_BOOK),
            metric("High Risk Players", count("SELECT COUNT(*) FROM wanted_players"), Material.CROSSBOW),
            DialogMenuItem.adminItem("Alert Queue", "Claim, teleport, and resolve persistent alerts.", "staffalerts list", "vs.staff.alerts", Material.BELL),
            DialogMenuItem.adminItem("Anti-Xray", "Verify or update actual Paper anti-xray configuration.", "staffalerts antixray", "vs.staff.alerts", Material.DEEPSLATE_DIAMOND_ORE),
            DialogMenuItem.adminItem("Storage Discovery", "Scan loaded nearby container tile entities and mark them.", "staffalerts storage 64", "vs.staff.alerts", Material.CHEST),
            DialogMenuItem.adminItem("Movement and Combat Alerts", "Review 24-hour movement and combat scores.", "staffalerts scores MOVEMENT", "vs.staff.alerts", Material.LEATHER_BOOTS),
            DialogMenuItem.adminItem("Inventory Exploit Alerts", "Review oversized-stack, injection, and click-rate scores.", "staffalerts scores INVENTORY", "vs.staff.alerts", Material.HOPPER),
            DialogMenuItem.adminItem("Police Abuse", "Open the filtered police-abuse report queue.", "vsmenu staff.police_abuse", "vs.staffmode.use", Material.IRON_BARS),
            DialogMenuItem.adminItem("Region Debug", "Open region debug shortcuts.", "vsmenu debug", "vs.region.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Reports", "Open player report queues.", "vsmenu staff.reports", "vs.staffmode.use", Material.PAPER),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffEconomyMenu(Player player) {
        CurrencyStats stats = currencyStats();
        return List.of(
            metric("Physical Cash Total", stats.getTotalEverCreated(), Material.GOLD_BLOCK),
            metric("Cash In Inventories", stats.getTotalCashInCirculation(), Material.GOLD_NUGGET),
            metric("Cash In Vaults", stats.getTotalCashInVaults(), Material.BARREL),
            metric("Cash In Lockers", stats.getTotalCashInLockers(), Material.ENDER_CHEST),
            metric("Cash In Treasuries", stats.getTotalCashInTreasuries(), Material.GOLD_INGOT),
            metric("Cash In Escrow", stats.getTotalCashInEscrow(), Material.CHEST),
            metric("Invalid Cash", stats.getTotalCashInvalidated(), Material.REDSTONE),
            metric("Admin Cash Events", count("SELECT COUNT(*) FROM admin_audit_log WHERE action_type='CASH_CREATE'"), Material.COMMAND_BLOCK),
            DialogMenuItem.adminItem("Cash Admin", "Open cash admin shortcuts.", "vsmenu admin_cash", "vs.cash.admin", Material.GOLD_NUGGET),
            DialogMenuItem.adminItem("Cash Trace", "Open cash lookup and trail tools.", "vsmenu staff.cash_trace", "vs.cash.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Vaults", "Open vault shortcuts.", "vsmenu vaults", "vs.vault.admin.inspect", Material.BARREL),
            DialogMenuItem.adminItem("Contracts", "Open contract and escrow tools.", "vsmenu contracts", "vs.admin", Material.WRITABLE_BOOK),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffContractsMenu(Player player) {
        return List.of(
            metric("Merchant Orders", count("SELECT COUNT(*) FROM merchant_orders"), Material.EMERALD),
            metric("District Jobs", count("SELECT COUNT(*) FROM district_jobs"), Material.IRON_PICKAXE),
            metric("Spawn Jobs", count("SELECT COUNT(*) FROM spawn_city_jobs"), Material.MAP),
            metric("Disputed Contracts", count("SELECT COUNT(*) FROM contract_disputes"), Material.REDSTONE_TORCH),
            metric("Escrow Records", count("SELECT COUNT(*) FROM contract_escrows WHERE status='LOCKED'"), Material.CHEST),
            metric("Cancelled High Value", count("SELECT COUNT(*) FROM contracts WHERE status='CANCELLED' AND amount >= 10000"), Material.GOLD_BLOCK),
            DialogMenuItem.adminItem("Active Contracts", "Show contract debug counts.", "contract debug", "vs.admin", Material.WRITABLE_BOOK),
            DialogMenuItem.adminItem("Escrow Audit", "Show contract audit log.", "contract audit", "vs.admin", Material.SPYGLASS),
            DialogMenuItem.adminItem("Escrow Debug", "List escrow records.", "escrow debug", "vs.admin", Material.CHEST),
            DialogMenuItem.adminItem("Payout Lockers", "Open payout locker command.", "payouts", "vs.admin", Material.ENDER_CHEST),
            DialogMenuItem.adminItem("Disputed Contracts", "Show dispute counts.", "contract debug", "vs.admin", Material.REDSTONE_TORCH),
            DialogMenuItem.adminItem("Suspicious Payouts", "Review high-value and burst-scored payout records.",
                "staffalerts payouts", "vs.staff.alerts", Material.GOLD_BLOCK),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffRegionDebugMenu() {
        return List.of(
            DialogMenuItem.adminItem("Regions Here", "Show regions at your location.", "region here", "vs.region.admin", Material.COMPASS),
            DialogMenuItem.adminItem("Region List", "List regions.", "region list", "vs.region.admin", Material.FILLED_MAP),
            DialogMenuItem.adminItem("Damage Info", "Show damage info for target block.", "damage info", "vs.damage.use", Material.STONE_BRICKS),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffRailMenu() {
        return List.of(
            DialogMenuItem.adminItem("Pending Applications", "Review pending station applications.",
                "rail applications", "vs.admin", Material.PAPER),
            DialogMenuItem.adminItem("All Stations", "List all train stations.",
                "rail stations", "vs.admin", Material.RAIL),
            DialogMenuItem.adminItem("All Routes", "View all rail routes.",
                "rail routes", "vs.admin", Material.MINECART),
            DialogMenuItem.adminItem("Suspended Stations", "Review suspended stations.",
                "rail stations", "vs.admin", Material.BARRIER),
            DialogMenuItem.adminItem("Revenue Logs", "Aggregate purchased tickets and gross revenue by route.",
                "civic rail revenue", "vs.staffmode.use", Material.GOLD_NUGGET),
            DialogMenuItem.adminItem("Travel Logs", "View recent ticket, boarding, departure, arrival, and cancellation events.",
                "civic rail travel", "vs.staffmode.use", Material.BOOK),
            backItem("staff"), homeItem(), closeItem()
        );
    }

    private List<DialogMenuItem> staffSystemMenu() {
        return List.of(
            DialogMenuItem.adminItem("Modules", "List loaded modules.", "vsadmin modules", "vs.admin", Material.BOOKSHELF),
            DialogMenuItem.adminItem("Analytics", "Open analytics.", "analytics", "vs.admin", Material.COMPARATOR),
            DialogMenuItem.adminItem("Reload Config", "Reload Vault Survival config.", "vs reload", "vs.admin.reload", Material.REPEATER),
            DialogMenuItem.adminItem("Optimize DB", "Run database checkpoint.", "vsadmin optimize", "vs.admin.reload", Material.ANVIL),
            backItem("staff"), homeItem(), closeItem()
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

    private List<DialogMenuItem> vaultMenu(Player player) {
        List<DialogMenuItem> items = new ArrayList<>();
        if (plugin.isStaffModeActive(player.getUniqueId()) && hasVsPermission(player, "vs.vault.admin.inspect")) {
            items.add(metric("All Vaults", count("SELECT COUNT(*) FROM vaults"), Material.BARREL));
            items.add(metric("High Value Vaults", count("SELECT COUNT(*) FROM (SELECT location_id FROM cash_items WHERE state='IN_VAULT' GROUP BY location_id HAVING SUM(amount) >= 10000)"), Material.GOLD_BLOCK));
            items.add(metric("Breached Vaults", count("SELECT COUNT(*) FROM vault_breaches WHERE success=1"), Material.TNT));
            items.add(metric("Lockdown Vaults", count("SELECT COUNT(*) FROM vaults WHERE is_locked_down=1"), Material.IRON_BARS));
            items.add(DialogMenuItem.adminItem("Vault Audit", "Inspect a vault by UUID.", "vsmenu input vault_inspect", "vs.vault.admin.inspect", Material.SPYGLASS));
        }
        items.addAll(List.of(
            DialogMenuItem.item("Info", "Inspect the vault you are looking at.", "vault info", "vs.vault.use", Material.BARREL),
            DialogMenuItem.item("Deposit", "Deposit held cash into a vault.", "vault deposit", "vs.vault.use", Material.HOPPER),
            DialogMenuItem.item("Withdraw", "Withdraw physical cash from the target vault.", "vsmenu input vault_withdraw", "vs.vault.use", Material.DROPPER),
            DialogMenuItem.item("Access", "Add or remove vault access.", "vsmenu input vault_access", "vs.vault.use", Material.IRON_DOOR),
            DialogMenuItem.item("Repair", "Repair the target vault.", "vault repair", "vs.vault.use", Material.ANVIL),
            DialogMenuItem.item("List", "List your vaults.", "vault list", "vs.vault.use", Material.BOOK),
            DialogMenuItem.item("Place", "Place a vault tier.", "vsmenu input vault_place", "vs.vault.place", Material.CHEST),
            DialogMenuItem.adminItem("Inspect", "Inspect a vault UUID.", "vsmenu input vault_inspect", "vs.vault.admin.inspect", Material.SPYGLASS),
            backItem()));
        return items;
    }

    private CurrencyStats currencyStats() {
        try { return plugin.getServiceRegistry().get(CurrencyService.class).getStats(); }
        catch (RuntimeException ignored) { return new CurrencyStats(0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }

    private DialogMenuItem metric(String label, long value, Material material) {
        return DialogMenuItem.locked(label, "Live count/value: " + value, "Read-only dashboard metric.", material);
    }

    private long count(String sql) {
        try (Connection connection = plugin.getDatabase().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (Exception ignored) { return 0L; }
    }

    private DialogMenuItem backItem() {
        return DialogMenuItem.item("Back", "Return to the main Vault Survival menu.", "vsmenu", null, Material.ARROW);
    }

    private DialogMenuItem backItem(String route) {
        return DialogMenuItem.item("Back", "Return to the previous menu.", "vsmenu " + route, null, Material.ARROW);
    }

    private DialogMenuItem homeItem() {
        return DialogMenuItem.item("Home", "Return to the player dashboard.", "vsmenu", null, Material.COMPASS);
    }

    private DialogMenuItem closeItem() {
        return DialogMenuItem.item("Close", "Close this menu.", "vsmenu locked Menu closed.", null, Material.BARRIER);
    }

    private DistrictData.District getDistrict(Player player) {
        try {
            DistrictService districtService = plugin.getServiceRegistry().get(DistrictService.class);
            return districtService.getPlayerDistrict(player.getUniqueId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private DistrictService getDistrictService() {
        try {
            return plugin.getServiceRegistry().get(DistrictService.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ChatChannelService getChatChannelService() {
        try {
            return plugin.getServiceRegistry().get(ChatChannelService.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CrimeService getCrimeService() {
        try {
            return plugin.getServiceRegistry().get(CrimeService.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CivicWorkflowService getCivicWorkflow() {
        try {
            return plugin.getServiceRegistry().get(CivicWorkflowService.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean preferNative(Player player) {
        CivicWorkflowService civic = getCivicWorkflow();
        if (civic == null) return plugin.getConfigManager().preferNativeDialogs();
        return switch (civic.preferences(player.getUniqueId()).menuStyle()) {
            case "NATIVE" -> true;
            case "COMPACT" -> false;
            default -> plugin.getConfigManager().preferNativeDialogs();
        };
    }

    private DialogMenuItem chatItem(Player player, ChatChannel channel, String label, String description, Material material) {
        ChatChannelService chatService = getChatChannelService();
        if (chatService == null) {
            return DialogMenuItem.locked(label, description, "Chat channel service is unavailable.", material);
        }
        String denial = chatService.getAccessDenial(player, channel);
        if (denial != null) {
            return DialogMenuItem.locked(label, description, denial, material);
        }
        return DialogMenuItem.item(label, description, "chat " + channel.id(), null, material);
    }

    private DialogMenuItem roleItem(Player player, DistrictData.District district, DistrictService districtService,
                                    String label, String description, String command, Material material,
                                    String lockedReason, boolean allowed) {
        if (district == null) {
            return DialogMenuItem.locked(label, description, "You are not a member of an active district.", material);
        }
        if (districtService == null) {
            return DialogMenuItem.locked(label, description, "District role service is unavailable.", material);
        }
        if (!allowed) {
            String roles = districtService.getDistrictRoles(player.getUniqueId(), district).stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
            return DialogMenuItem.locked(label, description, lockedReason + " Your roles: " + roles, material);
        }
        return DialogMenuItem.item(label, description, command, null, material);
    }

    private CurrentAreaContext getCurrentArea(Player player) {
        try {
            CurrentAreaService currentAreaService = plugin.getServiceRegistry().get(CurrentAreaService.class);
            return currentAreaService.resolve(player);
        } catch (RuntimeException ignored) {
            return new CurrentAreaContext(
                CurrentAreaContext.AreaType.OUTLANDS,
                "Outlands",
                null,
                "VISITOR",
                List.of(),
                Map.of(),
                "Law service unavailable.",
                "Risk service unavailable.",
                "Market service unavailable.",
                "Job service unavailable.",
                "Station service unavailable."
            );
        }
    }

    private String summarizeFlags(CurrentAreaContext context) {
        if (context.activeFlags().isEmpty()) {
            return "No explicit region flags; default rules apply.";
        }
        return context.activeFlags().entrySet().stream()
            .map(entry -> entry.getKey().name() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private List<DialogMenuItem> resolveLockedItems(Player player, List<DialogMenuItem> items) {
        return items.stream()
            .filter(item -> item.locked() || isAllowed(player, item))
            .toList();
    }

    private boolean isAllowed(Player player, DialogMenuItem item) {
        return !item.locked() && hasVsPermission(player, item.permission());
    }

    private boolean canOpenRoute(Player player, DialogMenuType menuType) {
        if (menuType == DialogMenuType.ADMIN || menuType.name().startsWith("ADMIN_")) {
            return plugin.isStaffModeActive(player.getUniqueId()) && hasVsPermission(player, "vs.admin");
        }
        if (menuType == DialogMenuType.STAFF || menuType.name().startsWith("STAFF_")) {
            return plugin.isStaffModeActive(player.getUniqueId())
                && (hasVsPermission(player, "vs.staffmode.use") || hasVsPermission(player, "vs.admin"));
        }
        return true;
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
            input("district_law", "Propose District Law", "Enter law key and true/false.", "LAW_KEY true|false", "district law propose $(value)", null, true),
            input("district_role", "Set District Role", "Enter: set/remove player role. Roles: MEMBER, BUILDER, MERCHANT, POLICE, TREASURER, DIPLOMAT, WARDEN, CO_MAYOR, MAYOR.", "set player role", "district role $(value)", null, true),
            input("district_deposit", "Deposit Treasury", "Enter physical cash amount to deposit.", "Amount", "district deposit $(value)", null, false),
            input("district_withdraw", "Withdraw Treasury", "Enter physical cash amount to withdraw.", "Amount", "district withdraw $(value)", null, true),
            input("district_job_create", "Create District Job", "Enter: TYPE reward hours item|none amount manual:true|false title. Example: DELIVERY 100 24 stone 32 false Bring stone.", "TYPE reward hours item amount manual title", "district job create $(value)", null, true),
            input("district_project_create", "Create District Project", "Enter: type cashRequired itemRequired. Example: BUILD_MARKET 5000 128", "type cash items", "district project create $(value)", null, true),
            input("district_project_contribute", "Contribute To Project", "Enter: projectId cash|items amount. Hold materials for item contributions.", "id cash|items amount", "district project contribute $(value)", null, false),
            input("district_job_deliver", "Deliver Job Items", "Enter job id. Required items are matched by job config.", "Job id", "district job deliver $(value)", null, false),
            input("district_job_submit", "Submit Manual Job", "Enter job id.", "Job id", "district job submit $(value)", null, false),
            input("district_job_approve", "Approve Job Claim", "Enter claim id.", "Claim id", "district job approve $(value)", null, true),
            input("crime_record", "Crime Record", "Enter player name for a crime record.", "Player", "crime record $(value)", null, false),
            input("crime_arrest", "Arrest", "Enter the nearby wanted player name.", "Player", "crime arrest $(value)", null, true),
            input("crime_fine", "Fine", "Enter evidence id and amount.", "evidenceId amount", "crime fine $(value)", null, true),
            input("crime_wanted", "Mark Wanted", "Enter evidence id.", "evidenceId", "crime wanted $(value)", null, true),
            input("crime_dismiss", "Dismiss Evidence", "Enter evidence id.", "evidenceId", "crime dismiss $(value)", null, true),
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
            input("vault_inspect", "Vault Inspect", "Enter vault UUID.", "Vault UUID", "vault inspect $(value)", "vs.vault.admin.inspect", true),
            input("merchant_create", "Create Buy Order", "Hold the item and enter: price quantity [partial]. Example: 100 64 partial.", "price quantity [partial]", "merchant order create $(value)", null, false),
            input("merchant_deliver", "Deliver Items", "Enter order id and optional quantity.", "orderId [quantity]", "merchant order deliver $(value)", null, false),
            input("merchant_cancel", "Cancel Order", "Enter the order id to cancel.", "orderId", "merchant order cancel $(value)", null, false),
            input("merchant_shop_create", "Create Shop NPC", "Enter a name for your shop NPC.", "Shop name", "merchant shop create $(value)", null, false),
            input("player_report", "Submit Report", "Enter: category player|none details. Categories include THEFT, GRIEFING, HARASSMENT, POLICE_ABUSE, EXPLOIT, and OTHER.", "category player|none details", "civic report $(value)", null, false),
            input("report_resolve", "Resolve Report", "Enter report id and a resolution note.", "id resolution", "civic reports resolve $(value)", "vs.staffmode.use", true),
            input("report_dismiss", "Dismiss Report", "Enter report id and a dismissal reason.", "id reason", "civic reports dismiss $(value)", "vs.staffmode.use", true),
            input("alert_resolve", "Resolve Security Alert", "Enter alert id and a resolution note.", "id resolution", "staffalerts resolve $(value)", "vs.staff.alerts", true),
            input("cash_trace", "Trace Physical Cash", "Enter the full cash UUID.", "Cash UUID", "cash trace $(value)", "vs.cash.admin", true),
            input("district_staff_tp", "Teleport to District", "Enter a district id or exact name.", "District", "district teleport $(value)", "vs.district.teleport", true),
            input("diplomacy_request", "Request Alliance", "Enter a district id or exact one-word name.", "District", "civic diplomacy request $(value)", null, true),
            input("diplomacy_neutral", "Set Neutral", "Enter a district id or exact one-word name.", "District", "civic diplomacy neutral $(value)", null, true),
            input("diplomacy_hostile", "Declare Hostile", "Enter a district id or exact one-word name.", "District", "civic diplomacy hostile $(value)", null, true),
            input("job_dispute", "Dispute District Job", "Enter claim id and a detailed reason.", "claimId reason", "civic jobs dispute $(value)", null, false),
            input("job_dispute_resolve", "Resolve Job Dispute", "Enter dispute id, approve|deny, and a resolution note.", "id approve|deny note", "civic jobs resolve $(value)", null, true),
            input("support_request", "Request Kingdom Support", "Enter a project id or all, followed by the needed materials/staffing.", "projectId|all details", "civic support request $(value)", null, true),
            input("staffinspect_search", "Search Player", "Search by player name, partial name, UUID, district, or rank.", "Search", "staffinspect search $(value)", "vs.staffinspect", true),
            input("staffinspect_profile", "Open Player Profile", "Enter a player name or UUID.", "Player", "staffinspect $(value)", "vs.staffinspect", true),
            input("station_request", "Request Station", "Enter station name.", "Station name", "district station request $(value)", null, false),
            input("station_setplatform", "Set Platform", "Stand at platform center. Enter radius (e.g. 5).", "Radius", "district station setplatform $(value)", null, false),
            input("station_setarrival", "Set Arrival", "Stand at arrival point.", "any", "district station setarrival", null, false),
            input("rail_travel", "Buy Ticket", "Enter the route ID to travel.", "Route ID", "rail travel $(value)", null, false)
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
