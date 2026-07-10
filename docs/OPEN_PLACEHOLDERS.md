# Placeholder closure audit

Release audit result: **0 open product placeholders**.

This file is the complete closure register for the 45 runtime, dialog, staff, and security gaps found before this release. Defensive “service unavailable” states and valid lifecycle values such as `PENDING` and `PLANNED` are not product placeholders.

## Runtime and gameplay contexts

| # | Capability | Implemented behavior |
|---:|---|---|
| 1 | Current-area risk | Computes a 0–100 score from area type, PvP/breach/damage flags, evidence, and wanted state. |
| 2 | Current-area market | Shows Auction Hall listings or district shop/order context. |
| 3 | Current-area jobs | Shows enabled Spawn City jobs and active local district jobs. |
| 4 | Current-area station | Resolves station/platform, status, routes, ticket price, and local absence. |
| 5 | District diplomacy | Persistent neutral, pending-alliance, allied, and hostile relations; accepted allies receive ally chat. |
| 6 | District maintenance | Computes/persists score and `STABLE`, `WARNING`, `CRITICAL`, or `SUSPENDED`; suspension blocks upgrades and stale districts decay at most one stored level per seven days. |

## Player workflows

| # | Capability | Implemented behavior |
|---:|---|---|
| 7 | Player Orders | Live merchant-order, Auction Hall, delivery, creation, and payout dashboard. |
| 8 | Player Risk | Computed local risk, law/flag context, crime record, and report entry point. |
| 9 | Current District | Live membership, roles, treasury, laws, market, jobs, station, and join requests. |
| 10 | District join requests | Persistent request, council notification, approve/deny lifecycle, membership update, and audit. |
| 11 | Player reports | Location-aware persistent reports with category, subject, claim, resolve, dismiss, and staff alert. |
| 12 | Notification preference | Persisted `ALL`, `IMPORTANT`, or `OFF`; workflow notifications honor it. |
| 13 | Menu style | Persisted `AUTO`, `NATIVE`, or `COMPACT`; dialog-provider selection honors it. |
| 14 | Privacy preference | Persisted `PUBLIC`, `FRIENDS`, or `PRIVATE` profile visibility choice. |
| 15 | Vault guide | Full workflow guide with direct vault actions. |
| 16 | District guide | Full claims, governance, economy, jobs, diplomacy, station, and development guide. |
| 17 | Auction Hall guide | Full listing, buying, selling, cancellation, and collection guide. |

## District, merchant, and rail dialogs

| # | Capability | Implemented behavior |
|---:|---|---|
| 18 | District Market | Market-zone border, shop/order counts, merchant dashboard, demand, and supply jobs. |
| 19 | District Merchant | Routes to the complete shop, order, storage, and payout dashboard. |
| 20 | District Treasury | Live balance plus role-gated deposit, withdrawal, development, and job escrow actions. |
| 21 | District Diplomacy | Relation board, transitions, pending acceptance, and ally chat. |
| 22 | Job history | Completed/reviewed claim history for players and district managers. |
| 23 | Job disputes | Persistent open/resolve lifecycle with claimant/manager checks and audited outcomes. |
| 24 | Create Order | Interactive held-item form, escrow explanation, active usage, and enforced configured limit. |
| 25 | Rail Station | Live station status, routes, revenue/upkeep, and district station controls. |
| 26 | Rail Routes | Live destination, price, tax, travel time, and status list. |
| 27 | Rail Ticket | Clickable active-route purchases plus route-ID input. |
| 28 | Rail revenue logs | Staff route aggregation of purchased tickets and gross revenue. |
| 29 | Rail travel logs | Staff event log for ticketing, boarding, departure, arrival, expiry, and cancellation. |

## Staff and security

| # | Capability | Implemented behavior |
|---:|---|---|
| 30 | Player search | Dedicated name, UUID, district, and rank search screen. |
| 31 | Player lists | Dedicated online, recent, wanted, and frozen lists. |
| 32 | Player profile | Dedicated audited profile/action launcher. |
| 33 | Reports | Persistent claim/resolve/dismiss queue and category filters. |
| 34 | Cash trace | Cash UUID trace, player inspection, scan, and live metrics. |
| 35 | District moderation | Applications, district list, teleport, join counts, support, and police-abuse routing. |
| 36 | Police abuse | Filtered reports, related alerts, and player inspection. |
| 37 | Quick Open Reports | Opens the live report queue. |
| 38 | Security alerts | Persistent severity queue with claim, resolution, assignment, routing, and audit. |
| 39 | Teleport last alert | Teleports to the newest located open alert. |
| 40 | Return location | Ten-entry per-staff return stack shared by alert, player-inspection, district, and storage teleports. |
| 41 | Anti-Xray | Reads actual Paper default/world configuration and supports audited config updates requiring restart. |
| 42 | Storage discovery | Scans loaded nearby container tile entities, marks them with particles, and offers audited teleports. |
| 43 | Movement/combat scoring | Speed, fly, fast-break, reach, and attack-rate signals with cooldown-routed alerts; never auto-bans. |
| 44 | Inventory scoring | Oversized-stack, unauthorized creative injection, and extreme click-rate signals. |
| 45 | Suspicious payouts | High-value and burst scoring, persistent alerts, and staff review without automatic confiscation. |

## Related silent gaps closed

- `merchant.max_active_orders` is enforced before escrow withdrawal.
- Kingdom Support has persistent request, assignment, completion, and audit states.
- Staff/admin Districts buttons route to `staff.districts`.
- All implemented route descriptions and dashboard labels were refreshed.
- Debug bundles point to the operational report and alert queues.

## Valid non-backlog states

- District NPC plan rows may be `PLANNED` until activation.
- Rail station applications may be `PENDING`, and their staging coordinates are replaced during setup.
- Empty-state and module-unavailable menus are fail-safe runtime states, not unfinished features.
