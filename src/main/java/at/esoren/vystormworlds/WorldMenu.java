package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The action layer of the world menus (1.21.0): every button of the world page, the "New world" form, the import list,
 * the templates page and the overview ends here, on the screen and in the chest alike. Each method checks the entry's
 * node and switches ({@link MenuAccess}, the same as the command), looks the world up again by name, and then runs the
 * command's own code: most of them through {@code /worlds ...} itself ({@link WorldsCommand#run}), so messages, checks
 * and the audit line are the command's. Server thread. No Core types newer than the plugin's minimum.
 */
final class WorldMenu {
    private final WorldsPlugin plugin;

    WorldMenu(WorldsPlugin plugin) { this.plugin = plugin; }

    WorldsPlugin plugin() { return plugin; }

    private String text(Player player, String key, Object... args) { return plugin.lang().plain(player, key, args); }

    private Result error(Player player, String key, Object... args) { return Result.error(text(player, key, args)); }

    /** Null when the player may use the entry now, else the refusal as a panel result. */
    Result refuse(Player player, String entry) {
        if (!plugin.isEnabled() || !player.isOnline()) return Result.error("");
        String reason = MenuAccess.refusal(entry, player::hasPermission, plugin.getConfig());
        return reason == null ? null : error(player, reason);
    }

    boolean may(Player player, String entry) { return plugin.isEnabled() && MenuAccess.allowed(entry, player::hasPermission, plugin.getConfig()); }

    /** The world's current entry, or null (the caller answers with {@code world-changed}). */
    WorldSettings world(String name) { return name == null || name.isBlank() ? null : plugin.registry().get(name); }

    private Result gone(Player player, String world) { return error(player, "world-changed", "world", world); }

    private Result broken(Player player) {
        return plugin.registry().error() == null ? null : error(player, "registry-broken", "error", plugin.registry().error());
    }

    /** Runs {@code /worlds <args>} for the player: the command's checks, messages and audit line. */
    private void command(Player player, String... args) {
        WorldsCommand commands = plugin.commands();
        if (commands != null) commands.run(player, "worlds", args);
    }

    private Result ran(Player player, String command) { return Result.ok(text(player, "menu-ran", "command", command)); }

    Predicate<String> worldExists() { return name -> plugin.registry().contains(name) || WorldManager.loaded(name) != null; }

    // ------------------------------------------------------------------ settings (pending + apply)

    /**
     * Adds one change to the page's pending map after the checks of {@code /worlds set}; the map is changed in place.
     * Nothing is saved yet.
     */
    Result stage(Player player, String name, Map<String, String> pending, String key, String value) {
        Result refused = refuse(player, "apply");
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        PendingChanges.Problem problem = PendingChanges.check(w, pending, key, value, plugin.getConfig(), worldExists());
        if (problem != null) return error(player, problem.error(), "key", text(player, label(key)), "value", value, "world", value);
        Map<String, String> next = PendingChanges.put(w, pending, key, value, worldExists());
        pending.clear();
        pending.putAll(next);
        return Result.ok();
    }

    /** Applies the pending map with one save and clears it; nothing is changed when one change is refused. */
    Result apply(Player player, String name, Map<String, String> pending) {
        Result refused = refuse(player, "apply");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        int count = PendingChanges.changes(w, pending, worldExists()).size();
        PendingChanges.Problem problem = plugin.menus().actions().applyAll(player, w, pending);
        if (problem != null) return error(player, problem.error(), "key", text(player, label(problem.key())), "value", problem.value(), "world", problem.value());
        pending.clear();
        return Result.ok(text(player, "page-applied", "count", count, "world", w.name));
    }

    /** One change at once (the chest: every click is one reversible change, as before 1.21.0). */
    Result setNow(Player player, String name, String key, String value) {
        Map<String, String> one = new LinkedHashMap<>();
        Result staged = stage(player, name, one, key, value);
        if (!staged.success()) return staged;
        if (one.isEmpty()) return Result.ok();
        return apply(player, name, one);
    }

    /** The pending map of a panel session value (a copy; unknown shapes give an empty map). */
    static Map<String, String> pending(Object stored) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> map) for (var e : map.entrySet()) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return out;
    }

    static String label(String key) {
        return "w-" + switch (key) { case "keep-spawn-loaded" -> "keep-spawn"; case "remember-position" -> "remember"; default -> key; };
    }

    /** Sends the commands as one chat line each with a click-to-copy button; the menu stays open. */
    Result copy(Player player, List<String> commands) {
        if (commands.isEmpty()) return Result.ok(text(player, "page-nothing-pending"));
        for (String command : commands) {
            Component line = Component.text(command + " ").append(plugin.core().message().chat().copy(text(player, "page-copy-button"), command));
            plugin.core().compat().platform().sendMessage(player, line);
        }
        return Result.ok(text(player, "page-copied", "count", commands.size()));
    }

    // ------------------------------------------------------------------ load, unload, go, send

    Result load(Player player, String name) {
        Result refused = refuse(player, "load");
        if (refused != null) return refused;
        if (world(name) == null) return gone(player, name);
        command(player, "load", name);
        return ran(player, "/worlds load " + name);
    }

    /** The screen asked first (button confirm, players named); the chest asks with a dialog. */
    Result unload(Player player, String name) {
        Result refused = refuse(player, "unload");
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        World loaded = WorldManager.loaded(name);
        String problem = DangerZone.refused(DangerZone.Kind.UNLOAD, loaded != null, w.serverWorld || loaded != null && WorldManager.serverWorld(loaded), plugin.manager().busy(name));
        if (problem != null) return error(player, problem, "world", name);
        command(player, "unload", name);
        return ran(player, "/worlds unload " + name);
    }

    Result go(Player player, String name) {
        Result refused = refuse(player, "go");
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        World loaded = WorldManager.loaded(name);
        if (loaded == null) return error(player, "not-loaded", "world", name);
        // the rules of /worlds tp WORLD: hidden worlds only for admins, then entry permission, limit and price
        if (w.hidden && !player.hasPermission(WorldsCommand.ADMIN_NODE)) return error(player, "unknown-world", "world", name);
        plugin.menus().actions().travel(player, w, true);
        return Result.ok();
    }

    /** {@code /worlds tp WORLD PLAYER} for each chosen player (the picker asked first). */
    Result send(Player player, String name, List<String> targets) {
        Result refused = refuse(player, "send");
        if (refused != null) return refused;
        if (world(name) == null) return gone(player, name);
        if (WorldManager.loaded(name) == null) return error(player, "not-loaded", "world", name);
        int sent = 0;
        for (String target : targets) {
            if (target == null || target.isBlank()) continue;
            command(player, "tp", name, target.strip());
            sent++;
        }
        return Result.ok(text(player, "page-sent", "count", sent, "world", name));
    }

    // ------------------------------------------------------------------ spawn and border centre

    /** {@code /worlds setspawn}: the player stands in the world. */
    Result spawnHere(Player player, String name) {
        Result refused = refuse(player, "set-spawn");
        if (refused != null) return refused;
        if (world(name) == null) return gone(player, name);
        if (!player.getWorld().equals(WorldManager.loaded(name))) return error(player, "stand-in-world", "world", name);
        command(player, "setspawn");
        return ran(player, "/worlds setspawn");
    }

    /** A picked spot of the world becomes its spawn (same as standing there and typing {@code /worlds setspawn}). */
    Result spawnAt(Player player, String name, Location at) {
        Result refused = refuse(player, "set-spawn");
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        World loaded = WorldManager.loaded(name);
        if (loaded == null || at == null || !loaded.equals(at.getWorld())) return error(player, "stand-in-world", "world", name);
        w.spawn = new WorldSettings.Spawn(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        plugin.audit(player, w.name, String.format(Locale.ROOT, "/worlds setspawn (picked %d %d %d)", at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        plugin.menus().actions().changed(w, false);
        String done = text(player, "spawn-set", "world", w.name, "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ());
        return Result.ok(done);
    }

    /** "x z" of a block as the border centre value. */
    static String center(double x, double z) { return String.format(Locale.ROOT, "%d %d", (long) Math.floor(x), (long) Math.floor(z)); }

    /** Starts the shrinking border event now (the world menu's button since 1.15.0). */
    Result borderStart(Player player, String name) {
        Result refused = refuse(player, "border-start");
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        if (!Features.on(plugin.getConfig(), Features.BORDER_EVENTS)) return error(player, Features.OFF);
        if (!Features.on(plugin.getConfig(), Features.BORDER))
            return error(player, Features.NEEDS, "switch", Features.BORDER, "name", text(player, Features.labelKey(Features.BORDER)));
        if (!plugin.borders().start(w)) return error(player, "border-no-size", "world", name);
        plugin.audit(player, name, "border event started");
        return Result.ok(text(player, "page-border-started", "world", name));
    }

    // ------------------------------------------------------------------ danger zone

    Result delete(Player player, String name, String typed) {
        Result refused = refuse(player, "delete");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        World loaded = WorldManager.loaded(name);
        String problem = DangerZone.refused(DangerZone.Kind.DELETE, loaded != null, w.serverWorld || loaded != null && WorldManager.serverWorld(loaded), plugin.manager().busy(name));
        if (problem == null) problem = DangerZone.typed(typed, w.name);
        if (problem != null) return error(player, problem, "world", w.name);
        plugin.menus().actions().delete(player, w.name);
        return Result.ok(text(player, "deleting", "world", w.name));
    }

    Result regen(Player player, String name, String seedChoice, String seedTyped, String typed) {
        Result refused = refuse(player, "regen");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        World loaded = WorldManager.loaded(name);
        String problem = DangerZone.refused(DangerZone.Kind.REGEN, loaded != null, w.serverWorld || loaded != null && WorldManager.serverWorld(loaded), plugin.manager().busy(name));
        if (problem != null) return error(player, problem, "world", w.name);
        String seed = DangerZone.seed(seedChoice, seedTyped);
        if (seed == null) return error(player, "page-seed-missing");
        problem = DangerZone.typed(typed, w.name);
        if (problem != null) return error(player, problem, "world", w.name);
        plugin.menus().actions().regenerate(player, w.name, seed);
        return Result.ok(text(player, "regenerating", "world", w.name));
    }

    /** {@code /worlds clone WORLD NEW [confirm]}; {@code confirmed} only after the player agreed to the players note. */
    Result cloneWorld(Player player, String name, String target, boolean confirmed) {
        Result refused = refuse(player, "clone");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        WorldSettings w = world(name);
        if (w == null) return gone(player, name);
        String to = target == null ? "" : target.strip().toLowerCase(Locale.ROOT);
        if (to.isEmpty() || !WorldSettings.validName(to)) return error(player, "bad-name", "world", to);
        if (needsCloneConfirm(name) && !confirmed) return error(player, "page-clone-needs-confirm", "world", name);
        if (confirmed) command(player, "clone", w.name, to, "confirm");
        else command(player, "clone", w.name, to);
        return ran(player, DangerZone.command(DangerZone.Kind.CLONE, w.name, null, to, confirmed));
    }

    boolean needsCloneConfirm(String name) {
        WorldSettings w = world(name);
        World loaded = WorldManager.loaded(name);
        WorldManager.CloneMode mode = WorldManager.cloneMode(loaded != null, loaded != null && WorldManager.serverWorld(loaded), w);
        return DangerZone.cloneNeedsConfirm(mode, loaded == null ? 0 : loaded.getPlayers().size());
    }

    // ------------------------------------------------------------------ new world, import, templates, reload

    /** Checks the "New world" form; ok carries no message. */
    NewWorldForm.Check check(NewWorldForm form) {
        return form.check(plugin.defaultGroup(), Features.on(plugin.getConfig(), Features.BUILD_HEIGHT), plugin.manager()::checkNewName);
    }

    Result create(Player player, NewWorldForm form) {
        Result refused = refuse(player, "new-world");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        NewWorldForm.Check check = check(form);
        if (!check.ok()) return Result.fieldErrors(Map.of(check.field(), text(player, check.error(), "token", check.token(), "world", form.name())));
        List<String> args = new ArrayList<>();
        args.add("create");
        args.add(check.request().name());
        args.addAll(form.tokens());
        command(player, args.toArray(String[]::new));
        return ran(player, form.command());
    }

    /** {@code /worlds import FOLDER [type] [group:..]}: {@code type} "" keeps the detected type. */
    Result importFolder(Player player, String folder, String type, String group) {
        Result refused = refuse(player, "import");
        if (refused == null) refused = broken(player);
        if (refused != null) return refused;
        List<String> args = new ArrayList<>(List.of("import", folder));
        if (type != null && !type.isBlank() && !type.equals("detected")) {
            if (WorldKind.parse(type) == null) return error(player, "bad-option", "token", type);
            args.add(type);
        }
        if (group != null && !group.isBlank()) {
            if (!WorldSettings.validGroup(group.strip().toLowerCase(Locale.ROOT))) return error(player, "bad-group");
            args.add("group:" + group.strip().toLowerCase(Locale.ROOT));
        }
        command(player, args.toArray(String[]::new));
        return ran(player, "/worlds " + String.join(" ", args));
    }

    /** {@code /worlds template create ID TERRAIN SECONDS INVENTORY}, {@code open ID} or {@code close ID}. */
    Result template(Player player, String... args) {
        Result refused = refuse(player, "templates");
        if (refused != null) return refused;
        if (plugin.templates() == null) return error(player, "template-failed");
        String[] full = new String[args.length + 1];
        full[0] = "template";
        System.arraycopy(args, 0, full, 1, args.length);
        command(player, full);
        return ran(player, "/worlds " + String.join(" ", full));
    }

    Result reload(Player player) {
        Result refused = refuse(player, "reload");
        if (refused != null) return refused;
        command(player, "reload");
        return ran(player, "/worlds reload");
    }

    /** Opens Vystorm Control's game rules for the world, when Control is installed. */
    Result gameRules(Player player, String name) {
        Result refused = refuse(player, "game-rules");
        if (refused != null) return refused;
        if (world(name) == null) return gone(player, name);
        if (WorldManager.loaded(name) == null) return error(player, "not-loaded", "world", name);
        return ControlLink.open(plugin, player, name) ? Result.ok() : error(player, "page-control-missing");
    }
}
