# Vault Survival v1.0.28

This release closes the complete 45-item product-placeholder audit and adds the isolated staff testing environment requested alongside it.

## Highlights

- Persistent district join requests, reports, diplomacy, district-job disputes, and Kingdom Support workflows.
- Completed player, district, merchant, rail, staff, security, and guide dialogs.
- Computed current-area risk, market, jobs, and station context.
- Persistent actionable staff alerts with assignment, resolution, alert teleports, and a shared return stack.
- Paper anti-xray verification/control, loaded-container discovery, movement/combat/inventory scoring, and suspicious payout review.
- Real district maintenance scoring with warnings, suspension, and controlled level decay.
- Enforced `merchant.max_active_orders` before escrow withdrawal.
- Merchant market-zone border visualization, staff district teleport, and missing-unlocked-only district NPC placement.
- Fully isolated `staff_test` Paper runtime reached from active staffmode with `/staffmode test`; it has separate world/player/plugin/economy data and sandbox limit overrides.

## Verification

- Maven clean build succeeded on Java 21.
- Paper sandbox smoke-test loaded VaultSurvival v1.0.28 and all 25 modules.
- The isolated SQLite database initialized all 72 tables, including all seven new workflow/alert tables.
- No `SEVERE`, exception, or schema initialization errors were present during smoke startup.

See `docs/OPEN_PLACEHOLDERS.md` for the complete closure register and `docs/COMMANDS.md` for new commands.
