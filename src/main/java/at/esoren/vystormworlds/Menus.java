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

    Menus(WorldsPlugin plugin) {
        this.plugin = plugin;
        this.actions = new Actions(plugin);
        this.favorites = new WorldFavorites(plugin);
    }

    Actions actions() { return actions; }

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

    /** Sets a property on the current entry of {@code name} and reopens the world menu. */
    private void set(Player player, String name, String key, java.util.function.Function<WorldSettings, String> value) {
        WorldSettings w = current(player, name);
        if (w == null) return;
        actions.setProperty(player, w, key, value.apply(w));
        world(player, name);
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
            ItemStack item = item(player, world == null ? Material.GRAY_DYE : icon(w), "admin-world-name", loreKey, "world", w.name, "title", title(w),
                    "type", w.kind.id(), "group", w.group, "players", world == null ? 0 : world.getPlayers().size(),
                    "chunks", world == null ? 0 : world.getLoadedChunks().length, "entities", world == null ? 0 : world.getEntities().size(),
                    "error", String.valueOf(error), "hidden", lang().plain(player, w.hidden ? "yes" : "no"));
            menu.put(slot++, item, (v, c) -> world(v, w.name));
        }
        if (current > 0) menu.put(45, item(player, Material.ARROW, "page-back", null), (v, c) -> admin(v, current - 1));
        if (current < pages - 1) menu.put(53, item(player, Material.ARROW, "page-next", null), (v, c) -> admin(v, current + 1));
        menu.put(47, item(player, Material.EMERALD, "admin-create", "admin-create-lore"), (v, c) -> { v.closeInventory(); createDialog(v); });
        menu.put(48, item(player, Material.CHEST, "admin-import", "admin-import-lore"), (v, c) -> { v.closeInventory(); actions.importList(v); });
        menu.put(49, item(player, Material.BOOK, "admin-list", "admin-list-lore"), (v, c) -> { v.closeInventory(); actions.list(v); });
        menu.put(50, item(player, Material.FILLED_MAP, "admin-selector", null), (v, c) -> selector(v, 0));
        menu.put(51, item(player, Material.COMPARATOR, "admin-config", "admin-config-lore"), (v, c) -> { v.closeInventory(); v.performCommand("vadmin config " + plugin.getName()); });
        player.openInventory(menu.inventory);
    }

    // ------------------------------------------------------------------ world settings

    private ItemStack toggle(Player player, boolean on, String nameKey) {
        return item(player, on ? Material.LIME_DYE : Material.GRAY_DYE, nameKey, on ? "toggle-on" : "toggle-off");
    }

    private ItemStack value(Player player, Material material, String nameKey, String value) {
        return item(player, material, nameKey, "value-lore", "value", value);
    }

    void world(Player player, String name) {
        if (off(player, Features.ADMIN_MENU)) return;
        if (panels != null && panels.openWorld(player, name)) return;
        worldChest(player, name);
    }

    void worldChest(Player player, String name) {
        WorldSettings w = plugin.registry().get(name);
        if (w == null || !player.hasPermission("vystormworlds.admin")) { admin(player, 0); return; }
        World world = WorldManager.loaded(w.name);
        Menu menu = open(player, 54, "world-title", "world", w.name);
        menu.put(4, item(player, icon(w), "admin-world-name", world != null ? "admin-world-loaded" : "admin-world-unloaded", "world", w.name, "title", title(w),
                "type", w.kind.id(), "group", w.group, "players", world == null ? 0 : world.getPlayers().size(), "chunks", world == null ? 0 : world.getLoadedChunks().length,
                "entities", world == null ? 0 : world.getEntities().size(), "error", "-", "hidden", lang().plain(player, w.hidden ? "yes" : "no")), null);
        String[][] toggles = {{"pvp", "w-pvp"}, {"monsters", "w-monsters"}, {"animals", "w-animals"}, {"hunger", "w-hunger"}, {"fly", "w-fly"},
                {"keep-spawn-loaded", "w-keep-spawn"}, {"autoload", "w-autoload"}};
        int slot = 10;
        for (String[] t : toggles) {
            boolean on = Boolean.parseBoolean(Properties.get(w, t[0]));
            menu.put(slot++, toggle(player, on, t[1]), (v, c) -> set(v, name, t[0], now -> Boolean.toString(!Boolean.parseBoolean(Properties.get(now, t[0])))));
        }
        cycle(menu, player, w, 19, Material.IRON_SWORD, "w-game-mode", "game-mode", List.of("none", "survival", "creative", "adventure", "spectator"));
        cycle(menu, player, w, 20, Material.ZOMBIE_HEAD, "w-difficulty", "difficulty", List.of("none", "peaceful", "easy", "normal", "hard"));
        cycle(menu, player, w, 21, Material.CLOCK, "w-time-lock", "time-lock", WorldSettings.TIME_LOCKS);
        cycle(menu, player, w, 22, Material.WATER_BUCKET, "w-weather-lock", "weather-lock", WorldSettings.WEATHER_LOCKS);
        cycle(menu, player, w, 23, Material.GOLD_NUGGET, "w-price-mode", "price-mode", WorldSettings.PRICE_MODES);
        menu.put(24, toggle(player, w.hidden, "w-hidden"), (v, c) -> set(v, name, "hidden", now -> Boolean.toString(!now.hidden)));
        menu.put(25, item(player, w.permission ? Material.IRON_DOOR : Material.OAK_DOOR, "w-permission", w.permission ? "w-permission-on" : "toggle-off",
                "node", Access.permission(w.name)), (v, c) -> set(v, name, "permission", now -> Boolean.toString(!now.permission)));
        prompt(menu, player, w, 28, Material.NAME_TAG, "w-display-name", "display-name");
        prompt(menu, player, w, 29, Material.WRITABLE_BOOK, "w-description", "description");
        menu.put(30, item(player, icon(w), "w-icon", "w-icon-lore", "value", w.icon.isBlank() ? "-" : w.icon), (v, c) -> {
            Material hand = v.getInventory().getItemInMainHand().getType();
            set(v, name, "icon", now -> hand.isAir() ? "none" : hand.name());
        });
        prompt(menu, player, w, 31, Material.GOLD_INGOT, "w-price", "price");
        prompt(menu, player, w, 32, Material.PLAYER_HEAD, "w-player-limit", "player-limit");
        prompt(menu, player, w, 33, Material.ENDER_CHEST, "w-group", "group");
        prompt(menu, player, w, 34, Material.RED_BED, "w-respawn-world", "respawn-world");
        if (world != null) {
            menu.put(37, item(player, Material.LODESTONE, "w-set-spawn", "w-set-spawn-lore"), (v, c) -> {
                if (!v.getWorld().equals(WorldManager.loaded(name))) { lang().send(v, "stand-in-world", "world", name); return; }
                v.performCommand("worlds setspawn");
                world(v, name);
            });
            menu.put(38, item(player, Material.ENDER_PEARL, "w-teleport", null), (v, c) -> {
                WorldSettings now = current(v, name);
                World target = WorldManager.loaded(name);
                if (now == null) return;
                v.closeInventory();
                if (target == null) { lang().send(v, "not-loaded", "world", name); return; }
                actions.go(v, now, target, false);
            });
        }
        if (world == null) menu.put(39, item(player, Material.LIME_CONCRETE, "w-load", null), (v, c) -> { v.performCommand("worlds load " + name); world(v, name); });
        else if (!WorldManager.serverWorld(world)) menu.put(39, item(player, Material.RED_CONCRETE, "w-unload", null), (v, c) -> { v.performCommand("worlds unload " + name); world(v, name); });
        prompt(menu, player, w, 40, Material.OBSIDIAN, "w-nether", "nether");
        prompt(menu, player, w, 41, Material.END_PORTAL_FRAME, "w-end", "end");
        menu.put(42, toggle(player, w.rememberPosition, "w-remember"), (v, c) -> set(v, name, "remember-position", now -> Boolean.toString(!now.rememberPosition)));
        menu.put(43, item(player, Material.COMPARATOR, "w-dialog", "w-dialog-lore"), (v, c) -> { v.closeInventory(); WorldSection.open(plugin, v, name); });
        prompt(menu, player, w, 50, Material.IRON_BARS, "w-border", "border");
        prompt(menu, player, w, 51, Material.COMPASS, "w-border-center", "border-center");
        prompt(menu, player, w, 52, Material.CLOCK, "w-border-event", "border-event");
        if (world != null && w.border > 0)
            menu.put(53, item(player, Material.TARGET, "w-border-start", "w-border-start-lore"), (v, c) -> {
                WorldSettings now = current(v, name);
                if (!Features.on(plugin.getConfig(), Features.BORDER_EVENTS) || !Features.on(plugin.getConfig(), Features.BORDER)) lang().send(v, Features.OFF);
                else if (now == null || !plugin.borders().start(now)) lang().send(v, "border-no-size", "world", name);
                world(v, name);
            });
        menu.put(45, item(player, Material.ARROW, "page-back", null), (v, c) -> admin(v, 0));
        if (!w.serverWorld) {
            menu.put(47, item(player, Material.TNT, "w-regenerate", "w-regenerate-lore"), (v, c) -> { v.closeInventory(); v.performCommand("worlds regen " + name); });
            menu.put(48, item(player, Material.LAVA_BUCKET, "w-delete", "w-delete-lore"), (v, c) -> { v.closeInventory(); v.performCommand("worlds delete " + name); });
        }
        menu.put(49, item(player, Material.STRUCTURE_VOID, "w-clone", "w-clone-lore"), (v, c) -> {
            v.closeInventory();
            plugin.core().dialogs().prompt(plugin, v, lang().plain(v, "w-clone"), lang().plain(v, "clone-question", "world", name), name + "_copy",
                    target -> v.performCommand("worlds clone " + name + " " + target.strip()), () -> {}, p -> p.hasPermission("vystormworlds.admin"));
        });
        player.openInventory(menu.inventory);
    }

    private void cycle(Menu menu, Player player, WorldSettings w, int slot, Material material, String nameKey, String key, List<String> options) {
        String current = Properties.get(w, key);
        String name = w.name;
        menu.put(slot, item(player, material, nameKey, "cycle-lore", "value", current), (v, c) -> set(v, name, key, now -> {
            int index = options.indexOf(Properties.get(now, key));
            return options.get(Math.floorMod(index + (c.isRightClick() ? -1 : 1), options.size()));
        }));
    }

    private void prompt(Menu menu, Player player, WorldSettings w, int slot, Material material, String nameKey, String key) {
        String current = Properties.get(w, key);
        String name = w.name;
        menu.put(slot, value(player, material, nameKey, current.isEmpty() ? "-" : current), (v, c) -> {
            v.closeInventory();
            plugin.core().dialogs().prompt(plugin, v, lang().plain(v, nameKey), lang().plain(v, "prompt-" + key, "world", w.name),
                    current.equals("none") ? "" : current, typed -> set(v, name, key, now -> typed),
                    () -> world(v, name), p -> p.hasPermission("vystormworlds.admin"));
        });
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
            String name = text(response.getText("name"));
            List<String> tokens = new ArrayList<>();
            tokens.add(text(response.getText("type")));
            String generator = text(response.getText("generator"));
            String id = text(response.getText("generator-id"));
            if (!generator.isEmpty() && !generator.equals("none")) tokens.add("generator:" + generator + (id.isEmpty() || generator.startsWith(GeneratorSpec.PRESET_PREFIX) ? "" : ":" + id));
            else if (!id.isEmpty()) tokens.add("generator:" + id);
            String seed = text(response.getText("seed"));
            if (!seed.isEmpty()) tokens.add("seed:" + seed);
            if (Boolean.FALSE.equals(response.getBoolean("structures"))) tokens.add("structures:false");
            if (Boolean.TRUE.equals(response.getBoolean("nether"))) tokens.add("with-nether");
            if (Boolean.TRUE.equals(response.getBoolean("end"))) tokens.add("with-end");
            String group = text(response.getText("group"));
            if (!group.isEmpty()) tokens.add("group:" + group);
            String minY = text(response.getText("min-y")), height = text(response.getText("height"));
            if (!minY.isEmpty()) tokens.add("min-y:" + minY);
            if (!height.isEmpty()) tokens.add("height:" + height);
            if (name.isEmpty()) { lang().send(p, "bad-name", "world", name); return; }
            CreateRequest.Parsed parsed = CreateRequest.parse(name.toLowerCase(Locale.ROOT), tokens, plugin.defaultGroup());
            if (parsed.error() != null) { lang().send(p, parsed.error(), "token", parsed.token()); return; }
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
