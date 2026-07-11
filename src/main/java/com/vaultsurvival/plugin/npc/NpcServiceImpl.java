package com.vaultsurvival.plugin.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of NpcService using packet-backed player visuals with a Bukkit
 * Interaction entity for click detection. A villager is used only when Mojang's
 * profile service or the packet bridge is unavailable.
 * NPCs persist in the SQLite database and are respawned on server restart.
 */
public class NpcServiceImpl implements NpcService {

    private static final long JOB_BOARD_SESSION_MILLIS = 120_000L;

    private final VaultSurvivalPlugin plugin;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;

    // All loaded NPCs (id → Npc)
    private final Map<Integer, NpcData.Npc> npcs = new ConcurrentHashMap<>();
    // NPC entity IDs assigned to players (player UUID → set of NPC entity IDs)
    private final Map<UUID, Set<Integer>> playerNpcEntities = new ConcurrentHashMap<>();
    // Cached skin textures (username → texture value + signature)
    private final Map<String, SkinTexture> skinCache = new ConcurrentHashMap<>();
    // Physical job-board NPC access window (player UUID -> expiry millis)
    private final Map<UUID, Long> jobBoardSessions = new ConcurrentHashMap<>();

    // NMS reflection
    private Method sendPacket;

    // Next entity ID counter
    private int nextEntityId = 200000;

    public NpcServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();

    }

    // ========================================================================
    // NMS Reflection Init
    // ========================================================================



    // ========================================================================
    // Create / Remove NPC
    // ========================================================================

    @Override
    public NpcData.Npc createNpc(String name, String skinUsername, Location location,
                                  NpcData.ActionType actionType, String actionData) {
        if (location.getWorld() == null) return null;

        String sql = "INSERT INTO npcs (name, skin_username, world, x, y, z, yaw, pitch, " +
                     "action_type, action_data, look_at_players) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";

        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, skinUsername);
            ps.setString(3, location.getWorld().getName());
            ps.setDouble(4, location.getX());
            ps.setDouble(5, location.getY());
            ps.setDouble(6, location.getZ());
            ps.setFloat(7, location.getYaw());
            ps.setFloat(8, location.getPitch());
            ps.setString(9, actionType.name());
            ps.setString(10, actionData != null ? actionData : "");
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                int id = keys.getInt(1);

                NpcData.Npc npc = new NpcData.Npc(id, name, skinUsername,
                    location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(),
                    actionType, actionData, true);

                // Assign entity ID
                npc.setEntityId(nextEntityId++);
                npcs.put(id, npc);

                // Spawn for all online players
                spawnNpcToAll(npc);

                audit.log(null, "CONSOLE", "NPC_CREATE", "NPC",
                    String.valueOf(id), "name=" + name + " skin=" + skinUsername);

                return npc;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create NPC", e);
        }
        return null;
    }

    @Override
    public boolean removeNpc(int npcId) {
        NpcData.Npc npc = npcs.remove(npcId);
        if (npc == null) return false;

        despawnNpcFromAll(npc);

        // Remove from database
        try {
            plugin.getDatabase().executeUpdate("DELETE FROM npcs WHERE id = ?", npcId);
            plugin.getDatabase().executeUpdate("DELETE FROM npc_shop_items WHERE npc_id = ?", npcId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to delete NPC from DB", e);
        }

        Bukkit.getPluginManager().callEvent(new NpcRemovedEvent(npc));

        return true;
    }

    @Override
    public void despawnNpcVisual(int npcId) {
        NpcData.Npc npc = npcs.get(npcId);
        if (npc != null) despawnNpcFromAll(npc);
    }

    @Override
    public void respawnNpc(int npcId) {
        NpcData.Npc npc = npcs.get(npcId);
        if (npc != null) spawnNpcToAll(npc);
    }

    @Override
    public boolean moveNpc(int npcId, Location newLocation) {
        NpcData.Npc npc = npcs.get(npcId);
        if (npc == null) return false;

        // Despawn old entity
        despawnNpcFromAll(npc);

        // Update DB
        String sql = "UPDATE npcs SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? WHERE id = ?";
        try {
            plugin.getDatabase().executeUpdate(sql,
                newLocation.getWorld().getName(), newLocation.getX(), newLocation.getY(),
                newLocation.getZ(), newLocation.getYaw(), newLocation.getPitch(), npcId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to move NPC", e);
            return false;
        }

        // Create new Npc with updated location
        NpcData.Npc updated = new NpcData.Npc(npc.getId(), npc.getName(), npc.getSkinUsername(),
            newLocation.getWorld().getName(),
            newLocation.getX(), newLocation.getY(), newLocation.getZ(),
            newLocation.getYaw(), newLocation.getPitch(),
            npc.getActionType(), npc.getActionData(), npc.isLookAtPlayers());
        updated.getShopItems().addAll(npc.getShopItems());
        updated.setEntityId(npc.getEntityId());

        npcs.put(npcId, updated);

        // Respawn
        spawnNpcToAll(updated);

        return true;
    }

    @Override
    public boolean setNpcSkin(int npcId, String skinUsername) {
        NpcData.Npc npc = npcs.get(npcId);
        if (npc == null) return false;

        // Clear cached skin to force re-fetch
        skinCache.remove(skinUsername);

        // Despawn and respawn with new skin
        despawnNpcFromAll(npc);

        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE npcs SET skin_username = ? WHERE id = ?", skinUsername, npcId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update NPC skin", e);
        }

        // Update in memory
        NpcData.Npc updated = new NpcData.Npc(npc.getId(), npc.getName(), skinUsername,
            npc.getWorldName(), npc.getX(), npc.getY(), npc.getZ(),
            npc.getYaw(), npc.getPitch(),
            npc.getActionType(), npc.getActionData(), npc.isLookAtPlayers());
        updated.getShopItems().addAll(npc.getShopItems());
        updated.setEntityId(npc.getEntityId());
        npcs.put(npcId, updated);

        spawnNpcToAll(updated);
        return true;
    }

    @Override
    public boolean setNpcAction(int npcId, NpcData.ActionType actionType, String actionData) {
        NpcData.Npc npc = npcs.get(npcId);
        if (npc == null) return false;

        try {
            plugin.getDatabase().executeUpdate(
                "UPDATE npcs SET action_type = ?, action_data = ? WHERE id = ?",
                actionType.name(), actionData != null ? actionData : "", npcId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update NPC action", e);
            return false;
        }

        // Update in memory
        NpcData.Npc updated = rebuildNpc(npc, actionType, actionData);
        npcs.put(npcId, updated);
        return true;
    }

    @Override
    public boolean addShopItem(int npcId, int slot, ItemStack item, long price, String command) {
        String itemData = serializeItem(item);
        try {
            plugin.getDatabase().executeUpdate(
                "INSERT OR REPLACE INTO npc_shop_items (npc_id, slot, item_data, price, command_on_purchase) " +
                "VALUES (?, ?, ?, ?, ?)", npcId, slot, itemData, price, command != null ? command : "");
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to add shop item", e);
            return false;
        }
        return true;
    }

    @Override
    public boolean clearShopItems(int npcId) {
        try {
            plugin.getDatabase().executeUpdate("DELETE FROM npc_shop_items WHERE npc_id = ?", npcId);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to clear shop items", e);
            return false;
        }
    }

    // ========================================================================
    // Spawning / Despawning
    // ========================================================================

    @Override
    public void spawnAllToPlayer(Player player) {
        npcs.values().forEach(npc -> spawnNpcToPlayer(npc, player));
    }

    @Override
    public void despawnAllFromPlayer(Player player) {
        Set<Integer> entityIds = playerNpcEntities.remove(player.getUniqueId());
        if (entityIds == null || entityIds.isEmpty()) return;

        try {
            // Send remove entities packet via reflection
            Class<?> packetClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Constructor<?> ctor = packetClass.getConstructor(int[].class);
            Object packet = ctor.newInstance((Object) entityIds.stream().mapToInt(i -> i).toArray());

            Object connection = getPlayerConnection(player);
            sendPacket(connection, packet);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to despawn NPCs for player", e);
        }
    }

    private void spawnNpcToAll(NpcData.Npc npc) {
        Bukkit.getOnlinePlayers().forEach(p -> spawnNpcToPlayer(npc, p));
    }

    private void despawnNpcFromAll(NpcData.Npc npc) {
        try {
            Class<?> packetClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Constructor<?> ctor = packetClass.getConstructor(int[].class);
            Object packet = ctor.newInstance((Object) new int[]{npc.getEntityId()});

            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    Object connection = getPlayerConnection(player);
                    sendPacket(connection, packet);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Legacy packet despawn skipped for NPC " + npc.getId() + ": " + e.getMessage());
        }

        if (npc.getInteractionUuid() != null) {
            var entity = Bukkit.getEntity(npc.getInteractionUuid());
            if (entity != null) entity.remove();
            npc.setInteractionUuid(null);
        }
        if (npc.getVisualUuid() != null) {
            var entity = Bukkit.getEntity(npc.getVisualUuid());
            if (entity != null) entity.remove();
            npc.setVisualUuid(null);
        }
    }

    private void spawnNpcToPlayer(NpcData.Npc npc, Player player) {
        World world = Bukkit.getWorld(npc.getWorldName());
        if (world == null || !world.equals(player.getWorld())) return;
        CompletableFuture.supplyAsync(() -> fetchSkin(npc.getSkinUsername()))
            .thenAccept(skin -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.getWorld().equals(world)) return;
                spawnInteractionEntity(npc);
                if (skin != null && sendSpawnPackets(npc, player, skin)) {
                    playerNpcEntities.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(npc.getEntityId());
                    return;
                }
                // Visible, functional fallback. This is deliberately only used after a real skin failure.
                spawnBukkitNpc(npc);
            }));
    }

    // ========================================================================
    // Packet Sending (NMS via Reflection)
    // ========================================================================

    @SuppressWarnings("unchecked")
    private boolean sendSpawnPackets(NpcData.Npc npc, Player player, SkinTexture skin) {
        try {
        Object connection = getPlayerConnection(player);

        // Build GameProfile with skin texture
        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        UUID npcUuid = UUID.nameUUIDFromBytes(("NPC:" + npc.getEntityId()).getBytes());
        Constructor<?> gpCtor = gameProfileClass.getConstructor(UUID.class, String.class);
        Object gameProfile = gpCtor.newInstance(npcUuid, npc.getName());

        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        Constructor<?> propCtor = propertyClass.getConstructor(String.class, String.class, String.class);
        Object skinProp = propCtor.newInstance("textures", skin.value, skin.signature);
        Method getProperties = gameProfileClass.getMethod("getProperties");
        Object properties = getProperties.invoke(gameProfile);
        Method put = properties.getClass().getMethod("put", Object.class, Object.class);
        put.invoke(properties, "textures", skinProp);

        // 1. Send ClientboundPlayerInfoUpdatePacket (ADD_PLAYER) so the client gets the skin
        try {
            Class<?> playerInfoClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> actionEnum = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");

            // Get ADD_PLAYER action
            Object addPlayerAction = null;
            for (Object a : actionEnum.getEnumConstants()) {
                if (a.toString().equals("ADD_PLAYER")) { addPlayerAction = a; break; }
            }

            // Create EnumSet with ADD_PLAYER
            Object actionSet = EnumSet.noneOf((Class<Enum>) actionEnum);
            actionSet.getClass().getMethod("add", Object.class).invoke(actionSet, addPlayerAction);

            // Create Entry: Entry(UUID, GameProfile, boolean listed, int latency, GameType gameMode, Component displayName, chatSession)
            Class<?> entryClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");

            List<Object> entryList = new ArrayList<>();
            for (Constructor<?> c : entryClass.getConstructors()) {
                try {
                    Class<?>[] parameters = c.getParameterTypes();
                    Object[] values = new Object[parameters.length];
                    for (int i = 0; i < parameters.length; i++) {
                        Class<?> type = parameters[i];
                        if (type == UUID.class) values[i] = npcUuid;
                        else if (type == gameProfileClass) values[i] = gameProfile;
                        else if (type == boolean.class) values[i] = true;
                        else if (type == int.class) values[i] = 0;
                        else if (type.getName().equals("net.minecraft.world.level.GameType")) values[i] = type.getEnumConstants()[0];
                        else values[i] = null;
                    }
                    entryList.add(c.newInstance(values));
                    break;
                } catch (Exception ignored) { }
            }

            if (entryList.isEmpty()) return false;

            // Create the packet: ClientboundPlayerInfoUpdatePacket(EnumSet<Action>, List<Entry>)
            for (Constructor<?> c : playerInfoClass.getConstructors()) {
                if (c.getParameterCount() == 2) {
                    Object packet = c.newInstance(actionSet, entryList);
                    sendPacket(connection, packet);
                    break;
                }
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to send skin profile packet: " + e.getMessage());
            return false;
        }

        // 2. Send ClientboundAddEntityPacket to spawn the entity visually
        try {
            Class<?> addEntityClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");
            Field playerTypeField = entityTypeClass.getField("PLAYER");
            Object playerType = playerTypeField.get(null);

            // Vec3 zero velocity for 1.20.2+ constructor
            Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            Constructor<?> vec3Ctor = vec3Class.getConstructor(double.class, double.class, double.class);
            Object zeroVel = vec3Ctor.newInstance(0.0, 0.0, 0.0);

            // Try 1.20.2+ constructor: (id, uuid, x, y, z, xRot, yRot, type, data, velocity, headYaw)
            Constructor<?> addEntityCtor = addEntityClass.getConstructor(
                int.class, UUID.class, double.class, double.class, double.class,
                float.class, float.class, entityTypeClass, int.class, vec3Class, double.class);

            Object addEntityPacket = addEntityCtor.newInstance(
                npc.getEntityId(), npcUuid, npc.getX(), npc.getY(), npc.getZ(),
                npc.getYaw(), npc.getPitch(), playerType, 0, zeroVel, 0.0);

            sendPacket(connection, addEntityPacket);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to send player NPC spawn packet: " + e.getMessage());
            return false;
        }

        // 3. Send ClientboundSetEntityDataPacket — enable all skin layers
        try {
            Class<?> setDataClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            Class<?> serializerClass = Class.forName(
                "net.minecraft.network.syncher.EntityDataSerializers");
            Field byteSerializerField = serializerClass.getField("BYTE");
            Object byteSerializer = byteSerializerField.get(null);

            // SynchedEntityData.DataValue(id=17, serializer=BYTE, value=(byte)127)
            Class<?> dataValueClass = Class.forName(
                "net.minecraft.network.syncher.SynchedEntityData$DataValue");
            Class<?> entityDataSerializerClass = Class.forName(
                "net.minecraft.network.syncher.EntityDataSerializer");
            Constructor<?> dataValueCtor = dataValueClass.getConstructor(
                int.class, entityDataSerializerClass, Object.class);
            Object skinDataValue = dataValueCtor.newInstance(17, byteSerializer, (byte) 127);

            Constructor<?> setDataCtor = setDataClass.getConstructor(int.class, List.class);
            Object setDataPacket = setDataCtor.newInstance(npc.getEntityId(), List.of(skinDataValue));
            sendPacket(connection, setDataPacket);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to send skin metadata packet: " + e.getMessage());
        }
        return true;
        } catch (Exception e) {
            logger.log(Level.FINE, "NPC skin packet bridge failed: " + e.getMessage());
            return false;
        }
    }



    // ========================================================================
    // Interaction Entity
    // ========================================================================

    private void spawnInteractionEntity(NpcData.Npc npc) {
        World world = Bukkit.getWorld(npc.getWorldName());
        if (world == null) return;

        Location loc = new Location(world, npc.getX(), npc.getY(), npc.getZ());

        // Remove old interaction entity if exists
        if (npc.getInteractionUuid() != null) {
            var old = Bukkit.getEntity(npc.getInteractionUuid());
            if (old != null) old.remove();
        }

        Interaction interaction = (Interaction) world.spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionWidth(0.6f);
        interaction.setInteractionHeight(1.8f);
        interaction.setResponsive(true);
        interaction.setPersistent(false); // Will be re-created on restart
        interaction.setCustomNameVisible(false);
        interaction.customName(MessageFormatter.deserializeLegacy(npc.getName()));

        npc.setInteractionUuid(interaction.getUniqueId());
    }

    private void spawnBukkitNpc(NpcData.Npc npc) {
        World world = Bukkit.getWorld(npc.getWorldName());
        if (world == null) return;

        Entity existingVisual = npc.getVisualUuid() != null ? Bukkit.getEntity(npc.getVisualUuid()) : null;
        Entity existingInteraction = npc.getInteractionUuid() != null ? Bukkit.getEntity(npc.getInteractionUuid()) : null;
        if (existingVisual != null && existingVisual.isValid()
            && existingInteraction != null && existingInteraction.isValid()) {
            return;
        }

        Location loc = new Location(world, npc.getX(), npc.getY(), npc.getZ(), npc.getYaw(), npc.getPitch());

        if (existingVisual != null) {
            existingVisual.remove();
        }

        boolean merchantShop = isMerchantShop(npc.getId());
        Villager villager = world.spawn(loc, Villager.class, spawned -> {
            spawned.customName(MessageFormatter.deserializeLegacy(npc.getName()));
            spawned.setCustomNameVisible(true);
            spawned.setAI(false);
            spawned.setInvulnerable(!merchantShop || !plugin.getConfigManager().getConfig().getBoolean("districtMarket.merchantNpc.killable", true));
            spawned.setSilent(true);
            spawned.setCollidable(false);
            spawned.setRemoveWhenFarAway(false);
            spawned.setPersistent(false);
            spawned.setAdult();
            spawned.setProfession(Villager.Profession.NONE);
            spawned.setVillagerType(Villager.Type.PLAINS);
            spawned.teleport(loc);
        });
        npc.setVisualUuid(villager.getUniqueId());

        spawnInteractionEntity(npc);
    }

    private boolean isMerchantShop(int npcId) {
        try {
            var shops = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.merchant.shop.MerchantShopService.class);
            return shops.getShopByNpcId(npcId) != null;
        } catch (RuntimeException ignored) { return false; }
    }

    // ========================================================================
    // Handle Interaction
    // ========================================================================

    @Override
    public void handleInteraction(Player player, NpcData.Npc npc) {
        if (npc == null) return;

        // Conductor NPCs always open the rail dialog, independent of cosmetic NPC action data.
        if (npc.getName().toLowerCase(java.util.Locale.ROOT).contains("conductor")) {
            try { plugin.getServiceRegistry().get(com.vaultsurvival.plugin.dialogs.DialogService.class).openMenu(player, com.vaultsurvival.plugin.dialogs.DialogMenuType.RAIL_HOME); return; }
            catch (RuntimeException ignored) { player.performCommand("station next"); return; }
        }

        // Fire custom event for other modules
        NpcInteractEvent event = new NpcInteractEvent(player, npc);
        Bukkit.getPluginManager().callEvent(event);

        switch (npc.getActionType()) {
            case COMMAND -> {
                String cmd = npc.getActionData()
                    .replace("%player%", player.getName());
                if (isJobBoardCommand(cmd)) grantJobBoardSession(player.getUniqueId());
                player.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
                audit.log(player.getUniqueId(), player.getName(), "NPC_COMMAND",
                    "NPC_" + npc.getId(), cmd, "");
            }
            case SHOP -> openShopGui(player, npc);
            case MARKET -> {
                // Open Auction Hall listings
                player.performCommand("ah listings");
            }
            case MERCHANT_SHOP -> {
                try {
                    var shopService = plugin.getServiceRegistry().get(
                        com.vaultsurvival.plugin.merchant.shop.MerchantShopService.class);
                    shopService.openShopGui(player, npc.getId());
                } catch (Exception ex) {
                    player.sendMessage(fmt.error("Merchant shop service is not available."));
                }
            }
            case NONE -> {
                // Do nothing — decoration NPC
            }
        }
    }

    @Override
    public void grantJobBoardSession(UUID playerUuid) {
        if (playerUuid != null) jobBoardSessions.put(playerUuid, System.currentTimeMillis() + JOB_BOARD_SESSION_MILLIS);
    }

    @Override
    public boolean hasJobBoardSession(UUID playerUuid) {
        if (playerUuid == null) return false;
        Long expiresAt = jobBoardSessions.get(playerUuid);
        if (expiresAt == null) return false;
        if (expiresAt < System.currentTimeMillis()) {
            jobBoardSessions.remove(playerUuid, expiresAt);
            return false;
        }
        return true;
    }

    private boolean isJobBoardCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.stripLeading();
        if (command.startsWith("/")) command = command.substring(1);
        command = command.toLowerCase(java.util.Locale.ROOT);
        return command.equals("spawnjobs") || command.startsWith("spawnjobs ")
            || command.equals("district jobs") || command.startsWith("district job ");
    }

    private void openShopGui(Player player, NpcData.Npc npc) {
        // Load shop items from DB
        List<NpcData.ShopItem> items = new ArrayList<>();
        String sql = "SELECT * FROM npc_shop_items WHERE npc_id = ? ORDER BY slot";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npc.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int slot = rs.getInt("slot");
                ItemStack item = deserializeItem(rs.getString("item_data"));
                long price = rs.getLong("price");
                String command = rs.getString("command_on_purchase");
                items.add(new NpcData.ShopItem(slot, item, price,
                    command != null && !command.isEmpty() ? command : null));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load shop items", e);
        }

        if (items.isEmpty()) {
            player.sendMessage(fmt.error("This shop has no items configured."));
            return;
        }

        // Build GUI
        var guiItems = new ArrayList<com.vaultsurvival.plugin.core.GUIFramework.GUIItem>();
        for (NpcData.ShopItem shopItem : items) {
            ItemStack display = shopItem.getItem();
            var meta = display.getItemMeta();
            if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = meta.lore() != null ?
                new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(MessageFormatter.deserializeLegacy("&7Price: &6" + fmt.formatMoney(shopItem.getPrice(),
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            lore.add(MessageFormatter.deserializeLegacy("&eClick to purchase!"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }

            guiItems.add(new com.vaultsurvival.plugin.core.GUIFramework.GUIItem(
                shopItem.getSlot(), display, (p, e) -> {
                    // Check if player has enough cash
                    var currency = plugin.getServiceRegistry().get(
                        com.vaultsurvival.plugin.currency.CurrencyService.class);
                    long playerCash = currency.getPlayerCashTotal(p.getUniqueId());
                    if (playerCash < shopItem.getPrice()) {
                        p.sendMessage(fmt.error("You need &6" + fmt.formatMoney(shopItem.getPrice(),
                            plugin.getConfigManager().getCurrencyName(),
                            plugin.getConfigManager().getCurrencyNamePlural()) +
                            " &cbut only have &6" + fmt.formatMoney(playerCash,
                            plugin.getConfigManager().getCurrencyName(),
                            plugin.getConfigManager().getCurrencyNamePlural())));
                        p.closeInventory();
                        return;
                    }

                    // Withdraw cash
                    var withdrawn = currency.withdrawCash(p, shopItem.getPrice());
                    if (withdrawn.isEmpty()) {
                        p.sendMessage(fmt.error("Payment failed."));
                        return;
                    }

                    // Give item
                    if (p.getInventory().firstEmpty() == -1) {
                        p.getWorld().dropItemNaturally(p.getLocation(), shopItem.getItem());
                        p.sendMessage(fmt.warn("Inventory full! Item dropped at your feet."));
                    } else {
                        p.getInventory().addItem(shopItem.getItem());
                    }

                    p.sendMessage(fmt.success("Purchased for &6" + fmt.formatMoney(shopItem.getPrice(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())));

                    // Run command if configured
                    if (shopItem.getCommandOnPurchase() != null && !shopItem.getCommandOnPurchase().isEmpty()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            shopItem.getCommandOnPurchase().replace("%player%", p.getName()));
                    }

                    p.closeInventory();
                }));
        }

        plugin.getGuiFramework().openGUI(player,
            "&8" + npc.getName() + "'s Shop", 4, guiItems);
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public NpcData.Npc getNpc(int npcId) {
        return npcs.get(npcId);
    }

    @Override
    public NpcData.Npc getNpcByInteractionUuid(UUID interactionUuid) {
        return npcs.values().stream()
            .filter(n -> interactionUuid.equals(n.getInteractionUuid()))
            .findFirst().orElse(null);
    }

    @Override
    public NpcData.Npc getNpcByVisualUuid(UUID visualUuid) {
        return npcs.values().stream()
            .filter(n -> visualUuid.equals(n.getVisualUuid()))
            .findFirst().orElse(null);
    }

    @Override
    public List<NpcData.Npc> getAllNpcs() {
        return new ArrayList<>(npcs.values());
    }

    // ========================================================================
    // Load from Database
    // ========================================================================

    @Override
    public void loadAndSpawnAll() {
        npcs.clear();
        String sql = "SELECT * FROM npcs";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String skinUsername = rs.getString("skin_username");
                String worldName = rs.getString("world");
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                double z = rs.getDouble("z");
                float yaw = rs.getFloat("yaw");
                float pitch = rs.getFloat("pitch");
                NpcData.ActionType actionType = NpcData.ActionType.valueOf(
                    rs.getString("action_type"));
                String actionData = rs.getString("action_data");
                boolean lookAtPlayers = rs.getInt("look_at_players") == 1;

                NpcData.Npc npc = new NpcData.Npc(id, name, skinUsername,
                    worldName, x, y, z, yaw, pitch,
                    actionType, actionData, lookAtPlayers);

                npc.setEntityId(nextEntityId++);
                npcs.put(id, npc);

                logger.info("Loaded NPC #" + id + ": " + name +
                    " (" + skinUsername + ") at " + worldName);
            }

            npcs.values().forEach(this::spawnNpcToAll);

            logger.info("Loaded " + npcs.size() + " NPCs from database");

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load NPCs from database", e);
        }
    }

    // ========================================================================
    // Skin Fetching
    // ========================================================================

    private SkinTexture fetchSkin(String username) {
        if (skinCache.containsKey(username)) {
            return skinCache.get(username);
        }

        try {
            // Step 1: Get UUID from username
            URL uuidUrl = URI.create(
                "https://api.mojang.com/users/profiles/minecraft/" + username).toURL();
            HttpURLConnection uuidConn = (HttpURLConnection) uuidUrl.openConnection();
            uuidConn.setConnectTimeout(5000);
            uuidConn.setReadTimeout(5000);

            if (uuidConn.getResponseCode() != 200) {
                logger.warning("Failed to fetch UUID for skin username: " + username);
                return null;
            }

            JsonObject uuidJson = JsonParser.parseReader(
                new InputStreamReader(uuidConn.getInputStream())).getAsJsonObject();
            String uuid = uuidJson.get("id").getAsString();
            uuidConn.disconnect();

            // Step 2: Get skin texture from UUID
            URL profileUrl = URI.create(
                "https://sessionserver.mojang.com/session/minecraft/profile/" +
                uuid + "?unsigned=false").toURL();
            HttpURLConnection profileConn = (HttpURLConnection) profileUrl.openConnection();
            profileConn.setConnectTimeout(5000);
            profileConn.setReadTimeout(5000);

            if (profileConn.getResponseCode() != 200) {
                logger.warning("Failed to fetch profile for UUID: " + uuid);
                return null;
            }

            JsonObject profileJson = JsonParser.parseReader(
                new InputStreamReader(profileConn.getInputStream())).getAsJsonObject();
            JsonObject properties = profileJson.getAsJsonArray("properties").get(0).getAsJsonObject();
            String value = properties.get("value").getAsString();
            String signature = properties.has("signature") ?
                properties.get("signature").getAsString() : null;

            profileConn.disconnect();

            SkinTexture skin = new SkinTexture(value, signature);
            skinCache.put(username, skin);
            return skin;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to fetch skin for " + username, e);
            return null;
        }
    }

    // ========================================================================
    // NMS Helpers
    // ========================================================================

    private Object getPlayerConnection(Player player) throws Exception {
        Class<?> craftPlayerClass = Class.forName(
            "org.bukkit.craftbukkit." + getNmsVersion() + ".entity.CraftPlayer");
        Method getHandle = craftPlayerClass.getMethod("getHandle");
        Object serverPlayer = getHandle.invoke(craftPlayerClass.cast(player));

        Class<?> serverPlayerClass = Class.forName(
            "net.minecraft.server.level.ServerPlayer");
        Field connectionField = serverPlayerClass.getField("connection");
        return connectionField.get(serverPlayer);
    }

    private void sendPacket(Object connection, Object packet) throws Exception {
        Class<?> connectionClass = Class.forName(
            "net.minecraft.server.network.ServerGamePacketListenerImpl");
        if (sendPacket == null) {
            sendPacket = connectionClass.getMethod("send",
                Class.forName("net.minecraft.network.protocol.Packet"));
        }
        sendPacket.invoke(connection, packet);
    }

    // ========================================================================
    // Rebuild NPC (for action changes)
    // ========================================================================

    private NpcData.Npc rebuildNpc(NpcData.Npc old, NpcData.ActionType actionType, String actionData) {
        NpcData.Npc npc = new NpcData.Npc(old.getId(), old.getName(), old.getSkinUsername(),
            old.getWorldName(), old.getX(), old.getY(), old.getZ(),
            old.getYaw(), old.getPitch(), actionType, actionData, old.isLookAtPlayers());
        npc.getShopItems().addAll(old.getShopItems());
        npc.setEntityId(old.getEntityId());
        npc.setInteractionUuid(old.getInteractionUuid());
        npc.setVisualUuid(old.getVisualUuid());
        return npc;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String getNmsVersion() {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }

    // Item serialization
    private static String serializeItem(ItemStack item) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream boos =
                new org.bukkit.util.io.BukkitObjectOutputStream(baos);
            boos.writeObject(item);
            boos.close();
            return org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.encodeLines(baos.toByteArray());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to serialize item", e);
        }
    }

    private static ItemStack deserializeItem(String data) {
        try {
            byte[] bytes = org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.decodeLines(data);
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            org.bukkit.util.io.BukkitObjectInputStream bois =
                new org.bukkit.util.io.BukkitObjectInputStream(bais);
            return (ItemStack) bois.readObject();
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize item", e);
        }
    }

    // ========================================================================
    // Inner class
    // ========================================================================

    private static class SkinTexture {
        final String value;
        final String signature;

        SkinTexture(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }
}
