package com.vaultsurvival.plugin.districts;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface DistrictTreasuryService {
    record TreasuryVault(UUID vaultUuid, int districtId, String world, int x, int y, int z,
                         String tier, boolean locked, long breachedUntil) {
        public boolean isAt(Block block) {
            return block.getWorld().getName().equals(world) && block.getX() == x && block.getY() == y && block.getZ() == z;
        }
        public Location location(org.bukkit.Server server) {
            var loaded = server.getWorld(world);
            return loaded == null ? null : new Location(loaded, x + .5, y + .5, z + .5);
        }
    }
    record Result(boolean success, String message, long amount, TreasuryVault vault) {
        public static Result ok(String message, long amount, TreasuryVault vault) { return new Result(true, message, amount, vault); }
        public static Result error(String message) { return new Result(false, message, 0, null); }
    }

    Result create(Player actor, Block block);
    Result remove(Player actor, UUID vaultUuid);
    Result depositHeld(Player actor, UUID vaultUuid);
    Result depositAll(Player actor, UUID vaultUuid);
    Result withdraw(Player actor, UUID vaultUuid, long amount);
    long getDistrictBalance(int districtId);
    long getVaultBalance(UUID vaultUuid);
    List<TreasuryVault> getVaults(int districtId);
    TreasuryVault getVault(UUID vaultUuid);
    TreasuryVault getVault(Block block);
    boolean canManage(Player player, TreasuryVault vault);
    boolean isNear(Player player, TreasuryVault vault);
    Result migrateLegacy(Player admin, int districtId, UUID vaultUuid);
    /** Credit system revenue into an actually registered physical treasury vault. */
    Result creditSystem(int districtId, long amount, String source, UUID actorUuid);
    /** Atomically consume physical treasury records for fees/upkeep. */
    Result debitSystem(int districtId, long amount, String reason, UUID actorUuid);
    void reportLegacyBalances();
}
