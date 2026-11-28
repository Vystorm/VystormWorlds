package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.SingleOptionDialogInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Chest menus (world selector, admin overview, world settings) and the create-world dialog. */
final class Menus implements Listener {
    private final WorldsPlugin plugin;
    private final Actions actions;
    private final WorldFavorites favorites;
    private WorldPanelMenus panels;

    private final WorldMenu worldMenu;
    private final PageChests chests;
    private Picks picks = Picks.NONE;

    /**
     * Which Core 0.71.0 parts this server has (1.21.0): the player picker, the world pick and chest fallbacks with the
     * panel's context. Each is used only behind its flag, through {@link WorldsPicks}.
     */
    record Picks(boolean players, boolean world, boolean context) {
        static final Picks NONE = new Picks(false, false, false);

        static Picks detect() {
            return new Picks(has("player-picker"), has("world-pick"), has("panel-fallback-context"));
        }

        private static boolean has(String capability) {
            try { return CoreApi.has(capability); } catch (RuntimeException | LinkageError old) { return false; }
        }
    }

    Menus(WorldsPlugin plugin) {
        this.plugin = plugin;
        this.actions = new Actions(plugin);
        this.favorites = new WorldFavorites(plugin);
        this.worldMenu = new WorldMenu(plugin);
        this.chests = new PageChests(plugin, this, worldMenu);
    }

    Actions actions() { return actions; }

    WorldMenu worldMenu() { return worldMenu; }

    PageChests chests() { return chests; }

    /** The Core 0.71.0 parts, asked at start; the pickers' own switch ({@code pickers.enabled}) is asked on use. */
    Picks picks() {
        if (picks == Picks.NONE) picks = Picks.detect();
        boolean on;
        try { on = (picks.players() || picks.world()) && WorldsPicks.available(plugin); } catch (RuntimeException | LinkageError none) { on = false; }
        return on ? picks : new Picks(false, false, picks.context());
    }

    boolean registerPanels() {
        try {
            WorldPanelMenus created = new WorldPanelMenus(plugin, this, favorites);
            created.register();
            panels = created;
            return true;
        } catch (RuntimeException unavailable) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "World panels unavailable", unavailable);
            return false;
        }
    }

    @EventHandler
    public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (panels != null) panels.forget(event.getPlayer().getUniqueId());
    }

    /** Inventory holder with one action per slot. */
    static final class Menu implements InventoryHolder {
        final Map<Integer, BiConsumer<Player, ClickType>> clicks = new HashMap<>();
        Inventory inventory;

        @Override public Inventory getInventory() { return inventory; }

        void put(int slot, ItemStack item, BiConsumer<Player, ClickType> action) {
            inventory.setItem(slot, item);
            if (action != null) clicks.put(slot, action);
        }
    }

    static Component title(WorldSettings w) {
        try { return MiniMessage.miniMessage().deserialize(w.title()); } catch (RuntimeException bad) { return Component.text(w.title()); }
    }

    static String plain(WorldSettings w) { return PlainTextComponentSerializer.plainText().serialize(title(w)); }

    private Lang lang() { return plugin.lang(); }

    /**
     * The world's entry as it is now. Menus and dialogs stay open while {@code /worlds reload} replaces every entry, so
     * each action looks the world up again by name instead of changing an old copy nobody saves. Null (menu closed,
     * note sent) when the world is gone.
     */
    WorldSettings current(Player player, String name) {
        WorldSettings w = plugin.registry().get(name);
        if (w == null) {
            player.closeInventory();
            lang().send(player, "world-changed", "world", name);
        }
        return w;
    }


    private Menu open(Player player, int size, String titleKey, Object... placeholders) {
        Menu menu = new Menu();
        menu.inventory = plugin.core().compat().platform().createInventory(menu, size, lang().component(player, titleKey, placeholders));
        return menu;
    }

    private static Component flat(Component c) { return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE); }

    ItemStack item(Player player, Material material, String nameKey, String loreKey, Object... placeholders) {
        ItemStack item = new ItemStack(material);
        plugin.core().compat().platform().editMeta(item, meta -> {
            plugin.core().compat().platform().displayName(meta, flat(lang().component(player, nameKey, placeholders)));
            if (loreKey != null) plugin.core().compat().platform().lore(meta, lang().lines(player, loreKey, placeholders).stream().map(Menus::flat).toList());
        });
        return item;
    }

    static Material icon(WorldSettings w) {
        Material material = w.icon.isBlank() ? null : Material.matchMaterial(w.icon);
        if (material != null && material.isItem() && !material.isAir()) return material;
        return switch (w.kind) {
            case NETHER -> Material.NETHERRACK;
            case END -> Material.END_STONE;
            case FLAT -> Material.GRASS_BLOCK;
            case VOID -> Material.GLASS;
            default -> Material.GRASS_BLOCK;
        };
    }

    // ------------------------------------------------------------------ selector

    /** Answers with the shared off message when the feature is switched off (1.17.0); true when it is off. */
    boolean off(org.bukkit.command.CommandSender sender, String feature) {
        if (Features.on(plugin.getConfig(), feature)) return false;
        lang().send(sender, Features.OFF);
        return true;
    }

    /** Worlds a player sees in the selector: loaded, not hidden; locked ones only with selector.show-locked. */
    List<WorldSettings> selectable(Player player) {
        List<WorldSettings> list = new ArrayList<>();
        boolean showLocked = plugin.getConfig().getBoolean("selector.show-locked", true);
        for (WorldSettings w : plugin.registry().all()) {
            World world = WorldManager.loaded(w.name);
            if (w.hidden || world == null || plugin.rules().coreWorld(world)) continue;
            Access.Result result = plugin.rules().access(player, w, world);
            if (result == Access.Result.NO_PERMISSION && !showLocked) continue;
            list.add(w);
        }
        list.sort(Comparator.comparing((WorldSettings w) -> !w.serverWorld).thenComparing(w -> w.name));
        return list;
    }

    void selector(Player player, int page) { selector(player, page, false); }

    /** Command shortcut with the same visibility rules as the selector. */
    void favorite(Player player, String name) {
        if (!player.hasPermission("vystormworlds.use")) { lang().send(player, "denied"); return; }
        if (off(player, Features.FAVORITES)) return;
        WorldSettings selected = selectable(player).stream().filter(w -> w.name.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (selected == null) { lang().send(player, "unknown-world", "world", name); return; }
        boolean added = favorites.toggle(player, selected.name);
        lang().send(player, added ? "favorite-added" : "favorite-removed", "world", title(selected));
    }

    void selector(Player player, int page, boolean onlyFavorites) {
        if (off(player, Features.SELECTOR) || onlyFavorites && off(player, Features.FAVORITES)) return;
        if (panels != null && panels.openSelector(player, page, onlyFavorites)) return;
        selectorChest(player, page, onlyFavorites);
    }

    void selectorChest(Player player, int page, boolean onlyFavorites) {
        if (!player.hasPermission("vystormworlds.use")) { lang().send(player, "denied"); return; }
        var saved = favorites.read(player);
        List<WorldSettings> worlds = WorldFavorites.arrange(selectable(player), saved, onlyFavorites);
        int pages = Math.max(1, (worlds.size() + 44) / 45);
        int current = Math.clamp(page, 0, pages - 1);
        int count = Math.min(45, worlds.size() - current * 45);
        int size = Math.clamp((count + 8) / 9 * 9 + 9, 18, 54);
        Menu menu = open(player, size, "selector-title", "count", worlds.size());
        int slot = 0;
        for (WorldSettings w : worlds.subList(current * 45, current * 45 + count)) {
            World world = WorldManager.loaded(w.name);
            Access.Result result = plugin.rules().access(player, w, world);
            boolean here = player.getWorld().equals(world);
            List<Component> lore = new ArrayList<>();
            if (!w.description.isBlank()) lore.add(flat(MiniMessage.miniMessage().deserialize("<gray>" + w.description)));
            lore.add(flat(lang().component(player, "selector-players", "players", world.getPlayers().size(), "limit", w.playerLimit > 0 ? "/" + w.playerLimit : "")));
            if (!w.gameMode.isEmpty()) lore.add(flat(lang().component(player, "selector-mode", "mode", lang().plain(player, "mode-" + w.gameMode))));
            if (w.price > 0 && Money.available())
                lore.add(flat(lang().component(player, Access.charges(w, plugin.rules().viewer(player, w)) ? "selector-price-" + w.priceMode : "selector-unlocked", "price", Money.format(w.price))));
            switch (result) {
                case NO_PERMISSION -> lore.add(flat(lang().component(player, "selector-locked")));
                case FULL -> lore.add(flat(lang().component(player, "selector-full")));
                case NO_MONEY -> lore.add(flat(lang().component(player, "selector-too-poor")));
                default -> lore.addAll(lang().lines(player, here ? "selector-here" : "selector-click").stream().map(Menus::flat).toList());
            }
            lore.add(flat(lang().component(player, WorldFavorites.contains(saved, w.name) ? "favorite-remove" : "favorite-add")));
            if (player.hasPermission("vystormworlds.admin")) lore.add(flat(lang().component(player, "selector-admin")));
            ItemStack item = new ItemStack(result == Access.Result.NO_PERMISSION ? Material.IRON_BARS : icon(w));
            plugin.core().compat().platform().editMeta(item, meta -> {
                plugin.core().compat().platform().displayName(meta, flat(title(w)));
                plugin.core().compat().platform().lore(meta, lore);
                if (here) meta.setEnchantmentGlintOverride(true);
            });
            String name = w.name;
            menu.put(slot++, item, (viewer, click) -> selectorClick(viewer, name, click, current, onlyFavorites));
        }
        if (worlds.isEmpty()) menu.put(4, item(player, Material.PAPER, onlyFavorites ? "favorites-empty" : "selector-none", null), null);
        int base = size - 9;
        if (current > 0) menu.put(base, item(player, Material.ARROW, "page-back", null), (v, c) -> selector(v, current - 1, onlyFavorites));
        if (current < pages - 1) menu.put(base + 8, item(player, Material.ARROW, "page-next", null), (v, c) -> selector(v, current + 1, onlyFavorites));
        menu.put(base + 3, item(player, Material.NETHER_STAR, onlyFavorites ? "favorites-show-all" : "favorites-show-only", "favorites-filter-lore"),
                (v, c) -> selector(v, 0, !onlyFavorites));
        menu.put(base + 4, item(player, Material.RED_BED, "selector-spawn", "selector-spawn-lore"), (v, c) -> {
            v.closeInventory();
            World world = v.getWorld();
            plugin.teleports().send(v, Teleports.spawn(world, plugin.settings(world)), ok -> { });
        });
        if (player.hasPermission("vystormworlds.admin")) menu.put(base + 5, item(player, Material.COMPASS, "selector-to-admin", null), (v, c) -> admin(v, 0));
        String arrival = arrivalChoice(player);
        if (!arrival.isEmpty()) menu.put(base + 2, item(player, arrival.equals("spawn") ? Material.RED_BED : Material.RECOVERY_COMPASS, "arrival-item",
                "arrival-item-lore", "choice", lang().plain(player, "arrival-option-" + arrival)), (v, c) -> {
            command(v, "arrival", arrivalChoice(v).equals("spawn") ? "last" : "spawn");
            selector(v, current, onlyFavorites);
        });
        String trip = trip(player);
        if (trip.equals("visit")) menu.put(base + 6, item(player, Material.SPYGLASS, "visit-button", "visit-item-lore"), (v, c) -> visits(v));
        else if (trip.equals("leave")) menu.put(base + 6, item(player, Material.OAK_DOOR, "leave-button", "leave-item-lore"), (v, c) -> {
            v.closeInventory();
            command(v, "leave");
        });
        player.openInventory(menu.inventory);
    }

    // ------------------------------------------------------------------ arrival choice and visit worlds (1.20.0)

    /** Runs {@code /worlds <args>} for the player: menu buttons share every check and message with the command. */
    void command(Player player, String... args) {
        WorldsCommand commands = plugin.commands();
        if (commands != null) commands.run(player, "worlds", args);
    }

    /** The arrival choice the selector shows, {@code "spawn"} or {@code "last"}; empty while the switch is off. */
    String arrivalChoice(Player player) {
        if (!Features.on(plugin.getConfig(), "teleport.return-to-last-position")) return "";
        return plugin.teleports().prefersSpawn(player) ? "spawn" : "last";
    }

    /** {@code "leave"} while the player is visiting, {@code "visit"} when open visit worlds exist, else empty. */
    String trip(Player player) {
        TemplateVisits templates = plugin.templates();
        if (templates == null) return "";
        if (templates.visiting(player)) return "leave";
        return Features.on(plugin.getConfig(), Features.TEMPLATES) && !templates.open().isEmpty() ? "visit" : "";
    }

    void visits(Player player) {
        if (panels != null && panels.openVisits(player)) return;
        visitChest(player);
    }

    void visitChest(Player player) {
        if (!player.hasPermission("vystormworlds.use")) { lang().send(player, "denied"); return; }
        TemplateVisits templates = plugin.templates();
        List<WorldTemplate> open = templates == null ? List.of() : templates.open();
        boolean visiting = templates != null && templates.visiting(player);
        Menu menu = open(player, 18, "visit-title");
        int slot = 0;
        if (!visiting) for (WorldTemplate t : open) {
            String id = t.id();
            menu.put(slot++, item(player, t.terrain().equals("island") ? Material.SAND : Material.GRASS_BLOCK, "visit-item", "visit-item-template-lore",
                    "template", id, "terrain", lang().plain(player, "template-terrain-" + t.terrain()), "seconds", t.seconds(),
                    "inventory", lang().plain(player, "template-inventory-" + t.inventory())), (v, c) -> {
                v.closeInventory();
                command(v, "visit", id);
            });
        }
        if (visiting) menu.put(4, item(player, Material.PAPER, "visit-busy", null), null);
        else if (open.isEmpty()) menu.put(4, item(player, Material.PAPER, "visit-none", null), null);
        menu.put(9, item(player, Material.ARROW, "page-back", null), (v, c) -> selector(v, 0));
        if (visiting) menu.put(13, item(player, Material.OAK_DOOR, "leave-button", "leave-item-lore"), (v, c) -> {
            v.closeInventory();
            command(v, "leave");
        });
        player.openInventory(menu.inventory);
    }

    void selectorClick(Player player, String name, ClickType click, int page, boolean onlyFavorites) {
        if (!player.hasPermission("vystormworlds.use")) { lang().send(player, "denied"); return; }
        WorldSettings now = current(player, name);
        if (now == null) return;
        if (click == ClickType.SHIFT_RIGHT) {
            if (off(player, Features.FAVORITES)) return;
            boolean added = favorites.toggle(player, now.name);
            lang().send(player, added ? "favorite-added" : "favorite-removed", "world", title(now));
            selector(player, page, onlyFavorites);
            return;
        }
        if (click == ClickType.SHIFT_LEFT && player.hasPermission("vystormworlds.admin")) { world(player, name); return; }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        player.closeInventory();
        actions.travel(player, now, click == ClickType.LEFT);
    }

    // ------------------------------------------------------------------ admin overview

    void admin(Player player, int page) {
        if (off(player, Features.ADMIN_MENU)) return;
        if (panels != null && panels.openAdmin(player, page)) return;
        adminChest(player, page);
    }

    void adminChest(Player player, int page) {
        if (!player.hasPermission("vystormworlds.admin")) { lang().send(player, "denied"); return; }
        List<WorldSettings> worlds = new ArrayList<>(plugin.registry().all());
        worlds.sort(Comparator.comparing((WorldSettings w) -> WorldManager.loaded(w.name) == null).thenComparing(w -> w.name));
        int pages = Math.max(1, (worlds.size() + 44) / 45);
        int current = Math.clamp(page, 0, pages - 1);
        Menu menu = open(player, 54, "admin-title", "count", worlds.size(), "tps", Actions.tps(plugin.core().compat().platform()));
        int slot = 0;
        for (WorldSettings w : worlds.subList(current * 45, Math.min(worlds.size(), current * 45 + 45))) {
            World world = WorldManager.loaded(w.name);
            String error = plugin.manager().loadError(w.name);
            String loreKey = world != null ? "admin-world-loaded" : error != null ? "admin-world-error" : "admin-world-unloaded";
            Object[] args = {"world", w.name, "title", title(w), "type", w.kind.id(), "group", w.group, "players", world == null ? 0 : world.getPlayers().size(),
                    "chunks", world == null ? 0 : world.getLoadedChunks().length, "entities", world == null ? 0 : world.getEntities().size(),
                    "error", String.valueOf(error), "hidden", lang().plain(player, w.hidden ? "yes" : "no")};
            ItemStack item = item(player, world == null ? Material.GRAY_DYE : icon(w), "admin-world-name", loreKey, args);
            // 1.21.0: the settings Worlds manages for this world, in one line
            String badges = WorldBadges.summary(w, key -> lang().plain(player, key));
            if (!badges.isEmpty()) {
                List<Component> lore = new ArrayList<>(lang().lines(player, loreKey, args));
                lore.add(Component.text(badges, net.kyori.adventure.text.format.NamedTextColor.AQUA));
                plugin.core().compat().platform().editMeta(item, meta -> plugin.core().compat().platform().lore(meta, lore.stream().map(Menus::flat).toList()));
            }
            menu.put(slot++, item, (v, c) -> world(v, w.name));
        }
        if (current > 0) menu.put(45, item(player, Material.ARROW, "page-back", null), (v, c) -> admin(v, current - 1));
        if (current < pages - 1) menu.put(53, item(player, Material.ARROW, "page-next", null), (v, c) -> admin(v, current + 1));
        // 1.21.0: every admin task has a button (the list stays as /worlds list)
        if (worldMenu.may(player, "new-world")) menu.put(46, item(player, Material.EMERALD, "admin-create", "admin-create-lore"), (v, c) -> { v.closeInventory(); newWorld(v); });
        if (worldMenu.may(player, "import")) menu.put(47, item(player, Material.CHEST, "admin-import", "admin-import-lore"), (v, c) -> imports(v));
        if (worldMenu.may(player, "templates")) menu.put(48, item(player, Material.SPYGLASS, "admin-templates", "admin-templates-lore"), (v, c) -> templates(v));
        menu.put(49, item(player, Material.ENDER_CHEST, "admin-groups", "admin-groups-lore"), (v, c) -> groups(v));
        menu.put(50, item(player, Material.REPEATER, "admin-reload", "admin-reload-lore"), (v, c) -> {
            v.closeInventory();
            worldMenu.reload(v);
        });
        menu.put(51, item(player, Material.FILLED_MAP, "admin-selector", null), (v, c) -> selector(v, 0));
        menu.put(52, item(player, Material.COMPARATOR, "admin-config", "admin-config-lore"), (v, c) -> { v.closeInventory(); v.performCommand("vadmin config " + plugin.getName()); });
        player.openInventory(menu.inventory);
    }

    // ------------------------------------------------------------------ world page and admin pages (1.21.0)

    void world(Player player, String name) {
        if (off(player, Features.ADMIN_MENU)) return;
        if (panels != null && panels.openWorld(player, name)) return;
        worldChest(player, name);
    }

    /** The world page as a chest, opened on its first tab (a fresh open without a panel context). */
    void worldChest(Player player, String name) {
        if (plugin.registry().get(name) == null || !worldMenu.may(player, "world-page")) { admin(player, 0); return; }
        chests.world(player, name, null, null);
    }

    /** "New world": the form page on screens, the create dialog otherwise. */
    void newWorld(Player player) {
        if (off(player, Features.ADMIN_MENU) || off(player, Features.MANAGEMENT)) return;
        if (panels != null && panels.openPage(player, WorldPanelMenus.CREATE)) return;
        createDialog(player);
    }

    void imports(Player player) {
        if (off(player, Features.ADMIN_MENU) || off(player, Features.MANAGEMENT)) return;
        if (panels != null && panels.openPage(player, WorldPanelMenus.IMPORT)) return;
        chests.imports(player);
    }

    void templates(Player player) {
        if (off(player, Features.ADMIN_MENU)) return;
        if (panels != null && panels.openPage(player, WorldPanelMenus.TEMPLATES)) return;
        chests.templates(player);
    }

    void groups(Player player) {
        if (off(player, Features.ADMIN_MENU)) return;
        if (panels != null && panels.openPage(player, WorldPanelMenus.GROUPS)) return;
        chests.groups(player);
    }

    // ------------------------------------------------------------------ create dialog

    void createDialog(Player player) {
        if (!player.hasPermission("vystormworlds.admin")) { lang().send(player, "denied"); return; }
        if (off(player, Features.MANAGEMENT)) return;
        List<SingleOptionDialogInput.OptionEntry> kinds = new ArrayList<>();
        for (WorldKind kind : WorldKind.values())
            kinds.add(SingleOptionDialogInput.OptionEntry.create(kind.id(), lang().component(player, "kind-" + kind.id()), kind == WorldKind.NORMAL));
        List<SingleOptionDialogInput.OptionEntry> generators = new ArrayList<>();
        generators.add(SingleOptionDialogInput.OptionEntry.create("none", lang().component(player, "generator-none"), true));
        for (Generators.Choice choice : plugin.manager().generators().choices())
            generators.add(SingleOptionDialogInput.OptionEntry.create(choice.value(), lang().component(player, choice.preset() ? "generator-preset" : "generator-plugin", "name", choice.label()), false));
        List<DialogInput> inputs = List.of(
                DialogInput.text("name", lang().component(player, "create-name")).maxLength(32).build(),
                DialogInput.singleOption("type", lang().component(player, "create-type"), kinds).build(),
                DialogInput.singleOption("generator", lang().component(player, "create-generator"), generators).build(),
                DialogInput.text("generator-id", lang().component(player, "create-generator-id")).maxLength(128).build(),
                DialogInput.text("seed", lang().component(player, "create-seed")).maxLength(64).build(),
                DialogInput.bool("structures", lang().component(player, "create-structures")).initial(true).build(),
                DialogInput.bool("nether", lang().component(player, "create-nether")).initial(false).build(),
                DialogInput.bool("end", lang().component(player, "create-end")).initial(false).build(),
                DialogInput.text("group", lang().component(player, "create-group")).initial(plugin.defaultGroup()).maxLength(32).build(),
                DialogInput.text("min-y", lang().component(player, "create-min-y")).maxLength(6).build(),
                DialogInput.text("height", lang().component(player, "create-height")).maxLength(6).build());
        DialogButton create = new DialogButton(lang().component(player, "create-button"), (p, response) -> {
            // the same option words as /worlds create and the "New world" page (NewWorldForm, 1.21.0)
            NewWorldForm form = new NewWorldForm(text(response.getText("name")), text(response.getText("type")), text(response.getText("generator")),
                    text(response.getText("generator-id")), text(response.getText("seed")), !Boolean.FALSE.equals(response.getBoolean("structures")),
                    Boolean.TRUE.equals(response.getBoolean("nether")), Boolean.TRUE.equals(response.getBoolean("end")), text(response.getText("group")),
                    text(response.getText("min-y")), text(response.getText("height")));
            String name = form.name();
            if (name.isEmpty()) { lang().send(p, "bad-name", "world", name); return; }
            CreateRequest.Parsed parsed = CreateRequest.parse(name.toLowerCase(Locale.ROOT), form.tokens(), plugin.defaultGroup());
            if (parsed.error() != null) { lang().send(p, parsed.error(), "token", parsed.token()); return; }
            if (!p.hasPermission(WorldsCommand.ADMIN_NODE) || off(p, Features.MANAGEMENT)) return;
            plugin.audit(p, parsed.request().name(), form.command());
            actions.create(p, parsed.request());
        });
        plugin.core().dialogs().open(plugin, player, new DialogRequest(lang().component(player, "create-title"),
                List.of(Vystorm.vystorm_core.compat.dialog.DialogBody.plainMessage(lang().component(player, "create-help"), 300)),
                inputs, List.of(create), p -> p.hasPermission("vystormworlds.admin"), () -> {}));
    }

    private static String text(String s) { return s == null ? "" : s.strip(); }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(plugin.core().compat().inventory().top(event.getView()).getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != plugin.core().compat().inventory().top(event.getView()) || !(event.getWhoClicked() instanceof Player player)) return;
        BiConsumer<Player, ClickType> action = menu.clicks.get(event.getSlot());
        ClickType type = event.getClick();
        if (action != null) plugin.tasks().now(() -> { if (player.isOnline()) action.accept(player, type); });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (plugin.core().compat().inventory().top(event.getView()).getHolder() instanceof Menu) event.setCancelled(true);
    }
}
