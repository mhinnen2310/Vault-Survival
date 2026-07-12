# Vault Survival 1.0.32

- Added a configurable three-second teleport warmup that cancels on movement and blocks physical-cash teleporting.
- Added exact-block mayor restricted land with PUBLIC, MEMBERS, and ALLOWLIST modes plus explicit player allow/deny rules.
- Fixed station platform setup to use the real pending station ID and a two-corner block selection instead of radius input.
- Merchant shop inventories now refresh from the database immediately after every successful purchase.
- Added an audited staffmode command to force-activate every pending district law and a configurable pending-law cap.
- Added an accessible district level and full statistics dialog.
- Locked treasury withdrawal to authorized district roles; other players require a consumed breach kit, channel time, proximity, capped theft, and lockdown.
