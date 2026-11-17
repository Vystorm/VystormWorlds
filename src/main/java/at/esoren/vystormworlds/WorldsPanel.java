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

    record Row(String name, String title, Material icon, String description, String kind, String group,
               boolean loaded, boolean protectedWorld, int players, int limit, int chunks, int entities,
               String error, String access, boolean favorite, boolean here, String price, boolean charge) {}
    record Overview(List<Row> rows, boolean admin, boolean canAdmin, boolean favorites, String selected,
                    int page, int pages, String search, Ui.Sort sort, String tps) {}
    record Editor(Row world, Map<String, String> values) {}

    interface Actions {
        Result state(Player player, boolean admin, String key, String value);
        Result set(Player player, String world, String key, String value);
        Result favorite(Player player, String world);
        void go(Player player, String action, String world);
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
                    .rows(o.rows(), r -> Ui.row(r.name()).cell(r.title()).cell(Integer.toString(r.players()))
                            .cell(ctx.text(r.loaded() ? "state-loaded" : "state-unloaded")))
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
        if (!o.admin()) {
            panel.footer(Ui.button("previous").label(ctx.text("page-back")).icon(Ui.item(Material.ARROW)).ghost().disabled(o.page() == 0)
                            .onClick(a -> refresh(a, actions.state(a.player(), false, "page", Integer.toString(o.page() - 1)))),
                    Ui.button("next").label(ctx.text("page-next")).icon(Ui.item(Material.ARROW)).ghost().disabled(o.page() + 1 >= o.pages())
                            .onClick(a -> refresh(a, actions.state(a.player(), false, "page", Integer.toString(o.page() + 1)))),
                    leave(ctx, actions, "spawn", "selector-spawn", Material.RED_BED, ""));
            if (o.canAdmin()) panel.footer(leave(ctx, actions, "admin", "selector-to-admin", Material.COMPASS, ""));
        } else {
            panel.footer(leave(ctx, actions, "create", "admin-create", Material.EMERALD, "").primary(),
                    leave(ctx, actions, "import", "admin-import", Material.CHEST, ""),
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

    static Panel editor(PanelContext ctx, Editor e, Actions actions) {
        Row row = e.world();
        Ui.Box rules = Ui.col().gap(6), entry = Ui.col().gap(6), borders = Ui.col().gap(6);
        for (String key : Properties.KEYS) {
            String value = e.values().getOrDefault(key, "");
            String label = ctx.text("w-" + switch (key) { case "keep-spawn-loaded" -> "keep-spawn"; case "remember-position" -> "remember"; default -> key; });
            Ui.Element<?> control;
            if (TOGGLES.contains(key)) control = Ui.toggle("set-" + key, label).value(Boolean.parseBoolean(value))
                    .onChange((a, v) -> refresh(a, actions.set(a.player(), row.name(), key, v.toString())));
            else if (CHOICES.containsKey(key)) {
                Ui.Select select = Ui.select("set-" + key, label);
                for (String option : CHOICES.get(key)) select.option(option, ctx.text(optionKey(option)));
                if (!CHOICES.get(key).contains(value) && !value.isEmpty()) select.option(value, value);
                control = select.value(value.isEmpty() ? "none" : value)
                        .onChange((a, v) -> refresh(a, actions.set(a.player(), row.name(), key, v)));
            } else if (key.equals("price") || key.equals("player-limit")) {
                // Core permits at most 100,000 steps. Exact prices of any size also have a text field below.
                double max = key.equals("price") ? Math.max(1000, Double.parseDouble(value)) : 10000;
                control = Ui.number("set-" + key, label).range(0, max, key.equals("price") ? Math.max(0.01, Math.ceil(max / 1000) / 100) : 1)
                        .decimals(key.equals("price") ? 2 : 0).value(Double.parseDouble(value))
                        .onChange((a, v) -> refresh(a, actions.set(a.player(), row.name(), key, key.equals("price") ? v.toString() : Long.toString(v.longValue()))));
            } else control = Ui.input("set-" + key, label).value(value).maxLength(key.equals("description") ? 120 : 64).submitOnEnter()
                    .onChange((a, v) -> refresh(a, actions.set(a.player(), row.name(), key, v)));
            if (key.startsWith("border") || key.startsWith("reset")) borders.child(control);
            else if (TOGGLES.contains(key) || key.endsWith("-lock") || key.equals("difficulty")) rules.child(control);
            else entry.child(control);
        }
        entry.child(Ui.input("exact-price", ctx.text("panel-exact-price")).value(e.values().get("price")).maxLength(32).submitOnEnter()
                .onChange((a, value) -> refresh(a, actions.set(a.player(), row.name(), "price", value))));
        Ui.Box operations = Ui.col().gap(6);
        if (row.loaded()) {
            operations.children(leave(ctx, actions, "setspawn", "w-set-spawn", Material.LODESTONE, row.name()),
                    leave(ctx, actions, "admin-travel", "w-teleport", Material.ENDER_PEARL, row.name()));
            if (!row.protectedWorld()) operations.child(leave(ctx, actions, "unload", "w-unload", Material.RED_CONCRETE, row.name()).danger()
                    .confirm(ctx.text("w-unload"), ctx.text("panel-unload-confirm", "world", row.title())));
            if (Integer.parseInt(e.values().getOrDefault("border", "0").replace("off", "0")) > 0)
                borders.child(leave(ctx, actions, "border-start", "w-border-start", Material.TARGET, row.name())
                        .confirm(ctx.text("w-border-start"), ctx.text("w-border-start-lore")));
        } else operations.child(leave(ctx, actions, "load", "w-load", Material.LIME_CONCRETE, row.name()).primary());
        if (!row.protectedWorld()) {
            operations.children(leave(ctx, actions, "regen", "w-regenerate", Material.TNT, row.name()).danger()
                            .confirm(ctx.text("regen-title", "world", row.name()), ctx.text("regen-question", "world", row.name())),
                    leave(ctx, actions, "delete", "w-delete", Material.LAVA_BUCKET, row.name()).danger()
                            .confirm(ctx.text("delete-title", "world", row.name()), ctx.text("delete-question", "world", row.name())));
        }
        operations.child(leave(ctx, actions, "clone", "w-clone", Material.STRUCTURE_VOID, row.name()));
        return Panel.page(ctx.text("world-title", "world", row.name())).subtitle(row.title()).icon(Ui.item(row.icon())).size(Panel.Size.LARGE)
                .children(stats(ctx, row, true), Ui.tabs("settings-tabs")
                        .tab("rules", ctx.text("panel-rules"), rules).tab("entry", ctx.text("panel-entry"), entry)
                        .tab("borders", ctx.text("panel-borders"), borders).tab("operations", ctx.text("panel-operations"), operations))
                .footer(leave(ctx, actions, "admin", "page-back", Material.ARROW, ""),
                        leave(ctx, actions, "dialog", "w-dialog", Material.COMPARATOR, row.name()).ghost());
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
