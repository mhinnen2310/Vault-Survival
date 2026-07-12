# Vault Survival — Changelog & Status

## Version 1.0.28 (2026-07-10)

- Closed the complete 45-item product-placeholder audit. See `docs/OPEN_PLACEHOLDERS.md` for the closure register.
- Added persistent district join requests, player reports, diplomacy, district-job disputes, and Kingdom Support assignment/completion.
- Added player notification, dialog-style, and privacy preferences plus complete Vault, District, and Auction Hall guides.
- Completed district market, merchant, treasury, rail station/route/ticket, staff player, reports, cash trace, moderation, police-abuse, and rail-log dialogs.
- Added persistent actionable staff alerts with assignment, resolution, located-alert teleports, and a shared staff return stack.
- Added Paper anti-xray verification/control, storage discovery, movement/combat/inventory scoring, and suspicious payout review.
- Added computed current-area risk, market, job, and station context plus real district maintenance scoring and controlled decay.
- Enforced `merchant.max_active_orders` before escrow withdrawal.
- Added merchant market-zone border visualization, staff district teleport, and missing-unlocked-only district NPC placement.
- Added a fully isolated, unlimited staff sandbox entered from active staffmode with `/staffmode test`.

---

## Version 0.1.0-pre-alpha (2026-07-08)

---

## ✅ WHAT'S NEW

### Phase 0 — Core Platform

| Module | Status | Description |
|--------|--------|-------------|
| VS-Core | ✅ Complete | Module system, ServiceRegistry, ConfigManager, SQLite database, AuditLogger, MessageFormatter, SchedulerHelper, TransactionHelper, GUIFramework, ItemSerializer |
| VS-Access | ✅ Complete | Custom permission system (groups, inheritance, temp grants, prefixes). Replaces LuckPerms. |

**Commands**: `/rank <player> <set|add|remove|info> [group]`

### Phase 1 — Physical Currency

| Module | Status | Description |
|--------|--------|-------------|
| VS-Currency | ✅ Complete | Physical cash with server-authoritative DB records. Mint, split, merge, validate, invalidate. Forbidden container enforcement (shulkers, bundles, ender chests, hoppers, droppers, dispensers, item frames). Death drop handling. Pickup validation. Counterfeit scanning. |

**Commands**: `/cash inspect`, `/cash trace <uuid>`, `/cash mint <amount> [player]`, `/cash invalidate <uuid>`, `/cash scan [player]`, `/cash stats`

### Phase 2 — Vaults

| Module | Status | Description |
|--------|--------|-------------|
| VS-Vaults | ✅ Complete | Physical vault blocks (BARREL). Cannot be broken, exploded, or piston-moved. Deposit/withdraw cash. 50% protection invariant. Lockdown on breach. Repair at 5% of balance. Access control. |

**Commands**: `/vault place <tier>`, `/vault remove`, `/vault info`, `/vault deposit`, `/vault withdraw <amount>`, `/vault access <add|remove> <player>`, `/vault repair`, `/vault list`, `/vault inspect <uuid>` (admin)

### Phase 3 — Breach

| Module | Status | Description |
|--------|--------|-------------|
| VS-Breach | ✅ Complete | Multi-stage vault breaching minigame. Players use breach kits to steal up to 50% of vault balance through 3 skill-based stages. Crash-safe, DB-logged, teleport cooldown after breach. |

**Minigame Stages**:
1. **Timing Tumbler** — Click when the green indicator hits the gold target slot. 3 attempts, scored on timing accuracy.
2. **Pressure Balance** — Keep pressure in the green zone by clicking LEFT/RIGHT buttons to flip direction. Scored on stability.
3. **Final Dial** — Crack a 3-digit combination with up/down buttons. Clues after each attempt. Scored on accuracy.

**Commands**: `/breach kit`, `/breach start`, `/breach cancel`, `/breach log <uuid>`, `/breach logplayer <name>`, `/breach escapecooldown <player>`

### Chat & Prefixes

| Feature | Status | Description |
|---------|--------|-------------|
| Rank prefixes | ✅ Complete | Prefix labels before player names in chat, tab list, and nametags. Format: `{prefix}&r {name}&7: {message}` |
| Configurable | ✅ Complete | All formatting options in `config.yml` under `chat:` section |

### Staff Mode

| Feature | Status | Description |
|---------|--------|-------------|
| `/staffmode` | ✅ Complete | Toggle staff mode with full inventory separation |
| Inventory separation | ✅ Complete | Gameplay inventory saved/restored. Staff inventory never leaks to gameplay. |
| Block tracking | ✅ Complete | All blocks placed/broken in staffmode tracked and reverted on exit (cross-world) |
| Container protection | ✅ Complete | Cannot open chests, interact with inventories, or drag items into containers |
| Drop/pickup prevention | ✅ Complete | Cannot drop items or pick up entities in staffmode |
| PvP protection | ✅ Complete | Staff in staffmode cannot be damaged |
| Visibility | ✅ Complete | Glowing effect + `[STAFF]` prefix in name |
| Bypass `/staffmode *` | ✅ Complete | Owner override — bypasses ALL staffmode restrictions for testing |
| Grant bypass | ✅ Complete | `/staffmode * <player>` — owner can grant bypass to other staff |
| Rejoin restore | ✅ Complete | Full staffmode state restored on disconnect/reconnect (including bypass) |

**Permissions**: `vs.staffmode.use`, `vs.staffmode.bypass`, `vs.staffmode.bypass.grant`

### Staff Testing Tools

| Feature | Status | Description |
|---------|--------|-------------|
| `/vsgive` | ✅ Complete | Spawns cash, breach kits, and vault blocks for testing. Only usable in staffmode bypass mode. |

**Commands**: `/vsgive cash <amount>`, `/vsgive breachkit`, `/vsgive vault`

### NPC System

| Module | Status | Description |
|--------|--------|-------------|
| VS-NPC | ✅ Complete | Custom NPC system with real player-model entities (NMS), Mojang skin fetching, Interaction entity click detection, custom shops, command execution. |

**Commands**: `/npc create`, `/npc remove`, `/npc movehere`, `/npc skin`, `/npc command`, `/npc shop`, `/npc market`, `/npc additem`, `/npc clearitems`, `/npc list`, `/npc tphere`

**Key features**: Real player-model NPCs via NMS packet reflection (ClientboundPlayerInfoUpdatePacket + ClientboundAddEntityPacket), Mojang skin fetching with caching, Bukkit Interaction entities for click detection, custom shop GUI, action types (COMMAND, SHOP, MARKET, NONE), full SQLite persistence, NpcInteractEvent for module hooks.

### Phase 4 — Physical Auction Hall

| Module | Status | Description |
|--------|--------|-------------|
| VS-Market | ✅ Complete | Physical Auction Hall. No global /ah — players must visit in person. Items escrowed, physical cash payment, seller earnings in physical Auction Locker. Atomic transactions with rollback. |

**Commands**: `/ah sell <price> [category] [hours]`, `/ah buy <listing_uuid>`, `/ah listings [category]`, `/ah collect`, `/ah cancel <listing_uuid>`, `/ah inspect <listing_uuid>`

**Key features**: Atomic buy transactions (DB commit first, inventory after), item escrow via Base64 serialization, seller earnings as IN_AUCTION_LOCKER cash_items, tax collection, listing expiration, category browsing.

### Phase 5 — Regions

| Module | Status | Description |
|--------|--------|-------------|
| VS-Regions | ✅ Complete | Custom region/rules system. Cuboid areas with configurable rule flags. Priority-based resolution. Wand selection tool. Replaces WorldGuard for core gameplay rules. |

**Commands**: `/region wand`, `/region create <name> <type> [priority]`, `/region delete <id>`, `/region info [id]`, `/region flag <id> <flag> <true|false>`, `/region list`, `/region here`, `/region show <id>`

**Key features**: 12 region types with type-based default flags, 13 rule flags, priority-based resolution, wand selection (golden axe) with particle feedback, `/region show` cuboid visualization, RegionService available to all modules.

### Phase 6 — District Foundation

| Module | Status | Description |
|--------|--------|-------------|
| VS-Districts | ✅ Complete | Full district governance. Players apply to found districts, admins approve/reject. Role hierarchy (7 roles), atomic treasury management, local laws, member management. |

**Commands**: `/district apply`, `/district approve`, `/district reject`, `/district info`, `/district invite`, `/district kick`, `/district role`, `/district deposit`, `/district withdraw`, `/district law`, `/district list`, `/district disband`, `/district applications`

**Key features**: 1500+ blocks from spawn requirement, 500+ blocks between districts, auto-creates DISTRICT region on approval, 7 roles (MAYOR, COUNCIL, TREASURER, POLICE, BUILDER, MERCHANT, CITIZEN), atomic treasury deposits/withdrawals, configurable local laws.

### Phase 7 — Temporary District Damage

| Module | Status | Description |
|--------|--------|-------------|
| VS-Damage | ✅ Complete | Temporary damage tracking. Non-member block breaks/places in districts are recorded and auto-restored. Drops cancelled. Block classification system. |

**Commands**: `/damage info`, `/damage list [districtId]`, `/damage restore <id>`

**Key features**: Block drops cancelled for non-members, original state saved and scheduled for restoration, 4 block classes (STRUCTURE, VALUABLE, CONTAINER, FUNCTIONAL), containers restored empty, explosion protection in districts, anti-dupe checks, periodic restore checker every 30s.

### Phase 8 — Repairmen

| Module | Status | Description |
|--------|--------|-------------|
| VS-Repair | ✅ Complete | Repair point system per district. Points consumed on damage. Daily wage from treasury. NPC integration. |

**Commands**: `/repair status`, `/repair pay`, `/repair emergency <damageId>`, `/repair npc`

**Key features**: 500 repair points/day per district, normal restore (10 min) vs exhausted (30 min), daily wage (1000 cash) from treasury, emergency repair costs 5 points (council+), repairman NPC creation at player location, cross-district validation, daily reset scheduler every 5 min.

### Phase 9 — Crime & Police

| Module | Status | Description |
|--------|--------|-------------|
| VS-Crime | ✅ Complete | Crime logging, wanted status, police tools, jail system. Auto-logs valuable block thefts. |

**Commands**: `/crime wanted`, `/crime record [player]`, `/crime bounty <player> <amount>`, `/crime arrest <player>`, `/crime fine <player> <amount>`, `/crime setjail`, `/crime release <player>`, `/crime jailed`

**Key features**: Auto-crime logging for VALUABLE and CONTAINER blocks via MONITOR listener, 5 crime types (THEFT, GRIEF, TRESPASS, ASSAULT, VANDALISM), 3 severity levels, per-district wanted tracking, police alerts to POLICE-role members, arrest with 10-block proximity, jail teleport (5 min/crime), movement constraint within 5 blocks, block interaction prevention, auto-release scheduler, bounty system, fines transferred to district treasury.

### Phase 10 — Display Auction Hall

| Module | Status | Description |
|--------|--------|-------------|
| VS-Display | ✅ Complete | Physical ItemDisplays and TextDisplays at designated slots showing auction listings. Sold animations on purchase. |

**Commands**: `/displays add <category>`, `/displays remove <id>`, `/displays list`, `/displays refresh`

**Key features**: ItemDisplay entities (GROUND transform, 0.8 scale), TextDisplay price using Adventure Component API, auto-refresh every 60s, sold animation (HAPPY_VILLAGER particles + SOLD text + buyer name for 5s), integrated into MarketServiceImpl.buyListing(), 8 categories, non-persistent entities.

---

## Configuration

| Feature | Status | Settings |
|---------|--------|----------|
| config.yml | ✅ Complete | Fully documented with every available setting |
| Chat | ✅ Configurable | `chat.format`, `chat.tab_prefix`, `chat.nametag_prefix` |
| Staffmode | ✅ Configurable | All settings under `staffmode:` section |
| Currency | ✅ Configurable | Name, plural, model data, material |
| Vaults | ✅ Configurable | Cooldown, breach %, material |
| Districts | ✅ Configurable | `districts.min_distance_from_spawn` (1500), `districts.min_distance_between` (500) |
| Restoration | ✅ Configurable | `restoration.normal_delay_minutes` (10), `restoration.exhausted_delay_minutes` (30), `restoration.daily_repair_points` (500), `restoration.daily_wage` (1000) |
| Market | ✅ Configurable | `market.tax_percent` (5), `market.default_listing_duration_hours` (48) |
| Breach | ✅ Configurable | Max distance, escape cooldown, minigame timing |

---

## 🔄 WHAT CHANGED

| Change | Description |
|--------|-------------|
| PostgreSQL → SQLite | No external database required. Database stored as file in plugin data folder. |
| HikariCP removed | SQLite uses single connection with WAL mode. |
| Paper version | `26.1.2.build.72-stable` |
| Version numbering | `0.1.0-pre-alpha` |
| ON CONFLICT → INSERT OR IGNORE | All PostgreSQL upsert syntax converted to SQLite |
| NOW() → datetime('now') | All timestamp functions converted |
| COALESCE → IFNULL | Aggregate null handling converted |
| UUID casting | All ResultSet UUID reads now use `UUID.fromString(rs.getString(...))` |
| Boolean → INTEGER | All boolean columns (0/1) with `rs.getInt() == 1` |

---

## ❌ REMOVED

| Item | Reason |
|------|--------|
| PostgreSQL dependency | Replaced by SQLite (embedded, no server required) |
| HikariCP dependency | SQLite doesn't benefit from connection pooling |
| SLF4J dependency | Was only needed for HikariCP logging |
| `access_temporary_grants` table | Was dead schema — never written to |

---

## ⏳ NOT YET BUILT (Pending Phases)

### Phase 11 — Friends, Groups, Stations, Contracts
- [ ] `/friend add|remove|list`
- [ ] Player groups (pre-district cooperation)
- [ ] Station routes (paid infrastructure travel)
- [ ] Contracts & bounties

### Phase 12 — Polish
- [ ] Resource pack
- [ ] Admin dashboards
- [ ] Analytics
- [ ] Performance optimization

---

## 🐛 KNOWN ISSUES (v0.1.0-pre-alpha)

| Issue | Severity | Description |
|-------|----------|-------------|
| No transaction wrapping on split/merge | Medium | Crash between invalidate+mint could lose money (rare, ~ms window) |
| ChatListener serializes to legacy format | Low | Adventure components lose click/hover events; colors preserved |
| No `/vsreload` command | Low | Config changes require server restart |
| `districtNpcIds` not persisted across restarts | Low | Repairman NPC duplicates possible after restart if re-created |

---

## 📁 FILE STRUCTURE

```
CustomPlugin/src/main/java/com/vaultsurvival/plugin/
├── commands/
│   └── VSGiveCommand.java
├── VaultSurvivalPlugin.java          — Main plugin class
├── core/                             — Shared infrastructure (10 files)
│   ├── Module.java, ModuleManager.java
│   ├── ServiceRegistry.java
│   ├── ConfigManager.java
│   ├── DatabaseManager.java          — SQLite connection + 24 tables
│   ├── AuditLogger.java, MessageFormatter.java
│   ├── ItemSerializer.java, SchedulerHelper.java
│   ├── TransactionHelper.java, GUIFramework.java
├── access/                           — Permission system (5 files)
├── chat/                             — Chat formatting (1 file)
├── staffmode/                        — Staff mode system (4 files)
├── currency/                         — Physical cash (7 files)
├── vaults/                           — Vault storage (6 files)
├── breach/                           — Vault breaching (6 files)
├── npc/                              — NPC system (8 files)
├── market/                           — Physical Auction Hall (5 files)
├── regions/                          — Region/rules system (6 files)
├── districts/                        — District governance (5 files)
├── damage/                           — Temporary damage tracking (6 files)
├── repair/                           — Repairmen system (5 files)
├── crime/                            — Crime & Police (7 files)
└── display/                          — Display Auction Hall (5 files)
```

**Total: ~85 Java source files** across 17 packages, 24 database tables.

## Database Tables

| Table | Phase | Purpose |
|-------|-------|---------|
| `players`, `player_sessions` | 0 | Player tracking |
| `access_groups`, `access_group_permissions`, `access_group_inheritance`, `access_player_groups`, `access_player_permissions` | 0 | Permission system |
| `admin_audit_log` | 0 | Audit logging |
| `cash_items`, `cash_transactions` | 1 | Physical currency |
| `vaults`, `vault_access`, `vault_breaches`, `vault_repairs` | 2-3 | Vaults & breach |
| `auction_listings`, `auction_escrow_items`, `auction_lockers`, `auction_transactions` | 4 | Auction Hall |
| `npcs`, `npc_shop_items` | NPC | NPC system |
| `regions`, `region_flags` | 5 | Region rules |
| `districts`, `district_members`, `district_laws` | 6 | Districts |
| `temporary_damage` | 7 | Temp damage tracking |
| `repair_states` | 8 | Repair points |
| `crime_log`, `wanted_players`, `jail_locations` | 9 | Crime & Police |
| `display_slots` | 10 | Display slots |
# 1.0.30

- Added physical, claim-bound district treasury vaults with live balances, local cash deposit/withdrawal, role checks, auditing, and legacy migration tooling.
- Disabled remote district treasury banking and concealed vault coordinates from list output.
- Added audited staffmode-only teleport, summon, return, fly, heal, and gamemode utilities.
- Added structured dialog results, validation, refresh, pagination, in-dialog law/job lists, and job creation result dialogs.
