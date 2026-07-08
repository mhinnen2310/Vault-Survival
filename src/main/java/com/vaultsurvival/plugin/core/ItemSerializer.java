package com.vaultsurvival.plugin.core;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Utility for serializing/deserializing ItemStacks.
 * Used for persistent storage of items (AH escrow, trade escrow, etc.)
 */
public class ItemSerializer {

    /**
     * Serialize an ItemStack to a Base64-encoded string.
     */
    public static String serialize(ItemStack item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    /**
     * Serialize an array of ItemStacks to a Base64 string.
     */
    public static String serializeArray(ItemStack[] items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeInt(items.length);
            for (ItemStack item : items) {
                oos.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize items", e);
        }
    }

    /**
     * Deserialize a Base64 string to an ItemStack.
     */
    public static ItemStack deserialize(String data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }

    /**
     * Deserialize a Base64 string to an array of ItemStacks.
     */
    public static ItemStack[] deserializeArray(String data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            int length = ois.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) ois.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize items", e);
        }
    }
}
