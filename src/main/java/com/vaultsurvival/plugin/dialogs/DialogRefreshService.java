package com.vaultsurvival.plugin.dialogs;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
public final class DialogRefreshService {
    private final Map<UUID, Supplier<DialogResult>> refreshers = new ConcurrentHashMap<>();
    public void register(UUID player, Supplier<DialogResult> refresher) { refreshers.put(player, refresher); }
    public DialogResult refresh(UUID player) { var supplier = refreshers.get(player); return supplier == null ? new DialogError("Expired", "Reopen this menu.").result() : supplier.get(); }
    public void clear(UUID player) { refreshers.remove(player); }
}
