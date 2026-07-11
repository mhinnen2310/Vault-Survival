# Sprint 1–5 implementation status (v1.0.35)

This file is intentionally candid. A checked item is implemented and covered by build/test evidence; an unchecked item must not be presented as complete.

## Done

- Existing `DatabaseExecutor` retained: one bounded SQLite writer, bounded independent reads, WAL, busy timeout and explicit rollback.
- Metrics extended with read/write depth, rejection/failure totals, average and longest latency, queue-pressure status and shutdown-flush status.
- `/vs database status` added as the readable alias of `/vs dbmetrics`.
- Audit writes remain batched; low-priority staff alerts remain rate-limited/batched.
- Central physical-cash types registered: `CashPaymentService`, `CashTransactionCoordinator`, `CashPaymentPlan`, `CashInventorySnapshot`, `CashLedgerRepository`, `CashRecoveryJournal`, `CashTransactionState`, `CashTransactionResult`, `CashDeliveryPlan`.
- Cash plans contain exact player, slot, UUID, PDC/database amount, consumption, deterministic change UUID, destination and idempotency key.
- Ledger commit conditionally validates state, amount and physical location; inventory is revalidated/applied on the Paper main thread; disconnect/mismatch enters `RECOVERY_REQUIRED`.
- Versioned migration adds issuer/original-owner/current-holder fields and durable cash journal/ledger tables without deleting existing cash rows.
- Bearer-cash planning validates physical possession/location and does not reject on historical owner inequality.
- New district claims accept any shape from 2,500 horizontal blocks through the level-0 maximum; later expansion keeps the existing level limits.
- Treasury dialog rows are `Deposit` left and matching `Withdraw` right.
- Region defaults are 0.25 perimeter/vertical spacing and 3-block wall/floor grids.
- Region display has a three-second two-tick pulse, TPS-aware sustained refresh, per-world budget, distance culling, double corner pillars and NORMAL/DENSE/EXTREME modes.
- Dedicated `TOWN_CLERK` NPC action calls a typed handler directly and rejects arbitrary action data. Existing district clerk NPC rows are migrated.
- VWE regression tests cover air, `0`, weighted patterns, two/three-material deterministic grids, legacy weights, suggestions and air awareness.

## Direct connection inventory after this release

The remaining direct calls are categorized at file level. Mixed files require method-by-method migration; counts are generated with `rg -n "getConnection\\(" src/main/java`.

### Justified startup/schema/shutdown (6)

- `DatabaseManager` 5: bootstrap/schema/migration/health/shutdown compatibility.
- `DatabaseExecutor` 1: creates an independent physical JDBC connection internally.

### Admin/debug surfaces (7)

- `AdminCommand` 1, `NpcCommand` 1, `StaffAlertCommand` 2, `StaffInspectCommand` 1, `DialogService` 2.

### Gameplay or mixed service access still requiring adoption (165)

- Access 7; current area 1; breach 3; transaction helper 2; crime 6; currency legacy API 6; damage 1; display 2.
- District development 8; jobs 5; NPC planning 4; restricted land 2; district service 9; treasury 9.
- Auction market 8; merchant orders 8; merchant shops 9; analytics 1; NPC runtime 3; rail 9; regions 2; repair 1; staff alerts 4.
- Contract audit 1; disputes 1; contracts 2; escrow 2; friends 1; groups 2; payout lockers 4; stations 3; Spawn jobs 5; store 1; travel 7; vaults 9; civic command 1; civic service 16.

## Must still be done

- Convert the 165 gameplay/mixed direct connection calls to async service APIs. The presence of the central executor does not make a caller asynchronous by itself.
- Route legacy synchronous `CurrencyService` methods (`mintCash`, `splitCash`, `mergeCash`, `withdrawCash`, `depositCash`, transfer and invalidate) through coordinator-backed async APIs and remove nullable financial fallbacks.
- Migrate Auction Hall, merchant shops/orders, treasury, jobs/contracts, rail, vaults/breaches and civic payments to `CashPaymentService`; their independent spend/remove/refund helpers still exist.
- Add delivery-locker execution for a full inventory, automated retry workers for every recovery phase, and refund/payout journal transitions.
- Add the complete Spawn City founding petition UI, invitations/acceptance, signed independence paper hand-in, claim attachment, submission/approval and audited staff bypass. Only schema and typed Town Clerk entry points exist now.
- Refactor district development away from abstract score thresholds and enforce Town Clerk-only facility/level purchasing in the service layer.
- Verify every listed selector workflow invokes the shared visualization service; the shared renderer is enhanced, but this release does not prove all sixteen call sites.
- Replace remaining silent `catch (Exception ignored)` blocks, especially financial mutation paths, with explicit results and audit/error propagation.

## Unclear / requires product decision

- Whether the 2,500-block founding minimum should also become the level-0 maximum. This release treats 2,500 as the minimum and preserves the configured 3,840 maximum so irregular claims are possible.
- Which physical NPC/build represents the Spawn City Town Clerk and how it is assigned safely in existing worlds.
- Whether recovery change goes to the existing player payout locker, a dedicated cash-recovery locker, or is held until the next login.
- Whether staff-created districts require a physical independence contract or only the permission/audit bypass.
