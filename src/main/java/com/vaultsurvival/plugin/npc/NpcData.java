package com.vaultsurvival.plugin.npc;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Data models for the NPC system.
 *
 * NPCs are fake player entities with real Mojang skins.
 * They support commands, custom shops, and integration with other modules
 * (Auction Hall, market desks, etc.).
 */
public class NpcData {

    /** What happens when a player right-clicks this NPC. */
    public enum ActionType {
        /** Execute a console command as the clicking player. */
        COMMAND,
        /** Open a custom shop GUI with configurable items/prices. */
        SHOP,
        /** Open the Auction Hall listing GUI (integrated with VS-Market). */
        MARKET,
        /** Do nothing — just for decoration. */
        NONE
    }

    /**
     * A shop item that can be sold by an NPC.
     */
    public static class ShopItem {
        private final int slot;
        private final ItemStack item;
        private final long price; // in coins
        private final String commandOnPurchase; // optional command to run

        public ShopItem(int slot, ItemStack item, long price, String commandOnPurchase) {
            this.slot = slot;
            this.item = item.clone();
            this.price = price;
            this.commandOnPurchase = commandOnPurchase;
        }

        public int getSlot() { return slot; }
        public ItemStack getItem() { return item.clone(); }
        public long getPrice() { return price; }
        public String getCommandOnPurchase() { return commandOnPurchase; }
    }

    /**
     * An NPC instance. Lives at a fixed location in a world,
     * has a custom skin, and performs an action on right-click.
     */
    public static class Npc {
        private final int id;
        private final String name;
        private final String skinUsername; // Mojang username whose skin to use
        private final String worldName;
        private final double x, y, z;
        private final float yaw, pitch;
        private final ActionType actionType;
        private final String actionData; // JSON or command string
        private final List<ShopItem> shopItems;
        private final boolean lookAtPlayers;

        // Runtime state (not persisted)
        private int entityId = -1; // The NMS entity ID
        private UUID interactionUuid; // The Bukkit Interaction entity UUID

        public Npc(int id, String name, String skinUsername, String worldName,
                   double x, double y, double z, float yaw, float pitch,
                   ActionType actionType, String actionData, boolean lookAtPlayers) {
            this.id = id;
            this.name = name;
            this.skinUsername = skinUsername;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.actionType = actionType;
            this.actionData = actionData;
            this.shopItems = new ArrayList<>();
            this.lookAtPlayers = lookAtPlayers;
        }

        public int getId() { return id; }
        public String getName() { return name; }
        public String getSkinUsername() { return skinUsername; }
        public String getWorldName() { return worldName; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
        public ActionType getActionType() { return actionType; }
        public String getActionData() { return actionData; }
        public List<ShopItem> getShopItems() { return shopItems; }
        public boolean isLookAtPlayers() { return lookAtPlayers; }

        public int getEntityId() { return entityId; }
        public void setEntityId(int entityId) { this.entityId = entityId; }

        public UUID getInteractionUuid() { return interactionUuid; }
        public void setInteractionUuid(UUID uuid) { this.interactionUuid = uuid; }

        public Location getLocation() {
            var world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
}
