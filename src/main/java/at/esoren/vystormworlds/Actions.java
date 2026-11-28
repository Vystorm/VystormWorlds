package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.chat.ChatActions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Operations shared by the command, the menus and the dialogs; every one checks permissions itself. */
final class Actions {
    private final WorldsPlugin plugin;

    Actions(WorldsPlugin plugin) { this.plugin = plugin; }

    private Vystorm.vystorm_core.lang.Lang lang() { return plugin.lang(); }

    // ------------------------------------------------------------------ travel

    /** A player travels to a world through the selector or {@code /worlds tp}: access check, price confirmation, safe teleport. */
    void travel(Player player, WorldSettings w, boolean preferLast) {
        World world = WorldManager.loaded(w.name);
        if (world != null && world.equals(player.getWorld()) && !preferLast) { go(player, w, world, false); return; }
        Access.Result result = plugin.rules().access(player, w, world);
        switch (result) {
            case OK -> go(player, w, world, preferLast);
            case PAY -> plugin.core().dialogs().confirm(plugin, player, lang().plain(player, "pay-title"),
                    lang().plain(player, "pay-question", "world", Menus.plain(w), "price", Money.format(w.price)), () -> confirmPay(player, w, preferLast), () -> {}, Player::isOnline);
            default -> plugin.rules().explain(player, w, result);
        }
    }

    private void confirmPay(Player player, WorldSettings old, boolean preferLast) {
        WorldSettings w = plugin.registry().get(old.name); // the dialog may outlive a /worlds reload
        if (w == null) { lang().send(player, "world-changed", "world", old.name); return; }
        World world = WorldManager.loaded(w.name);
        Access.Result result = plugin.rules().access(player, w, world);
        if (result == Access.Result.OK || result == Access.Result.PAY) go(player, w, world, preferLast);
        else plugin.rules().explain(player, w, result);
    }

    void go(Player player, WorldSettings w, World world, boolean preferLast) {
        Location target = plugin.teleports().arrival(player, world, w, preferLast);
        plugin.teleports().send(player, target, ok -> { if (ok) plugin.core().compat().platform().sendActionBar(player, lang().component(player, "teleported", "world", Menus.title(w))); });
    }

    // ------------------------------------------------------------------ changes

    /** After a property change: save, apply to the loaded world, update links and players. */
    void changed(WorldSettings w, boolean locksChanged) {
        plugin.saveRegistry();
        World world = WorldManager.loaded(w.name);
        if (world != null) {
            plugin.manager().apply(world, w);
            if (locksChanged) WorldManager.applyLocks(world, w, true);
        }
        plugin.links().sync();
        plugin.rules().recheckAll();
    }

    /** Sets a property; messages go to the sender. */
    boolean setProperty(CommandSender sender, WorldSettings w, String key, String value) {
        if (Features.blocks(plugin.getConfig(), w, key, value)) { lang().send(sender, Features.OFF); return false; }
        String before = w.timeLock + "|" + w.weatherLock;
        String error = Properties.set(w, key, value, name -> plugin.registry().contains(name) || WorldManager.loaded(name) != null);
        if (error != null) {
            lang().send(sender, error, "key", key, "value", value, "world", value);
            return false;
        }
        changed(w, !before.equals(w.timeLock + "|" + w.weatherLock));
        lang().send(sender, "property-set", "world", w.name, "key", key, "value", Properties.get(w, key));
        return true;
    }

    /**
     * Applies the world page's pending changes at once (1.21.0): every change is checked first with the rules of
     * {@code /worlds set}, so either all are applied with one save or none. Each change gets its audit line.
     *
     * @return null when applied, else the first problem (nothing was changed)
     */
    PendingChanges.Problem applyAll(CommandSender sender, WorldSettings w, java.util.Map<String, String> changes) {
        java.util.function.Predicate<String> exists = name -> plugin.registry().contains(name) || WorldManager.loaded(name) != null;
        java.util.Map<String, String> checked = new java.util.LinkedHashMap<>();
        for (var entry : changes.entrySet()) {
            PendingChanges.Problem problem = PendingChanges.check(w, checked, entry.getKey(), entry.getValue(), plugin.getConfig(), exists);
            if (problem != null) return problem;
            checked.put(entry.getKey(), entry.getValue());
        }
        List<PendingChanges.Change> list = PendingChanges.changes(w, checked, exists);
        if (list.isEmpty()) return null;
        String before = w.timeLock + "|" + w.weatherLock;
        for (PendingChanges.Change change : list) {
            Properties.set(w, change.key(), checked.get(change.key()), exists);
            plugin.audit(sender, w.name, change.command(w.name));
        }
        changed(w, !before.equals(w.timeLock + "|" + w.weatherLock));
        return null;
    }

    /** Asks the player to type a word (the world name) to confirm; anything else cancels. */
    void typedConfirm(Player player, String titleKey, String questionKey, String expected, Runnable action) {
        plugin.core().dialogs().prompt(plugin, player, lang().plain(player, titleKey, "world", expected), lang().plain(player, questionKey, "world", expected), "", typed -> {
            if (typed != null && typed.strip().equalsIgnoreCase(expected)) action.run();
            else lang().send(player, "confirm-mismatch", "world", expected);
        }, () -> lang().send(player, "cancelled"), p -> p.isOnline() && p.hasPermission("vystormworlds.admin"));
    }

    // ------------------------------------------------------------------ create

    void create(CommandSender sender, CreateRequest request) {
        if (!Features.on(plugin.getConfig(), Features.MANAGEMENT)) { lang().send(sender, Features.OFF); return; }
        if (request.minY() != null && !Features.on(plugin.getConfig(), Features.BUILD_HEIGHT)) { lang().send(sender, Features.OFF); return; }
        WorldManager manager = plugin.manager();
        String problem = request.name().length() > 32 ? "bad-name" : manager.checkNewName(request.name());
        if (problem == null && request.withNether()) problem = manager.checkNewName(request.name() + "_nether");
        if (problem == null && request.withEnd()) problem = manager.checkNewName(request.name() + "_the_end");
        if (problem != null) { lang().send(sender, problem, "world", request.name()); return; }
        WorldSettings w = request.settings();
        lang().send(sender, "creating", "world", w.name);
        WorldManager.Outcome outcome = manager.create(w);
        if (outcome.world() == null && outcome.error() == null) {
            lang().send(sender, "created-restart", "world", w.name, "min", w.minY, "max", w.minY + w.height);
            return;
        }
        if (outcome.world() == null) { lang().send(sender, "create-failed", "world", w.name, "error", outcome.error()); return; }
        List<String> extra = new ArrayList<>();
        if (request.withNether()) {
            WorldSettings nether = request.linked(WorldKind.NETHER, w.seed);
            if (manager.create(nether).world() != null) { w.nether = nether.name; extra.add(nether.name); }
        }
        if (request.withEnd()) {
            WorldSettings end = request.linked(WorldKind.END, w.seed);
            if (manager.create(end).world() != null) { w.end = end.name; extra.add(end.name); }
        }
        plugin.saveRegistry();
        plugin.links().sync();
        plugin.core().compat().platform().sendMessage(sender, lang().component(sender, "created", "world", w.name, "type", w.kind.id(), "seed", String.valueOf(w.seed),
                        "extra", extra.isEmpty() ? "-" : String.join(", ", extra))
                .append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-tp"), lang().plain(sender, "button-tp-hover"), "/worlds tp " + w.name))
                .append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-edit"), lang().plain(sender, "button-edit-hover"), "/worlds edit " + w.name)));
        World world = outcome.world();
        if (manager.spawnPending(world)) {
            lang().send(sender, "spawn-searching", "world", w.name);
            manager.findSpawn(world, checked -> lang().send(sender, "spawn-search-progress", "world", w.name, "count", checked),
                    spawn -> { if (spawn != null) lang().send(sender, "spawn-found", "world", w.name, "x", spawn.getBlockX(), "y", spawn.getBlockY(), "z", spawn.getBlockZ()); });
        }
    }

    // ------------------------------------------------------------------ destructive

    void delete(CommandSender sender, String name) {
        if (!Features.on(plugin.getConfig(), Features.MANAGEMENT)) { lang().send(sender, Features.OFF); return; }
        plugin.audit(sender, name, "/worlds delete " + name + " (confirmed)");
        lang().send(sender, "deleting", "world", name);
        plugin.manager().delete(name, problem -> {
            if (problem == null) lang().send(sender, plugin.getConfig().getBoolean("delete.backup", true) ? "deleted-backup" : "deleted", "world", name);
            else lang().send(sender, problem, "world", name);
        });
    }

    void regenerate(CommandSender sender, String name, String seedOption) {
        if (!Features.on(plugin.getConfig(), Features.MANAGEMENT)) { lang().send(sender, Features.OFF); return; }
        Long seed = null;
        if (seedOption != null && !seedOption.isBlank() && !seedOption.equalsIgnoreCase("same")) {
            seed = seedOption.equalsIgnoreCase("random") ? java.util.concurrent.ThreadLocalRandom.current().nextLong() : CreateRequest.seed(seedOption);
        }
        plugin.audit(sender, name, "/worlds regen " + name + " " + (seedOption == null || seedOption.isBlank() ? "same" : seedOption) + " (confirmed)");
        lang().send(sender, "regenerating", "world", name);
        plugin.manager().regenerate(name, seed, problem -> {
            if (problem == null) lang().send(sender, "regenerated", "world", name);
            else lang().send(sender, problem, "world", name);
        });
    }

    // ------------------------------------------------------------------ overview

    /** Clickable admin list: every known world with state, players, chunks and buttons. */
    void list(CommandSender sender) {
        List<WorldSettings> all = new ArrayList<>(plugin.registry().all());
        all.sort(Comparator.comparing((WorldSettings w) -> WorldManager.loaded(w.name) == null).thenComparing(w -> w.name));
        lang().send(sender, "list-header", "count", all.size(), "tps", tps(plugin.core().compat().platform()));
        for (WorldSettings w : all) {
            World world = WorldManager.loaded(w.name);
            String state = world != null ? "loaded" : plugin.manager().busy(w.name) ? "busy" : plugin.manager().loadError(w.name) != null ? "error" : "unloaded";
            Component line = lang().component(sender, "list-entry-" + state, "world", w.name, "type", w.kind.id(), "group", w.group,
                    "players", world == null ? 0 : world.getPlayers().size(), "chunks", world == null ? 0 : world.getLoadedChunks().length,
                    "entities", world == null ? 0 : world.getEntities().size(), "error", String.valueOf(plugin.manager().loadError(w.name)));
            if (sender instanceof Player) {
                if (world != null) line = line.append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-tp"), lang().plain(sender, "button-tp-hover"), "/worlds tp " + w.name));
                line = line.append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-edit"), lang().plain(sender, "button-edit-hover"), "/worlds edit " + w.name));
                line = line.append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-info"), lang().plain(sender, "button-info-hover"), "/worlds info " + w.name));
                if (world == null) line = line.append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-load"), lang().plain(sender, "button-load-hover"), "/worlds load " + w.name));
                else if (!WorldManager.serverWorld(world)) line = line.append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-unload"), lang().plain(sender, "button-unload-hover"), "/worlds unload " + w.name));
            }
            plugin.core().compat().platform().sendMessage(sender, line);
        }
        if (sender instanceof Player) plugin.core().compat().platform().sendMessage(sender, plugin.core().message().chat().command(lang().plain(sender, "button-create"), lang().plain(sender, "button-create-hover"), "/worlds create")
                .append(Component.space()).append(plugin.core().message().chat().command(lang().plain(sender, "button-import"), lang().plain(sender, "button-import-hover"), "/worlds import")));
    }

    static String tps(Vystorm.vystorm_core.compat.PlatformCompatInterface platform) {
        try {
            double tps = platform.getTPS()[0];
            return String.format(Locale.ROOT, "%.1f TPS, %.1f ms", Math.min(20, tps), platform.getAverageTickTime());
        } catch (RuntimeException | LinkageError unknown) { return "?"; }
    }

    void info(CommandSender sender, WorldSettings w) {
        World world = WorldManager.loaded(w.name);
        lang().send(sender, "info-header", "world", w.name, "title", Menus.title(w));
        lang().send(sender, "info-state", "state", world != null ? lang().plain(sender, "state-loaded") : lang().plain(sender, "state-unloaded"),
                "type", w.kind.id(), "seed", String.valueOf(w.seed), "generator", w.generator.none() ? "-" : w.generator.toString(),
                "group", w.group, "height", w.customHeight() ? w.minY + ".." + (w.minY + w.height) : "-");
        if (world != null) lang().send(sender, "info-load", "players", world.getPlayers().size(), "chunks", world.getLoadedChunks().length,
                "entities", world.getEntities().size(), "tiles", java.util.Arrays.stream(world.getLoadedChunks()).mapToInt(chunk -> chunk.getTileEntities().length).sum(), "tps", tps(plugin.core().compat().platform()));
        String error = plugin.manager().loadError(w.name);
        if (error != null) lang().send(sender, "info-error", "error", error);
        StringBuilder props = new StringBuilder();
        for (String key : Properties.KEYS) props.append(props.isEmpty() ? "" : ", ").append(key).append('=').append(Properties.get(w, key));
        lang().send(sender, "info-properties", "properties", props.toString());
        // backups: counted off the server thread (a world copy can be thousands of files), answered on it
        java.nio.file.Path folder = plugin.getDataFolder().toPath().resolve("backups");
        int keep = Math.clamp(plugin.getConfig().getInt("reset.keep-backups", 3), 0, 100);
        plugin.tasks().async(() -> {
            WorldResets.Backups backups = WorldResets.backups(folder, w.name);
            if (backups.count() == 0) return;
            plugin.tasks().now(() -> {
                if (sender instanceof Player p && !p.isOnline()) return;
                lang().send(sender, "info-backups", "regenerated", backups.regenerated(), "deleted", backups.deleted(),
                        "keep", keep, "size", backups.size());
            });
        });
    }

    /** Importable folders as clickable lines. */
    void importList(CommandSender sender) {
        if (!Features.on(plugin.getConfig(), Features.MANAGEMENT)) { lang().send(sender, Features.OFF); return; }
        List<WorldPaths.Candidate> candidates = plugin.manager().paths().candidates(plugin.usedNames());
        if (candidates.isEmpty()) { lang().send(sender, "import-none"); return; }
        lang().send(sender, "import-header", "count", candidates.size());
        for (WorldPaths.Candidate c : candidates) {
            Component line = lang().component(sender, c.legacy() ? "import-entry-legacy" : "import-entry", "world", c.name(), "type", c.kind().id());
            if (sender instanceof Player) line = line.append(Component.space())
                    .append(plugin.core().message().chat().command(lang().plain(sender, "button-import-one"), lang().plain(sender, "button-import-one-hover"), "/worlds import " + c.name() + " " + c.kind().id()));
            plugin.core().compat().platform().sendMessage(sender, line);
        }
    }

    void importWorld(CommandSender sender, String name, List<String> options) {
        if (!Features.on(plugin.getConfig(), Features.MANAGEMENT)) { lang().send(sender, Features.OFF); return; }
        WorldPaths.Candidate candidate = null;
        for (WorldPaths.Candidate c : plugin.manager().paths().candidates(plugin.usedNames()))
            if (c.name().equalsIgnoreCase(name)) candidate = c;
        if (candidate == null) { lang().send(sender, "import-unknown", "world", name); return; }
        CreateRequest.Parsed parsed = CreateRequest.parse(candidate.name(), options, plugin.defaultGroup());
        if (parsed.error() != null) { lang().send(sender, parsed.error(), "token", parsed.token()); return; }
        WorldSettings w = parsed.request().settings();
        w.minY = null;
        w.height = null;
        boolean kindGiven = options.stream().anyMatch(o -> o.indexOf(':') < 0 && WorldKind.parse(o) != null);
        if (!kindGiven) w.kind = candidate.kind();
        if (w.generator.none()) {
            String[] bukkit = Generators.bukkitYml(java.nio.file.Path.of("bukkit.yml"), candidate.name());
            try { w.generator = GeneratorSpec.parse(bukkit[0]); } catch (IllegalArgumentException ignored) { }
            if (w.biomeProvider.isBlank()) w.biomeProvider = bukkit[1];
            if (!w.generator.none()) lang().send(sender, "import-bukkit-generator", "generator", w.generator.toString());
        }
        lang().send(sender, "importing", "world", w.name);
        plugin.manager().importWorld(candidate, w, outcome -> {
            if (outcome.world() != null) lang().send(sender, "imported", "world", w.name);
            else lang().send(sender, "create-failed", "world", w.name, "error", outcome.error());
        });
    }
}
