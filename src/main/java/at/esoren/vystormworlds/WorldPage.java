package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * The world page on Vystorm Screens (1.21.0): one world with the tabs Overview, Rules, Travel, Access, Border, Spawn,
 * Copies and Danger. Pure renderer: everything it shows comes in {@link View}, every click goes to {@link Handlers}.
 * Settings are collected as pending changes and applied together after one confirm that lists them; "Copy command"
 * sends the exact {@code /worlds} commands to chat. Regenerate and delete need the world name typed into the form.
 */
final class WorldPage {
    /** Session keys: the open tab and the pending changes ({@code Map<String, String>}); the chest reads the same. */
    static final String TAB_KEY = "tab", PENDING_KEY = "pending";
    static final List<String> TABS = List.of("overview", "rules", "travel", "access", "border", "spawn", "copies", "danger");
    static final List<String> RULES = List.of("game-mode", "difficulty", "pvp", "monsters", "animals", "hunger", "fly",
            "time-lock", "weather-lock", "respawn-world", "keep-spawn-loaded", "autoload");
    static final List<String> TRAVEL = List.of("display-name", "description", "icon", "group", "nether", "end", "remember-position");
    static final List<String> ACCESS = List.of("hidden", "permission", "price", "price-mode", "player-limit");
    static final List<String> BORDER = List.of("border", "border-center", "border-event");
    static final List<String> RESET = List.of("reset", "reset-seed");
    static final Set<String> WORLD_CHOICES = Set.of("respawn-world", "nether", "end");

    /**
     * What the page shows.
     *
     * @param values   every property as {@link Properties#get} shows it now
     * @param pending  the pending changes (property -> new value as typed)
     * @param changes  the real pending changes with before/after
     * @param worlds   the other worlds (for respawn world, nether and end)
     * @param allowed  menu entries ({@link MenuAccess}) the viewer may use
     * @param inWorld  the viewer stands in this world
     */
    record View(String world, String title, Material icon, boolean loaded, boolean serverWorld, boolean busy, String kind,
                String seed, String generator, String height, String group, int players, int chunks, int entities,
                String error, List<String> inside, String spawn, Map<String, String> values, Map<String, String> pending,
                List<PendingChanges.Change> changes, List<String> worlds, Set<String> allowed, boolean inWorld,
                boolean pickers, boolean gameRules, boolean cloneConfirm, boolean borderEvents, String last, String tab) {
        boolean may(String entry) { return allowed.contains(entry); }

        String value(String key) { return pending.containsKey(key) ? pendingShown(key) : values.getOrDefault(key, ""); }

        private String pendingShown(String key) {
            for (PendingChanges.Change c : changes) if (c.key().equals(key)) return c.after();
            return pending.get(key);
        }
    }

    interface Handlers {
        /** A changed setting: becomes a pending change. */
        Result stage(PanelAction a, String key, String value);
        Result apply(PanelAction a);
        Result discard(PanelAction a);
        Result copy(PanelAction a);
        Result tab(PanelAction a, String tab);
        /** A button or form of the page ({@code go}, {@code send}, {@code unload}, {@code delete} ...); {@code values}: form values. */
        Result run(PanelAction a, String op, Map<String, Object> values);
    }

    private WorldPage() {}

    private static Result refresh(PanelAction a, Result result) {
        a.refresh();
        return result;
    }

    static Panel render(PanelContext ctx, View v, Handlers h) {
        Panel panel = Panel.page(ctx.text("world-title", "world", v.world())).subtitle(v.title()).icon(Ui.item(v.icon())).size(Panel.Size.LARGE);
        String tab = TABS.contains(v.tab()) ? v.tab() : "overview";
        Ui.Tabs tabs = Ui.tabs("page-tabs")
                .tab("overview", ctx.text("page-tab-overview"), overview(ctx, v, h))
                .tab("rules", ctx.text("page-tab-rules"), settings(ctx, v, h, RULES))
                .tab("travel", ctx.text("page-tab-travel"), settings(ctx, v, h, TRAVEL))
                .tab("access", ctx.text("page-tab-access"), access(ctx, v, h))
                .tab("border", ctx.text("page-tab-border"), border(ctx, v, h))
                .tab("spawn", ctx.text("page-tab-spawn"), spawn(ctx, v, h))
                .tab("copies", ctx.text("page-tab-copies"), copies(ctx, v, h))
                .tab("danger", ctx.text("page-tab-danger"), danger(ctx, v, h))
                .select(tab).onTab((a, id) -> h.tab(a, id));
        panel.child(tabs);
        if (!v.changes().isEmpty())
            panel.child(Ui.text(ctx.text("page-pending", "count", v.changes().size(), "changes", summary(ctx, v))).id("pending-line"));
        if (!v.last().isEmpty()) panel.child(Ui.text(ctx.text("page-last", "line", v.last())).id("last-line"));
        panel.footer(Ui.button("back").label(ctx.text("page-back-list")).icon(Ui.item(Material.ARROW)).ghost()
                .onClick(a -> h.run(a, "back", Map.of())));
        if (!v.changes().isEmpty() && v.may("apply")) {
            panel.footer(Ui.button("copy").label(ctx.text("page-copy")).icon(Ui.item(Material.PAPER)).ghost().onClick(h::copy),
                    Ui.button("discard").label(ctx.text("page-discard")).icon(Ui.item(Material.BARRIER)).ghost().onClick(a -> refresh(a, h.discard(a))),
                    Ui.button("apply").label(ctx.text("page-apply", "count", v.changes().size())).icon(Ui.item(Material.LIME_DYE)).primary()
                            .confirm(ctx.text("page-apply-title", "world", v.world()), ctx.text("page-apply-text", "changes", summary(ctx, v)))
                            .onClick(a -> refresh(a, h.apply(a))));
        }
        return panel;
    }

    /** "PvP: on to off; Time lock: Off to Noon" in the viewer's language. */
    static String summary(PanelContext ctx, View v) {
        return PendingChanges.summary(v.changes(), c -> ctx.text("page-change", "key", ctx.text(WorldMenu.label(c.key())),
                "before", PendingChanges.show(c.before()), "after", PendingChanges.show(c.after())));
    }

    // ------------------------------------------------------------------ tabs

    private static Ui.Box overview(PanelContext ctx, WorldPage.View v, Handlers h) {
        Ui.Kv stats = Ui.kv().id("world-stats")
                .row(ctx.text("create-type"), ctx.text("kind-" + v.kind()))
                .row(ctx.text("panel-state"), ctx.text(v.busy() ? "page-state-busy" : v.loaded() ? "state-loaded" : "state-unloaded"))
                .row(ctx.text("page-seed"), v.seed()).row(ctx.text("create-generator"), v.generator())
                .row(ctx.text("page-height"), v.height()).row(ctx.text("w-group"), v.group())
                .row(ctx.text("panel-players"), Integer.toString(v.players()))
                .row(ctx.text("panel-chunks"), Integer.toString(v.chunks())).row(ctx.text("panel-entities"), Integer.toString(v.entities()));
        Ui.Box box = Ui.col().gap(6).child(stats);
        if (!v.error().isBlank()) box.child(Ui.text(ctx.text("info-error", "error", v.error())).id("load-error"));
        box.child(Ui.text(v.inside().isEmpty() ? ctx.text("page-nobody") : ctx.text("page-inside", "players", String.join(", ", v.inside()))).id("inside"));
        Ui.Box buttons = Ui.row().gap(4).wrap();
        if (v.loaded() && v.may("go")) buttons.child(button(ctx, h, "go", "page-go", Material.ENDER_PEARL).id("overview-go").primary());
        if (v.loaded() && v.may("send")) buttons.child(button(ctx, h, "send", "page-send", Material.PLAYER_HEAD).id("overview-send").tooltip(ctx.text("page-send-desc")));
        if (!v.loaded() && v.may("load")) buttons.child(button(ctx, h, "load", "page-load", Material.LIME_CONCRETE).id("overview-load").primary());
        if (v.gameRules() && v.loaded() && v.may("game-rules"))
            buttons.child(button(ctx, h, "game-rules", "page-game-rules", Material.COMMAND_BLOCK).tooltip(ctx.text("page-game-rules-desc")));
        if (v.may("settings-dialog")) buttons.child(button(ctx, h, "dialog", "page-dialog", Material.COMPARATOR).ghost().tooltip(ctx.text("w-dialog-lore")));
        if (v.may("info")) buttons.child(button(ctx, h, "info", "page-info", Material.BOOK).ghost().tooltip(ctx.text("page-info-desc")));
        return box.child(buttons);
    }

    private static Ui.Section settings(PanelContext ctx, View v, Handlers h, List<String> keys) {
        Ui.Section section = Ui.section(ctx.text(keys == RULES ? "panel-rules" : "panel-entry")).list();
        for (String key : keys) section.child(control(ctx, v, h, key));
        if (keys == TRAVEL && v.may("apply"))
            section.child(button(ctx, h, "icon-hand", "page-icon-hand", Material.ITEM_FRAME).ghost().tooltip(ctx.text("page-icon-hand-desc")));
        return section;
    }

    private static Ui.Box access(PanelContext ctx, View v, Handlers h) {
        Ui.Section section = Ui.section(ctx.text("page-tab-access")).list();
        for (String key : ACCESS) section.child(control(ctx, v, h, key));
        section.child(Ui.input("exact-price", ctx.text("panel-exact-price")).value(v.value("price")).maxLength(32).submitOnEnter()
                .tooltip(ctx.text("panel-exact-price-desc")).onChange((a, value) -> refresh(a, h.stage(a, "price", value))));
        Ui.Box box = Ui.col().gap(6).child(section)
                .child(Ui.text(ctx.text("page-entry-node", "node", Access.permission(v.world()))).id("entry-node"));
        if (v.loaded() && v.may("send")) box.child(button(ctx, h, "send", "page-send", Material.PLAYER_HEAD).id("access-send").tooltip(ctx.text("page-send-desc")));
        return box;
    }

    private static Ui.Box border(PanelContext ctx, View v, Handlers h) {
        Ui.Section section = Ui.section(ctx.text("page-tab-border")).list();
        for (String key : BORDER) section.child(control(ctx, v, h, key));
        Ui.Box buttons = Ui.row().gap(4).wrap();
        if (v.may("apply") && v.inWorld()) buttons.child(button(ctx, h, "center-here", "page-center-here", Material.COMPASS));
        if (v.may("apply") && v.pickers() && v.inWorld()) buttons.child(button(ctx, h, "center-pick", "page-center-pick", Material.SPYGLASS)
                .tooltip(ctx.text("page-center-pick-desc")));
        if (v.loaded() && v.borderEvents() && v.may("border-start") && !"off".equals(v.values().getOrDefault("border", "off")))
            buttons.child(button(ctx, h, "border-start", "page-border-start", Material.TARGET)
                    .confirm(ctx.text("w-border-start"), ctx.text("w-border-start-lore")));
        return Ui.col().gap(6).children(section, buttons);
    }

    private static Ui.Box spawn(PanelContext ctx, View v, Handlers h) {
        Ui.Box box = Ui.col().gap(6).child(Ui.text(ctx.text("page-spawn-now", "spawn", v.spawn())).id("spawn-now"));
        Ui.Box buttons = Ui.row().gap(4).wrap();
        if (v.may("set-spawn")) {
            if (v.inWorld()) buttons.child(button(ctx, h, "spawn-here", "page-spawn-here", Material.LODESTONE).primary());
            else box.child(Ui.text(ctx.text("stand-in-world", "world", v.world())).id("spawn-stand"));
            if (v.pickers() && v.inWorld()) buttons.child(button(ctx, h, "spawn-pick", "page-spawn-pick", Material.SPYGLASS).tooltip(ctx.text("page-spawn-pick-desc")));
        }
        if (v.loaded() && v.may("go")) buttons.child(button(ctx, h, "go", "page-go", Material.ENDER_PEARL).id("spawn-go"));
        return box.child(buttons);
    }

    private static Ui.Box copies(PanelContext ctx, View v, Handlers h) {
        Ui.Box box = Ui.col().gap(6);
        if (v.may("clone")) {
            Ui.Form form = Ui.form("clone-form", ctx.text("page-clone")).child(Ui.input("clone-target", ctx.text("page-clone-name"))
                    .value(v.world() + "_copy").maxLength(32));
            if (v.cloneConfirm()) {
                form.child(Ui.text(ctx.text("page-clone-players", "players", v.players())).id("clone-players"));
                form.confirm(ctx.text("w-clone"), ctx.text("page-clone-players", "players", v.players()));
            }
            form.onSubmit((a, values) -> h.run(a, "clone", values));
            box.child(form);
        }
        if (v.may("templates")) box.child(button(ctx, h, "templates", "page-templates", Material.SPYGLASS).ghost().tooltip(ctx.text("page-templates-desc")));
        return box;
    }

    private static Ui.Box danger(PanelContext ctx, View v, Handlers h) {
        Ui.Box box = Ui.col().gap(6);
        if (v.serverWorld()) box.child(Ui.text(ctx.text("server-world", "world", v.world())).id("server-world"));
        if (v.loaded() && !v.serverWorld() && v.may("unload"))
            box.child(button(ctx, h, "unload", "page-unload", Material.RED_CONCRETE).danger()
                    .confirm(ctx.text("w-unload"), ctx.text(v.players() > 0 ? "page-unload-players" : "panel-unload-confirm", "world", v.title(), "players", v.players())));
        if (!v.loaded() && v.may("load")) box.child(button(ctx, h, "load", "page-load", Material.LIME_CONCRETE).primary());
        if (!v.serverWorld() && v.may("regen")) {
            box.child(Ui.form("regen-form", ctx.text("page-regen"))
                    .child(Ui.select("regen-seed-choice", ctx.text("page-seed-choice")).option("same", ctx.text("panel-option-same"))
                            .option("random", ctx.text("panel-option-random")).option("own", ctx.text("page-seed-own")).value("same"))
                    .child(Ui.input("regen-seed", ctx.text("page-seed-own-value")).maxLength(64))
                    .child(Ui.input("regen-typed", ctx.text("page-type-name", "world", v.world())).placeholder(v.world()).maxLength(40))
                    .confirm(ctx.text("regen-title", "world", v.world()), ctx.text("page-regen-confirm", "world", v.world(), "players", v.players()))
                    .onSubmit((a, values) -> h.run(a, "regen", values)));
            box.child(Ui.form("delete-form", ctx.text("page-delete"))
                    .child(Ui.input("delete-typed", ctx.text("page-type-name", "world", v.world())).placeholder(v.world()).maxLength(40))
                    .confirm(ctx.text("delete-title", "world", v.world()), ctx.text("page-delete-confirm", "world", v.world(), "players", v.players()))
                    .onSubmit((a, values) -> h.run(a, "delete", values)));
        }
        Ui.Section reset = Ui.section(ctx.text("page-reset")).list();
        for (String key : RESET) reset.child(control(ctx, v, h, key));
        return box.child(reset);
    }

    // ------------------------------------------------------------------ one control per property

    private static Ui.Element<?> control(PanelContext ctx, View v, Handlers h, String key) {
        String name = WorldMenu.label(key);
        boolean pending = v.pending().containsKey(key);
        String label = pending ? ctx.text("page-pending-mark", "name", ctx.text(name)) : ctx.text(name);
        String description = ctx.text(name + "-desc");
        String value = v.value(key);
        boolean editable = v.may("apply");
        Ui.Element<?> control;
        if (WorldsPanel.TOGGLES.contains(key)) {
            control = Ui.toggle("set-" + key, label).value(Boolean.parseBoolean(value)).description(description)
                    .onChange((a, on) -> refresh(a, h.stage(a, key, on.toString())));
        } else if (WorldsPanel.CHOICES.containsKey(key) || WORLD_CHOICES.contains(key)) {
            Ui.Select select = Ui.select("set-" + key, label);
            List<String> options = WORLD_CHOICES.contains(key) ? worldOptions(key, v) : WorldsPanel.CHOICES.get(key);
            for (String option : options) select.option(option, WORLD_CHOICES.contains(key) && !option.equals("none") && !option.equals("vanilla")
                    ? option : ctx.text(WorldsPanel.optionKey(option)));
            if (!options.contains(value) && !value.isEmpty()) select.option(value, value);
            if (options.size() > 8) select.searchable();
            control = select.value(value.isEmpty() ? "none" : value).tooltip(description).onChange((a, chosen) -> refresh(a, h.stage(a, key, chosen)));
        } else if (key.equals("price") || key.equals("player-limit") || key.equals("border") && parse(value) <= 60_000) {
            // Core allows at most 100,000 steps; larger borders and exact prices are typed (input below / text field)
            double now = parse(value);
            double max = switch (key) { case "price" -> Math.max(1000, now); case "border" -> 60_000; default -> 10000; };
            double step = key.equals("price") ? Math.max(0.01, Math.ceil(max / 1000) / 100) : 1;
            control = Ui.number("set-" + key, label).range(0, max, step).decimals(key.equals("price") ? 2 : 0).value(now).tooltip(description)
                    .onChange((a, n) -> refresh(a, h.stage(a, key, key.equals("price") ? n.toString() : number(key, n))));
        } else {
            control = Ui.input("set-" + key, label).value(value.equals("none") ? "" : value).maxLength(key.equals("description") ? 120 : 64).submitOnEnter()
                    .tooltip(description).onChange((a, typed) -> refresh(a, h.stage(a, key, typed)));
        }
        if (!editable) control.disabled(true);
        return control;
    }

    /** "none" first (a select cycles to the next option, so the no-op must lead), then "vanilla" for the respawn world. */
    private static List<String> worldOptions(String key, View v) {
        List<String> options = new java.util.ArrayList<>();
        options.add("none");
        if (key.equals("respawn-world")) options.add("vanilla");
        for (String w : v.worlds()) if (!w.equalsIgnoreCase(v.world())) options.add(w);
        return options;
    }

    private static double parse(String value) {
        try { return value == null || value.equals("off") ? 0 : Double.parseDouble(value); } catch (NumberFormatException bad) { return 0; }
    }

    /** The border field sends 0 for "off" (the command's value), whole blocks otherwise. */
    static String number(String key, double n) {
        long whole = Math.round(n);
        return key.equals("border") && whole <= 0 ? "off" : Long.toString(whole);
    }

    private static Ui.Button button(PanelContext ctx, Handlers h, String op, String label, Material icon) {
        return Ui.button(op).label(ctx.text(label)).icon(Ui.item(icon)).onClick(a -> h.run(a, op, Map.of()));
    }
}
