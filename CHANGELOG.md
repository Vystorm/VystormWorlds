## 1.18.0 — 2026-11-20

- A `config.yml` with a YAML syntax error is never changed: the console names the line and column of the error on start and on `/worlds reload`, the reload answers with the same position and keeps the last working settings instead of falling back to the defaults (`worlds.yml` is still reloaded).
- `/vadmin features` shows every switch of the plugin with a readable name in the admin's language, for example "Return to the last position" instead of "Teleport › Return to last position".
- "Start border event now" in the world menu with `border.enabled` off now answers "This needs “World border” (border.enabled), which is switched off on this server." instead of "This feature is switched off on this server.", because the border events switch itself is on.
- The world menu shows a world's settings as lists on Vystorm Screens: the tabs "World rules", "Travel and appearance" and "Borders and resets" have one row per setting with its name on the left, the current value on the right and a one-line description below, in all seven languages. Before, they were small tiles with cut names. The same descriptions now appear in the world's page of `/vsettings`. Players without screens see the menus as before.
- The plugin now registers Vystorm Core's update check for its SpigotMC page, like the other Vystorm plugins, so admins hear about a newer version. The README names the platforms it was tested on (Paper and Purpur 26.2, Paper 1.20.6, Spigot 26.2).
- Requires Vystorm Core 0.66.0 or newer. Update Core before you update this plugin.

## 1.17.0 — 2026-11-18

- Requires Vystorm Core 0.65.0. Update Core before you update this plugin.
- Every feature can now be switched on and off. All switches are listed in `/vadmin features` with a one-line description in all seven languages, and a change applies at once (only `tutorial.enabled` needs a restart). Core adds the new switches to your existing `config.yml` with their comments; a missing switch counts as on, so nothing changes until you switch something off.
- New switches for worlds: `management.enabled` (create, import, load, unload, clone, regen and delete), `border.enabled` (border size and centre; border events need it too).
- New switches for players: `selector.enabled` (world selector, Core main menu entry, `/worlds tp WORLD`, `/worlds WORLD`, `/worlds spawn`), `favorites.enabled`, `teleport.safe-spot.enabled`, `portals.enabled` (linked nether and end; off means vanilla portals).
- New switches for rules: `world-rules.enabled` (game mode, difficulty, PvP, mobs, hunger, flying, time and weather lock, spawn chunks), `entry-rules.enabled` (entry permission, price and player limit).
- New switches for administration: `admin-menu.enabled` (admin menu, world menu and settings dialog; every action stays available as a command), `tutorial.enabled`.
- Older keys keep their names and now also show in `/vadmin features`: `groups.notify`, `teleport.return-to-last-position`, `portals.sync-vystorm-portals`, `respawn.same-world`, `delete.backup`, `selector.show-locked`.
- A command of a switched-off feature answers "This feature is switched off on this server." It is also gone from tab completion and help while the switch is off. The menu entries disappear while their switch is off. Saved worlds, favorites, links and per-world values stay.
- Placeholders: the plugin registers the namespace `worlds` with 10 values (the player's world, its group, kind, players, limit and next reset, the number of worlds, template visits). Examples: `%vystorm_worlds_world%` and `%vystorm_worlds_world_players_lobby%`. They work in Vystorm texts and, through PlaceholderAPI, in other plugins. A value of a switched-off feature is empty.
- Tab completion and help follow a switch at once, without registering the command again.
- README: the licence line now says GPL-3.0, the Spigot resource is named, and the compatibility line says what the build checks.

## 1.16.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The world entry rules (who may teleport into a world) now deny before other plugins act.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.15.0 — 2026-11-16

- Four more features can be switched off on their own in `config.yml` (and in Core's feature switches): scheduled world resets (`reset.enabled`), shrinking border events (`border-events.enabled`), visit-world templates (`templates.enabled`) and custom build heights for new worlds (`build-height.enabled`). All are on by default, so nothing changes until you turn one off.
- A switched-off feature answers "This feature is turned off on this server." and does nothing. Saved schedules, templates, backups and worlds are kept; players already on a visit can still leave, and existing worlds with a custom height keep loading.

## 1.14.0 — 2026-11-15

- Rebuild the world selector, admin overview and world settings as Core Panels, with search, favorites, paging and editable settings.
- Keep all three chest menus as fallbacks for unavailable panels. No item-storage menus are present; creation and typed-name confirmation dialogs remain available.

## 1.13.1 — 2026-11-14

- Fixed: `/worlds template` with no templates now says so and shows how to create one, instead of printing nothing.

## 1.13.0 — 2026-11-12

- World border editor: new world properties `border` (size in blocks, `off` = untouched), `border-center` (`spawn` or `X Z`) and `border-event` – in the world menu, the settings dialog and `/worlds set`. A shrinking border event (schedule like resets: `daily 20:00`, `weekly sat 18:00`, …) shrinks the border to a share of its size, holds it and lets it grow back; players in the world are warned 5 and 1 minutes before. Admins can also start an event from the world menu. Settings under `border-events`.

## 1.12.0 — 2026-11-11

- Scheduled world resets: `/worlds set WORLD reset "daily 04:00"` (also `weekly mon 04:00`, `every 3 days 04:00`, `off`) regenerates a farm world on schedule – with the same or a new random seed (`reset-seed same|random`). Players in the world are warned 10 minutes and 1 minute before; the old terrain goes to backups like `/worlds regen`, and only the newest `reset.keep-backups` (3) per world are kept. Server worlds are never reset.

## 1.11.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- Unknown setting messages now point to Tab completion.

## 1.10.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.9.0 — 2026-11-07

- Add reusable flat/island visit-world recipes with temporary inventory rules, durable Core recovery and timed return retries.
- Include all seven bundled languages and targeted persistence, ownership and cancellation tests.
- Preserve Bukkit/Spigot fallback paths and existing Paper behavior.

## 1.8.0 (2026-11-07)

- Support custom-height datapack dimensions on CraftBukkit/Spigot 26.x using the server startup loader, canonical named folders and correctly bound plugin generators.
- Bukkit live clones flush saved chunks through the real server command; world rules run after respawn and portal routing uses Bukkit events.
- Use shared Core compatibility adapters for Bukkit/Spigot rich text, menus and the server operations used by this plugin.
- Requires Vystorm Core 0.51.0 for Core integration. Server acceptance is recorded separately; a successful build alone does not establish runtime compatibility.

## 1.7.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

# Changelog

## 1.5.0 (2026-11-03)

### Changed
- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and minimum API 1.20.6; requires Vystorm Core 0.48.0 for portable dialogs, inventory views, item snapshots and attributes.
- Native dialogs on newer servers, chat prompts on older servers. Separate bundled adapter for newer world APIs, selected by method detection; portable world paths, biome keys and gamerule lookups.
- On 1.20.6, custom build heights are unavailable without the modern level-directory API. Live cloning is unavailable without flush-save support; offline worlds and managed worlds that can be unloaded remain clonable. Initial spawn search uses the server default without forced-spawn support. Unavailable world features are logged once.
- Old-layout imports correctly recognize Nether and End folders.
- Offline `compat1206` compilation and the full `test1206` suite are part of `check`, with tests for feature detection and fallback selection.
## 1.4.0 (2026-11-03)

### Added
- Personal world favorites in the selector: Shift-right-click to add/remove a bookmark, favorites sorted first, and a star button to show favorites only or all worlds. Bookmarks persist in player data across reconnects and restarts. Visibility, access checks, prices and arrival preferences still apply. English, German and Simplified Chinese feedback and menu hints; no new commands, permissions or configuration required.

### Changed
- Selector admin shortcut is now Shift-left-click; Shift-right-click manages favorites for admins too.

## 1.3.0 (2026-11-02)

### Added
- Personal arrival preference: `/worlds arrival spawn|last` chooses the destination for selector and command travel, persisted in player data. Last-position travel still respects server and world settings; saved positions are kept. Localized command, completion, help and feedback in English, German and Simplified Chinese.

## 1.2.0 (unreleased)

### Added
- Arrival title: players who come into another world see its display name as a title and its description as subtitle (both as set with `/worlds set WORLD display-name|description`, MiniMessage colours included). Config `arrival-title` (default `true`, in the config editor).

## 1.1.0 (unreleased)

Needs Vystorm Core 0.45.0.

### Added
- Simplified Chinese (zh_cn) translation.
- `/worlds` now runs through Vystorm Core's command system: subcommands, keywords, help, tab completion and error messages in the player's language (`/worlds liste`, `/worlds klonen WELT NEU bestätigen`, `/worlds 列表` work next to the English names for everyone), a clickable `/worlds help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/welten` (German) and `/世界` (Chinese) from the language files (`commands.worlds.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/worlds`, `/vw` and `/mvw`, every English subcommand, `/worlds WORLD` and the console forms stay as before; the old option tokens (`seed:`, `generator:`, `with-nether` ...) are completed at every position. Subcommands without arguments (`list`, `menu`, `reload` ...) now refuse extra words instead of ignoring them. The command is no longer declared in plugin.yml.

### Fixed
- A clone of a server world (for example the main world) or of a world loaded by another plugin was not loaded again after a restart (it took over their `autoload: false`, the latter also `hidden`). Such clones are now ordinary worlds that load on start and show in the selector. Clones made before this fix: `/worlds set NAME autoload true`.
- `/worlds clone WORLD NEW` with a name that is already taken said "A world called WORLD already exists" about the source world instead of the new name.

## 1.0.0 (2026-10-30)

- First release: create (normal, nether, end, flat, void, amplified, large biomes, seed, structures, linked nether and end), import, load, unload, clone, regenerate and delete worlds; deleting and regenerating need the typed world name and keep a backup. `worlds.yml` is the source of truth, worlds load automatically after all plugins are enabled.
- Third-party generators: any generator plugin (`Plugin[:id]`, Multiverse style) with biome providers, a generator picker in the create dialog, tab completion, bukkit.yml generators adopted on import, and a public API (`VystormWorldsApi.registerGenerator`) for named presets. A world whose generator is missing is never loaded as vanilla terrain.
- Custom build height per world (min-y and height, data pack dimension type derived from the server's own vanilla files; created on the next restart).
- Per-world rules: display name, icon, description, spawn, enforced game mode, difficulty, PvP, monsters, animals, hunger, flying, keep spawn loaded, time and weather lock, respawn world, entry permission, entry price (once or per visit, Vault through Vystorm Core), player limit, hidden.
- World groups with separate inventory, ender chest, XP, health, hunger and effects; crash-safe swap (atomic write before change, group stamp in the player file, broken files set aside).
- World selector (`/worlds`, `/vmenu`) with safe asynchronous teleports and return to the last position; linked nether and end portals with scaling; respawn in the world of death.
- Admin menu, world menu, Core settings dialog, clickable chat lists with TPS hint, `/vadmin` module, config editor; English and German texts.
- Soft integration with VystormPortals (world links for its previews).

### Fixed
- Creating (or regenerating) a void world froze the server: it searched the empty world for a spawn point chunk by chunk until the watchdog stopped it. Void worlds now have a fixed spawn on their platform.
- Group swap: if a saved group state could be read but not applied (for example items that no longer decode), the player could be left with half of the new group under the old group's stamp, and the next swap would then duplicate those items and overwrite the old group's file. All items are now decoded before anything changes, and a failed apply restores the old state and sends the player back.
- While `worlds.yml` cannot be read, inventories are no longer swapped (every world looked like the default group until the file was fixed).
- A broken inventory-group file that could not be moved aside (for example locked by a backup tool) let the player enter that group with an empty inventory, and the next swap saved the empty state over the old items. The swap is now refused and the player keeps the current inventory.
- Cloning a loaded world copied its files while the server was still writing them, so the copy could have torn chunks. A loaded world is now unloaded for the copy (everything saved, files closed) and loaded again afterwards; server worlds and worlds that cannot be reloaded are saved with a full flush and auto-save off before copying. With players in the source world the clone asks for `confirm`.
- Changes in an open world menu, selector or pay dialog after `/worlds reload` went to an old copy of the world and were silently lost. Every click now looks the world up again; a removed world shows "changed, open the menu again".
- Creating a normal world froze the server for a minute and more while the server searched a spawn point chunk by chunk on the main thread (Paper 26.x). The world now starts with a provisional spawn and a dry spawn is searched in the background with asynchronous chunk loading (ocean and river areas are skipped without generating them); the admin gets progress messages.
