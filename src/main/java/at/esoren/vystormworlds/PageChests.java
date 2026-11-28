package at.esoren.vystormworlds;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import Vystorm.vystorm_core.panel.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Chest versions of the world page and the admin pages (1.21.0), for players without Vystorm Screens (Spigot, Paper
 * 1.20.6 without the client mod, screens off, or for a moment in water or after damage). They cover every task of the
 * screen. The tab lives in the panel's session when Core hands over a context ({@code panel-fallback-context}), else in
 * the click handlers of the open chest; a pick started here comes back to the same page and tab. A chest click changes
 * one setting at once (a reversible single change, as before 1.21.0); regenerate and delete still need the typed name,
 * unload and a copy with players inside a confirm. Every item names the command it runs.
 */
final class PageChests {
    static final List<String> TAB_ICONS = List.of("BOOK", "IRON_SWORD", "COMPASS", "OAK_DOOR", "IRON_BARS", "RED_BED", "STRUCTURE_VOID", "TNT");
    private final WorldsPlugin plugin;
    private final Menus menus;
    private final WorldMenu menu;

    PageChests(WorldsPlugin plugin, Menus menus, WorldMenu menu) {
        this.plugin = plugin;
        this.menus = menus;
        this.menu = menu;
    }

    private String text(Player player, String key, Object... args) { return plugin.lang().plain(player, key, args); }

    private static Component flat(Component c) { return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE); }

    /** An item with a plain name and plain lore lines (the last line in yellow: what a click does). */
    private ItemStack stack(Material material, String name, List<String> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        var platform = plugin.core().compat().platform();
        platform.editMeta(item, meta -> {
            platform.displayName(meta, flat(Component.text(name, NamedTextColor.YELLOW)));
            List<Component> lines = new ArrayList<>();
            for (int i = 0; i < lore.size(); i++)
                lines.add(flat(Component.text(lore.get(i), i == lore.size() - 1 && lore.size() > 1 ? NamedTextColor.YELLOW : NamedTextColor.GRAY)));
            platform.lore(meta, lines);
            if (glint) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private Menus.Menu chest(Player player, String titleKey, Object... args) {
        Menus.Menu chest = new Menus.Menu();
        chest.inventory = plugin.core().compat().platform().createInventory(chest, 54, plugin.lang().component(player, titleKey, args));
        return chest;
    }

    /** Sends a menu result to chat (the chest has no status line). */
    private void say(Player player, Result result) {
        if (result == null) return;
        String message = result.message();
        if ((message == null || message.isBlank()) && !result.fields().isEmpty()) message = String.join(" ", result.fields().values());
        if (message == null || message.isBlank()) return;
        plugin.core().compat().platform().sendMessage(player, Component.text(message, result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ world page

    /** The world page as a chest. {@code ctx} is Core's fallback context or null; {@code tab} null = from the context. */
    void world(Player player, String name, String tab, Object ctx) {
        if (!menu.may(player, "world-page")) { plugin.lang().send(player, "denied"); return; }
        WorldSettings w = menu.world(name);
        if (w == null) { menus.adminChest(player, 0); return; }
        String current = tab != null ? tab : (ctx == null ? null : WorldsPicks.get(ctx, WorldPage.TAB_KEY)) instanceof String s ? s : "overview";
        if (!WorldPage.TABS.contains(current)) current = "overview";
        if (ctx != null) WorldsPicks.put(ctx, WorldPage.TAB_KEY, current);
        String shown = current;
        Menus.Menu chest = chest(player, "world-title", "world", w.name);
        World loaded = WorldManager.loaded(w.name);
        switch (current) {
            case "rules" -> {
                int slot = 10;
                for (String key : List.of("pvp", "monsters", "animals", "hunger", "fly", "keep-spawn-loaded", "autoload")) flip(chest, player, w, slot++, key, shown, ctx);
                cycle(chest, player, w, 19, Material.IRON_SWORD, "game-mode", WorldsPanel.CHOICES.get("game-mode"), shown, ctx);
                cycle(chest, player, w, 20, Material.ZOMBIE_HEAD, "difficulty", WorldsPanel.CHOICES.get("difficulty"), shown, ctx);
                cycle(chest, player, w, 21, Material.CLOCK, "time-lock", WorldSettings.TIME_LOCKS, shown, ctx);
                cycle(chest, player, w, 22, Material.WATER_BUCKET, "weather-lock", WorldSettings.WEATHER_LOCKS, shown, ctx);
                cycle(chest, player, w, 23, Material.RED_BED, "respawn-world", worlds(w, true), shown, ctx);
            }
            case "travel" -> {
                prompt(chest, player, w, 10, Material.NAME_TAG, "display-name", shown, ctx);
                prompt(chest, player, w, 11, Material.WRITABLE_BOOK, "description", shown, ctx);
                chest.put(12, stack(Menus.icon(w), text(player, "w-icon"), List.of(value(player, w, "icon"), text(player, "w-icon-lore"),
                        PendingChanges.command(w.name, "icon", "HAND")), false), (v, c) -> {
                    Material hand = v.getInventory().getItemInMainHand().getType();
                    after(v, w.name, shown, ctx, setNow(v, ctx, w.name, "icon", hand.isAir() ? "none" : hand.name()));
                });
                prompt(chest, player, w, 13, Material.ENDER_CHEST, "group", shown, ctx);
                cycle(chest, player, w, 14, Material.OBSIDIAN, "nether", worlds(w, false), shown, ctx);
                cycle(chest, player, w, 15, Material.END_PORTAL_FRAME, "end", worlds(w, false), shown, ctx);
                flip(chest, player, w, 16, "remember-position", shown, ctx);
            }
            case "access" -> {
                flip(chest, player, w, 10, "hidden", shown, ctx);
                flip(chest, player, w, 11, "permission", shown, ctx);
                prompt(chest, player, w, 12, Material.GOLD_INGOT, "price", shown, ctx);
                cycle(chest, player, w, 13, Material.GOLD_NUGGET, "price-mode", WorldSettings.PRICE_MODES, shown, ctx);
                prompt(chest, player, w, 14, Material.PLAYER_HEAD, "player-limit", shown, ctx);
                chest.put(22, stack(Material.IRON_DOOR, text(player, "page-entry-node", "node", Access.permission(w.name)), List.of(text(player, "w-permission-desc")), false), null);
                if (loaded != null && menu.may(player, "send")) send(chest, player, w, 16, shown, ctx);
            }
            case "border" -> {
                prompt(chest, player, w, 10, Material.IRON_BARS, "border", shown, ctx);
                prompt(chest, player, w, 11, Material.COMPASS, "border-center", shown, ctx);
                boolean here = loaded != null && player.getWorld().equals(loaded);
                if (here && menu.may(player, "apply"))
                    chest.put(12, stack(Material.COMPASS, text(player, "page-center-here"), List.of(text(player, "page-center-here-desc"),
                            PendingChanges.command(w.name, "border-center", WorldMenu.center(player.getLocation().getX(), player.getLocation().getZ()))), false),
                            (v, c) -> after(v, w.name, shown, ctx, setNow(v, ctx, w.name, "border-center", WorldMenu.center(v.getLocation().getX(), v.getLocation().getZ()))));
                if (here && menu.may(player, "apply") && ctx != null && plugin.menus().picks().world())
                    chest.put(13, stack(Material.SPYGLASS, text(player, "page-center-pick"), List.of(text(player, "page-center-pick-desc")), false), (v, c) -> {
                        v.closeInventory();
                        WorldsPicks.center(menu, v, w.name, ctx);
                    });
                prompt(chest, player, w, 14, Material.CLOCK, "border-event", shown, ctx);
                if (loaded != null && w.border > 0 && menu.may(player, "border-start") && Features.on(plugin.getConfig(), Features.BORDER_EVENTS))
                    chest.put(16, stack(Material.TARGET, text(player, "page-border-start"), List.of(text(player, "w-border-start-lore"), text(player, "page-click-confirm")), false),
                            (v, c) -> confirm(v, text(v, "w-border-start"), text(v, "w-border-start-lore"), () -> after(v, w.name, shown, ctx, menu.borderStart(v, w.name)),
                                    () -> world(v, w.name, shown, ctx)));
            }
            case "spawn" -> {
                chest.put(4, stack(Material.RED_BED, text(player, "page-tab-spawn"), List.of(text(player, "page-spawn-now", "spawn", spawnText(w))), false), null);
                if (menu.may(player, "set-spawn")) {
                    boolean here = loaded != null && player.getWorld().equals(loaded);
                    chest.put(11, stack(Material.LODESTONE, text(player, "page-spawn-here"), List.of(here ? text(player, "w-set-spawn-lore") : text(player, "stand-in-world", "world", w.name),
                            "/worlds setspawn"), false), (v, c) -> after(v, w.name, shown, ctx, menu.spawnHere(v, w.name)));
                    if (here && ctx != null && plugin.menus().picks().world())
                        chest.put(13, stack(Material.SPYGLASS, text(player, "page-spawn-pick"), List.of(text(player, "page-spawn-pick-desc")), false), (v, c) -> {
                            v.closeInventory();
                            WorldsPicks.spawn(menu, v, w.name, ctx);
                        });
                }
                if (loaded != null && menu.may(player, "go")) go(chest, player, w, 15);
            }
            case "copies" -> {
                if (menu.may(player, "clone"))
                    chest.put(11, stack(Material.STRUCTURE_VOID, text(player, "w-clone"), List.of(text(player, "w-clone-lore"),
                            DangerZone.command(DangerZone.Kind.CLONE, w.name, null, w.name + "_copy", false), text(player, "page-click-name")), false), (v, c) -> {
                        v.closeInventory();
                        plugin.core().dialogs().prompt(plugin, v, text(v, "w-clone"), text(v, "clone-question", "world", w.name), w.name + "_copy", typed -> {
                            if (menu.needsCloneConfirm(w.name))
                                confirm(v, text(v, "w-clone"), text(v, "page-clone-players", "players", playersIn(w.name)),
                                        () -> after(v, w.name, shown, ctx, menu.cloneWorld(v, w.name, typed, true)), () -> world(v, w.name, shown, ctx));
                            else after(v, w.name, shown, ctx, menu.cloneWorld(v, w.name, typed, false));
                        }, () -> world(v, w.name, shown, ctx), p -> menu.may(p, "clone"));
                    });
                if (menu.may(player, "templates"))
                    chest.put(15, stack(Material.SPYGLASS, text(player, "page-templates"), List.of(text(player, "page-templates-desc"), "/worlds template list"), false),
                            (v, c) -> templates(v));
            }
            case "danger" -> {
                boolean server = w.serverWorld || loaded != null && WorldManager.serverWorld(loaded);
                if (server) chest.put(4, stack(Material.BEDROCK, text(player, "page-tab-danger"), List.of(text(player, "server-world", "world", w.name)), false), null);
                if (loaded != null && !server && menu.may(player, "unload"))
                    chest.put(10, stack(Material.RED_CONCRETE, text(player, "page-unload"), List.of(text(player, playersIn(w.name) > 0 ? "page-unload-players" : "panel-unload-confirm",
                            "world", w.name, "players", playersIn(w.name)), "/worlds unload " + w.name, text(player, "page-click-confirm")), false),
                            (v, c) -> confirm(v, text(v, "w-unload"), text(v, playersIn(w.name) > 0 ? "page-unload-players" : "panel-unload-confirm", "world", w.name, "players", playersIn(w.name)),
                                    () -> after(v, w.name, shown, ctx, menu.unload(v, w.name)), () -> world(v, w.name, shown, ctx)));
                if (loaded == null && menu.may(player, "load")) load(chest, player, w, 10, shown, ctx);
                if (!server && menu.may(player, "regen"))
                    chest.put(12, stack(Material.TNT, text(player, "page-regen"), List.of(text(player, "page-regen-confirm", "world", w.name, "players", playersIn(w.name)),
                            DangerZone.command(DangerZone.Kind.REGEN, w.name, "same", null, false), text(player, "page-click-name")), false), (v, c) -> {
                        v.closeInventory();
                        plugin.core().dialogs().prompt(plugin, v, text(v, "regen-title", "world", w.name), text(v, "page-seed-question"), "same", seed -> {
                            String word = seed == null ? "" : seed.strip();
                            String choice = word.equalsIgnoreCase("same") || word.isEmpty() ? "same" : word.equalsIgnoreCase("random") ? "random" : "own";
                            typedName(v, "regen-title", "regen-question", w.name, typed -> after(v, w.name, shown, ctx, menu.regen(v, w.name, choice, word, typed)), shown, ctx);
                        }, () -> world(v, w.name, shown, ctx), p -> menu.may(p, "regen"));
                    });
                if (!server && menu.may(player, "delete"))
                    chest.put(14, stack(Material.LAVA_BUCKET, text(player, "page-delete"), List.of(text(player, "page-delete-confirm", "world", w.name, "players", playersIn(w.name)),
                            DangerZone.command(DangerZone.Kind.DELETE, w.name, null, null, false), text(player, "page-click-name")), false), (v, c) -> {
                        v.closeInventory();
                        typedName(v, "delete-title", "delete-question", w.name, typed -> after(v, w.name, shown, ctx, menu.delete(v, w.name, typed)), shown, ctx);
                    });
                prompt(chest, player, w, 19, Material.CLOCK, "reset", shown, ctx);
                cycle(chest, player, w, 20, Material.WHEAT_SEEDS, "reset-seed", List.of("same", "random"), shown, ctx);
            }
            default -> overview(chest, player, w, loaded, shown, ctx);
        }
        // bottom row: back to the overview, then one button per tab (the open one glints)
        chest.put(45, menus.item(player, Material.ARROW, "page-back", null), (v, c) -> menus.admin(v, 0));
        for (int i = 0; i < WorldPage.TABS.size(); i++) {
            String id = WorldPage.TABS.get(i);
            chest.put(46 + i, stack(Material.valueOf(TAB_ICONS.get(i)), text(player, "page-tab-" + id), List.of(), id.equals(shown)), (v, c) -> world(v, w.name, id, ctx));
        }
        // changes still pending from the screen (the chest opened for a moment, same session): Apply or Discard here too
        java.util.Map<String, String> pending = ctx == null ? java.util.Map.of() : WorldMenu.pending(WorldsPicks.get(ctx, WorldPage.PENDING_KEY));
        List<PendingChanges.Change> changes = PendingChanges.changes(w, pending, menu.worldExists());
        if (!changes.isEmpty() && menu.may(player, "apply")) {
            String summary = PendingChanges.summary(changes, c -> text(player, "page-change", "key", text(player, WorldMenu.label(c.key())),
                    "before", PendingChanges.show(c.before()), "after", PendingChanges.show(c.after())));
            chest.put(6, stack(Material.LIME_DYE, text(player, "page-apply", "count", changes.size()), List.of(summary, text(player, "page-click-confirm")), true),
                    (v, c) -> confirm(v, text(v, "page-apply-title", "world", w.name), text(v, "page-apply-text", "changes", summary), () -> {
                        java.util.Map<String, String> now = WorldMenu.pending(WorldsPicks.get(ctx, WorldPage.PENDING_KEY));
                        Result result = menu.apply(v, w.name, now);
                        WorldsPicks.put(ctx, WorldPage.PENDING_KEY, new java.util.LinkedHashMap<>(now));
                        after(v, w.name, shown, ctx, result);
                    }, () -> world(v, w.name, shown, ctx)));
            chest.put(7, stack(Material.BARRIER, text(player, "page-discard"), List.of(summary), false), (v, c) -> {
                WorldsPicks.put(ctx, WorldPage.PENDING_KEY, new java.util.LinkedHashMap<String, String>());
                after(v, w.name, shown, ctx, Result.ok(text(v, "page-discarded")));
            });
        }
        AuditLog.Line last = plugin.auditLog().last(w.name);
        if (last != null) chest.put(8, stack(Material.PAPER, text(player, "page-last-title"), List.of(last.time() + " " + last.actor(), last.action()), false), null);
        player.openInventory(chest.inventory);
        if (ctx != null) WorldsPicks.result(ctx, result -> say(player, result));
    }

    private void overview(Menus.Menu chest, Player player, WorldSettings w, World loaded, String tab, Object ctx) {
        String error = plugin.manager().loadError(w.name);
        List<String> stats = new ArrayList<>(List.of(
                text(player, "create-type") + ": " + text(player, "kind-" + w.kind.id()),
                text(player, "panel-state") + ": " + text(player, plugin.manager().busy(w.name) ? "page-state-busy" : loaded != null ? "state-loaded" : "state-unloaded"),
                text(player, "page-seed") + ": " + (w.seed == null ? "-" : w.seed.toString()),
                text(player, "create-generator") + ": " + (w.generator.none() ? "-" : w.generator.toString()),
                text(player, "page-height") + ": " + (w.customHeight() ? w.minY + ".." + (w.minY + w.height) : "-"),
                text(player, "w-group") + ": " + w.group,
                text(player, "panel-players") + ": " + (loaded == null ? 0 : loaded.getPlayers().size()),
                text(player, "panel-chunks") + ": " + (loaded == null ? 0 : loaded.getLoadedChunks().length),
                text(player, "panel-entities") + ": " + (loaded == null ? 0 : loaded.getEntities().size())));
        if (error != null) stats.add(text(player, "info-error", "error", error));
        chest.put(4, stack(Menus.icon(w), Menus.plain(w), stats, false), null);
        List<String> inside = loaded == null ? List.of() : loaded.getPlayers().stream().map(Player::getName).sorted().limit(12).toList();
        chest.put(13, stack(Material.PLAYER_HEAD, text(player, "panel-players"), List.of(inside.isEmpty() ? text(player, "page-nobody")
                : text(player, "page-inside", "players", String.join(", ", inside))), false), null);
        if (loaded != null && menu.may(player, "go")) go(chest, player, w, 20);
        if (loaded != null && menu.may(player, "send")) send(chest, player, w, 21, tab, ctx);
        if (loaded == null && menu.may(player, "load")) load(chest, player, w, 22, tab, ctx);
        if (loaded != null && ControlLink.available(plugin) && menu.may(player, "game-rules"))
            chest.put(23, stack(Material.COMMAND_BLOCK, text(player, "page-game-rules"), List.of(text(player, "page-game-rules-desc")), false),
                    (v, c) -> { v.closeInventory(); say(v, menu.gameRules(v, w.name)); });
        if (menu.may(player, "settings-dialog"))
            chest.put(24, stack(Material.COMPARATOR, text(player, "page-dialog"), List.of(text(player, "w-dialog-lore"), "/worlds settings " + w.name), false),
                    (v, c) -> { v.closeInventory(); WorldSection.open(plugin, v, w.name); });
        if (menu.may(player, "info"))
            chest.put(25, stack(Material.BOOK, text(player, "page-info"), List.of(text(player, "page-info-desc"), "/worlds info " + w.name), false),
                    (v, c) -> {
                        v.closeInventory();
                        WorldSettings now = menu.world(w.name);
                        if (menu.may(v, "info") && now != null) plugin.menus().actions().info(v, now);
                    });
    }

    private int playersIn(String name) {
        World loaded = WorldManager.loaded(name);
        return loaded == null ? 0 : loaded.getPlayers().size();
    }

    private static String spawnText(WorldSettings w) {
        return w.spawn == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", w.spawn.x(), w.spawn.y(), w.spawn.z());
    }

    private List<String> worlds(WorldSettings w, boolean respawn) {
        List<String> out = new ArrayList<>(List.of("none"));
        if (respawn) out.add("vanilla");
        for (WorldSettings other : plugin.registry().all()) if (!other.name.equalsIgnoreCase(w.name)) out.add(other.name);
        return out;
    }

    private String value(Player player, WorldSettings w, String key) {
        String v = Properties.get(w, key);
        return text(player, "page-now", "value", v.isEmpty() ? "-" : v);
    }

    /** One change at once from the chest; a value of the same setting still pending from the screen is dropped. */
    private Result setNow(Player player, Object ctx, String world, String key, String value) {
        Result result = menu.setNow(player, world, key, value);
        if (ctx != null && result.success()) {
            java.util.Map<String, String> pending = WorldMenu.pending(WorldsPicks.get(ctx, WorldPage.PENDING_KEY));
            if (pending.remove(key) != null) WorldsPicks.put(ctx, WorldPage.PENDING_KEY, new java.util.LinkedHashMap<>(pending));
        }
        return result;
    }

    /** Back to the same page and tab after an action, with the result in chat. */
    private void after(Player player, String world, String tab, Object ctx, Result result) {
        say(player, result);
        if (player.isOnline()) world(player, world, tab, ctx);
    }

    private void flip(Menus.Menu chest, Player player, WorldSettings w, int slot, String key, String tab, Object ctx) {
        boolean on = Boolean.parseBoolean(Properties.get(w, key));
        chest.put(slot, stack(on ? Material.LIME_DYE : Material.GRAY_DYE, text(player, WorldMenu.label(key)), List.of(text(player, on ? "page-on" : "page-off"),
                text(player, WorldMenu.label(key) + "-desc"), PendingChanges.command(w.name, key, Boolean.toString(!on)), text(player, "page-click-switch")), false),
                (v, c) -> {
                    WorldSettings now = menu.world(w.name);
                    if (now == null) { menus.adminChest(v, 0); return; }
                    after(v, w.name, tab, ctx, setNow(v, ctx, w.name, key, Boolean.toString(!Boolean.parseBoolean(Properties.get(now, key)))));
                });
    }

    private void cycle(Menus.Menu chest, Player player, WorldSettings w, int slot, Material material, String key, List<String> options, String tab, Object ctx) {
        String current = Properties.get(w, key);
        int index = options.indexOf(current);
        String next = options.get(Math.floorMod(index + 1, options.size()));
        chest.put(slot, stack(material, text(player, WorldMenu.label(key)), List.of(value(player, w, key), text(player, WorldMenu.label(key) + "-desc"),
                PendingChanges.command(w.name, key, next), text(player, "page-click-cycle")), false), (v, c) -> {
            WorldSettings now = menu.world(w.name);
            if (now == null) { menus.adminChest(v, 0); return; }
            int at = options.indexOf(Properties.get(now, key));
            String chosen = options.get(Math.floorMod(at + (c.isRightClick() ? -1 : 1), options.size()));
            after(v, w.name, tab, ctx, setNow(v, ctx, w.name, key, chosen));
        });
    }

    private void prompt(Menus.Menu chest, Player player, WorldSettings w, int slot, Material material, String key, String tab, Object ctx) {
        String current = Properties.get(w, key);
        chest.put(slot, stack(material, text(player, WorldMenu.label(key)), List.of(value(player, w, key), text(player, WorldMenu.label(key) + "-desc"),
                PendingChanges.command(w.name, key, "VALUE"), text(player, "page-click-type")), false), (v, c) -> {
            v.closeInventory();
            plugin.core().dialogs().prompt(plugin, v, text(v, WorldMenu.label(key)), text(v, "prompt-" + promptKey(key), "world", w.name),
                    current.equals("none") ? "" : current, typed -> after(v, w.name, tab, ctx, setNow(v, ctx, w.name, key, typed)),
                    () -> world(v, w.name, tab, ctx), p -> menu.may(p, "apply"));
        });
    }

    /** The question of a typed value: the 1.18.0 prompt texts, a generic one for the rest. */
    static String promptKey(String key) {
        return List.of("display-name", "description", "price", "player-limit", "group", "respawn-world", "nether", "end", "border", "border-center",
                "border-event", "reset").contains(key) ? key : "value";
    }

    private void go(Menus.Menu chest, Player player, WorldSettings w, int slot) {
        chest.put(slot, stack(Material.ENDER_PEARL, text(player, "page-go"), List.of("/worlds tp " + w.name), false),
                (v, c) -> { v.closeInventory(); say(v, menu.go(v, w.name)); });
    }

    private void load(Menus.Menu chest, Player player, WorldSettings w, int slot, String tab, Object ctx) {
        chest.put(slot, stack(Material.LIME_CONCRETE, text(player, "page-load"), List.of("/worlds load " + w.name), false),
                (v, c) -> after(v, w.name, tab, ctx, menu.load(v, w.name)));
    }

    private void send(Menus.Menu chest, Player player, WorldSettings w, int slot, String tab, Object ctx) {
        chest.put(slot, stack(Material.PLAYER_HEAD, text(player, "page-send"), List.of(text(player, "page-send-desc"), "/worlds tp " + w.name + " PLAYER"), false), (v, c) -> {
            v.closeInventory();
            if (ctx != null && plugin.menus().picks().players()) { WorldsPicks.send(menu, v, w.name, ctx); return; }
            plugin.core().dialogs().prompt(plugin, v, text(v, "page-send"), text(v, "page-send-question", "world", w.name), "",
                    typed -> after(v, w.name, tab, ctx, menu.send(v, w.name, typed == null ? List.of() : List.of(typed.strip()))), () -> world(v, w.name, tab, ctx), p -> menu.may(p, "send"));
        });
    }

    private void confirm(Player player, String title, String question, Runnable yes, Runnable no) {
        player.closeInventory();
        plugin.core().dialogs().confirm(plugin, player, title, question, yes, no, Player::isOnline);
    }

    /** The world name typed again (as {@code /worlds delete|regen} asks): the result goes to {@code then}. */
    private void typedName(Player player, String titleKey, String questionKey, String world, Consumer<String> then, String tab, Object ctx) {
        plugin.core().dialogs().prompt(plugin, player, text(player, titleKey, "world", world), text(player, questionKey, "world", world), "",
                then, () -> { plugin.lang().send(player, "cancelled"); world(player, world, tab, ctx); }, Player::isOnline);
    }

    // ------------------------------------------------------------------ import, templates, groups

    void imports(Player player) {
        if (!menu.may(player, "import")) { say(player, menu.refuse(player, "import")); return; }
        Menus.Menu chest = chest(player, "admin-import");
        int slot = 0;
        for (WorldPaths.Candidate c : plugin.manager().paths().candidates(plugin.usedNames())) {
            if (slot >= 45) break;
            String name = c.name();
            chest.put(slot++, stack(Material.CHEST, name, List.of(text(player, "kind-" + c.kind().id()) + (c.legacy() ? " *" : ""),
                    "/worlds import " + name, text(player, "page-click-confirm")), false), (v, click) -> confirm(v, text(v, "admin-import"),
                    text(v, "page-import-confirm", "world", name, "type", text(v, "kind-" + c.kind().id()), "group", plugin.defaultGroup()),
                    () -> say(v, menu.importFolder(v, name, "detected", "")), () -> imports(v)));
        }
        if (slot == 0) chest.put(22, menus.item(player, Material.PAPER, "import-none", null), null);
        chest.put(45, menus.item(player, Material.ARROW, "page-back", null), (v, c) -> menus.admin(v, 0));
        player.openInventory(chest.inventory);
    }

    void templates(Player player) {
        if (!menu.may(player, "templates")) { say(player, menu.refuse(player, "templates")); return; }
        Menus.Menu chest = chest(player, "page-templates");
        TemplateVisits visits = plugin.templates();
        int slot = 0;
        for (WorldTemplate t : visits == null ? List.<WorldTemplate>of() : visits.all()) {
            if (slot >= 45) break;
            String id = t.id();
            chest.put(slot++, stack(t.enabled() ? Material.OAK_DOOR : Material.IRON_DOOR, id, List.of(text(player, "template-terrain-" + t.terrain()) + ", "
                    + text(player, "visit-seconds", "seconds", t.seconds()), text(player, "template-inventory-" + t.inventory()),
                    text(player, t.enabled() ? "template-state-open" : "template-state-closed"), "/worlds template " + (t.enabled() ? "close " : "open ") + id,
                    text(player, t.enabled() ? "page-template-close" : "page-template-open")), t.enabled()), (v, c) -> {
                say(v, menu.template(v, t.enabled() ? "close" : "open", id));
                templates(v);
            });
        }
        if (slot == 0) chest.put(22, menus.item(player, Material.PAPER, "template-none", null), null);
        chest.put(45, menus.item(player, Material.ARROW, "page-back", null), (v, c) -> menus.admin(v, 0));
        chest.put(49, stack(Material.EMERALD, text(player, "page-template-create"), List.of("/worlds template create NAME flat|island SECONDS locked|kit"), false),
                (v, c) -> { v.closeInventory(); templateDialog(v); });
        player.openInventory(chest.inventory);
    }

    /** Create a visit template with a Core dialog (Paper dialog, screen form or chat form, whatever the player has). */
    void templateDialog(Player player) {
        List<SingleOptionDialogInput.OptionEntry> terrains = List.of(
                SingleOptionDialogInput.OptionEntry.create("flat", plugin.lang().component(player, "template-terrain-flat"), true),
                SingleOptionDialogInput.OptionEntry.create("island", plugin.lang().component(player, "template-terrain-island"), false));
        List<SingleOptionDialogInput.OptionEntry> inventories = List.of(
                SingleOptionDialogInput.OptionEntry.create("locked", plugin.lang().component(player, "template-inventory-locked"), true),
                SingleOptionDialogInput.OptionEntry.create("kit", plugin.lang().component(player, "template-inventory-kit"), false));
        List<DialogInput> inputs = List.of(
                DialogInput.text("id", plugin.lang().component(player, "page-template-id")).maxLength(24).build(),
                DialogInput.singleOption("terrain", plugin.lang().component(player, "visit-col-terrain"), terrains).build(),
                DialogInput.text("seconds", plugin.lang().component(player, "page-template-seconds")).initial("300").maxLength(4).build(),
                DialogInput.singleOption("inventory", plugin.lang().component(player, "page-template-inventory"), inventories).build());
        DialogButton create = new DialogButton(plugin.lang().component(player, "page-template-create"), (p, response) -> {
            Result result = templateCreate(p, response.getText("id"), response.getText("terrain"), response.getText("seconds"), response.getText("inventory"));
            say(p, result);
            templates(p);
        });
        plugin.core().dialogs().open(plugin, player, new DialogRequest(plugin.lang().component(player, "page-template-create"),
                List.of(), inputs, List.of(create), p -> menu.may(p, "templates"), () -> templates(player)));
    }

    /** Checks the template fields like the record does, then runs {@code /worlds template create}. */
    Result templateCreate(Player player, String id, String terrain, String seconds, String inventory) {
        String i = id == null ? "" : id.strip().toLowerCase(java.util.Locale.ROOT);
        String s = seconds == null ? "" : seconds.strip();
        try { new WorldTemplate(i, terrain == null ? "" : terrain, Integer.parseInt(s), inventory == null ? "" : inventory, true); }
        catch (RuntimeException invalid) { return Result.error(text(player, "page-template-invalid")); }
        return menu.template(player, "create", i, terrain, s, inventory);
    }

    void groups(Player player) {
        if (!menu.may(player, "groups")) { say(player, menu.refuse(player, "groups")); return; }
        Menus.Menu chest = chest(player, "page-groups");
        int slot = 0;
        for (var entry : groupMap(plugin).entrySet()) {
            if (slot >= 45) break;
            chest.put(slot++, stack(Material.ENDER_CHEST, entry.getKey(), List.of(entry.getValue().isEmpty() ? "-" : String.join(", ", entry.getValue())), false), null);
        }
        chest.put(45, menus.item(player, Material.ARROW, "page-back", null), (v, c) -> menus.admin(v, 0));
        chest.put(49, stack(Material.BOOK, text(player, "page-groups"), List.of(text(player, "page-groups-hint")), false), null);
        player.openInventory(chest.inventory);
    }

    /** Group -> worlds, the default group always listed (as {@code /worlds groups}). */
    static Map<String, List<String>> groupMap(WorldsPlugin plugin) {
        Map<String, List<String>> groups = new TreeMap<>();
        groups.computeIfAbsent(plugin.defaultGroup(), g -> new ArrayList<>());
        for (WorldSettings w : plugin.registry().all()) groups.computeIfAbsent(w.group, g -> new ArrayList<>()).add(w.name);
        return groups;
    }
}
