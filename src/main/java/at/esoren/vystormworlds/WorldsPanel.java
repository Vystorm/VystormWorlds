package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pure renderers shared by the selector, management overview and world editor. */
final class WorldsPanel {
    static final List<String> TOGGLES = List.of("pvp", "monsters", "animals", "hunger", "fly", "keep-spawn-loaded",
            "autoload", "hidden", "permission", "remember-position");
    static final Map<String, List<String>> CHOICES = Map.of(
            "game-mode", List.of("none", "survival", "creative", "adventure", "spectator"),
            "difficulty", List.of("none", "peaceful", "easy", "normal", "hard"),
            "time-lock", WorldSettings.TIME_LOCKS, "weather-lock", WorldSettings.WEATHER_LOCKS,
            "price-mode", WorldSettings.PRICE_MODES, "reset-seed", List.of("same", "random"));

    /** @param badges the settings Worlds manages for the world, one line ({@link WorldBadges}, 1.21.0; admin view) */
    record Row(String name, String title, Material icon, String description, String kind, String group,
               boolean loaded, boolean protectedWorld, int players, int limit, int chunks, int entities,
               String error, String access, boolean favorite, boolean here, String price, boolean charge, String badges) {
        Row(String name, String title, Material icon, String description, String kind, String group,
            boolean loaded, boolean protectedWorld, int players, int limit, int chunks, int entities,
            String error, String access, boolean favorite, boolean here, String price, boolean charge) {
            this(name, title, icon, description, kind, group, loaded, protectedWorld, players, limit, chunks, entities, error, access, favorite, here, price, charge, "");
        }
    }
    /**
     * @param arrival the player's arrival choice for the selector, {@code "spawn"} or {@code "last"}; empty hides it
     * @param trip    {@code "visit"} (open visit worlds exist), {@code "leave"} (the player is visiting) or empty (1.20.0)
     */
    record Overview(List<Row> rows, boolean admin, boolean canAdmin, boolean favorites, String selected,
                    int page, int pages, String search, Ui.Sort sort, String tps, String arrival, String trip) {
        Overview(List<Row> rows, boolean admin, boolean canAdmin, boolean favorites, String selected,
                 int page, int pages, String search, Ui.Sort sort, String tps) {
            this(rows, admin, canAdmin, favorites, selected, page, pages, search, sort, tps, "", "");
        }
    }
    /** An open visit template as the visit menu shows it (1.20.0). */
    record Trip(String id, String terrain, int seconds, String inventory) {}

    interface Actions {
        Result state(Player player, boolean admin, String key, String value);
        Result set(Player player, String world, String key, String value);
        Result favorite(Player player, String world);
        void go(Player player, String action, String world);
        /** {@code /worlds arrival spawn|last} from the selector (1.20.0). */
        default Result arrival(Player player, String choice) { return Result.ok(); }
    }

    private WorldsPanel() {}

    private static Result refresh(PanelAction a, Result result) {
        if (result.success()) a.refresh();
        return result;
    }

    static Panel overview(PanelContext ctx, Overview o, Actions actions) {
        Panel panel = Panel.page(ctx.text(o.admin() ? "vmenu-admin" : "vmenu-worlds"))
                .subtitle(ctx.text(o.admin() ? "vmenu-admin-desc" : "vmenu-worlds-desc"))
                .icon(Ui.item(o.admin() ? Material.COMPASS : Material.FILLED_MAP)).size(Panel.Size.LARGE);
        panel.child(Ui.input("search", ctx.text("panel-search")).value(o.search()).maxLength(80)
                .search((a, value) -> refresh(a, actions.state(a.player(), o.admin(), "search", value))));
        Row chosen = o.rows().stream().filter(r -> r.name().equals(o.selected())).findFirst()
                .orElse(o.rows().isEmpty() ? null : o.rows().getFirst());
        Ui.Box body = Ui.row().gap(8);
        String empty = ctx.text(o.favorites() ? "favorites-empty" : "selector-none");
        if (o.admin()) {
            Ui.Table table = Ui.table("worlds-table").column("name", ctx.text("vmenu-worlds")).sortable()
                    .column("players", ctx.text("panel-players")).sortable()
                    .column("state", ctx.text("panel-state")).sortable()
                    .column("settings", ctx.text("panel-settings"))
                    .rows(o.rows(), r -> Ui.row(r.name()).cell(r.title()).cell(Integer.toString(r.players()))
                            .cell(ctx.text(!r.error().isBlank() ? "page-state-error" : r.loaded() ? "state-loaded" : "state-unloaded"))
                            .cell(r.badges().isEmpty() ? "-" : r.badges()))
                    .empty(empty).selected(chosen == null ? null : chosen.name()).sort(o.sort().column(), o.sort().descending())
                    .paging(o.page(), o.pages(),
                            (a, page) -> refresh(a, actions.state(a.player(), true, "page", page.toString())),
                            (a, sort) -> refresh(a, actions.state(a.player(), true, "sort", sort.column() + ":" + sort.descending())))
                    .onSelect((a, name) -> refresh(a, actions.state(a.player(), true, "selected", name)));
            body.child(table.grow(2));
        } else {
            Ui.ListView list = Ui.list("worlds-list").items(o.rows(), r -> Ui.listItem(r.name(), r.title())
                            .icon(Ui.item(r.icon())).subtitle(ctx.text("selector-players", "players", r.players(), "limit", r.limit() > 0 ? "/" + r.limit() : ""))
                            .right(r.favorite() ? ctx.text("panel-favorite") : ""))
                    .empty(empty).selected(chosen == null ? null : chosen.name())
                    .onSelect((a, name) -> refresh(a, actions.state(a.player(), false, "selected", name)));
            body.child(list.grow(2));
        }
        if (chosen != null) body.child(detail(ctx, chosen, o, actions).grow(3));
        if (o.admin()) panel.children(Ui.badge(o.tps()).id("server-performance"), body);
        else panel.child(Ui.tabs("world-filter")
                .tab("all", ctx.text("panel-all"), o.favorites() ? Ui.col() : body)
                .tab("favorites", ctx.text("panel-favorites"), o.favorites() ? body : Ui.col())
                .select(o.favorites() ? "favorites" : "all")
                .onTab((a, tab) -> refresh(a, actions.state(a.player(), false, "favorites", Boolean.toString(tab.equals("favorites"))))));
        if (!o.admin() && !o.arrival().isEmpty())
            panel.child(Ui.select("arrival", ctx.text("arrival-label"))
                    .option("last", ctx.text("arrival-option-last")).option("spawn", ctx.text("arrival-option-spawn"))
                    .value(o.arrival()).tooltip(ctx.text("arrival-desc"))
                    .onChange((a, value) -> refresh(a, actions.arrival(a.player(), value))));
        if (!o.admin()) {
            panel.footer(Ui.button("previous").label(ctx.text("page-back")).icon(Ui.item(Material.ARROW)).ghost().disabled(o.page() == 0)
                            .onClick(a -> refresh(a, actions.state(a.player(), false, "page", Integer.toString(o.page() - 1)))),
                    Ui.button("next").label(ctx.text("page-next")).icon(Ui.item(Material.ARROW)).ghost().disabled(o.page() + 1 >= o.pages())
                            .onClick(a -> refresh(a, actions.state(a.player(), false, "page", Integer.toString(o.page() + 1)))),
                    leave(ctx, actions, "spawn", "selector-spawn", Material.RED_BED, ""));
            if (o.trip().equals("visit")) panel.footer(leave(ctx, actions, "visits", "visit-button", Material.SPYGLASS, "").tooltip(ctx.text("visit-desc")));
            else if (o.trip().equals("leave")) panel.footer(leave(ctx, actions, "leave", "leave-button", Material.OAK_DOOR, "").tooltip(ctx.text("leave-desc")));
            if (o.canAdmin()) panel.footer(leave(ctx, actions, "admin", "selector-to-admin", Material.COMPASS, ""));
        } else {
            panel.footer(leave(ctx, actions, "create", "admin-create", Material.EMERALD, "").primary(),
                    leave(ctx, actions, "import", "admin-import", Material.CHEST, ""),
                    leave(ctx, actions, "templates", "admin-templates", Material.SPYGLASS, ""),
                    leave(ctx, actions, "groups", "admin-groups", Material.ENDER_CHEST, ""),
                    leave(ctx, actions, "reload", "admin-reload", Material.REPEATER, ""),
                    leave(ctx, actions, "list", "admin-list", Material.BOOK, ""),
                    leave(ctx, actions, "selector", "admin-selector", Material.FILLED_MAP, ""),
                    leave(ctx, actions, "config", "admin-config", Material.COMPARATOR, ""));
        }
        return panel;
    }

    private static Ui.Box detail(PanelContext ctx, Row row, Overview o, Actions actions) {
        Ui.Box box = Ui.col().gap(6).children(Ui.heading(row.title()), Ui.text(row.description()), stats(ctx, row, o.admin()));
        if (!row.error().isBlank()) box.child(Ui.text(ctx.text("info-error", "error", row.error())));
        if (o.admin()) box.child(leave(ctx, actions, "edit", "button-edit-hover", Material.COMPARATOR, row.name()).primary());
        else {
            if (row.here()) box.child(Ui.badge(ctx.text("panel-here")));
            if (!row.access().isEmpty()) box.child(Ui.badge(ctx.text(row.access())));
            if (row.limit() > 0) box.child(Ui.progress(Math.min(row.players(), row.limit()), row.limit()).id("capacity")
                    .label(ctx.text("selector-players", "players", row.players(), "limit", "/" + row.limit())));
            if (!row.price().isEmpty()) box.child(Ui.text(row.price()));
            Ui.Button travel = leave(ctx, actions, "travel", "w-teleport", Material.ENDER_PEARL, row.name()).primary();
            Ui.Button spawn = leave(ctx, actions, "travel-spawn", "panel-spawn", Material.RED_BED, row.name());
            if (row.charge()) {
                travel.confirm(ctx.text("pay-title"), row.price());
                spawn.confirm(ctx.text("pay-title"), row.price());
            }
            box.children(travel, spawn, Ui.toggle("favorite", ctx.text("panel-favorite")).value(row.favorite())
                    .onChange((a, value) -> refresh(a, actions.favorite(a.player(), row.name()))));
            if (o.canAdmin()) box.child(leave(ctx, actions, "edit", "button-edit-hover", Material.COMPARATOR, row.name()));
        }
        return box;
    }

    private static Ui.Kv stats(PanelContext ctx, Row row, boolean admin) {
        Ui.Kv stats = Ui.kv().id("world-stats").row(ctx.text("create-type"), ctx.text("kind-" + row.kind()))
                .row(ctx.text("w-group"), row.group()).row(ctx.text("panel-state"), ctx.text(row.loaded() ? "state-loaded" : "state-unloaded"))
                .row(ctx.text("panel-players"), Integer.toString(row.players()));
        if (admin) stats.row(ctx.text("panel-chunks"), Integer.toString(row.chunks())).row(ctx.text("panel-entities"), Integer.toString(row.entities()));
        return stats;
    }

    /** The visit menu (1.20.0): the open templates with a Visit button each, or Leave while the player is visiting. */
    static Panel visits(PanelContext ctx, List<Trip> trips, boolean visiting, Actions actions) {
        Panel panel = Panel.page(ctx.text("visit-title")).subtitle(ctx.text("visit-desc"))
                .icon(Ui.item(Material.SPYGLASS)).size(Panel.Size.MEDIUM);
        if (visiting) panel.child(Ui.text(ctx.text("visit-busy")).id("visiting"));
        else panel.child(Ui.table("visit-table").column("name", ctx.text("visit-col-name"))
                .column("terrain", ctx.text("visit-col-terrain")).column("time", ctx.text("visit-col-time"))
                .column("go", "")
                .rows(trips, t -> Ui.row(t.id()).cell(t.id()).cell(ctx.text("template-terrain-" + t.terrain()))
                        .cell(ctx.text("visit-seconds", "seconds", t.seconds()))
                        .cell(leave(ctx, actions, "visit", "visit-button", Material.ENDER_PEARL, t.id()).primary())
                        .tooltip(ctx.text("template-inventory-" + t.inventory())))
                .empty(ctx.text("visit-none")));
        panel.footer(leave(ctx, actions, "selector", "page-back", Material.ARROW, ""));
        if (visiting) panel.footer(leave(ctx, actions, "leave", "leave-button", Material.OAK_DOOR, "").primary());
        return panel;
    }

    static String optionKey(String value) {
        return List.of("survival", "creative", "adventure", "spectator").contains(value) ? "mode-" + value : "panel-option-" + value;
    }

    private static Ui.Button leave(PanelContext ctx, Actions actions, String action, String label, Material icon, String world) {
        return Ui.button(action).label(ctx.text(label)).icon(Ui.item(icon)).onClick(a -> {
            a.closeAndThen(player -> actions.go(player, action, world));
            return Result.ok();
        });
    }
}
