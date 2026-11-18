# Vystorm Worlds

**Multiworld management that players can actually use.** Create, import, link and manage worlds from menus, give every world its own rules, let groups of worlds keep their own inventories, and send players around with a world selector and safe teleports. One plugin for what usually takes Multiverse-Core, Multiverse-Inventories and Multiverse-NetherPortals together. Free and open source (GPL-3.0).

**Requires [Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.65.0 or newer (free).** Paper/Purpur 1.20.6 – 26.x. Plugin bytecode requires Java 21 or newer; use the Java version required by your server (Java 25 for 26.x). Vault is optional (entry prices).

## Feature switches (1.17.0)

Every feature has its own on/off switch in `config.yml`, listed with a one-line description in all seven languages in `/vadmin features`. Changes apply at once; only `tutorial.enabled` needs a restart. Core adds new switches to an existing `config.yml` with their comments; a missing switch counts as on.

- New in 1.17.0: `management.enabled` (create, import, load, unload, clone, regen, delete), `selector.enabled` (selector, Core main menu entry, `/worlds tp WORLD`, `/worlds WORLD`, `/worlds spawn`), `favorites.enabled`, `teleport.safe-spot.enabled`, `portals.enabled` (linked nether and end), `world-rules.enabled` (game mode, difficulty, PvP, mobs, hunger, flying, time and weather lock, spawn chunks), `entry-rules.enabled` (entry permission, price, player limit), `border.enabled`, `admin-menu.enabled`, `tutorial.enabled`.
- Already there: `groups.enabled`, `border-events.enabled`, `reset.enabled`, `templates.enabled`, `build-height.enabled`, and the top-level `autoload`, `adopt-loaded-worlds`, `arrival-title`.
- Older keys keep their names and now also show in `/vadmin features`: `groups.notify`, `teleport.return-to-last-position`, `portals.sync-vystorm-portals`, `respawn.same-world`, `delete.backup`, `selector.show-locked`.

A command of a switched-off feature answers "This feature is switched off on this server."; it disappears from tab completion and help at once and comes back when the switch is on again. Per-world values in `worlds.yml` are never changed by a switch.

## Placeholders (1.17.0)

With Vystorm Core 0.65.0 the plugin registers the namespace `worlds`: `world`, `world_name`, `world_group`, `world_kind`, `world_players` (with a world name as argument: players in that world), `world_limit`, `world_next_reset`, `worlds_count`, `worlds_loaded`, `in_visit`. Each works as `%vystorm_worlds_<key>%` (also through PlaceholderAPI) and as `{worlds:<key>}` in Vystorm texts; `/vadmin placeholders worlds` lists them. Examples: `%vystorm_worlds_world%` shows the display name of the player's world, `%vystorm_worlds_world_players_lobby%` the players in `lobby`. A value of a switched-off feature is empty. All values answer on the server thread only.

## Compatibility

Built against Paper 26.2; the `compat1206` and `test1206` checks compile the sources and run the tests against Paper 1.20.6. Spigot and CraftBukkit 26.2 and 26.3 were checked with 1.8.0 (see below). Core selects native dialogs where available and chat prompts on older servers; chest menus remain available. Custom build heights require the modern level-directory API (26.x). Live cloning of worlds that cannot be unloaded requires the flush-save API; on 1.20.6, clone an unloaded world or an ordinary managed world, which is unloaded and reloaded safely. Servers without the forced-spawn API use their normal initial spawn search. Each unavailable world feature is reported once at startup.

Existing inventory snapshots from newer Minecraft versions must be restored on a compatible server; this does not convert newer items or world data for a server downgrade.

## Features

**Worlds**
- Create worlds in a dialog or by command: normal, nether, end, flat, void, amplified, large biomes; seed (numbers or words), structures on/off, any generator plugin or registered preset, and optionally a linked nether and end in one go.
- **Custom build height** per world (for example -128 to 512): Vystorm Worlds writes a data pack dimension type the same way Iris does, derived from your server's own vanilla files, so the format always matches. The server creates such a world on the next restart.
- Import existing world folders (a clickable list shows what can be imported; old 1.21 folders are moved into the 26.x layout by the server; generators configured in bukkit.yml are adopted).
- Load, unload, clone, regenerate (same, random or new seed) and delete. Deleting and regenerating ask you to **type the world name** and move the old files to `plugins/VystormWorlds/backups` instead of erasing them. Server worlds, other folders and Vystorm Core plugin worlds are never touched.
- `worlds.yml` is the single source of truth. Worlds load automatically one tick after start, when every generator plugin is ready. A world whose generator plugin is missing stays unloaded with a clear warning instead of being generated as vanilla terrain.

**Per-world rules** (world menu, settings dialog or `/worlds set`)
- Display name (MiniMessage colours), description, icon, spawn. Players arriving in a world see its display name and description as a title (`arrival-title`).
- Game mode enforced on entry, difficulty, PvP, monsters and animals on/off, hunger on/off, flying allowed, keep spawn chunks loaded.
- Time lock (day, noon, sunset, night, midnight or exact ticks) and weather lock (clear, rain, thunder).
- Respawn world: by default players respawn in the world they died in (nether/end: its overworld), not in the main world.
- Entry permission (`vystormworlds.enter.<world>`), entry price once or per visit (Vault through Vystorm Core), player limit, hidden from the selector.

**Players**
- World selector in `/worlds` and the Vystorm main menu (`/vmenu` → World): icons, player counts, game mode, price state, locks. Left-click follows your arrival preference (by default, where you left that world), right-click goes to the spawn.
- **Personal world favorites:** Shift-right-click a world in the selector to bookmark it. Favorites appear first; the star button switches between all visible worlds and favorites only. Bookmarks survive reconnects and restarts. Hidden, unloaded and permission-filtered worlds remain excluded. Admin settings use Shift-left-click. The selector opens with all worlds each time.
- Personal arrival preference: `/worlds arrival spawn|last` is saved across reconnects and restarts without erasing remembered positions. Applies to selector left-click and `/worlds tp WORLD` (also `/worlds WORLD`). Default: last position where allowed. Right-click still goes to spawn; portals, respawns and admin-directed travel are unaffected.
- Safe teleports: the target chunks load asynchronously, then a safe spot is searched (no lava, fire, cactus, suffocation or void).
- Portals: nether and end portals lead to the world's own linked nether and end (with correct 8:1 scaling and the end platform). Worlds named `<world>_nether` / `<world>_the_end` link automatically.

**World groups**
- Worlds in a group share inventory, armour, off-hand, ender chest, XP, health, hunger and potion effects. Crossing into another group swaps them.
- Built to never lose items: the target group is read first, the old state is written to disk atomically (temp file, fsync, rename) before anything changes, and the group the inventory belongs to is stamped into the player's own data file together with the inventory. A crash at any point leaves either the old or the new state, never a mix and never a duplicate. Unreadable files are set aside, never deleted. If a save fails, the player keeps everything and is sent back.
- Vystorm Core plugin worlds and running Core visits are left alone, because Core already saves and restores players there.

**Admins**
- `/worlds admin`: every world as an icon with state, players, chunks and entities; click for the world menu with toggles, cycles and prompts for every property, load/unload, set spawn, go there, clone, regenerate and delete.
- `/worlds list`: clickable chat list with [Go] [Edit] [Info] [Load]/[Unload] buttons and a TPS/MSPT hint; `/worlds info` shows chunks, entities, block entities and all properties.
- All properties also as a native settings dialog (`/worlds settings <world>`), the plugin config in `/vadmin config VystormWorlds`, and a module in the `/vadmin` dashboard.
- Texts in seven languages (English, German, French, Italian, Brazilian Portuguese, Russian and Simplified Chinese) through the Vystorm Core language system (each player sees their language).

## Scheduled world resets (1.12.0)

`/worlds set WORLD reset "daily 04:00"` (or `weekly mon 04:00`, `every 3 days 04:00`) resets a farm world automatically, same or random seed (`reset-seed`), with warnings 10 and 1 minute before and rotating backups.

## Reusable visit-world templates (1.9.0)

Administrators can define a protected visit landscape without copying an existing world folder:

```text
/worlds template create island island 120 kit
/worlds template create lobby flat 60 locked
/worlds template list
/worlds visit island
/worlds leave
/worlds template close island
/worlds template open island
```

Recipes support `flat` or a small `island`, a 10–3,600 second visit and either `locked` inventory or a temporary `kit` (spyglass and compass). The world has a safe fixed spawn, a 128-block border, peaceful weather and building protection. The island has shallow water around it. Templates are lazy-loaded, saved and reusable; closing/reopening retains the world. Up to eight recipes persist atomically in `templates.yml`. Names are limited to lowercase letters, digits, hyphens and underscores. Existing recipes cannot be overwritten or deleted by these commands.

`vystormworlds.admin` controls template creation/opening/closing; `vystormworlds.use` allows visits and leaving. Core saves the original inventory and XP before admitting a visitor, isolates the temporary tools, and restores the snapshot after returning. An already active visit or pending recovery blocks another entry. The timer requests a return after the configured duration. If another plugin cancels that return, the visitor and durable snapshot remain together and Worlds retries; it never restores the original inventory inside the visit world. Quit/restart recovery uses Core's existing visit recovery. Ordinary world inventory groups remain independent.

Compatibility update: Core integration now requires **Vystorm Core 0.51.0+**. Custom-height generation and restart persistence passed on official Spigot/CraftBukkit 26.2 and 26.3; see `release/VALIDATION-SPIGOT.md`.

## Why not Multiverse?

- One plugin instead of three, and players get menus instead of commands to remember.
- Group inventories with a crash-safe swap: the inventory and the stamp of its group are saved in the same player file, so a crash can never duplicate or wipe items.
- Destructive actions need the typed world name and keep a backup.
- Generator plugins are first class: a missing generator never silently produces vanilla terrain.
- Custom build heights without a separate plugin.
- Made for the 26.x world layout (all dimensions inside the main level folder).

## Commands

Main command `/worlds`, aliases `/vw` and `/mvw`. (`/vworlds` belongs to Vystorm Core and lists its plugin worlds.)

| Command | |
|---|---|
| `/worlds` | world selector with persistent favorites and a favorites filter |
| `/worlds favorites` | open your favorite worlds directly |
| `/worlds favorite [WORLD]` | add or remove a selector bookmark; defaults to your current world, uses the selector's visibility rules |
| `/worlds tp WORLD [PLAYER]`, `/worlds WORLD` | travel to a world (others: admin) |
| `/worlds arrival spawn\|last` | save your personal arrival preference; `last` respects server/world settings |
| `/worlds spawn` | spawn of the world you are in |
| `/worlds admin` | admin menu |
| `/worlds list`, `/worlds info [WORLD]`, `/worlds groups`, `/worlds generators` | overviews |
| `/worlds create` | create dialog (in game) |
| `/worlds create NAME [type] [seed:S] [generator:Plugin[:id]] [biomes:Plugin[:id]] [structures:false] [with-nether] [with-end] [group:G] [min-y:-128 height:512]` | create by command; types: normal, nether, end, flat, void, amplified, large-biomes |
| `/worlds import [FOLDER] [type] [generator:..] [seed:..]` | list or import folders |
| `/worlds load WORLD`, `/worlds unload WORLD` | load / save and unload |
| `/worlds clone WORLD NEW [confirm]` | copy a world; a loaded world is unloaded for a clean copy and loaded again (server worlds are copied after a flushed save); `confirm` is needed while players are in it |
| `/worlds regen WORLD [same\|random\|SEED]` | new terrain (typed confirmation) |
| `/worlds delete WORLD` | delete (typed confirmation; console: repeat the name) |
| `/worlds set WORLD PROPERTY VALUE` | change a property |
| `/worlds edit [WORLD]`, `/worlds settings [WORLD]` | world menu / settings dialog |
| `/worlds setspawn` | spawn of the world you stand in |
| `/worlds link WORLD nether\|end TARGET` | link a nether or end (`none` removes) |
| `/worlds reload` | re-read config.yml and worlds.yml |

Properties for `/worlds set`: `display-name, description, icon, game-mode, difficulty, pvp, monsters, animals, hunger, fly, keep-spawn-loaded, autoload, hidden, time-lock, weather-lock, respawn-world, permission, price, price-mode, player-limit, group, nether, end, remember-position`. Type, seed, generator and build height are fixed when a world is created.

## Permissions

| Permission | Default | |
|---|---|---|
| `vystormworlds.use` | everyone | selector and favorites, `/worlds tp`, `/worlds spawn`, `/worlds arrival` |
| `vystormworlds.admin` | op | everything else |
| `vystormworlds.enter.<world>` / `vystormworlds.enter.*` | op | enter worlds with `permission: true` |
| `vystormworlds.bypass` | op | ignore entry permission, player limit and price |
| `vystormworlds.bypass.gamemode` | op | keep your game mode when entering |

## Configuration

`config.yml` (also editable in game with `/vadmin config VystormWorlds`):

| Key | Default | |
|---|---|---|
| `autoload` | `true` | load worlds marked autoload on start |
| `adopt-loaded-worlds` | `true` | add loaded worlds missing in worlds.yml (other plugins' worlds hidden, not auto-loaded) |
| `default-group` | `default` | group of worlds without one |
| `groups.enabled` | `true` | separate inventories per group |
| `groups.separate` | all six | `inventory, ender-chest, experience, health, hunger, effects` |
| `groups.notify` | `true` | action bar note on a group switch |
| `teleport.safe-radius` | `8` | safe spot search radius (0-32) |
| `teleport.return-to-last-position` | `true` | selector left-click returns to the last position |
| `portals.unlinked` | `vanilla` | portals in worlds without links: `vanilla` or `block` |
| `portals.sync-vystorm-portals` | `true` | tell VystormPortals which worlds belong together |
| `respawn.same-world` | `true` | respawn in the world of death instead of the main world |
| `delete.backup` | `true` | keep deleted/regenerated data in `backups/` |
| `selector.show-locked` | `true` | show worlds a player may not enter, with a lock |

Data files: `worlds.yml` (all worlds and their properties), `groups/<uuid>/<group>.yml` (saved group states), `backups/` (deleted and regenerated worlds). Per player, the current group, last positions, favorite world names and paid entries are stored in the player's own data (persistent data container).

## Custom build height

`/worlds create tall normal min-y:-128 height:640` (or the two fields in the create dialog). Rules: both values multiples of 16, min-y at least -2032, height 16 to 4064, min-y + height at most 2032; with vanilla terrain the range must contain the normal terrain (-64 to 320, nether/end 0 to 128).

- Minecraft only reads new dimension types while the server starts, so **such a world appears after the next restart** (the plugin tells you). It is stored as `<level>/datapacks/vystormworlds` and the world key `vystormworlds:<name>`.
- Works with the vanilla types and with generator plugins that load at server start (`load: STARTUP`, like Iris); Vystorm Worlds then writes the generator into `bukkit.yml` (with a backup). Presets and plugins that load later are refused, because the server would otherwise create vanilla terrain.
- The height is fixed for the life of the world: changing it would cut off or misplace existing chunks, so it is refused. Deleting the world also removes its data pack entry.
- Such worlds use the main world's seed. Regenerating one needs a restart before it is back.

## Generators and API

Any installed generator plugin works: `generator:PluginName` or `generator:PluginName:id` (the id can carry parameters). `/worlds generators` and the create dialog list installed plugins that provide a generator plus registered presets; tab completion offers them too. The generator is stored per world and used on every load. If it is missing, the world is not loaded.

Other plugins can register named presets that appear in the picker (`softdepend: [VystormWorlds]`):

```java
if (getServer().getPluginManager().isPluginEnabled("VystormWorlds"))
    at.esoren.vystormworlds.VystormWorldsApi.registerGenerator(this, "myplugin:islands", "Floating islands",
            worldName -> new IslandGenerator(), worldName -> new IslandBiomes());   // biome provider may be null
```

Presets are stored as `preset:myplugin:islands` and removed automatically when their plugin is disabled. The API also offers `managed(World)`, `group(World)`, `worlds()`, `generators()` and `unregisterGenerator(...)`.

## Integrations

- **Vystorm Core:** language files, menus, dialogs, settings dialogs, config editor, admin dashboard, task groups, atomic files, Vault bridge, void generator. Core plugin worlds (`/vworlds`) are left alone.
- **VystormPortals** (optional): its portal previews get the same world links, and manual portals work in every managed world.
- **Vault / VaultUnlocked** (optional): entry prices.

## Release

- Spigot resource: https://www.spigotmc.org/resources/140132/. The plugin does not register a Vystorm Core update check yet.

## Building

`./gradlew build` → `build/libs/VystormWorlds-<version>.jar` (expects the Core jar at `../Vystorm_Core/build/libs/...` or `-PvystormCoreJar=path`).

Build with JDK 25; all source sets target Java 21. `check` includes `compat1206` (main sources compiled against only the old API and Core) and `test1206` (the full test suite using shipped main bytecode with the old runtime API). Supply the offline libraries with `-Ppaper1206Libraries=C:/path/to/smoke-1206/libraries`; the default is the maintainer's local smoke-server library directory. The `modern` source set compiles against the current API and is included in the same jar, loaded only after feature detection. Core provides the separate native-dialog and inventory adapters.

## License

Copyright (c) 2026 Vystorm

Versions after 1.15.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.15.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.15.0.txt](LICENSE-MIT-until-1.15.0.txt)) and stay available under those terms.
