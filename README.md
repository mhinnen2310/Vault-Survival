# Vault Survival

Vault Survival is a Paper/Java 21 survival gamemode built around physical cash, breachable vaults, player districts, local law and policing, merchant trade, rail travel, jobs, NPCs, and audited staff operations.

## Main systems

- Server-authoritative physical cash with traceable UUIDs, transaction history, escrow, lockers, and counterfeit handling.
- Tiered vaults with access control, breach limits, lockdown, repairs, and persistent audit data.
- Exact block-boundary districts with roles, physical treasury vaults, laws, development, market zones, stations, and level-gated NPCs.
- Auction Hall listings, merchant buy orders, merchant NPC shops, physical payouts, and enforced active-order limits.
- Spawn City jobs, district jobs, claims, completion history, disputes, contracts, and Kingdom Support workflow.
- Rail station applications, routes, tickets, live journeys, revenue logs, and travel-event logs.
- Native Paper dialogs where supported, with inventory/clickable fallback menus and per-player style preferences.
- Audited staffmode utilities for teleporting, time, weather, speed, protected area breaking, and owner-approved build sessions.
- Safe vanilla Structure Block `.nbt` imports from `plugins/VaultSurvival/schematics` through the VWE confirmation and undo engine.
- Persistent player reports and staff alert queues with assignment, resolution, teleports, and audit logging.
- Conservative signal-only anti-cheat scoring for movement, combat, breaking, inventory exploits, and suspicious payouts. It never auto-bans.

## Build

Requirements: Java 21 and PowerShell or a compatible shell.

```powershell
.\mvnw.cmd clean package
```

The release artifact is `target/VaultSurvival.jar`.

## Install

1. Stop the Paper server.
2. Copy `target/VaultSurvival.jar` to the server's `plugins` directory.
3. Start Paper and verify `/vs version`, `/vs configcheck`, and `/vs audit`.
4. Configure database-backed ranks with `/rank` and review `permissions.yml`.

The plugin uses its own SQLite database under `plugins/VaultSurvival` and initializes compatible schema additions automatically.

## Staff building and schematics

Staff utilities require active staffmode. The owner can grant session-only build access with `/staffmode build <player> on`; it is revoked automatically on logout or staffmode exit. Use `/breaker 3x3` through `/breaker 9x9`, `/speed`, `/time`, and `/weather` for audited building work.

The schematic loader accepts vanilla Structure Block `.nbt` files only. Place files directly in `plugins/VaultSurvival/schematics`, then use `/vwe schematic list`, `/vwe schematic preview <file>`, and `/vwe schematic paste <file>`. Paths, symlinks, dimensions, block counts, confirmation, cancellation, and undo are validated server-side.

## Staff sandbox

`staff-test-server` provisions a second Paper process with its own `staff_test` world, playerdata, statistics, advancements, inventories, plugin database, economy, districts, markets, and audit data. Production staff enter it only from active staffmode with `/staffmode test` and return with `/staffmode return`.

```powershell
.\staff-test-server\start.ps1 -Staff "uuid:MinecraftName"
```

See `staff-test-server/README.md` for fail-closed allowlisting, remote transfers, and reset instructions.

## Documentation

- `docs/COMMANDS.md` — complete command reference.
- `docs/OPEN_PLACEHOLDERS.md` — complete 45-item closure audit; current open count is zero.
- `CHANGELOG.md` — release history and feature status.

## Release

The version declared in `pom.xml` and `plugin.yml` targets the configured Paper API and Java 21 runtime. GitHub release assets use the shaded `VaultSurvival.jar` produced by Maven.
