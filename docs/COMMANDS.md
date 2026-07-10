# Vault Survival Command Reference

This reference follows the plugin modules. Permission-protected commands use the database-backed access ranks from `permissions.yml`; the server console is allowed to manage ranks directly.

## Core and Diagnostics

| Command | Purpose |
| --- | --- |
| `/vs version` | Plugin and server version. |
| `/vs modules` | Module status list. |
| `/vs debug` | Admin diagnostics. |
| `/vs reload` | Reload `config.yml` safely. |
| `/vs checklist` | Gameplay QA checklist. |
| `/vs configcheck` | Validate required config keys. |
| `/vs permissions` | Permission summary. |
| `/vs audit` | Startup audit report. |
| `/vs debugbundle` | Write an admin debug bundle. |
| `/vs update check` | Check the latest GitHub release. |
| `/vs update stage` | Download and stage the update jar. |
| `/vs update install` | Queue the staged jar for Paper's next restart. |
| `/vs update status` | Show update and staged-file status. |
| `/analytics`, `/vsadmin dashboard|reload|modules` | Admin monitoring tools. |

## Access and Staff Mode

| Command | Purpose |
| --- | --- |
| `/rank <player> info` | Show database rank and effective groups. |
| `/rank <player> set <group>` | Replace a player's rank groups. Console supported. |
| `/rank <player> add <group>` | Add a rank group. |
| `/rank <player> remove <group>` | Remove a rank group. |
| `/staffmode` | Toggle protected staff mode. |
| `/staffmode *` | Toggle permitted staff bypass testing mode. |
| `/staffmode test` | Transfer from active staffmode to the isolated staff test world. |
| `/staffmode return` | Return from the isolated test world to production. |
| `/staffinspect <player>` | Open a player profile dialog. |
| `/staffinspect search <query>` | Search players by name, UUID, rank, or district. |
| `/staffinspect online|recent|wanted|frozen [page]` | Staff player lists. |
| `/staffinspect freeze|unfreeze <player>` | Confirmed freeze controls. |
| `/staffinspect tp|bring|spectate <player>` | Confirmed movement actions. |
| `/staffalerts list [type]` | Open the persistent actionable security queue. |
| `/staffalerts claim|resolve <id> [note]` | Assign or close an alert with audit history. |
| `/staffalerts tp <id>`, `/staffalerts last`, `/staffalerts return` | Alert teleports and shared staff return stack. |
| `/staffalerts antixray [set <world|default> <on|off> [mode]]` | Verify or update actual Paper anti-xray configuration. |
| `/staffalerts storage [radius]` | Discover loaded nearby container tile entities. |
| `/staffalerts scores [type]`, `/staffalerts payouts` | Review anti-cheat scores and suspicious payouts. |

## Spawn City

| Command | Purpose |
| --- | --- |
| `/spawncity info` | Spawn City overview. |
| `/spawncity teleport` | Teleport to configured Spawn City. |
| `/spawncity setname <name>` | Staff rename Spawn City. |
| `/spawncity setspawn` | Staff set the Spawn City location. |
| `/spawncity claim` | Staff start a whole-chunk Spawn City claim. |
| `/spawncity claim confirm|cancel|status` | Confirm, cancel, or inspect a Spawn City chunk selection. |
| `/spawncity message welcome <text>` | Staff set Spawn City entry message. |
| `/spawncity message leave <text>` | Staff set Spawn City exit message. |
| `/spawncity setcapitalregion` | Save VWE selection as capital region. |
| `/spawncity setauctionhallregion` | Save VWE selection as Auction Hall. |
| `/spawncity setmintregion` | Save VWE selection as Mint. |
| `/spawncity regions` | List saved Spawn City regions. |

## Districts

| Command | Purpose |
| --- | --- |
| `/district apply <name>` | Start the required chunk claim for a new district. |
| `/district confirm|cancel|selection` | Confirm, cancel, or inspect current chunk selection. |
| `/district borders` | Show nearby district borders. |
| `/district expand` | Start a level-gated district claim expansion. |
| `/district current` | Current district context. |
| `/district info [id]` | District information. |
| `/district list` | List districts. |
| `/district invite|kick <player>` | Membership actions. |
| `/district members` | Member list and roles. |
| `/district role list <player>` | List a member's district roles. |
| `/district role set|remove <player> <role>` | Manage district roles. |
| `/district permissions` | Show district-role capabilities. |
| `/district deposit|withdraw <amount>` | District treasury physical cash actions. |
| `/district laws [pending]` | Active or pending district laws. |
| `/district law propose <law> <true|false>` | Propose a daily law change. |
| `/district message welcome <text>` | MAYOR-only entry message. |
| `/district message leave <text>` | MAYOR-only exit message. |
| `/district chat prefix <text>` | MAYOR-only district chat prefix. |
| `/district chat rolecolor <role> <color>` | MAYOR-only district role chat color. |
| `/district marketzone` | Start merchant market-zone chunk selection. |
| `/district marketzone confirm|cancel|status` | Manage market-zone selection. |
| `/district marketzone borders` | Show the saved market-zone chunk borders (merchant-capable roles). |
| `/district npcs start|confirm|cancel|activate` | Place only missing, currently unlocked district NPCs and activate saved plans. |
| `/district development|projects|maintenance|contributors` | District progression overview. |
| `/district project create|list|info|pause|resume|contribute|support` | Project lifecycle. |
| `/district jobs` | District jobs in current district. |
| `/district job create|list|accept|deliver|submit|approve|deny` | District job workflow. |
| `/district station status|request|setplatform|confirm|cancel|setarrival` | District station application and platform setup. |
| `/district disband` | Disband your district. |
| `/district applications`, `/district approve <id>`, `/district reject <id> <reason>` | Staff district application controls. |
| `/district teleport <id|name>` | Teleport staff to a district center. |
| `/civic join <district> [message]` | Submit a persistent district membership request. |
| `/civic joins [approve|deny <id>]` | Council review of open join requests. |
| `/civic diplomacy <list|request|accept|deny|neutral|hostile> [district]` | Persistent inter-district relations and alliances. |
| `/civic jobs history|disputes` | Completed-claim history and dispute queue. |
| `/civic jobs dispute <claim> <reason>` | Open a district-job dispute. |
| `/civic support <list|request|assign|complete>` | Kingdom Support request lifecycle. |

## VS-WorldEdit

`/vwe` aliases: `/vswe`, `/vedit`. Double-slash shortcuts are available, such as `//set`.

| Command | Purpose |
| --- | --- |
| `//wand`, `//pos1`, `//pos2`, `//selection`, `//clearselection` | Selection controls. |
| `//set <material|pattern>` | Fill selection. Supports `air`, `0`, equal random lists, percentages, and `grid:`. |
| `//setgrid <materials>` | Deterministic coordinate grid; example: `//setgrid stone,cobblestone`. |
| `//replace <from> <material|pattern>` | Replace matching blocks, including with `air` or `0`. |
| `//replacegrid <from> <materials>` | Replace matching blocks with a deterministic grid. |
| `/vwe pattern <pattern>` | Validate and preview parsed syntax without editing blocks. |
| `/vwe operation` | Open the VWE operation, pattern preview, confirm, and cancel dialog. |
| `//walls`, `//outline`, `//floor`, `//ceiling`, `//hollow` | Cuboid editing tools. |
| `//cylinder`, `//hcylinder` | Solid or hollow vertical cylinders. |
| `//circle`, `//hcircle` | Solid or hollow flat circles. |
| `//sphere`, `//hsphere`, `//line` | Sphere and line tools. |
| `//confirm`, `//cancel`, `//undo` | Large-operation confirmation and undo. |

## Economy, Vaults, Market, and Merchants

| Command | Purpose |
| --- | --- |
| `/cash inspect|trace|mint|invalidate|scan|stats` | Admin physical-cash tools. |
| `/vault place|remove|info|deposit|withdraw|access|repair|list` | Cash-only vault management. |
| `/breach kit|start|cancel|log|logplayer|escapecooldown` | Vault breach flow. |
| `/ah sell|buy|listings|collect|cancel|inspect` | Auction Hall. |
| `/merchant orders` | Merchant's own buy orders. |
| `/merchant order create|list|cancel|deliver|collect` | Buy order workflow. |
| `/merchant orderboard` | Convert looked-at sign into merchant order board. |
| `/merchant shop create|list|stock|prices|collect` | Merchant NPC shop management. |
| `/payouts [claim]` | Claim physical pending payouts. |

## Rail, Stations, Jobs, and Contracts

| Command | Purpose |
| --- | --- |
| `/rail stations|routes|applications|approve|deny|createroute|travel` | Rail network and staff routes. |
| `/station next|buy <route>|board <route>|journey status|list` | Passenger rail journey. |
| `/spawnjobs [active|accept|turnin|abandon|admin]` | Spawn City starter jobs. |
| `/contract create|accept|complete|cancel|list|debug|audit` | Shared contracts. |
| `/escrow debug` | Escrow diagnostics. |

## NPCs, Regions, Crime, Repair, and Social

| Command | Purpose |
| --- | --- |
| `/npc create|remove|movehere|skin|command|shop|market|additem|clearitems|list|tphere` | NPC management. |
| `/region wand|create|delete|info|flag|list|here` | Region management. |
| `/crime evidence [id]`, `/crime fine|wanted|dismiss` | Evidence and police actions. |
| `/damage info|list|restore` | Temporary damage tools. |
| `/repair status|pay|emergency` | Repair system. |
| `/friend add|remove|list` | Friend system. |
| `/group create|disband|invite|accept|kick|leave|info` | Player groups. |
| `/displays add|remove|list|refresh` | Auction display controls. |
| `/store list|buy` | Cosmetic store. |

## Menus, Chat, and Convenience

| Command | Purpose |
| --- | --- |
| `/vsmenu [route]`, `/vsm` | Open Vault Survival dialogs. |
| `/quickactions` | Open quick actions dialog. |
| `/whereami` | Current area, district, role, flags, and laws. |
| `/chat <channel>` | Select GLOBAL, LOCAL, DISTRICT, ALLY, POLICE, MERCHANT, STAFF, or HELP. |
| `/g`, `/l`, `/dc`, `/ac`, `/pc`, `/mc`, `/sc`, `/helpchat <message>` | Channel aliases. |
| `/chatsettings`, `/chatpreview [message]` | Chat configuration and preview. |
| `/vsresourcepack [player|reload]` | Resource-pack delivery. |
| `/vsgive cash|breachkit|vault [amount]` | Staff-mode test items. |
| `/civic preferences [notifications|menu|privacy] [value|next]` | Persist player notification, menu-style, and privacy choices. |
| `/civic profile [player]` | View a privacy-filtered public player profile. |
| `/civic report <category> <player|none> <details>` | Submit a location-aware staff report. |
| `/civic reports [category|claim|resolve|dismiss]` | Staff report queue and lifecycle. |
| `/civic guide <vaults|districts|auction>` | Open complete gameplay guides. |
| `/civic rail <revenue|travel>` | Staff rail revenue and journey logs. |
