package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * The admin pages next to the world page (1.21.0), as pure renderers: "New world" (form with the exact command and one
 * confirm), "Import folder" (the folders {@code /worlds import} lists), "Visit templates" ({@code /worlds template}) and
 * "World groups" ({@code /worlds groups}).
 */
final class AdminPages {
    /** A generator choice of the new world form: value and label. */
    record Choice(String value, String label) {}
    /** An importable folder: name, detected type id, old server folder layout. */
    record Folder(String name, String kind, boolean legacy) {}
    /** A visit template as the templates page shows it. */
    record Template(String id, String terrain, int seconds, String inventory, boolean open) {}

    interface Handlers {
        Result form(PanelAction a, String field, Object value);
        Result submit(PanelAction a, String op, Map<String, Object> values);
        Result go(PanelAction a, String op, String arg);
    }

    private AdminPages() {}

    private static Ui.Button button(PanelContext ctx, Handlers h, String id, String op, String arg, String label, Material icon) {
        return Ui.button(id).label(ctx.text(label)).icon(Ui.item(icon)).onClick(a -> h.go(a, op, arg));
    }

    private static Result refresh(PanelAction a, Result r) { a.refresh(); return r; }

    // ------------------------------------------------------------------ new world

    /**
     * @param check   the form's current check (problem shown under the form)
     * @param heights custom build heights may be set ({@code build-height.enabled} and a server that supports them)
     */
    static Panel newWorld(PanelContext ctx, NewWorldForm f, NewWorldForm.Check check, List<Choice> generators, boolean heights, Handlers h) {
        Ui.Select type = Ui.select("type", ctx.text("create-type")).segmented();
        for (WorldKind kind : WorldKind.values()) type.option(kind.id(), ctx.text("kind-" + kind.id()));
        type.value(f.type()).onChange((a, v) -> refresh(a, h.form(a, "type", v)));
        Ui.Select generator = Ui.select("generator", ctx.text("create-generator")).option("none", ctx.text("generator-none"));
        for (Choice c : generators) generator.option(c.value(), c.label());
        if (generators.size() > 8) generator.searchable();
        generator.value(f.generator()).onChange((a, v) -> refresh(a, h.form(a, "generator", v)));
        Ui.Form form = Ui.form("new-world", ctx.text("create-button"))
                .child(Ui.input("name", ctx.text("create-name")).value(f.name()).maxLength(32)
                        .onChange((a, v) -> refresh(a, h.form(a, "name", v))))
                .child(type).child(generator)
                .child(Ui.input("generator-id", ctx.text("create-generator-id")).value(f.generatorId()).maxLength(128)
                        .onChange((a, v) -> refresh(a, h.form(a, "generator-id", v))))
                .child(Ui.input("seed", ctx.text("create-seed")).value(f.seed()).maxLength(64).onChange((a, v) -> refresh(a, h.form(a, "seed", v))))
                .child(Ui.toggle("structures", ctx.text("create-structures")).value(f.structures()).onChange((a, v) -> refresh(a, h.form(a, "structures", v))))
                .child(Ui.toggle("nether", ctx.text("create-nether")).value(f.nether()).onChange((a, v) -> refresh(a, h.form(a, "nether", v))))
                .child(Ui.toggle("end", ctx.text("create-end")).value(f.end()).onChange((a, v) -> refresh(a, h.form(a, "end", v))))
                .child(Ui.input("group", ctx.text("create-group")).value(f.group()).maxLength(32).onChange((a, v) -> refresh(a, h.form(a, "group", v))));
        if (heights) form.child(Ui.input("min-y", ctx.text("create-min-y")).value(f.minY()).maxLength(6).onChange((a, v) -> refresh(a, h.form(a, "min-y", v))))
                .child(Ui.input("height", ctx.text("create-height")).value(f.height()).maxLength(6).onChange((a, v) -> refresh(a, h.form(a, "height", v))));
        form.confirm(ctx.text("create-title"), ctx.text("page-create-confirm", "command", f.command()))
                .onSubmit((a, values) -> h.submit(a, "create", values));
        Panel panel = Panel.page(ctx.text("create-title")).subtitle(ctx.text("create-help")).icon(Ui.item(Material.EMERALD)).size(Panel.Size.LARGE)
                .child(form).child(Ui.text(ctx.text("page-command", "command", f.command())).id("command-line"));
        if (!f.name().isEmpty() && !check.ok()) panel.child(Ui.text(ctx.text(check.error(), "token", check.token(), "world", f.name())).id("form-problem"));
        return panel.footer(button(ctx, h, "back", "admin", "", "page-back-list", Material.ARROW).ghost(),
                button(ctx, h, "copy", "copy", f.command(), "page-copy", Material.PAPER).ghost());
    }

    // ------------------------------------------------------------------ import

    static Panel imports(PanelContext ctx, List<Folder> folders, String type, String group, Handlers h) {
        Ui.Select kind = Ui.select("import-type", ctx.text("page-import-type")).option("detected", ctx.text("page-import-detected"));
        for (WorldKind k : WorldKind.values()) kind.option(k.id(), ctx.text("kind-" + k.id()));
        kind.value(type).onChange((a, v) -> refresh(a, h.form(a, "import-type", v)));
        Ui.Table table = Ui.table("import-table").column("name", ctx.text("page-folder")).column("type", ctx.text("create-type")).column("go", "")
                .rows(folders, f -> Ui.row(f.name()).cell(f.name()).cell(ctx.text("kind-" + f.kind()) + (f.legacy() ? " *" : ""))
                        .cell(Ui.button("import-" + f.name()).label(ctx.text("page-import-one")).icon(Ui.item(Material.CHEST)).primary()
                                .confirm(ctx.text("admin-import"), ctx.text("page-import-confirm", "world", f.name(),
                                        "type", ctx.text(type.equals("detected") ? "kind-" + f.kind() : "kind-" + type), "group", group))
                                .onClick(a -> h.go(a, "import", f.name())))
                        .tooltip(ctx.text(f.legacy() ? "page-import-legacy" : "page-import-hint")))
                .empty(ctx.text("import-none"));
        return Panel.page(ctx.text("admin-import")).subtitle(ctx.text("admin-import-lore")).icon(Ui.item(Material.CHEST)).size(Panel.Size.LARGE)
                .children(Ui.row().gap(6).children(kind, Ui.input("import-group", ctx.text("create-group")).value(group).maxLength(32)
                        .onChange((a, v) -> refresh(a, h.form(a, "import-group", v)))), table)
                .footer(button(ctx, h, "back", "admin", "", "page-back-list", Material.ARROW).ghost());
    }

    // ------------------------------------------------------------------ visit templates

    static Panel templates(PanelContext ctx, List<Template> templates, boolean on, Handlers h) {
        Panel panel = Panel.page(ctx.text("page-templates")).subtitle(ctx.text("page-templates-desc")).icon(Ui.item(Material.SPYGLASS)).size(Panel.Size.LARGE);
        if (!on) panel.child(Ui.text(ctx.text(Features.OFF)).id("templates-off"));
        panel.child(Ui.table("template-table").column("name", ctx.text("visit-col-name")).column("terrain", ctx.text("visit-col-terrain"))
                .column("time", ctx.text("visit-col-time")).column("state", ctx.text("panel-state")).column("go", "")
                .rows(templates, t -> Ui.row(t.id()).cell(t.id()).cell(ctx.text("template-terrain-" + t.terrain()))
                        .cell(ctx.text("visit-seconds", "seconds", t.seconds()))
                        .cell(ctx.text(t.open() ? "template-state-open" : "template-state-closed"))
                        .cell(Ui.button("toggle-" + t.id()).label(ctx.text(t.open() ? "page-template-close" : "page-template-open"))
                                .icon(Ui.item(t.open() ? Material.IRON_DOOR : Material.OAK_DOOR)).onClick(a -> h.go(a, t.open() ? "close" : "open", t.id())))
                        .tooltip(ctx.text("template-inventory-" + t.inventory())))
                .empty(ctx.text("template-none")));
        Ui.Select terrain = Ui.select("template-terrain", ctx.text("visit-col-terrain")).option("flat", ctx.text("template-terrain-flat"))
                .option("island", ctx.text("template-terrain-island")).value("flat");
        Ui.Select inventory = Ui.select("template-inventory", ctx.text("page-template-inventory")).option("locked", ctx.text("template-inventory-locked"))
                .option("kit", ctx.text("template-inventory-kit")).value("locked");
        panel.child(Ui.form("template-form", ctx.text("page-template-create"))
                .child(Ui.input("template-id", ctx.text("page-template-id")).maxLength(24))
                .child(terrain).child(Ui.number("template-seconds", ctx.text("page-template-seconds")).range(10, 3600, 10).value(300).decimals(0))
                .child(inventory).onSubmit((a, values) -> h.submit(a, "template-create", values)));
        return panel.footer(button(ctx, h, "back", "admin", "", "page-back-list", Material.ARROW).ghost());
    }

    // ------------------------------------------------------------------ groups

    static Panel groups(PanelContext ctx, Map<String, List<String>> groups, boolean on, String separate, Handlers h) {
        Ui.Kv kv = Ui.kv().id("groups");
        for (var entry : groups.entrySet()) kv.row(entry.getKey(), entry.getValue().isEmpty() ? "-" : String.join(", ", entry.getValue()));
        return Panel.page(ctx.text("page-groups")).subtitle(ctx.text(on ? "page-groups-on" : "page-groups-off", "separate", separate))
                .icon(Ui.item(Material.ENDER_CHEST)).size(Panel.Size.MEDIUM).child(kv)
                .child(Ui.text(ctx.text("page-groups-hint")).id("groups-hint"))
                .footer(button(ctx, h, "back", "admin", "", "page-back-list", Material.ARROW).ghost());
    }
}
