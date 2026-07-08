package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class DialogService {

    private final VaultSurvivalPlugin plugin;
    private final DialogProvider nativeProvider;
    private final DialogProvider fallbackProvider;
    private String lastProviderName = "none";

    public DialogService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.nativeProvider = new NativePaperDialogProvider();
        this.fallbackProvider = new FallbackDialogProvider(plugin, this);
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
        if (!player.hasPermission("vs.menu")) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }

        List<DialogMenuItem> items = buildItems(player, menuType);
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
        if (!item.isAllowed(player)) {
            player.sendMessage(plugin.getMessageFormatter().permissionDenied());
            return;
        }
        if (item.adminSensitive()) {
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                "DIALOG_ACTION", item.command(), "menu_button=" + item.label());
        }
        player.performCommand(item.command());
    }

    private List<DialogMenuItem> buildItems(Player player, DialogMenuType menuType) {
        return switch (menuType) {
            case MAIN -> mainMenu();
            case DISTRICTS -> districtMenu(player);
            case ADMIN -> adminMenu();
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
        items.add(DialogMenuItem.item("Apply", "Apply for a new district.", "district apply", null, Material.WRITABLE_BOOK));

        DistrictData.District district = getDistrict(player);
        if (district != null) {
            if (district.isCouncil(player.getUniqueId())) {
                items.add(DialogMenuItem.item("Invite Member", "Usage help for inviting members.", "district invite", null, Material.PLAYER_HEAD));
                items.add(DialogMenuItem.item("Set Law", "Usage help for district law settings.", "district law", null, Material.LECTERN));
            }
            if (district.isMayor(player.getUniqueId())) {
                items.add(DialogMenuItem.adminItem("Set Role", "Usage help for district role settings.", "district role", null, Material.NAME_TAG));
                items.add(DialogMenuItem.adminItem("Disband", "Usage help for district disband command.", "district disband", null, Material.TNT));
            }
            if (district.isTreasurer(player.getUniqueId())) {
                items.add(DialogMenuItem.item("Deposit Treasury", "Usage help for treasury deposit.", "district deposit", null, Material.GOLD_NUGGET));
                items.add(DialogMenuItem.item("Withdraw Treasury", "Usage help for treasury withdrawal.", "district withdraw", null, Material.CHEST));
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
            DialogMenuItem.adminItem("Reload Config", "Reload Vault Survival config.", "vs reload", "vs.admin.reload", Material.REPEATER),
            DialogMenuItem.adminItem("Optimize DB", "Run database checkpoint.", "vsadmin optimize", "vs.admin.reload", Material.ANVIL),
            backItem()
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
            DialogMenuItem.item("Sell Help", "Show sell usage. Must be inside Auction Hall.", "ah sell", "vs.market.sell", Material.WRITABLE_BOOK),
            DialogMenuItem.adminItem("Inspect Help", "Show listing inspect usage.", "ah inspect", "vs.market.admin", Material.SPYGLASS),
            backItem()
        );
    }

    private List<DialogMenuItem> vaultMenu() {
        return List.of(
            DialogMenuItem.item("Info", "Inspect the vault you are looking at.", "vault info", "vs.vault.use", Material.BARREL),
            DialogMenuItem.item("Deposit", "Deposit held cash into a vault.", "vault deposit", "vs.vault.use", Material.HOPPER),
            DialogMenuItem.item("Withdraw Help", "Show withdrawal usage.", "vault withdraw", "vs.vault.use", Material.DROPPER),
            DialogMenuItem.item("Access Help", "Show vault access usage.", "vault access", "vs.vault.use", Material.IRON_DOOR),
            DialogMenuItem.item("Repair", "Repair the target vault.", "vault repair", "vs.vault.use", Material.ANVIL),
            DialogMenuItem.item("List", "List your vaults.", "vault list", "vs.vault.use", Material.BOOK),
            DialogMenuItem.item("Place Help", "Show vault placement usage.", "vault place", "vs.vault.place", Material.CHEST),
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
}
