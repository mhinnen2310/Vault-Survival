package com.vaultsurvival.plugin.store;

import java.util.List;

public interface StoreService {
    List<StoreData.CosmeticItem> getItems(String category);
    boolean purchaseItem(java.util.UUID player, String itemId);
    void reloadItems();
}
