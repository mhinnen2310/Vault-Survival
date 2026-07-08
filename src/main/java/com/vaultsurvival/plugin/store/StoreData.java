package com.vaultsurvival.plugin.store;

import java.util.*;

public class StoreData {
    public static class CosmeticItem {
        private final String id;
        private final String displayName;
        private final String material;
        private final int customModelData;
        private final long price;
        private final String category;
        private final String description;

        public CosmeticItem(String id, String displayName, String material, int customModelData,
                            long price, String category, String description) {
            this.id = id; this.displayName = displayName; this.material = material;
            this.customModelData = customModelData; this.price = price;
            this.category = category; this.description = description;
        }
        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public String getMaterial() { return material; }
        public int getCustomModelData() { return customModelData; }
        public long getPrice() { return price; }
        public String getCategory() { return category; }
        public String getDescription() { return description; }
    }
}
