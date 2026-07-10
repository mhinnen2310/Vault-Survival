# Isolated staff sandbox

This profile runs `staff_test` in a second Paper process. That process boundary is intentional: Bukkit playerdata, vanilla statistics, advancements, inventories, plugin caches, physical cash, vaults, districts, markets, NPCs, audit logs, and the SQLite database are all stored under `staff-test-server/runtime` and cannot touch production files.

Sandbox mode raises or disables production limits while retaining gameplay invariants needed for realistic tests. Staff are operators, start in creative mode, can switch gamemodes, have no spawn protection, can use the full vanilla world size, pay no configured taxes/rail fees/upkeep, and receive very high safe ceilings where Java/SQLite still require finite values.

## Start

Build the plugin first from the project directory:

```powershell
.\mvnw.cmd clean package
```

Start the sandbox and explicitly provide every allowed staff profile:

```powershell
.\staff-test-server\start.ps1 -Staff "00000000-0000-0000-0000-000000000000:PlayerName"
```

After the first provision, the saved fail-closed whitelist is reused, so the normal start command is simply:

```powershell
.\staff-test-server\start.ps1
```

Multiple staff members:

```powershell
.\staff-test-server\start.ps1 -Staff "uuid-1:NameOne","uuid-2:NameTwo" -Port 25566 -MemoryGb 6
```

Staff enter from production with `/staffmode test` and return from the sandbox with `/staffmode return`. The production server restores the normal gameplay inventory/location during the transfer, so staff tools never cross into the sandbox and sandbox items never return to production.

For remote staff, configure `staffSandbox.transfer.testHost` on production with the public sandbox hostname. Start the sandbox with the public production destination:

```powershell
.\staff-test-server\start.ps1 -Staff "uuid:Name" -ProductionHost "play.example.com" -ProductionPort 25565
```

The production destination must set `accepts-transfers=true` in `server.properties`; the included sandbox profile already accepts return transfers.

Use `-ProvisionOnly` to generate/update the isolated runtime without launching Paper. The launcher rewrites `whitelist.json` and `ops.json` from the supplied staff list on every run, and the plugin independently rejects UUIDs not on the same list.

## Isolation and reset

- Runtime: `staff-test-server/runtime`
- Primary world: `staff_test`
- Plugin database: `staff-test-server/runtime/plugins/VaultSurvival/staff_sandbox.db`
- Vanilla playerdata/statistics/advancements: stored only in the sandbox world folders
- Network: port `25566` by default

To reset the sandbox, stop Paper and archive or remove only `staff-test-server/runtime`. Never point this launcher at a production server directory.
