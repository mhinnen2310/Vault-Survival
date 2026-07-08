package com.vaultsurvival.plugin.breach;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.AuditLogger;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.regions.RegionData;
import com.vaultsurvival.plugin.regions.RegionService;
import com.vaultsurvival.plugin.vaults.VaultData;
import com.vaultsurvival.plugin.vaults.VaultService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of BreachService.
 *
 * The breach minigame has three stages played through a GUI:
 * 1. Timing Tumbler — click when the flashing indicator hits the target slot
 * 2. Pressure Balance — alternate left/right clicks to keep pressure in the green zone
 * 3. Final Dial — set three digits to match the hidden combination
 *
 * Score from each stage determines the stolen percentage (0% to 50% of vault balance).
 */
public class BreachServiceImpl implements BreachService {

    private final VaultSurvivalPlugin plugin;
    private final VaultService vaults;
    private final CurrencyService currency;
    private final RegionService regions;
    private final AuditLogger audit;
    private final MessageFormatter fmt;
    private final Logger logger;
    private final Random random;

    // In-memory state
    private final Map<UUID, BreachData.ActiveBreach> activeBreaches = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> breachTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>(); // expiry timestamps

    // PDC keys
    private final NamespacedKey breachKitKey;

    // Config constants
    private static final int TUMBLER_SLOTS = 5;
    private static final int TUMBLER_MAX_ATTEMPTS = 3;
    private static final int TUMBLER_SPEED_TICKS = 6; // ticks between indicator moves
    private static final int PRESSURE_BALANCE_DURATION_TICKS = 80; // ~4 seconds to hold
    private static final int PRESSURE_TICK_INTERVAL = 2; // pressure changes every N ticks
    private static final float PRESSURE_SPEED = 0.04f;
    private static final int DIAL_TIME_TICKS = 200; // ~10 seconds for dial
    private static final int DIAL_MAX_ATTEMPTS = 5;
    private static final int MAX_BREACH_DISTANCE = 8; // blocks from vault
    private static final int TELEPORT_COOLDOWN_SECONDS = 30;

    // GUI slot layouts
    private static final int GUI_ROWS = 5;
    private static final int TUMBLER_ROW = 2; // row index for tumbler slots
    private static final int TUMBLER_START_SLOT = 20; // slot for first tumbler indicator
    private static final int PRESSURE_LEFT_BUTTON = 29;
    private static final int PRESSURE_RIGHT_BUTTON = 33;
    private static final int PRESSURE_BAR_START = 21; // 5 slots for pressure bar
    private static final int DIAL_DIGIT_1 = 20;
    private static final int DIAL_DIGIT_2 = 22;
    private static final int DIAL_DIGIT_3 = 24;
    private static final int DIAL_UP_1 = 11;
    private static final int DIAL_DOWN_1 = 29;
    private static final int DIAL_UP_2 = 13;
    private static final int DIAL_DOWN_2 = 31;
    private static final int DIAL_UP_3 = 15;
    private static final int DIAL_DOWN_3 = 33;
    private static final int DIAL_SUBMIT = 40;

    public BreachServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.vaults = plugin.getServiceRegistry().get(VaultService.class);
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.regions = plugin.getServiceRegistry().get(RegionService.class);
        this.audit = plugin.getAuditLogger();
        this.fmt = plugin.getMessageFormatter();
        this.logger = plugin.getLogger();
        this.random = new Random();
        this.breachKitKey = new NamespacedKey(plugin, "vs_breach_kit");
    }

    // ========================================================================
    // Breach Kit
    // ========================================================================

    @Override
    public ItemStack createBreachKit() {
        ItemStack kit = new ItemStack(Material.PAPER);
        ItemMeta meta = kit.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageFormatter.deserializeLegacy("&c&lBreach Kit"));
            meta.lore(List.of(
                MessageFormatter.deserializeLegacy("&7Use on a vault to attempt a breach."),
                MessageFormatter.deserializeLegacy("&7Consumed on use. Success is not guaranteed."),
                MessageFormatter.deserializeLegacy("&8&oRight-click a vault or use /breach start")
            ));
            meta.getPersistentDataContainer().set(breachKitKey, PersistentDataType.BOOLEAN, true);
            kit.setItemMeta(meta);
        }
        return kit;
    }

    @Override
    public boolean isBreachKit(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(breachKitKey, PersistentDataType.BOOLEAN);
    }

    // ========================================================================
    // Start / Cancel Breach
    // ========================================================================

    @Override
    public boolean startBreach(Player thief, UUID vaultUuid) {
        UUID thiefUuid = thief.getUniqueId();

        // Already breaching?
        if (isBreaching(thiefUuid)) {
            thief.sendMessage(fmt.error("You are already breaching a vault!"));
            return false;
        }

        // Vault already being breached?
        if (isVaultBeingBreached(vaultUuid)) {
            thief.sendMessage(fmt.error("This vault is already being breached by someone else."));
            return false;
        }

        // Get vault data
        VaultData vault = vaults.getVault(vaultUuid);
        if (vault == null) {
            thief.sendMessage(fmt.error("Vault not found."));
            return false;
        }

        // Check lockdown
        if (vaults.isLockedDown(vaultUuid)) {
            thief.sendMessage(fmt.error("This vault is in lockdown and cannot be breached right now."));
            long remaining = vaults.getLockdownRemaining(vaultUuid);
            if (remaining > 0) {
                thief.sendMessage(fmt.info("Lockdown expires in: &e" + (remaining / 60) + "m " + (remaining % 60) + "s"));
            }
            return false;
        }

        // Check stealable amount
        long stealable = vaults.getStealableAmount(vaultUuid);
        if (stealable <= 0) {
            thief.sendMessage(fmt.error("This vault has no money that can be stolen."));
            return false;
        }

        // Check region allows breaching
        Location vaultLoc = new Location(
            plugin.getServer().getWorld(vault.getWorld()),
            vault.getX(), vault.getY(), vault.getZ()
        );
        if (regions != null && !regions.isAllowed(vaultLoc, RegionData.RuleFlag.BREACH_ALLOWED)) {
            thief.sendMessage(fmt.error("Breaching is not allowed in this area."));
            return false;
        }

        // Check proximity
        if (thief.getLocation().getWorld() != vaultLoc.getWorld() ||
            thief.getLocation().distance(vaultLoc) > MAX_BREACH_DISTANCE) {
            thief.sendMessage(fmt.error("You must be within " + MAX_BREACH_DISTANCE + " blocks of the vault."));
            return false;
        }

        // Consume breach kit from inventory
        boolean kitFound = false;
        for (ItemStack item : thief.getInventory().getContents()) {
            if (isBreachKit(item)) {
                item.setAmount(item.getAmount() - 1);
                kitFound = true;
                break;
            }
        }
        if (!kitFound) {
            thief.sendMessage(fmt.error("You need a Breach Kit to breach a vault!"));
            thief.sendMessage(fmt.info("Obtain one with &e/breach kit"));
            return false;
        }

        // Insert DB record
        UUID breachId = UUID.randomUUID();
        long vaultBalanceBefore = vaults.getBalance(vaultUuid);
        String sql = "INSERT INTO vault_breaches (vault_uuid, thief_uuid, started_at, vault_balance_before, success) " +
                     "VALUES (?, ?, datetime('now'), ?, 0)";
        try {
            plugin.getDatabase().executeUpdate(sql,
                vaultUuid.toString(), thiefUuid.toString(), vaultBalanceBefore);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to start breach DB record", e);
            thief.sendMessage(fmt.error("Internal error starting breach. Please try again."));
            return false;
        }

        // Create active breach state
        BreachData.ActiveBreach breach = new BreachData.ActiveBreach(
            breachId, thiefUuid, vaultUuid, vaultBalanceBefore, stealable
        );
        activeBreaches.put(thiefUuid, breach);

        // Start the minigame
        startMinigame(thief, breach, vaultLoc);

        audit.log(thiefUuid, thief.getName(), "BREACH_START", "VAULT",
            vaultUuid.toString(), "balance_before=" + vaultBalanceBefore);

        thief.sendMessage(fmt.header("VAULT BREACH"));
        thief.sendMessage(fmt.info("Target vault balance: &6" + fmt.formatMoney(vaultBalanceBefore,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));

        return true;
    }

    @Override
    public void cancelBreach(UUID playerUuid, String reason) {
        BreachData.ActiveBreach breach = activeBreaches.remove(playerUuid);
        if (breach == null) return;

        // Cancel the minigame task
        BukkitTask task = breachTasks.remove(playerUuid);
        if (task != null) task.cancel();

        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null) {
            player.closeInventory();
            sendBreachTitle(player, "&cBREACH FAILED", "&7" + reason, 10, 60, 10);
        }

        // Mark as failed in DB
        updateBreachRecord(breach.getVaultUuid(), breach.getThiefUuid(), false, 0,
            breach.getVaultBalanceBefore(), breach.getVaultBalanceBefore(), 0, reason);

        // Set lockdown on the vault (breach kit was consumed, vault gets cooldown)
        vaults.updateBalanceAfterBreach(breach.getVaultUuid(), 0);
    }

    @Override
    public boolean handleMinigameClick(Player player, int slot) {
        BreachData.ActiveBreach breach = activeBreaches.get(player.getUniqueId());
        if (breach == null) return false;

        switch (breach.getCurrentStage()) {
            case TUMBLER:
                handleTumblerClick(player, breach, slot);
                return true;
            case PRESSURE:
                handlePressureClick(player, breach, slot);
                return true;
            case DIAL:
                handleDialClick(player, breach, slot);
                return true;
            default:
                return false;
        }
    }

    // ========================================================================
    // Minigame - Stage Setup
    // ========================================================================

    private void startMinigame(Player thief, BreachData.ActiveBreach breach, Location vaultLoc) {
        // Create the GUI
        Inventory inv = Bukkit.createInventory(null, GUI_ROWS * 9,
            MessageFormatter.deserializeLegacy("&8⚡ Breach Minigame"));

        thief.openInventory(inv);
        setupTumbler(thief, breach, inv);
    }

    private void setupTumbler(Player thief, BreachData.ActiveBreach breach, Inventory inv) {
        breach.setTumblerTargetSlot(random.nextInt(TUMBLER_SLOTS));
        breach.setTumblerCurrentSlot(random.nextInt(TUMBLER_SLOTS));
        breach.setTumblerTick(0);
        breach.setTumblerAttempts(TUMBLER_MAX_ATTEMPTS);

        // Show status bar at top
        fillStatusRow(inv, breach);

        // Start tumbler animation task
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isBreaching(breach.getThiefUuid())) return;

            Player p = plugin.getServer().getPlayer(breach.getThiefUuid());
            if (p == null) {
                cancelBreach(breach.getThiefUuid(), "Disconnected");
                return;
            }

            // Check vault proximity
            if (!isNearVault(p, breach.getVaultUuid())) {
                cancelBreach(breach.getThiefUuid(), "Moved too far from vault");
                return;
            }

            // Advance tumbler indicator
            breach.setTumblerTick(breach.getTumblerTick() + 1);
            if (breach.getTumblerTick() >= TUMBLER_SPEED_TICKS) {
                breach.setTumblerTick(0);
                int next = (breach.getTumblerCurrentSlot() + 1) % TUMBLER_SLOTS;
                breach.setTumblerCurrentSlot(next);
                renderTumbler(p, breach);
            }
        }, 0L, 1L);

        breachTasks.put(breach.getThiefUuid(), task);
        renderTumbler(thief, breach);
    }

    // ========================================================================
    // Minigame - Stage 1: Timing Tumbler
    // ========================================================================

    private void renderTumbler(Player player, BreachData.ActiveBreach breach) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < 9; i++) {
            // Clear content rows
            for (int row = 1; row < GUI_ROWS; row++) {
                inv.setItem(row * 9 + i, null);
            }
        }

        fillStatusRow(inv, breach);

        // Instruction
        inv.setItem(10, createInfoItem(Material.PAPER,
            "&eStage 1: Timing Tumbler",
            List.of("&7Click the slot when the &aGREEN &7indicator",
                "&7hits the &6TARGET &7position!",
                "&7Attempts remaining: &e" + breach.getTumblerAttempts())));

        // Render tumbler slots
        for (int i = 0; i < TUMBLER_SLOTS; i++) {
            int slot = TUMBLER_START_SLOT + i;
            Material mat;
            String name;

            if (i == breach.getTumblerTargetSlot()) {
                // Target slot - always gold
                mat = Material.GOLD_BLOCK;
                name = "&6⭐ TARGET ⭐";
            } else if (i == breach.getTumblerCurrentSlot()) {
                // Current indicator - green (player should click when this hits target)
                mat = Material.LIME_STAINED_GLASS_PANE;
                name = "&a▼ CLICK WHEN ON TARGET";
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "&8•";
            }

            inv.setItem(slot, createClickableItem(mat, name, null));
        }

        // Score display
        inv.setItem(44, createInfoItem(Material.EXPERIENCE_BOTTLE,
            "&7Score: &e" + String.format("%.0f%%", breach.getTumblerScore() * 100),
            null));

        player.updateInventory();
    }

    private void handleTumblerClick(Player player, BreachData.ActiveBreach breach, int slot) {
        int clickedIndex = slot - TUMBLER_START_SLOT;
        if (clickedIndex < 0 || clickedIndex >= TUMBLER_SLOTS) return;

        int distance = Math.abs(clickedIndex - breach.getTumblerCurrentSlot());

        // Score based on timing accuracy
        double clickScore;
        if (clickedIndex == breach.getTumblerCurrentSlot()) {
            clickScore = 1.0; // Perfect!
        } else if (distance == 1 || distance == TUMBLER_SLOTS - 1) {
            clickScore = 0.5; // Close
        } else {
            clickScore = 0.1; // Way off
        }

        breach.setTumblerScore(Math.max(breach.getTumblerScore(), clickScore));
        breach.setTumblerAttempts(breach.getTumblerAttempts() - 1);

        if (clickScore >= 1.0) {
            sendBreachTitle(player, "&aPERFECT!", "&7Timing tumbler complete", 5, 30, 5);
        } else if (clickScore >= 0.5) {
            sendBreachTitle(player, "&eCLOSE!", "&7" + breach.getTumblerAttempts() + " attempts left", 5, 30, 5);
        } else {
            sendBreachTitle(player, "&cMISS!", "&7" + breach.getTumblerAttempts() + " attempts left", 5, 30, 5);
        }

        if (breach.getTumblerAttempts() <= 0 || clickScore >= 1.0) {
            // Advance to next stage
            advanceToNextStage(player, breach);
        } else {
            // Pick new target and keep going
            breach.setTumblerTargetSlot(random.nextInt(TUMBLER_SLOTS));
            renderTumbler(player, breach);
        }
    }

    // ========================================================================
    // Minigame - Stage 2: Pressure Balance
    // ========================================================================

    private void setupPressure(Player thief, BreachData.ActiveBreach breach, Inventory inv) {
        breach.setPressureLevel(0.3f + random.nextFloat() * 0.4f); // start at 30-70%
        breach.setPressureMovingUp(random.nextBoolean());
        breach.setPressureTicksRemaining(PRESSURE_BALANCE_DURATION_TICKS);
        breach.setPressureScore(0.5); // start at middle

        // Cancel old task, start new one
        BukkitTask oldTask = breachTasks.remove(breach.getThiefUuid());
        if (oldTask != null) oldTask.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isBreaching(breach.getThiefUuid())) return;

            Player p = plugin.getServer().getPlayer(breach.getThiefUuid());
            if (p == null) {
                cancelBreach(breach.getThiefUuid(), "Disconnected");
                return;
            }

            if (!isNearVault(p, breach.getVaultUuid())) {
                cancelBreach(breach.getThiefUuid(), "Moved too far from vault");
                return;
            }

            // Update pressure
            float delta = PRESSURE_SPEED * (breach.isPressureMovingUp() ? 1 : -1);
            breach.setPressureLevel(breach.getPressureLevel() + delta);

            // Bounce off limits
            if (breach.getPressureLevel() >= 1.0f) {
                breach.setPressureLevel(1.0f);
                breach.setPressureMovingUp(false);
            } else if (breach.getPressureLevel() <= 0.0f) {
                breach.setPressureLevel(0.0f);
                breach.setPressureMovingUp(true);
            }

            // Track how well player maintains balance (target is 0.4-0.6 range)
            float midPoint = 0.5f;
            float deviation = Math.abs(breach.getPressureLevel() - midPoint);
            double instantScore = deviation < 0.1 ? 1.0 : deviation < 0.2 ? 0.7 : deviation < 0.3 ? 0.4 : 0.1;
            breach.setPressureScore((breach.getPressureScore() * 0.8) + (instantScore * 0.2)); // smooth average

            breach.setPressureTicksRemaining(breach.getPressureTicksRemaining() - 1);

            if (breach.getPressureTicksRemaining() <= 0) {
                advanceToNextStage(p, breach);
                return;
            }

            renderPressure(p, breach);
        }, 0L, PRESSURE_TICK_INTERVAL);

        breachTasks.put(breach.getThiefUuid(), task);
        renderPressure(thief, breach);
    }

    private void renderPressure(Player player, BreachData.ActiveBreach breach) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < 9; i++) {
            for (int row = 1; row < GUI_ROWS; row++) {
                inv.setItem(row * 9 + i, null);
            }
        }

        fillStatusRow(inv, breach);
        int remainingSec = breach.getPressureTicksRemaining() / 20;

        // Instruction
        inv.setItem(10, createInfoItem(Material.PAPER,
            "&eStage 2: Pressure Balance",
            List.of("&7Click &aLEFT &7or &cRIGHT &7to flip the direction",
                "&7and keep pressure in the &aGREEN ZONE!",
                "&7Time left: &e" + remainingSec + "s")));

        // Pressure bar (5 slots)
        float pressure = breach.getPressureLevel();
        for (int i = 0; i < 5; i++) {
            int slot = PRESSURE_BAR_START + i;
            float regionStart = i / 5.0f;
            float regionEnd = (i + 1) / 5.0f;

            Material mat;
            String name;
            if (pressure >= regionStart && pressure < regionEnd) {
                // This is where the indicator is
                if (i == 2) {
                    mat = Material.LIME_STAINED_GLASS_PANE; // Green zone
                    name = "&a◆ PERFECT BALANCE";
                } else if (i == 1 || i == 3) {
                    mat = Material.YELLOW_STAINED_GLASS_PANE; // Yellow zone
                    name = "&e◆ Acceptable";
                } else {
                    mat = Material.RED_STAINED_GLASS_PANE; // Red zone
                    name = "&c◆ DANGER";
                }
            } else {
                mat = Material.GRAY_STAINED_GLASS_PANE;
                name = "&8•";
            }
            inv.setItem(slot, createClickableItem(mat, name, null));
        }

        // Buttons
        Material leftMat = breach.isPressureMovingUp() ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        inv.setItem(PRESSURE_LEFT_BUTTON, createClickableItem(leftMat,
            "&a◀ LEFT (push pressure DOWN)",
            List.of("&7Click to make pressure go DOWN")));

        Material rightMat = breach.isPressureMovingUp() ? Material.RED_CONCRETE : Material.LIME_CONCRETE;
        inv.setItem(PRESSURE_RIGHT_BUTTON, createClickableItem(rightMat,
            "&cRIGHT ▶ (push pressure UP)",
            List.of("&7Click to make pressure go UP")));

        // Score display
        inv.setItem(44, createInfoItem(Material.EXPERIENCE_BOTTLE,
            "&7Score: &e" + String.format("%.0f%%", breach.getPressureScore() * 100),
            null));

        player.updateInventory();
    }

    private void handlePressureClick(Player thievingPlayer, BreachData.ActiveBreach breach, int slot) {
        if (slot == PRESSURE_LEFT_BUTTON) {
            breach.setPressureMovingUp(false); // left = go down (toward center)
            thievingPlayer.playSound(thievingPlayer.getLocation(),
                org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        } else if (slot == PRESSURE_RIGHT_BUTTON) {
            breach.setPressureMovingUp(true); // right = go up (toward center)
            thievingPlayer.playSound(thievingPlayer.getLocation(),
                org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
        }
    }

    // ========================================================================
    // Minigame - Stage 3: Final Dial
    // ========================================================================

    private void setupDial(Player thief, BreachData.ActiveBreach breach, Inventory inv) {
        // Generate target combination
        int[] target = new int[]{
            random.nextInt(10), random.nextInt(10), random.nextInt(10)
        };
        breach.setDialTarget(target);
        breach.setDialCurrent(new int[]{5, 5, 5});
        breach.setDialAttemptsRemaining(DIAL_MAX_ATTEMPTS);
        breach.setDialTicksRemaining(DIAL_TIME_TICKS);

        // Cancel old task
        BukkitTask oldTask = breachTasks.remove(breach.getThiefUuid());
        if (oldTask != null) oldTask.cancel();

        // Timer task
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isBreaching(breach.getThiefUuid())) return;

            Player p = plugin.getServer().getPlayer(breach.getThiefUuid());
            if (p == null) {
                cancelBreach(breach.getThiefUuid(), "Disconnected");
                return;
            }

            if (!isNearVault(p, breach.getVaultUuid())) {
                cancelBreach(breach.getThiefUuid(), "Moved too far from vault");
                return;
            }

            breach.setDialTicksRemaining(breach.getDialTicksRemaining() - 1);

            if (breach.getDialTicksRemaining() <= 0) {
                // Time's up - dial fails
                breach.setDialScore(0);
                advanceToNextStage(p, breach);
                return;
            }

            renderDial(p, breach);
        }, 0L, 20L); // update every second

        breachTasks.put(breach.getThiefUuid(), task);
        sendBreachTitle(thief, "&dFINAL DIAL", "&7Crack the combination!", 10, 40, 10);
        renderDial(thief, breach);
    }

    private void renderDial(Player player, BreachData.ActiveBreach breach) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < 9; i++) {
            for (int row = 1; row < GUI_ROWS; row++) {
                inv.setItem(row * 9 + i, null);
            }
        }

        fillStatusRow(inv, breach);
        int remainingSec = breach.getDialTicksRemaining() / 20;

        // Instruction
        inv.setItem(10, createInfoItem(Material.PAPER,
            "&eStage 3: Final Dial",
            List.of("&7Set the three digits to crack the vault!",
                "&7You'll get clues after each attempt.",
                "&7Attempts: &e" + breach.getDialAttemptsRemaining() + " &7| Time: &e" + remainingSec + "s")));

        // Three digit display
        int[] digits = breach.getDialCurrent();
        Material digitMat = Material.CLOCK;
        for (int d = 0; d < 3; d++) {
            int digitSlot = DIAL_DIGIT_1 + d * 2;
            inv.setItem(digitSlot, createClickableItem(digitMat,
                "&6" + digits[d],
                List.of("&7Digit " + (d + 1))));
        }

        // Up/Down buttons for each digit
        inv.setItem(DIAL_UP_1, createUpDownButton(Material.LIME_STAINED_GLASS_PANE, "&a▲", true, 1));
        inv.setItem(DIAL_DOWN_1, createUpDownButton(Material.RED_STAINED_GLASS_PANE, "&c▼", false, 1));
        inv.setItem(DIAL_UP_2, createUpDownButton(Material.LIME_STAINED_GLASS_PANE, "&a▲", true, 2));
        inv.setItem(DIAL_DOWN_2, createUpDownButton(Material.RED_STAINED_GLASS_PANE, "&c▼", false, 2));
        inv.setItem(DIAL_UP_3, createUpDownButton(Material.LIME_STAINED_GLASS_PANE, "&a▲", true, 3));
        inv.setItem(DIAL_DOWN_3, createUpDownButton(Material.RED_STAINED_GLASS_PANE, "&c▼", false, 3));

        // Submit button
        inv.setItem(DIAL_SUBMIT, createClickableItem(Material.GOLD_BLOCK,
            "&6&lSUBMIT CODE",
            List.of("&7Click to test your combination")));

        player.updateInventory();
    }

    private void handleDialClick(Player player, BreachData.ActiveBreach breach, int slot) {
        int[] digits = breach.getDialCurrent();

        // Digit up/down buttons
        if (slot == DIAL_UP_1) { digits[0] = (digits[0] + 1) % 10; }
        else if (slot == DIAL_DOWN_1) { digits[0] = (digits[0] + 9) % 10; }
        else if (slot == DIAL_UP_2) { digits[1] = (digits[1] + 1) % 10; }
        else if (slot == DIAL_DOWN_2) { digits[1] = (digits[1] + 9) % 10; }
        else if (slot == DIAL_UP_3) { digits[2] = (digits[2] + 1) % 10; }
        else if (slot == DIAL_DOWN_3) { digits[2] = (digits[2] + 9) % 10; }
        else if (slot == DIAL_SUBMIT) {
            // Check combination
            int[] target = breach.getDialTarget();
            int correctDigits = 0;
            int closeDigits = 0;

            for (int i = 0; i < 3; i++) {
                if (digits[i] == target[i]) {
                    correctDigits++;
                } else if (Math.abs(digits[i] - target[i]) <= 2) {
                    closeDigits++;
                }
            }

            breach.setDialAttemptsRemaining(breach.getDialAttemptsRemaining() - 1);

            if (correctDigits == 3) {
                // Perfect!
                breach.setDialScore(1.0);
                sendBreachTitle(player, "&a&lCRACKED!", "&7Perfect combination!", 10, 60, 10);
                advanceToNextStage(player, breach);
                return;
            } else {
                // Give feedback
                StringBuilder hint = new StringBuilder("&e" + correctDigits + " &7correct, &e" + closeDigits + " &7close");
                sendBreachTitle(player, "&cWRONG", hint.toString(), 5, 30, 5);

                double attemptScore = (correctDigits * 0.33) + (closeDigits * 0.11);
                breach.setDialScore(Math.max(breach.getDialScore(), attemptScore));

                if (breach.getDialAttemptsRemaining() <= 0) {
                    sendBreachTitle(player, "&cOUT OF ATTEMPTS",
                        "&7Code was: &e" + target[0] + target[1] + target[2], 10, 60, 10);
                    advanceToNextStage(player, breach);
                    return;
                }
            }
        }

        breach.setDialCurrent(digits);
        renderDial(player, breach);
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    // ========================================================================
    // Stage Transition & Completion
    // ========================================================================

    private void advanceToNextStage(Player thief, BreachData.ActiveBreach breach) {
        breach.advanceStage();

        switch (breach.getCurrentStage()) {
            case PRESSURE:
                // Integrate tumbler score
                breach.setScore(breach.getScore() + breach.getTumblerScore() * (1.0 / 3.0));
                thief.sendMessage(fmt.info("Tumbler score: &e" + String.format("%.0f%%", breach.getTumblerScore() * 100)));
                setupPressure(thief, breach, thief.getOpenInventory().getTopInventory());
                break;

            case DIAL:
                // Integrate pressure score
                breach.setScore(breach.getScore() + breach.getPressureScore() * (1.0 / 3.0));
                thief.sendMessage(fmt.info("Pressure score: &e" + String.format("%.0f%%", breach.getPressureScore() * 100)));
                setupDial(thief, breach, thief.getOpenInventory().getTopInventory());
                break;

            case COMPLETE:
                // Integrate dial score
                breach.setScore(breach.getScore() + breach.getDialScore() * (1.0 / 3.0));
                thief.sendMessage(fmt.info("Dial score: &e" + String.format("%.0f%%", breach.getDialScore() * 100)));
                completeBreach(thief, breach);
                break;
        }
    }

    private void completeBreach(Player thief, BreachData.ActiveBreach breach) {
        // Cancel task
        BukkitTask task = breachTasks.remove(breach.getThiefUuid());
        if (task != null) task.cancel();

        // Remove from active map
        activeBreaches.remove(breach.getThiefUuid());

        // Close GUI
        thief.closeInventory();

        long stolen = breach.calculateStolenAmount();
        boolean success = stolen > 0;

        if (success) {
            // Enforce 50% cap at service level
            long stealable = vaults.getStealableAmount(breach.getVaultUuid());
            if (stolen > stealable) stolen = stealable;

            // Create stolen cash
            ItemStack stolenCash = currency.mintCash(stolen, breach.getThiefUuid(), breach.getThiefUuid());

            // Give to thief (or drop if inventory full)
            if (thief.getInventory().firstEmpty() == -1) {
                thief.getWorld().dropItemNaturally(thief.getLocation(), stolenCash);
                thief.sendMessage(fmt.warn("Your inventory is full! Cash dropped at your feet."));
            } else {
                thief.getInventory().addItem(stolenCash);
            }

            sendBreachTitle(thief, "&a&lBREACH SUCCESSFUL!",
                "&7Stole: &6" + fmt.formatMoney(stolen,
                    plugin.getConfigManager().getCurrencyName(),
                    plugin.getConfigManager().getCurrencyNamePlural()),
                10, 80, 20);

            // Update vault balance (mark vault cash as spent proportional to stolen amount)
            spendVaultCashForBreach(breach.getVaultUuid(), stolen);
            vaults.updateBalanceAfterBreach(breach.getVaultUuid(), stolen);

        } else {
            sendBreachTitle(thief, "&cBREACH FAILED",
                "&7You couldn't crack this vault.", 10, 60, 10);

            // Still trigger lockdown on failure
            vaults.updateBalanceAfterBreach(breach.getVaultUuid(), 0);
        }

        // Apply teleport cooldown
        applyTeleportCooldown(breach.getThiefUuid(), TELEPORT_COOLDOWN_SECONDS);

        // Update DB record
        long balanceAfter = vaults.getBalance(breach.getVaultUuid());
        updateBreachRecord(breach.getVaultUuid(), breach.getThiefUuid(), success, stolen,
            breach.getVaultBalanceBefore(), balanceAfter, breach.getScore(), null);

        // Audit
        audit.log(breach.getThiefUuid(), thief.getName(),
            success ? "BREACH_SUCCESS" : "BREACH_FAILED", "VAULT",
            breach.getVaultUuid().toString(),
            "stolen=" + stolen + " score=" + String.format("%.2f", breach.getScore()));

        thief.sendMessage(fmt.header("BREACH COMPLETE"));
        thief.sendMessage(fmt.info("Final score: &e" + String.format("%.0f%%", breach.getScore() * 100)));
        thief.sendMessage(fmt.info("Stolen: &6" + fmt.formatMoney(stolen,
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        thief.sendMessage(fmt.info("Vault is now in &clockdown&7."));
        thief.sendMessage(fmt.warn("You cannot teleport for " + TELEPORT_COOLDOWN_SECONDS + " seconds. Escape!"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private boolean isNearVault(Player player, UUID vaultUuid) {
        VaultData vault = vaults.getVault(vaultUuid);
        if (vault == null) return false;
        Location vaultLoc = new Location(
            plugin.getServer().getWorld(vault.getWorld()),
            vault.getX(), vault.getY(), vault.getZ()
        );
        return player.getLocation().getWorld() == vaultLoc.getWorld() &&
               player.getLocation().distance(vaultLoc) <= MAX_BREACH_DISTANCE;
    }

    private void fillStatusRow(Inventory inv, BreachData.ActiveBreach breach) {
        // Stage indicator in top row
        String[] stageNames = {"TUMBLER", "PRESSURE", "DIAL"};
        for (int i = 0; i < 3; i++) {
            Material mat = i < breach.getStageIndex() ? Material.LIME_STAINED_GLASS_PANE :
                           i == breach.getStageIndex() ? Material.YELLOW_STAINED_GLASS_PANE :
                           Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(i + 3, createInfoItem(mat,
                "&7Stage " + (i + 1) + ": &f" + stageNames[i] +
                (i < breach.getStageIndex() ? " &a✔" : i == breach.getStageIndex() ? " &e◀" : ""),
                null));
        }
    }

    /**
     * Mark vault cash items as SPENT proportional to the stolen amount.
     * This prevents money duplication: the breach steals existing vault cash,
     * it doesn't create new money from thin air.
     */
    private void spendVaultCashForBreach(UUID vaultUuid, long amount) {
        if (amount <= 0) return;

        String findSql = "SELECT cash_uuid, amount FROM cash_items " +
                         "WHERE state = 'IN_VAULT' AND location_id = ? ORDER BY amount ASC";

        try (Connection conn = plugin.getDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try {
                long remaining = amount;
                List<UUID> toSpend = new ArrayList<>();
                UUID partialUuid = null;
                long partialNewAmount = 0;

                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setString(1, vaultUuid.toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next() && remaining > 0) {
                        UUID cashUuid = UUID.fromString(rs.getString("cash_uuid"));
                        long cashAmount = rs.getLong("amount");

                        if (cashAmount <= remaining) {
                            toSpend.add(cashUuid);
                            remaining -= cashAmount;
                        } else {
                            // Partial spend: reduce this item's amount
                            partialUuid = cashUuid;
                            partialNewAmount = cashAmount - remaining;
                            remaining = 0;
                        }
                    }
                }

                // Mark whole items as SPENT
                if (!toSpend.isEmpty()) {
                    String spendSql = "UPDATE cash_items SET state = 'SPENT', last_seen_at = datetime('now') " +
                                      "WHERE cash_uuid = ?";
                    try (PreparedStatement ps = conn.prepareStatement(spendSql)) {
                        for (UUID uuid : toSpend) {
                            ps.setString(1, uuid.toString());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                // Reduce amount of partially-spent item
                if (partialUuid != null) {
                    String partialSql = "UPDATE cash_items SET amount = ?, last_seen_at = datetime('now') " +
                                        "WHERE cash_uuid = ?";
                    try (PreparedStatement ps = conn.prepareStatement(partialSql)) {
                        ps.setLong(1, partialNewAmount);
                        ps.setString(2, partialUuid.toString());
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to spend vault cash after breach, vault=" +
                vaultUuid + " amount=" + amount, e);
        }
    }

    /**
     * Update breach record using vault and thief UUIDs.
     */
    private void updateBreachRecord(UUID vaultUuid, UUID thiefUuid, boolean success, long stolenAmount,
                                     long balanceBefore, long balanceAfter, double score, String failedReason) {
        String sql = "UPDATE vault_breaches SET completed_at = datetime('now'), success = ?, " +
                     "stolen_amount = ?, vault_balance_before = ?, vault_balance_after = ?, " +
                     "breach_score = ?, failed_reason = ? " +
                     "WHERE vault_uuid = ? AND thief_uuid = ? AND completed_at IS NULL";
        try {
            plugin.getDatabase().executeUpdate(sql,
                success ? 1 : 0, stolenAmount, balanceBefore, balanceAfter, score,
                failedReason, vaultUuid.toString(), thiefUuid.toString());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to update breach record for vault=" +
                vaultUuid + " thief=" + thiefUuid, e);
        }
    }

    private ItemStack createClickableItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageFormatter.deserializeLegacy(name));
            if (lore != null) {
                List<Component> compLore = new ArrayList<>();
                for (String line : lore) {
                    compLore.add(MessageFormatter.deserializeLegacy(line));
                }
                meta.lore(compLore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(Material mat, String name, List<String> lore) {
        return createClickableItem(mat, name, lore);
    }

    private ItemStack createUpDownButton(Material mat, String name, boolean isUp, int digit) {
        return createClickableItem(mat, name,
            List.of("&7" + (isUp ? "Increase" : "Decrease") + " digit " + digit));
    }

    private void sendBreachTitle(Player player, String title, String subtitle,
                                  int fadeIn, int stay, int fadeOut) {
        player.showTitle(net.kyori.adventure.title.Title.title(
            MessageFormatter.deserializeLegacy(title),
            MessageFormatter.deserializeLegacy(subtitle),
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(fadeIn * 50L),
                java.time.Duration.ofMillis(stay * 50L),
                java.time.Duration.ofMillis(fadeOut * 50L)
            )
        ));
    }

    // ========================================================================
    // Queries
    // ========================================================================

    @Override
    public BreachData.ActiveBreach getActiveBreach(UUID playerUuid) {
        return activeBreaches.get(playerUuid);
    }

    @Override
    public boolean isBreaching(UUID playerUuid) {
        return activeBreaches.containsKey(playerUuid);
    }

    @Override
    public boolean isVaultBeingBreached(UUID vaultUuid) {
        return activeBreaches.values().stream()
            .anyMatch(b -> b.getVaultUuid().equals(vaultUuid));
    }

    @Override
    public void applyTeleportCooldown(UUID playerUuid, int seconds) {
        teleportCooldowns.put(playerUuid, System.currentTimeMillis() + seconds * 1000L);
    }

    @Override
    public boolean isTeleportBlocked(UUID playerUuid) {
        Long expiry = teleportCooldowns.get(playerUuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            teleportCooldowns.remove(playerUuid);
            return false;
        }
        return true;
    }

    @Override
    public List<BreachData.BreachLogEntry> getBreachLogs(UUID vaultUuid) {
        List<BreachData.BreachLogEntry> logs = new ArrayList<>();
        String sql = "SELECT * FROM vault_breaches WHERE vault_uuid = ? ORDER BY started_at DESC LIMIT 50";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vaultUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                logs.add(mapLogEntry(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get breach logs for vault: " + vaultUuid, e);
        }
        return logs;
    }

    @Override
    public List<BreachData.BreachLogEntry> getBreachLogsByThief(UUID thiefUuid) {
        List<BreachData.BreachLogEntry> logs = new ArrayList<>();
        String sql = "SELECT * FROM vault_breaches WHERE thief_uuid = ? ORDER BY started_at DESC LIMIT 50";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, thiefUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                logs.add(mapLogEntry(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get breach logs for thief: " + thiefUuid, e);
        }
        return logs;
    }

    private BreachData.BreachLogEntry mapLogEntry(ResultSet rs) throws SQLException {
        return new BreachData.BreachLogEntry(
            UUID.fromString(rs.getString("vault_uuid")),
            UUID.fromString(rs.getString("thief_uuid")),
            rs.getString("started_at"),
            rs.getString("completed_at"),
            rs.getInt("success") == 1,
            rs.getLong("stolen_amount"),
            rs.getLong("vault_balance_before"),
            rs.getLong("vault_balance_after"),
            rs.getDouble("breach_score"),
            rs.getString("failed_reason")
        );
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    /** Cancel all active breaches (called on plugin disable). */
    public void cancelAllBreaches() {
        for (UUID playerUuid : new ArrayList<>(activeBreaches.keySet())) {
            cancelBreach(playerUuid, "Server shutdown");
        }
        breachTasks.values().forEach(BukkitTask::cancel);
        breachTasks.clear();
        activeBreaches.clear();
    }
}
