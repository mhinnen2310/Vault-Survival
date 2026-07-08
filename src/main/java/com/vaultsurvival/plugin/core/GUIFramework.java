package com.vaultsurvival.plugin.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lightweight GUI framework for creating interactive inventory menus.
 *
 * Features:
 * - Paginated inventories
 * - Click handlers on items
 * - Action buttons (previous, next, close, confirm, cancel)
 * - Dynamic slot assignment
 *
 * Used by: Auction Hall UI, Trade UI, Vault UI, District management, etc.
 */
public class GUIFramework implements Listener {

    private final Plugin plugin;
    private final Map<UUID, ActiveGUI> activeGUIs = new HashMap<>();

    public GUIFramework(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Open a simple inventory GUI for a player.
     */
    public void openGUI(Player player, String title, int rows, List<GUIItem> items) {
        Inventory inv = createInventory(title, rows, items);
        activeGUIs.put(player.getUniqueId(), new ActiveGUI(inv, items));
        player.openInventory(inv);
    }

    /**
     * Open a paginated inventory GUI.
     */
    public void openPaginated(Player player, String title, List<GUIItem> allItems,
                               int rows, int page, Consumer<PaginatedGUI> builder) {
        PaginatedGUI gui = new PaginatedGUI(allItems, rows);
        builder.accept(gui);
        gui.open(player, title, page);
        activeGUIs.put(player.getUniqueId(), new ActiveGUI(gui.getInventory(), gui.getCurrentItems()));
    }

    private Inventory createInventory(String title, int rows, List<GUIItem> items) {
        int size = rows * 9;
        Inventory inv = Bukkit.createInventory(null, size, MessageFormatter.deserializeLegacy(title));

        for (GUIItem item : items) {
            if (item.slot >= 0 && item.slot < size) {
                inv.setItem(item.slot, item.build());
            }
        }

        return inv;
    }

    // --- Event handlers ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ActiveGUI gui = activeGUIs.get(player.getUniqueId());
        if (gui == null || !event.getInventory().equals(gui.inventory)) return;

        event.setCancelled(true); // Prevent item moving

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= gui.inventory.getSize()) return;

        for (GUIItem item : gui.items) {
            if (item.slot == slot && item.clickHandler != null) {
                item.clickHandler.accept(player, event);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (activeGUIs.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        ActiveGUI gui = activeGUIs.remove(player.getUniqueId());
        if (gui != null && gui.closeHandler != null) {
            gui.closeHandler.accept(event);
        }
    }

    /**
     * Manually close a GUI and trigger cleanup.
     */
    public void closeGUI(Player player) {
        activeGUIs.remove(player.getUniqueId());
        player.closeInventory();
    }

    // --- Inner classes ---

    /**
     * Represents a single clickable item in a GUI.
     */
    public static class GUIItem {
        public final int slot;
        public final ItemStack item;
        public final BiConsumer<Player, InventoryClickEvent> clickHandler;

        public GUIItem(int slot, ItemStack item, BiConsumer<Player, InventoryClickEvent> clickHandler) {
            this.slot = slot;
            this.item = item.clone();
            this.clickHandler = clickHandler;
        }

        public ItemStack build() {
            return item.clone();
        }

        // --- Static factory methods ---

        public static GUIItem button(int slot, Material material, String name, List<String> lore,
                                      BiConsumer<Player, InventoryClickEvent> handler) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(MessageFormatter.deserializeLegacy(name));
                if (lore != null) {
                    List<Component> componentLore = new ArrayList<>();
                    for (String line : lore) {
                        componentLore.add(MessageFormatter.deserializeLegacy(line));
                    }
                    meta.lore(componentLore);
                }
                item.setItemMeta(meta);
            }
            return new GUIItem(slot, item, handler);
        }

        public static GUIItem closeButton(int rows) {
            return button((rows * 9) - 1, Material.BARRIER,
                "§cClose", null,
                (p, e) -> p.closeInventory());
        }

        public static GUIItem backButton(int slot, BiConsumer<Player, InventoryClickEvent> handler) {
            return button(slot, Material.ARROW, "§7← Back", null, handler);
        }

        public static GUIItem spacer(int slot) {
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = glass.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(" "));
                glass.setItemMeta(meta);
            }
            return new GUIItem(slot, glass, null);
        }
    }

    /**
     * Paginated GUI helper.
     */
    public static class PaginatedGUI {
        private final List<GUIItem> allItems;
        private final int rows;
        private final int contentSlots;
        private int currentPage = 0;
        private Inventory inventory;
        private List<GUIItem> currentItems;

        public PaginatedGUI(List<GUIItem> allItems, int rows) {
            this.allItems = allItems;
            this.rows = rows;
            // Leave bottom row for navigation
            this.contentSlots = (rows - 1) * 9;
        }

        public void open(Player player, String title, int page) {
            this.currentPage = Math.max(0, Math.min(page, getTotalPages() - 1));
            this.currentItems = new ArrayList<>();

            int startIndex = currentPage * contentSlots;
            int endIndex = Math.min(startIndex + contentSlots, allItems.size());

            for (int i = startIndex; i < endIndex; i++) {
                GUIItem item = allItems.get(i);
                GUIItem repositioned = new GUIItem(
                    (i - startIndex),
                    item.item,
                    item.clickHandler
                );
                currentItems.add(repositioned);
            }

            // Navigation buttons (bottom row)
            int navRow = (rows - 1) * 9;
            if (currentPage > 0) {
                currentItems.add(GUIItem.button(navRow, Material.ARROW, "§7← Previous",
                    List.of("§7Page " + currentPage + " / " + (getTotalPages())),
                    (p, e) -> open(p, title, currentPage - 1)));
            }
            if (currentPage < getTotalPages() - 1) {
                currentItems.add(GUIItem.button(navRow + 8, Material.ARROW, "§7Next →",
                    List.of("§7Page " + (currentPage + 2) + " / " + (getTotalPages())),
                    (p, e) -> open(p, title, currentPage + 1)));
            }
            currentItems.add(GUIItem.button(navRow + 4, Material.BARRIER, "§cClose", null,
                (p, e) -> p.closeInventory()));

            // Build inventory
            int size = rows * 9;
            this.inventory = Bukkit.createInventory(null, size,
                MessageFormatter.deserializeLegacy(title + " §8(Page " + (currentPage + 1) + "/" + getTotalPages() + ")"));

            for (GUIItem item : currentItems) {
                if (item.slot >= 0 && item.slot < size) {
                    inventory.setItem(item.slot, item.build());
                }
            }

            player.openInventory(inventory);
        }

        public int getTotalPages() {
            return Math.max(1, (int) Math.ceil((double) allItems.size() / contentSlots));
        }

        public Inventory getInventory() { return inventory; }
        public List<GUIItem> getCurrentItems() { return currentItems; }
    }

    /**
     * Tracks an open GUI for a player.
     */
    private static class ActiveGUI {
        final Inventory inventory;
        final List<GUIItem> items;
        Consumer<InventoryCloseEvent> closeHandler;

        ActiveGUI(Inventory inventory, List<GUIItem> items) {
            this.inventory = inventory;
            this.items = items;
        }
    }
}
