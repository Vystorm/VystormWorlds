package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
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
    /** Panel names (Core ids {@code vystormworlds:<name>}); the world page keeps its 1.14.0 name. */
    static final String PAGE = "world-settings", CREATE = "worlds-create", IMPORT = "worlds-import", TEMPLATES = "worlds-templates", GROUPS = "worlds-groups";
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
            // 1.21.0: the world page with tabs, and its chest with the panel's context where Core has it
            panels.register(PAGE, withChest(PanelSpec.builder(plugin.lang().ref("section-title")).access(p -> allowed(p, true)).render(this::page),
                    (p, arg, ctx) -> menus.chests().world(p, arg.isBlank() ? editing.getOrDefault(p.getUniqueId(), "") : arg, null, ctx),
                    p -> menus.worldChest(p, editing.getOrDefault(p.getUniqueId(), ""))).build());
            panels.register("worlds-visit", PanelSpec.builder(plugin.lang().ref("visit-title"))
                    .access(p -> allowed(p, false)).render(this::visits)
                    .fallback(menus::visitChest).build());
            panels.register(CREATE, PanelSpec.builder(plugin.lang().ref("create-title"))
                    .access(p -> allowed(p, true) && menus.worldMenu().may(p, "new-world")).render(this::newWorld)
                    .fallback(menus::createDialog).build());
            panels.register(IMPORT, PanelSpec.builder(plugin.lang().ref("admin-import"))
                    .access(p -> allowed(p, true) && menus.worldMenu().may(p, "import")).render(this::imports)
                    .fallback(p -> menus.chests().imports(p)).build());
            panels.register(TEMPLATES, PanelSpec.builder(plugin.lang().ref("page-templates"))
                    .access(p -> allowed(p, true)).render(this::templates)
                    .fallback(p -> menus.chests().templates(p)).build());
            panels.register(GROUPS, PanelSpec.builder(plugin.lang().ref("page-groups"))
                    .access(p -> allowed(p, true)).render(this::groups)
                    .fallback(p -> menus.chests().groups(p)).build());
        } catch (RuntimeException failure) {
            for (String name : List.of("worlds", "worlds-admin", PAGE, "worlds-visit", CREATE, IMPORT, TEMPLATES, GROUPS)) {
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

    boolean openVisits(Player player) { return open(player, "worlds-visit", ""); }

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
                state.favorites, selected.get(player.getUniqueId()), state.page, pages, state.search, state.sort, Actions.tps(plugin.core().compat().platform()),
                admin ? "" : menus.arrivalChoice(player), admin ? "" : menus.trip(player)), this);
    }

    private Panel visits(PanelContext ctx) {
        TemplateVisits templates = plugin.templates();
        List<WorldsPanel.Trip> trips = templates == null ? List.of()
                : templates.open().stream().map(t -> new WorldsPanel.Trip(t.id(), t.terrain(), t.seconds(), t.inventory())).toList();
        return WorldsPanel.visits(ctx, trips, templates != null && templates.visiting(ctx.player()), this);
    }

    @Override public Result arrival(Player player, String choice) {
        if (!allowed(player, false)) return denied(player);
        if (!List.of("spawn", "last").contains(choice)) return denied(player);
        menus.command(player, "arrival", choice);
        return Result.ok();
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
                error == null ? "" : error, accessKey, favorite, world != null && world.equals(player.getWorld()), price, charge,
                admin ? WorldBadges.summary(w, key -> plugin.lang().plain(player, key)) : "");
    }

    // ------------------------------------------------------------------ 1.21.0: world page and admin pages

    /** A chest fallback with the panel's context (Core 0.71.0) or, on older Cores, the plain one. */
    private PanelSpec.Builder withChest(PanelSpec.Builder spec, WorldsPicks.Chest chest, java.util.function.Consumer<Player> plain) {
        Menus.Picks picks = menus.picks();
        if (picks != null && picks.context()) WorldsPicks.contextFallback(spec, chest);
        else spec.fallback(plain);
        return spec;
    }

    boolean openPage(Player player, String name) { return open(player, name, ""); }

    private WorldMenu worldMenu() { return menus.worldMenu(); }

    private Panel page(PanelContext ctx) {
        Player player = ctx.player();
        String name = ctx.arg().isBlank() ? editing.getOrDefault(player.getUniqueId(), "") : ctx.arg();
        WorldSettings w = plugin.registry().get(name);
        if (w == null) return Panel.page(ctx.text("section-title")).child(Ui.text(ctx.text("world-changed", "world", name)).id("missing-world"));
        editing.put(player.getUniqueId(), w.name);
        Map<String, String> values = new HashMap<>();
        for (String key : Properties.KEYS) values.put(key, Properties.get(w, key));
        Map<String, String> pending = WorldMenu.pending(ctx.session().get(WorldPage.PENDING_KEY));
        World world = WorldManager.loaded(w.name);
        Menus.Picks picks = menus.picks();
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (MenuAccess.Entry entry : MenuAccess.ENTRIES) if (worldMenu().may(player, entry.id())) allowed.add(entry.id());
        AuditLog.Line last = plugin.auditLog().last(w.name);
        String error = plugin.manager().loadError(w.name);
        Object tab = ctx.session().get(WorldPage.TAB_KEY);
        WorldPage.View view = new WorldPage.View(w.name, Menus.plain(w), Menus.icon(w), world != null,
                w.serverWorld || world != null && WorldManager.serverWorld(world), plugin.manager().busy(w.name), w.kind.id(),
                w.seed == null ? "-" : w.seed.toString(), w.generator.none() ? "-" : w.generator.toString(),
                w.customHeight() ? w.minY + ".." + (w.minY + w.height) : "-", w.group, world == null ? 0 : world.getPlayers().size(),
                world == null ? 0 : world.getLoadedChunks().length, world == null ? 0 : world.getEntities().size(), error == null ? "" : error,
                world == null ? List.of() : world.getPlayers().stream().map(Player::getName).sorted().limit(20).toList(),
                w.spawn == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f", w.spawn.x(), w.spawn.y(), w.spawn.z()),
                Map.copyOf(values), pending, PendingChanges.changes(w, pending, worldMenu().worldExists()),
                plugin.registry().all().stream().map(s -> s.name).sorted().toList(), allowed, world != null && world.equals(player.getWorld()),
                picks != null && picks.world(), ControlLink.available(plugin), worldMenu().needsCloneConfirm(w.name),
                Features.on(plugin.getConfig(), Features.BORDER_EVENTS), last == null ? "" : last.time() + " " + last.actor() + ": " + last.action(),
                tab instanceof String s ? s : "overview");
        return WorldPage.render(ctx, view, new PageHandlers(w.name, view.cloneConfirm()));
    }

    /** The world page's clicks for one world (the name is the panel argument, checked again on every click). */
    private final class PageHandlers implements WorldPage.Handlers {
        private final String world;
        private final boolean cloneConfirmShown;

        PageHandlers(String world, boolean cloneConfirmShown) { this.world = world; this.cloneConfirmShown = cloneConfirmShown; }

        private Map<String, String> pending(PanelAction a) { return WorldMenu.pending(a.session().get(WorldPage.PENDING_KEY)); }

        @Override public Result stage(PanelAction a, String key, String value) {
            Map<String, String> pending = pending(a);
            Result result = worldMenu().stage(a.player(), world, pending, key, value);
            a.session().put(WorldPage.PENDING_KEY, new java.util.LinkedHashMap<>(pending));
            return result;
        }

        @Override public Result apply(PanelAction a) {
            Map<String, String> pending = pending(a);
            Result result = worldMenu().apply(a.player(), world, pending);
            a.session().put(WorldPage.PENDING_KEY, new java.util.LinkedHashMap<>(pending));
            return result;
        }

        @Override public Result discard(PanelAction a) {
            a.session().put(WorldPage.PENDING_KEY, new java.util.LinkedHashMap<String, String>());
            return Result.ok(plugin.lang().plain(a.player(), "page-discarded"));
        }

        @Override public Result copy(PanelAction a) {
            WorldSettings w = plugin.registry().get(world);
            if (w == null) return Result.error(plugin.lang().plain(a.player(), "world-changed", "world", world));
            return worldMenu().copy(a.player(), PendingChanges.commands(w.name, PendingChanges.changes(w, pending(a), worldMenu().worldExists())));
        }

        @Override public Result tab(PanelAction a, String tab) {
            if (WorldPage.TABS.contains(tab)) a.session().put(WorldPage.TAB_KEY, tab);
            return Result.ok();
        }

        @Override public Result run(PanelAction a, String op, Map<String, Object> values) {
            Player player = a.player();
            Menus.Picks picks = menus.picks();
            Result result = switch (op) {
                case "back" -> { a.closeAndThen(p -> menus.admin(p, 0)); yield Result.ok(); }
                case "go" -> {
                    Result refused = worldMenu().refuse(player, "go");
                    if (refused != null) yield refused;
                    a.closeAndThen(p -> say(p, worldMenu().go(p, world)));
                    yield Result.ok();
                }
                case "send" -> {
                    Result refused = worldMenu().refuse(player, "send");
                    if (refused != null) yield refused;
                    if (picks != null && picks.players()) yield WorldsPicks.send(worldMenu(), a, world);
                    a.closeAndThen(p -> plugin.core().dialogs().prompt(plugin, p, plugin.lang().plain(p, "page-send"),
                            plugin.lang().plain(p, "page-send-question", "world", world), "",
                            typed -> { say(p, worldMenu().send(p, world, typed == null ? List.of() : List.of(typed))); menus.world(p, world); },
                            () -> menus.world(p, world), q -> worldMenu().may(q, "send")));
                    yield Result.ok();
                }
                case "load" -> worldMenu().load(player, world);
                case "unload" -> worldMenu().unload(player, world);
                case "game-rules" -> {
                    Result refused = worldMenu().refuse(player, "game-rules");
                    if (refused != null) yield refused;
                    a.closeAndThen(p -> { Result r = worldMenu().gameRules(p, world); if (!r.success()) say(p, r); });
                    yield Result.ok();
                }
                case "dialog" -> {
                    Result refused = worldMenu().refuse(player, "settings-dialog");
                    if (refused != null) yield refused;
                    a.closeAndThen(p -> WorldSection.open(plugin, p, world));
                    yield Result.ok();
                }
                case "info" -> {
                    Result refused = worldMenu().refuse(player, "info");
                    if (refused != null) yield refused;
                    WorldSettings w = plugin.registry().get(world);
                    if (w == null) yield Result.error(plugin.lang().plain(player, "world-changed", "world", world));
                    menus.actions().info(player, w);
                    yield Result.ok(plugin.lang().plain(player, "page-info-sent"));
                }
                case "icon-hand" -> {
                    org.bukkit.Material hand = player.getInventory().getItemInMainHand().getType();
                    yield stage(a, "icon", hand.isAir() ? "none" : hand.name());
                }
                case "center-here" -> stage(a, "border-center", WorldMenu.center(player.getLocation().getX(), player.getLocation().getZ()));
                case "center-pick" -> picks != null && picks.world() ? pick(a, "apply", () -> WorldsPicks.center(worldMenu(), a, world)) : Result.ok();
                case "border-start" -> worldMenu().borderStart(player, world);
                case "spawn-here" -> worldMenu().spawnHere(player, world);
                case "spawn-pick" -> picks != null && picks.world() ? pick(a, "set-spawn", () -> WorldsPicks.spawn(worldMenu(), a, world)) : Result.ok();
                case "clone" -> worldMenu().cloneWorld(player, world, String.valueOf(values.getOrDefault("clone-target", "")), cloneConfirmShown);
                case "regen" -> worldMenu().regen(player, world, String.valueOf(values.getOrDefault("regen-seed-choice", "same")),
                        String.valueOf(values.getOrDefault("regen-seed", "")), String.valueOf(values.getOrDefault("regen-typed", "")));
                case "delete" -> {
                    Result r = worldMenu().delete(player, world, String.valueOf(values.getOrDefault("delete-typed", "")));
                    if (r.success()) a.closeAndThen(p -> menus.admin(p, 0));
                    yield r;
                }
                case "templates" -> { a.closeAndThen(menus::templates); yield Result.ok(); }
                default -> denied(player);
            };
            if (!List.of("back", "go", "send", "game-rules", "dialog", "templates", "delete", "center-pick", "spawn-pick").contains(op)) a.refresh();
            return result;
        }

        private Result pick(PanelAction a, String entry, java.util.function.Supplier<Result> start) {
            Result refused = worldMenu().refuse(a.player(), entry);
            return refused != null ? refused : start.get();
        }
    }

    /** A menu result after the screen closed: as a chat line. */
    private void say(Player player, Result result) {
        if (result == null || result.message() == null || result.message().isBlank()) return;
        plugin.core().compat().platform().sendMessage(player, net.kyori.adventure.text.Component.text(result.message(),
                result.success() ? net.kyori.adventure.text.format.NamedTextColor.GREEN : net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ new world, import, templates, groups

    private static final String FORM = "form", IMPORT_TYPE = "import-type", IMPORT_GROUP = "import-group";

    private Panel newWorld(PanelContext ctx) {
        NewWorldForm form = ctx.session().get(FORM) instanceof NewWorldForm f ? f : NewWorldForm.defaults(plugin.defaultGroup());
        List<AdminPages.Choice> generators = plugin.manager().generators().choices().stream()
                .map(c -> new AdminPages.Choice(c.value(), ctx.text(c.preset() ? "generator-preset" : "generator-plugin", "name", c.label()))).toList();
        boolean heights = Features.on(plugin.getConfig(), Features.BUILD_HEIGHT) && plugin.manager().paths().customHeights();
        return AdminPages.newWorld(ctx, form, worldMenu().check(form), generators, heights, pageHandlers);
    }

    private Panel imports(PanelContext ctx) {
        List<AdminPages.Folder> folders = plugin.manager().paths().candidates(plugin.usedNames()).stream()
                .map(c -> new AdminPages.Folder(c.name(), c.kind().id(), c.legacy())).toList();
        String type = ctx.session().get(IMPORT_TYPE) instanceof String s ? s : "detected";
        String group = ctx.session().get(IMPORT_GROUP) instanceof String s ? s : plugin.defaultGroup();
        return AdminPages.imports(ctx, folders, type, group, pageHandlers);
    }

    private Panel templates(PanelContext ctx) {
        TemplateVisits visits = plugin.templates();
        List<AdminPages.Template> list = visits == null ? List.of() : visits.all().stream()
                .map(t -> new AdminPages.Template(t.id(), t.terrain(), t.seconds(), t.inventory(), t.enabled())).toList();
        return AdminPages.templates(ctx, list, Features.on(plugin.getConfig(), Features.TEMPLATES), pageHandlers);
    }

    private Panel groups(PanelContext ctx) {
        return AdminPages.groups(ctx, PageChests.groupMap(plugin), plugin.getConfig().getBoolean("groups.enabled", true),
                String.join(", ", plugin.getConfig().getStringList("groups.separate")), pageHandlers);
    }

    private final AdminPages.Handlers pageHandlers = new AdminPages.Handlers() {
        @Override public Result form(PanelAction a, String field, Object value) {
            switch (field) {
                case IMPORT_TYPE, IMPORT_GROUP -> a.session().put(field, String.valueOf(value));
                default -> {
                    NewWorldForm form = a.session().get(FORM) instanceof NewWorldForm f ? f : NewWorldForm.defaults(plugin.defaultGroup());
                    a.session().put(FORM, form.with(Map.of(field, value)));
                }
            }
            return Result.ok();
        }

        @Override public Result submit(PanelAction a, String op, Map<String, Object> values) {
            Player player = a.player();
            switch (op) {
                case "create" -> {
                    NewWorldForm form = (a.session().get(FORM) instanceof NewWorldForm f ? f : NewWorldForm.defaults(plugin.defaultGroup())).with(values);
                    a.session().put(FORM, form);
                    Result result = worldMenu().create(player, form);
                    String name = form.name().toLowerCase(Locale.ROOT);
                    if (result.success() && plugin.registry().contains(name)) a.closeAndThen(p -> menus.world(p, name));
                    else a.refresh();
                    return result;
                }
                case "template-create" -> {
                    Object seconds = values.get("template-seconds");
                    String s = seconds instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(seconds);
                    Result result = menus.chests().templateCreate(player, String.valueOf(values.getOrDefault("template-id", "")),
                            String.valueOf(values.getOrDefault("template-terrain", "flat")), s, String.valueOf(values.getOrDefault("template-inventory", "locked")));
                    a.refresh();
                    return result;
                }
                default -> { return denied(player); }
            }
        }

        @Override public Result go(PanelAction a, String op, String arg) {
            Player player = a.player();
            switch (op) {
                case "admin" -> { a.closeAndThen(p -> menus.admin(p, 0)); return Result.ok(); }
                case "copy" -> { return worldMenu().copy(player, List.of(arg)); }
                case "import" -> {
                    Result result = worldMenu().importFolder(player, arg, a.session().get(IMPORT_TYPE) instanceof String s ? s : "detected",
                            a.session().get(IMPORT_GROUP) instanceof String g ? g : "");
                    a.refresh();
                    return result;
                }
                case "open", "close" -> {
                    Result result = worldMenu().template(player, op, arg);
                    a.refresh();
                    return result;
                }
                default -> { return denied(player); }
            }
        }
    };

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
        boolean admin = !List.of("travel", "travel-spawn", "spawn", "selector", "visits", "visit", "leave").contains(action);
        if (!allowed(player, admin)) { plugin.lang().send(player, "denied"); return; }
        switch (action) {
            // visit worlds (1.20.0): the same code as /worlds visit TEMPLATE and /worlds leave; "world" is the template id
            case "visits" -> { menus.visits(player); return; }
            case "visit" -> { menus.command(player, "visit", world); return; }
            case "leave" -> { menus.command(player, "leave"); return; }
            default -> { }
        }
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
            case "create" -> menus.newWorld(player);
            case "import" -> menus.imports(player);
            case "templates" -> menus.templates(player);
            case "groups" -> menus.groups(player);
            case "reload" -> { Result r = menus.worldMenu().reload(player); if (!r.success()) say(player, r); }
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
                if (!Features.on(plugin.getConfig(), Features.BORDER_EVENTS)) plugin.lang().send(player, Features.OFF);
                else if (!Features.on(plugin.getConfig(), Features.BORDER)) plugin.lang().send(player, "feature-needs", "switch", Features.BORDER, "name", plugin.lang().plain(player, Features.labelKey(Features.BORDER)));
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
