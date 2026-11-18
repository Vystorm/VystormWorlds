package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Optional Core adapter, constructed only inside the PANELS capability guard. Server thread only. */
final class WorldPanelMenus implements WorldsPanel.Actions {
    private static final int PAGE_SIZE = 9;
    private final WorldsPlugin plugin;
    private final Menus menus;
    private final WorldFavorites favorites;
    private final Panels panels;
    private final Map<UUID, State> selectors = new HashMap<>(), admins = new HashMap<>();
    private final Map<UUID, String> selected = new HashMap<>(), editing = new HashMap<>();

    private static final class State {
        int page;
        boolean favorites;
        String search = "";
        Ui.Sort sort = new Ui.Sort("name", false);
    }

    WorldPanelMenus(WorldsPlugin plugin, Menus menus, WorldFavorites favorites) {
        this.plugin = plugin;
        this.menus = menus;
        this.favorites = favorites;
        this.panels = plugin.core().gui().panels().of(plugin);
    }

    void register() {
        try {
            panels.register("worlds", PanelSpec.builder(plugin.lang().ref("vmenu-worlds"))
                    .access(p -> allowed(p, false)).render(ctx -> overview(ctx, false))
                    .menu(MenuCategory.WORLD, Material.FILLED_MAP, plugin.lang().ref("vmenu-worlds-desc"))
                    .fallback(p -> { State s = state(p, false); menus.selectorChest(p, s.page, s.favorites); }).build());
            panels.register("worlds-admin", PanelSpec.builder(plugin.lang().ref("vmenu-admin"))
                    .access(p -> allowed(p, true)).render(ctx -> overview(ctx, true))
                    .menu(MenuCategory.ADMIN, Material.COMPASS, plugin.lang().ref("vmenu-admin-desc"))
                    .fallback(p -> menus.adminChest(p, state(p, true).page)).build());
            panels.register("world-settings", PanelSpec.builder(plugin.lang().ref("section-title"))
                    .access(p -> allowed(p, true)).render(this::editor)
                    .fallback(p -> menus.worldChest(p, editing.getOrDefault(p.getUniqueId(), ""))).build());
        } catch (RuntimeException failure) {
            for (String name : List.of("worlds", "worlds-admin", "world-settings")) {
                try { panels.unregister(name); } catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }

    boolean openSelector(Player player, int page, boolean onlyFavorites) {
        State state = state(player, false);
        state.page = Math.max(0, page);
        state.favorites = onlyFavorites;
        return open(player, "worlds", "");
    }

    boolean openAdmin(Player player, int page) {
        state(player, true).page = Math.max(0, page);
        return open(player, "worlds-admin", "");
    }

    boolean openWorld(Player player, String world) {
        editing.put(player.getUniqueId(), world);
        return open(player, "world-settings", world);
    }

    private boolean open(Player player, String name, String arg) {
        try { return panels.open(player, name, arg) != PanelOpenResult.NONE; }
        catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "World panel not opened: " + name, failure);
            return false;
        }
    }

    void forget(UUID player) {
        selectors.remove(player);
        admins.remove(player);
        selected.remove(player);
        editing.remove(player);
    }

    private State state(Player player, boolean admin) {
        return (admin ? admins : selectors).computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    private boolean allowed(Player player, boolean admin) {
        // selector.enabled / admin-menu.enabled (1.17.0): the panel and its main-menu entry disappear while switched off
        return plugin.isEnabled() && player.isOnline() && player.hasPermission(admin ? "vystormworlds.admin" : "vystormworlds.use")
                && Features.on(plugin.getConfig(), admin ? Features.ADMIN_MENU : Features.SELECTOR);
    }

    private Result denied(Player player) { return Result.error(plugin.lang().plain(player, "denied")); }

    private Panel overview(PanelContext ctx, boolean admin) {
        Player player = ctx.player();
        State state = state(player, admin);
        var saved = favorites.read(player);
        List<WorldSettings> worlds = admin ? new ArrayList<>(plugin.registry().all())
                : WorldFavorites.arrange(menus.selectable(player), saved, state.favorites);
        List<WorldsPanel.Row> rows = worlds.stream().map(w -> row(player, w, WorldFavorites.contains(saved, w.name), admin))
                .filter(r -> (r.name() + " " + r.title()).toLowerCase(Locale.ROOT).contains(state.search.toLowerCase(Locale.ROOT))).toList();
        if (admin) {
            Comparator<WorldsPanel.Row> order = switch (state.sort.column()) {
                case "players" -> Comparator.comparingInt(WorldsPanel.Row::players);
                case "state" -> Comparator.comparing(WorldsPanel.Row::loaded);
                default -> Comparator.comparing(WorldsPanel.Row::name);
            };
            if (state.sort.descending()) order = order.reversed();
            rows = rows.stream().sorted(order.thenComparing(WorldsPanel.Row::name)).toList();
        }
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        state.page = Math.clamp(state.page, 0, pages - 1);
        rows = rows.subList(state.page * PAGE_SIZE, Math.min(rows.size(), (state.page + 1) * PAGE_SIZE));
        return WorldsPanel.overview(ctx, new WorldsPanel.Overview(rows, admin, player.hasPermission("vystormworlds.admin"),
                state.favorites, selected.get(player.getUniqueId()), state.page, pages, state.search, state.sort, Actions.tps(plugin.core().compat().platform())), this);
    }

    private WorldsPanel.Row row(Player player, WorldSettings w, boolean favorite, boolean admin) {
        World world = WorldManager.loaded(w.name);
        Access.Result access = admin ? Access.Result.OK : plugin.rules().access(player, w, world);
        String accessKey = switch (access) {
            case NO_PERMISSION -> "selector-locked";
            case FULL -> "selector-full";
            case NO_MONEY -> "selector-too-poor";
            default -> "";
        };
        boolean charge = w.price > 0 && Money.available() && Access.charges(w, plugin.rules().viewer(player, w));
        String price = w.price > 0 && Money.available() ? plugin.lang().plain(player,
                charge ? "selector-price-" + w.priceMode : "selector-unlocked", "price", Money.format(w.price)) : "";
        String error = plugin.manager().loadError(w.name);
        return new WorldsPanel.Row(w.name, Menus.plain(w), Menus.icon(w), w.description, w.kind.id(), w.group,
                world != null, w.serverWorld || world != null && WorldManager.serverWorld(world), world == null ? 0 : world.getPlayers().size(), w.playerLimit,
                admin && world != null ? world.getLoadedChunks().length : 0, admin && world != null ? world.getEntities().size() : 0,
                error == null ? "" : error, accessKey, favorite, world != null && world.equals(player.getWorld()), price, charge);
    }

    private Panel editor(PanelContext ctx) {
        String name = ctx.arg().isBlank() ? editing.getOrDefault(ctx.player().getUniqueId(), "") : ctx.arg();
        WorldSettings w = plugin.registry().get(name);
        if (w == null) return Panel.page(ctx.text("section-title")).child(Ui.text(ctx.text("world-changed", "world", name)).id("missing-world"));
        editing.put(ctx.player().getUniqueId(), w.name);
        Map<String, String> values = new HashMap<>();
        for (String key : Properties.KEYS) values.put(key, Properties.get(w, key));
        return WorldsPanel.editor(ctx, new WorldsPanel.Editor(row(ctx.player(), w, false, true), Map.copyOf(values)), this);
    }

    @Override public Result state(Player player, boolean admin, String key, String value) {
        if (!allowed(player, admin)) return denied(player);
        State state = state(player, admin);
        switch (key) {
            case "selected" -> {
                if (admin ? !plugin.registry().contains(value) : menus.selectable(player).stream().noneMatch(w -> w.name.equals(value)))
                    return Result.error(plugin.lang().plain(player, "world-changed", "world", value));
                selected.put(player.getUniqueId(), value);
            }
            case "page" -> state.page = Math.max(0, Integer.parseInt(value));
            case "search" -> { state.search = value; state.page = 0; }
            case "favorites" -> { state.favorites = Boolean.parseBoolean(value); state.page = 0; }
            case "sort" -> {
                String[] parts = value.split(":");
                if (parts.length != 2 || !List.of("name", "players", "state").contains(parts[0])) return denied(player);
                state.sort = new Ui.Sort(parts[0], Boolean.parseBoolean(parts[1]));
                state.page = 0;
            }
            default -> { return denied(player); }
        }
        return Result.ok();
    }

    @Override public Result set(Player player, String world, String key, String value) {
        if (!allowed(player, true)) return denied(player);
        if (plugin.registry().error() != null)
            return Result.error(plugin.lang().plain(player, "registry-broken", "error", plugin.registry().error()));
        WorldSettings current = plugin.registry().get(world);
        if (current == null) return Result.error(plugin.lang().plain(player, "world-changed", "world", world));
        if (!Properties.KEYS.contains(key)) return denied(player);
        if (Features.blocks(plugin.getConfig(), current, key, value)) return Result.error(plugin.lang().plain(player, Features.OFF));
        return menus.actions().setProperty(player, current, key, value) ? Result.ok()
                : Result.error(plugin.lang().plain(player, "bad-value", "key", plugin.lang().plain(player, "w-" + key), "value", value));
    }

    @Override public Result favorite(Player player, String world) {
        if (!allowed(player, false)) return denied(player);
        if (!Features.on(plugin.getConfig(), Features.FAVORITES)) return Result.error(plugin.lang().plain(player, Features.OFF));
        WorldSettings current = menus.selectable(player).stream().filter(w -> w.name.equals(world)).findFirst().orElse(null);
        if (current == null) return Result.error(plugin.lang().plain(player, "world-changed", "world", world));
        boolean added = favorites.toggle(player, world);
        return Result.ok(plugin.lang().plain(player, added ? "favorite-added" : "favorite-removed", "world", Menus.plain(current)));
    }

    @Override public void go(Player player, String action, String world) {
        boolean admin = !List.of("travel", "travel-spawn", "spawn", "selector").contains(action);
        if (!allowed(player, admin)) { plugin.lang().send(player, "denied"); return; }
        WorldSettings current = world.isEmpty() ? null : plugin.registry().get(world);
        if (!world.isEmpty() && current == null) { plugin.lang().send(player, "world-changed", "world", world); return; }
        switch (action) {
            case "travel", "travel-spawn" -> {
                if (menus.selectable(player).stream().noneMatch(w -> w.name.equals(world))) {
                    plugin.lang().send(player, "world-changed", "world", world); return;
                }
                menus.actions().travel(player, current, action.equals("travel"));
            }
            case "spawn" -> plugin.teleports().send(player, Teleports.spawn(player.getWorld(), plugin.settings(player.getWorld())), ok -> { });
            case "selector" -> menus.selector(player, 0);
            case "admin" -> menus.admin(player, 0);
            case "edit" -> menus.world(player, world);
            case "create" -> menus.createDialog(player);
            case "import" -> menus.actions().importList(player);
            case "list" -> menus.actions().list(player);
            case "config" -> player.performCommand("vadmin config " + plugin.getName());
            case "dialog" -> WorldSection.open(plugin, player, world);
            case "setspawn" -> {
                if (!player.getWorld().equals(WorldManager.loaded(world))) { plugin.lang().send(player, "stand-in-world", "world", world); return; }
                player.performCommand("worlds setspawn");
                menus.world(player, world);
            }
            case "admin-travel" -> {
                World target = WorldManager.loaded(world);
                if (target == null) { plugin.lang().send(player, "not-loaded", "world", world); return; }
                menus.actions().go(player, current, target, false);
            }
            case "load", "unload", "regen", "delete" -> {
                // Commands retain busy/server-world checks and the typed-name confirmation dialogs.
                player.performCommand("worlds " + action + " " + world);
                if (action.equals("load") || action.equals("unload")) menus.world(player, world);
            }
            case "border-start" -> {
                if (!Features.on(plugin.getConfig(), Features.BORDER_EVENTS) || !Features.on(plugin.getConfig(), Features.BORDER)) plugin.lang().send(player, Features.OFF);
                else if (!plugin.borders().start(current)) plugin.lang().send(player, "border-no-size", "world", world);
                menus.world(player, world);
            }
            case "clone" -> plugin.core().dialogs().prompt(plugin, player, plugin.lang().plain(player, "w-clone"),
                    plugin.lang().plain(player, "clone-question", "world", world), world + "_copy",
                    target -> { if (allowed(player, true)) player.performCommand("worlds clone " + world + " " + target.strip()); },
                    () -> {}, p -> allowed(p, true));
            default -> { }
        }
    }
}
