package com.vaultsurvival.plugin.display;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.SchedulerHelper;
import com.vaultsurvival.plugin.market.MarketData;
import com.vaultsurvival.plugin.market.MarketService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of DisplayService.
 *
 * Spawns ItemDisplays and TextDisplays at designated slot locations
 * to physically represent auction listings in the world.
 */
public class DisplayServiceImpl implements DisplayService {

    private final VaultSurvivalPlugin plugin;
    private final MarketService market;
    private final SchedulerHelper scheduler;
    private final Logger logger;
    private final Map<Integer, DisplayData.DisplaySlot> slots = new ConcurrentHashMap<>();
    private BukkitTask refreshTask = null;

    public DisplayServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.market = plugin.getServiceRegistry().get(MarketService.class);
        this.scheduler = plugin.getScheduler();
        this.logger = plugin.getLogger();
    }

    @Override
    public DisplayData.DisplaySlot addSlot(Location location, MarketData.Category category) {
        String sql = "INSERT INTO display_slots (world, x, y, z, category) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, location.getWorld().getName());
            ps.setInt(2, location.getBlockX());
            ps.setInt(3, location.getBlockY());
            ps.setInt(4, location.getBlockZ());
            ps.setString(5, category.name());
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);
                var slot = new DisplayData.DisplaySlot(id, location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(), category);
                slots.put(id, slot);
                logger.info("Added display slot #" + id + " for " + category + " at " +
                    location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
                return slot;
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add display slot", e);
        }
        return null;
    }

    @Override
    public boolean removeSlot(int slotId) {
        var slot = slots.remove(slotId);
        if (slot == null) return false;

        // Despawn any active displays
        despawnDisplays(slot);

        try {
            plugin.getDatabase().executeUpdate("DELETE FROM display_slots WHERE id = ?", slotId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to remove display slot", e);
        }
        return true;
    }

    @Override
    public List<DisplayData.DisplaySlot> getAllSlots() {
        return new ArrayList<>(slots.values());
    }

    @Override
    public List<DisplayData.DisplaySlot> getSlotsByCategory(MarketData.Category category) {
        return slots.values().stream()
            .filter(s -> s.getCategory() == category)
            .toList();
    }

    @Override
    public int refreshAll() {
        int refreshed = 0;
        for (var slot : slots.values()) {
            if (refreshSlot(slot)) refreshed++;
        }
        return refreshed;
    }

    private boolean refreshSlot(DisplayData.DisplaySlot slot) {
        if (market == null) return false;
        Location loc = slot.getLocation();
        if (loc == null || loc.getWorld() == null) return false;

        // Get next active listing for this category
        var listings = market.getActiveListings(slot.getCategory());
        if (listings.isEmpty()) {
            despawnDisplays(slot);
            return false;
        }

        // Show the most recent listing for this category
        var listing = listings.getFirst();

        // If already showing this listing, skip
        if (listing.getListingUuid().equals(slot.getCurrentListingUuid())) return false;

        // Despawn old displays
        despawnDisplays(slot);

        // Spawn item display (the actual item)
        ItemStack item = deserializeItem(listing.getItemData());
        if (item != null) {
            ItemDisplay itemDisplay = (ItemDisplay) loc.getWorld().spawnEntity(loc, EntityType.ITEM_DISPLAY);
            itemDisplay.setItemStack(item);
            itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            // Scale down slightly and rotate for nicer presentation
            itemDisplay.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new Quaternionf(),
                new Vector3f(0.8f, 0.8f, 0.8f),
                new Quaternionf()
            ));
            itemDisplay.setPersistent(false);
            slot.setCurrentItemDisplayId(itemDisplay.getUniqueId());
        }

        // Spawn text display (price) above the item
        String priceText = plugin.getMessageFormatter().formatMoney(listing.getPrice(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural());
        TextDisplay textDisplay = (TextDisplay) loc.getWorld().spawnEntity(
            loc.clone().add(0, 0.4, 0), EntityType.TEXT_DISPLAY);
        textDisplay.text(Component.text(priceText, NamedTextColor.GOLD));
        textDisplay.setSeeThrough(false);
        textDisplay.setBillboard(TextDisplay.Billboard.CENTER);
        textDisplay.setPersistent(false);
        slot.setCurrentTextDisplayId(textDisplay.getUniqueId());

        slot.setCurrentListingUuid(listing.getListingUuid());
        return true;
    }

    private void despawnDisplays(DisplayData.DisplaySlot slot) {
        if (slot.getCurrentItemDisplayId() != null) {
            Entity e = Bukkit.getEntity(slot.getCurrentItemDisplayId());
            if (e != null) e.remove();
            slot.setCurrentItemDisplayId(null);
        }
        if (slot.getCurrentTextDisplayId() != null) {
            Entity e = Bukkit.getEntity(slot.getCurrentTextDisplayId());
            if (e != null) e.remove();
            slot.setCurrentTextDisplayId(null);
        }
        slot.setCurrentListingUuid(null);
    }

    @Override
    public void soldAnimation(int slotId, String buyerName, long price) {
        var slot = slots.get(slotId);
        if (slot == null) return;

        Location loc = slot.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        // Spawn happy particles
        loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 0.5, 0), 20, 0.3, 0.3, 0.3, 0);

        // Flash the text display if it exists
        if (slot.getCurrentTextDisplayId() != null) {
            Entity e = Bukkit.getEntity(slot.getCurrentTextDisplayId());
            if (e instanceof TextDisplay td) {
                td.text(Component.text("SOLD!", NamedTextColor.GREEN));
                // Schedule reset
                scheduler.runDelayed(() -> refreshSlot(slot), 60L); // 3 seconds
            }
        }

        // Spawn a temporary text display showing buyer name
        TextDisplay soldText = (TextDisplay) loc.getWorld().spawnEntity(
            loc.clone().add(0, 0.8, 0), EntityType.TEXT_DISPLAY);
        soldText.text(Component.text("Sold to " + buyerName + "!", NamedTextColor.GREEN));
        soldText.setBillboard(TextDisplay.Billboard.CENTER);
        soldText.setPersistent(false);
        // Remove after 5 seconds
        scheduler.runDelayed(() -> {
            if (soldText.isValid()) soldText.remove();
        }, 100L); // 5 seconds
    }

    @Override
    public void loadAll() {
        slots.clear();
        String sql = "SELECT * FROM display_slots";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                MarketData.Category category;
                try {
                    category = MarketData.Category.valueOf(rs.getString("category"));
                } catch (IllegalArgumentException e) {
                    category = MarketData.Category.MISC;
                }
                var slot = new DisplayData.DisplaySlot(id,
                    rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    category);
                slots.put(id, slot);
            }
            logger.info("Loaded " + slots.size() + " display slots");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load display slots", e);
        }
    }

    /** Start periodic refresh (every 60 seconds). */
    public void startRefreshScheduler() {
        if (refreshTask != null) return;
        refreshTask = scheduler.runRepeating(this::refreshAll, 1200L, 1200L); // 60s
    }

    /** Stop refresh scheduler. */
    public void stopRefreshScheduler() {
        if (refreshTask != null) {
            scheduler.cancel(refreshTask);
            refreshTask = null;
        }
    }

    /** Despawn all displays (on shutdown). */
    public void despawnAll() {
        for (var slot : slots.values()) {
            despawnDisplays(slot);
        }
    }

    // Item serialization (same as MarketServiceImpl)
    private static ItemStack deserializeItem(String data) {
        try {
            byte[] bytes = Base64Coder.decodeLines(data);
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
            return (ItemStack) bois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return new ItemStack(Material.BARRIER);
        }
    }
}
