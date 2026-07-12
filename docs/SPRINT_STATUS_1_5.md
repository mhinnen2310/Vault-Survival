# Sprint 1-5 implementation status (v1.0.36)

This file is intentionally candid. A completed item has build/test evidence; remaining work is not presented as complete.

## Done

- The existing bounded `DatabaseExecutor` remains the database execution core, with WAL, busy timeout, independent reads and metrics through `/vs database status`.
- Central cash plans validate UUID, PDC/database amount, ledger state, holder, physical location and exact inventory slot.
- Cash recovery now has a durable recovery-delivery table, startup reconciliation and login delivery. A full inventory keeps cash locked for a later retry.
- Payout lockers issue through the durable payout/recovery path instead of minting directly into an offline inventory.
- District treasury facility and farm upgrades commit their cash ledger mutation and level row atomically.
- Spawn Town Clerk founding supports petitions, UUID invitations, explicit acceptance, a minimum of three accepted unique players, exact claims, independence contracts and application submission.
- District Town Clerk is the mayor hub for overview, facilities, market-zone selection, station management, laws/settings, home, restricted land, treasury and farms.
- Town Hall, Market, Station, Jail and Auction House have independent levels 0-5. Auction House unlocks at Town Hall level 5.
- New `FARMER` district role can create exact crop/animal cuboids, link output containers and place level-limited worker NPCs.
- Farm workers only operate while their NPC exists within the configured distance. They do not force-load chunks.
- Crop workers harvest mature ageable crops; animal workers never kill babies and retain the configured adult reserve per species.
- Output, interval, scan budget, distance, farm footprint, NPC count and upgrade costs are documented/configurable. Full chests pause work without deleting output.
- Versioned migration `2026071204` adds recovery, founding, facility and farm persistence without deleting existing district/cash data.
- Shared region visualization includes FARM_ZONE and the canonical selector coverage list includes all required workflows.
- Automated tests cover cash coordinator rollback/idempotency paths, VWE regressions, selector coverage and farm name/footprint/level rules.

## Still required

- `rg "getConnection("` currently reports 170 occurrences: 6 executor/bootstrap calls and 164 admin/gameplay/mixed calls. The mixed gameplay methods still need method-by-method `DatabaseExecutor` adoption.
- Auction, merchant, treasury legacy paths, jobs, rail, vault and civic still contain independent payment code that must be removed in favor of `CashPaymentService`.
- Remaining legacy `CurrencyService` mint/read methods are not all coordinator-backed.
- Staff-created district founding bypass with explicit permission/source/audit is not complete.
- Facility benefit descriptions exist, but every advertised downstream capacity/tax bonus is not yet enforced in its owning service.
- The selector coverage registry proves the expected list but does not integration-test every live command/NPC call site.
- Farm NPC creation still uses the existing synchronous NPC persistence service; that service needs executor adoption before worker placement is fully main-thread-write free.
