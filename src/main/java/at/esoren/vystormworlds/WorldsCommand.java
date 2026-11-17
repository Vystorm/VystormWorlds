package at.esoren.vystormworlds;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /worlds} (aliases {@code /vw}, {@code /mvw}). Players get menus; admins get every operation as command too. */
final class WorldsCommand {
    private static final List<String> ADMIN = List.of("admin", "list", "info", "groups", "create", "import", "load", "unload", "delete", "regen",
            "clone", "set", "edit", "settings", "setspawn", "link", "generators", "reload", "template");
    private static final List<String> PLAYER = List.of("menu", "tp", "spawn", "arrival", "favorite", "favorites", "help", "visit", "leave");

    private final WorldsPlugin plugin;

    WorldsCommand(WorldsPlugin plugin) { this.plugin = plugin; }

    private Actions actions() { return plugin.menus().actions(); }

    private void send(CommandSender sender, String key, Object... placeholders) { plugin.lang().send(sender, key, placeholders); }

    /** The world a clone message is about: name problems ("exists", "folder-exists") concern the new name, not the source. */
    static String cloneSubject(String problem, String source, String target) {
        return "exists".equals(problem) || "folder-exists".equals(problem) ? target : source;
    }

    private WorldSettings world(CommandSender sender, String name) {
        WorldSettings w = plugin.registry().get(name);
        if (w == null) {
            World loaded = WorldManager.loaded(name);
            if (loaded != null) w = plugin.settings(loaded);
        }
        if (w == null) send(sender, "unknown-world", "world", name);
        return w;
    }

    /** The old dispatch on English subcommand ids; Core hands it the canonical arguments (see {@link #commandSpec}). */
    void run(CommandSender sender, String label, String[] args) {
        dispatch(sender, label, args);
    }

    private boolean dispatch(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? (sender instanceof Player ? "menu" : "list") : args[0].toLowerCase(Locale.ROOT);
        if (ADMIN.contains(sub) && !sender.hasPermission("vystormworlds.admin")) { send(sender, "denied"); return true; }
        if (PLAYER.contains(sub) && !sender.hasPermission("vystormworlds.use")) { send(sender, "denied"); return true; }
        if (plugin.registry().error() != null && List.of("create", "import", "delete", "regen", "clone", "set", "link").contains(sub)) {
            send(sender, "registry-broken", "error", plugin.registry().error());
            return true;
        }
        switch (sub) {
            case "template" -> {
                if (!Features.on(plugin.getConfig(), Features.TEMPLATES)) send(sender, Features.OFF);
                else if (plugin.templates() == null) send(sender, "template-failed"); else plugin.templates().command(sender, args);
            }
            case "visit" -> {
                if (!(sender instanceof Player p)) { send(sender,"player-only"); return true; }
                // only new visits are refused; /worlds leave and the visit timer keep working for players already inside
                if (!Features.on(plugin.getConfig(), Features.TEMPLATES)) send(sender, Features.OFF);
                else if (plugin.templates() == null) send(sender,"template-failed"); else plugin.templates().visit(p,args.length>1?args[1]:null);
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) { send(sender,"player-only"); return true; }
                if (plugin.templates() == null) send(sender,"template-failed"); else plugin.templates().leave(p);
            }
            case "menu" -> { if (sender instanceof Player p) plugin.menus().selector(p, 0); else send(sender, "player-only"); }
            case "admin" -> { if (sender instanceof Player p) plugin.menus().admin(p, 0); else actions().list(sender); }
            case "help" -> help(sender, label);
            case "tp" -> tp(sender, args);
            case "favorites" -> { if (sender instanceof Player p) plugin.menus().selector(p, 0, true); else send(sender, "player-only"); }
            case "favorite" -> {
                if (!(sender instanceof Player p)) { send(sender, "player-only"); return true; }
                if (args.length > 2) { send(sender, "usage-favorite"); return true; }
                plugin.menus().favorite(p, args.length == 2 ? args[1] : p.getWorld().getName());
            }
            case "arrival" -> {
                if (!(sender instanceof Player p)) { send(sender, "player-only"); return true; }
                if (args.length != 2 || !List.of("spawn", "last").contains(args[1].toLowerCase(Locale.ROOT))) {
                    send(sender, "usage-arrival", "label", label);
                    return true;
                }
                boolean spawn = args[1].equalsIgnoreCase("spawn");
                plugin.teleports().preferSpawn(p, spawn);
                send(sender, spawn ? "arrival-spawn" : "arrival-last");
            }
            case "spawn" -> {
                if (!(sender instanceof Player p)) { send(sender, "player-only"); return true; }
                plugin.teleports().send(p, Teleports.spawn(p.getWorld(), plugin.settings(p.getWorld())), ok -> { });
            }
            case "list" -> actions().list(sender);
            case "info" -> {
                WorldSettings w = args.length > 1 ? world(sender, args[1]) : sender instanceof Player p ? plugin.settings(p.getWorld()) : null;
                if (w != null) actions().info(sender, w);
                else if (args.length <= 1) send(sender, "usage-info", "label", label);
            }
            case "groups" -> groups(sender);
            case "create" -> {
                if (args.length < 2) {
                    if (sender instanceof Player p) plugin.menus().createDialog(p);
                    else send(sender, "usage-create", "label", label);
                    return true;
                }
                CreateRequest.Parsed parsed = CreateRequest.parse(args[1], Arrays.asList(args).subList(2, args.length), plugin.defaultGroup());
                if (parsed.error() != null) send(sender, parsed.error(), "token", parsed.token());
                else actions().create(sender, parsed.request());
            }
            case "import" -> {
                if (args.length < 2) actions().importList(sender);
                else actions().importWorld(sender, args[1], Arrays.asList(args).subList(2, args.length));
            }
            case "load" -> {
                if (args.length < 2) { send(sender, "usage-world", "label", label, "sub", sub); return true; }
                WorldSettings w = world(sender, args[1]);
                if (w == null) return true;
                if (WorldManager.loaded(w.name) != null) { send(sender, "already-loaded", "world", w.name); return true; }
                WorldManager.Outcome outcome = plugin.manager().load(w);
                if (outcome.world() != null) { plugin.saveRegistry(); plugin.links().sync(); send(sender, "loaded", "world", w.name); }
                else send(sender, "create-failed", "world", w.name, "error", outcome.error());
            }
            case "unload" -> {
                if (args.length < 2) { send(sender, "usage-world", "label", label, "sub", sub); return true; }
                String problem = plugin.manager().unload(args[1]);
                send(sender, problem == null ? "unloaded" : problem, "world", args[1]);
            }
            case "delete" -> destructive(sender, args, label, true);
            case "regen" -> destructive(sender, args, label, false);
            case "clone" -> {
                if (args.length < 3) { send(sender, "usage-clone", "label", label); return true; }
                send(sender, "cloning", "world", args[1], "target", args[2]);
                boolean confirmed = args.length > 3 && args[3].equalsIgnoreCase("confirm");
                String target = args[2].toLowerCase(Locale.ROOT);
                plugin.manager().cloneWorld(args[1], target, confirmed, problem ->
                        send(sender, problem == null ? "cloned" : problem, "world", cloneSubject(problem, args[1], target), "target", target, "label", label));
            }
            case "set" -> {
                if (args.length < 3) { send(sender, "usage-set", "label", label); properties(sender); return true; }
                WorldSettings w = world(sender, args[1]);
                if (w == null) return true;
                if (args[2].equalsIgnoreCase("build-height") || args[2].equalsIgnoreCase("generator") || args[2].equalsIgnoreCase("seed")) {
                    send(sender, "fixed-at-creation", "key", args[2]);
                    return true;
                }
                actions().setProperty(sender, w, args[2].toLowerCase(Locale.ROOT), args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "");
            }
            case "link" -> {
                if (args.length < 4 || !List.of("nether", "end").contains(args[2].toLowerCase(Locale.ROOT))) { send(sender, "usage-link", "label", label); return true; }
                WorldSettings w = world(sender, args[1]);
                if (w != null) actions().setProperty(sender, w, args[2].toLowerCase(Locale.ROOT), args[3]);
            }
            case "edit", "settings" -> {
                if (!(sender instanceof Player p)) { send(sender, "player-only"); return true; }
                WorldSettings w = args.length > 1 ? world(sender, args[1]) : plugin.settings(p.getWorld());
                if (w == null) return true;
                if (sub.equals("edit")) plugin.menus().world(p, w.name);
                else WorldSection.open(plugin, p, w.name);
            }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) { send(sender, "player-only"); return true; }
                WorldSettings w = plugin.settings(p.getWorld());
                if (w == null) { send(sender, "unknown-world", "world", p.getWorld().getName()); return true; }
                Location at = p.getLocation();
                w.spawn = new WorldSettings.Spawn(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
                actions().changed(w, false);
                send(sender, "spawn-set", "world", w.name, "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ());
            }
            case "generators" -> generators(sender);
            case "reload" -> {
                plugin.reload();
                if (plugin.registry().error() != null) send(sender, "registry-broken", "error", plugin.registry().error());
                else send(sender, "reloaded", "count", plugin.registry().all().size());
            }
            default -> {
                // "/worlds <world>" travels there
                WorldSettings w = plugin.registry().get(args[0]);
                if (w != null && sender instanceof Player p && sender.hasPermission("vystormworlds.use") && !w.hidden) actions().travel(p, w, true);
                else help(sender, label);
            }
        }
        return true;
    }

    private void tp(CommandSender sender, String[] args) {
        if (args.length < 2) { send(sender, "usage-tp"); return; }
        WorldSettings w = world(sender, args[1]);
        if (w == null) return;
        if (args.length > 2) {
            if (!sender.hasPermission("vystormworlds.admin")) { send(sender, "denied"); return; }
            Player target = plugin.getServer().getPlayerExact(args[2]);
            if (target == null) { send(sender, "player-offline", "player", args[2]); return; }
            World world = WorldManager.loaded(w.name);
            if (world == null) { send(sender, "not-loaded", "world", w.name); return; }
            actions().go(target, w, world, false);
            send(sender, "sent", "player", target.getName(), "world", w.name);
            return;
        }
        if (!(sender instanceof Player p)) { send(sender, "player-only"); return; }
        if (w.hidden && !p.hasPermission("vystormworlds.admin")) { send(sender, "unknown-world", "world", args[1]); return; }
        actions().travel(p, w, true);
    }

    private void destructive(CommandSender sender, String[] args, String label, boolean delete) {
        if (args.length < 2) { send(sender, delete ? "usage-delete" : "usage-regen", "label", label); return; }
        WorldSettings w = world(sender, args[1]);
        if (w == null) return;
        if (w.serverWorld) { send(sender, "server-world", "world", w.name); return; }
        String seed = delete ? null : args.length > 2 ? args[2] : "same";
        if (sender instanceof Player p) {
            plugin.menus().actions().typedConfirm(p, delete ? "delete-title" : "regen-title", delete ? "delete-question" : "regen-question", w.name, () -> {
                if (delete) actions().delete(p, w.name);
                else actions().regenerate(p, w.name, seed);
            });
            return;
        }
        // console / RCON: the world name must be typed a second time as the last argument
        String confirm = args[args.length - 1];
        if (args.length < (delete ? 3 : 4) || !confirm.equalsIgnoreCase(w.name)) {
            send(sender, delete ? "confirm-console-delete" : "confirm-console-regen", "label", label, "world", w.name, "seed", seed);
            return;
        }
        if (delete) actions().delete(sender, w.name);
        else actions().regenerate(sender, w.name, seed);
    }

    private void groups(CommandSender sender) {
        Map<String, List<String>> groups = new TreeMap<>();
        groups.computeIfAbsent(plugin.defaultGroup(), g -> new ArrayList<>());
        for (WorldSettings w : plugin.registry().all()) groups.computeIfAbsent(w.group, g -> new ArrayList<>()).add(w.name);
        send(sender, plugin.getConfig().getBoolean("groups.enabled", true) ? "groups-header" : "groups-header-off",
                "separate", String.join(", ", plugin.getConfig().getStringList("groups.separate")));
        for (var entry : groups.entrySet()) send(sender, "groups-entry", "group", entry.getKey(), "worlds", entry.getValue().isEmpty() ? "-" : String.join(", ", entry.getValue()));
    }

    private void generators(CommandSender sender) {
        var choices = plugin.manager().generators().choices();
        if (choices.isEmpty()) { send(sender, "generators-none"); return; }
        send(sender, "generators-header", "count", choices.size());
        for (Generators.Choice choice : choices) send(sender, choice.preset() ? "generators-preset" : "generators-plugin", "value", choice.value(), "name", choice.label());
    }

    private void properties(CommandSender sender) { send(sender, "properties-list", "keys", String.join(", ", Properties.KEYS)); }

    private void help(CommandSender sender, String label) {
        send(sender, "help-player", "label", label);
        if (sender.hasPermission("vystormworlds.admin")) send(sender, "help-admin", "label", label);
    }

    // ------------------------------------------------------------------ tab completion

    /** Old tab completion options for canonical arguments, unfiltered (Core filters by the typed prefix). */
    List<String> complete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("vystormworlds.admin");
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER);
            if (admin) options.addAll(ADMIN);
            for (WorldSettings w : plugin.registry().all()) if (!w.hidden && WorldManager.loaded(w.name) != null) options.add(w.name);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && sub.equals("arrival")) return List.of("spawn", "last");
            if (args.length == 2 && sub.equals("favorite") && sender instanceof Player p && p.hasPermission("vystormworlds.use"))
                return plugin.menus().selectable(p).stream().map(w -> w.name).toList();
            if (args.length == 2 && List.of("tp", "info", "load", "unload", "delete", "regen", "clone", "set", "edit", "settings", "link").contains(sub)) {
                for (WorldSettings w : plugin.registry().all()) if (admin || !w.hidden && sub.equals("tp")) options.add(w.name);
            } else if (args.length == 2 && sub.equals("import") && admin) {
                for (WorldPaths.Candidate c : plugin.manager().paths().candidates(plugin.usedNames())) options.add(c.name());
            } else if (args.length == 3 && sub.equals("tp") && admin) {
                for (Player p : plugin.getServer().getOnlinePlayers()) options.add(p.getName());
            } else if (args.length == 3 && sub.equals("set") && admin) {
                options.addAll(Properties.KEYS);
            } else if (args.length == 4 && sub.equals("set") && admin) {
                options.addAll(Properties.suggestions(args[2].toLowerCase(Locale.ROOT)));
                if (List.of("respawn-world", "nether", "end").contains(args[2].toLowerCase(Locale.ROOT)))
                    for (WorldSettings w : plugin.registry().all()) options.add(w.name);
            } else if (args.length == 4 && sub.equals("clone") && admin) {
                options.add("confirm");
            } else if (args.length == 3 && sub.equals("link") && admin) {
                options.addAll(List.of("nether", "end"));
            } else if (args.length == 4 && sub.equals("link") && admin) {
                options.add("none");
                for (WorldSettings w : plugin.registry().all()) options.add(w.name);
            } else if (args.length == 3 && sub.equals("regen") && admin) {
                options.addAll(List.of("same", "random"));
            } else if (args.length >= 3 && (sub.equals("create") || sub.equals("import")) && admin) {
                String last = args[args.length - 1].toLowerCase(Locale.ROOT);
                for (WorldKind kind : WorldKind.values()) options.add(kind.id());
                options.addAll(List.of("seed:", "generator:", "biomes:", "structures:false", "group:" + plugin.defaultGroup()));
                if (sub.equals("create")) options.addAll(List.of("with-nether", "with-end"));
                if (sub.equals("create") && Features.on(plugin.getConfig(), Features.BUILD_HEIGHT)) options.addAll(List.of("min-y:-128", "height:512"));
                if (last.startsWith("generator:") || last.startsWith("gen:") || last.startsWith("biomes:")) {
                    String prefix = last.substring(0, last.indexOf(':') + 1);
                    for (Generators.Choice choice : plugin.manager().generators().choices()) options.add(prefix + choice.value());
                }
            }
        }
        return options.stream().distinct().toList();
    }

    // ------------------------------------------------------------------ Vystorm Core command tree

    /** Free arguments of every subcommand: as many single words as the old syntax uses, then the rest of the line. */
    private static final int WORDS = 12;
    /** English keyword values by subcommand and argument position (0 = the subcommand itself), localized in the lang files. */
    private static final Map<String, Map<Integer, List<String>>> KEYWORDS = Map.of(
            "arrival", Map.of(1, List.of("spawn", "last")),
            "clone", Map.of(3, List.of("confirm")),
            "link", Map.of(2, List.of("nether", "end"), 3, List.of("none")),
            "regen", Map.of(2, List.of("same", "random")));
    private static final List<String> SUBS = List.of("menu", "tp", "spawn", "arrival", "favorite", "favorites", "admin", "list", "info", "groups", "create", "import", "load", "unload",
            "delete", "regen", "clone", "set", "edit", "settings", "setspawn", "link", "generators", "reload", "template", "visit", "leave");
    /** Subcommands the old executor ran without looking at further words. */
    private static final List<String> NO_ARGS = List.of("menu", "spawn", "favorites", "admin", "list", "groups", "setspawn", "generators", "reload", "leave");
    private static final List<String> PLAYER_ONLY = List.of("menu", "spawn", "arrival", "favorite", "favorites", "edit", "settings", "setspawn", "visit", "leave");

    /**
     * {@code /worlds} as a Vystorm Core command tree: subcommand names, keyword values, usage and help come from
     * {@code commands.worlds.*} in the language files ({@code /welten liste}, {@code /世界 列表}); English always works.
     * Every node hands the arguments, with subcommand names and keywords turned back into the English ids, to the
     * unchanged {@link #run} dispatch, so behaviour and messages stay those of the old executor. {@code /worlds WORLD}
     * (a world name instead of a subcommand) is the root's argument. {@code c} may be {@code null} in tests that only
     * inspect names and help.
     */
    static CommandSpec commandSpec(WorldsCommand c) {
        CommandSpec.Builder root = CommandSpec.command("worlds").aliases("vw", "mvw")
                .arg(Arg.word("world").optional().suggests((ctx, prefix) -> c.rootWorlds()))
                .arg(Arg.greedy("extra").optional().suggests(List.of()))
                .executes(ctx -> c.run(ctx.sender(), ctx.label(), canonical(ctx)));
        for (String id : SUBS) {
            root.sub(id, s -> {
                s.permission(PLAYER.contains(id) ? "vystormworlds.use" : "vystormworlds.admin");
                if (PLAYER_ONLY.contains(id)) s.playerOnly();
                s.usage("");
                if (!NO_ARGS.contains(id)) {
                    // single words, not one greedy argument: Core completes a greedy argument only at its first word
                    for (int i = 1; i <= WORDS; i++) s.arg(Arg.word("a" + i).optional().suggests((ctx, prefix) -> c.suggest(ctx, prefix)));
                    s.arg(Arg.greedy("more").optional().suggests((ctx, prefix) -> c.suggest(ctx, prefix)));
                }
                s.executes(ctx -> c.run(ctx.sender(), ctx.label(), canonical(ctx)));
            });
        }
        return root.build();
    }

    /** The typed arguments with localized subcommand names and keyword values replaced by the English ids. */
    static String[] canonical(CommandContext ctx) {
        String[] args = ctx.rawArgs();
        List<String> path = ctx.path();
        for (int i = 0; i < path.size() && i < args.length; i++) args[i] = path.get(i);
        Map<Integer, List<String>> keywords = path.isEmpty() ? null : KEYWORDS.get(path.get(0));
        if (keywords != null)
            for (var entry : keywords.entrySet()) {
                int at = entry.getKey();
                if (at >= args.length) continue;
                String key = ctx.keyword(args[at], entry.getValue().toArray(String[]::new));
                if (key != null) args[at] = key;
            }
        return args;
    }

    /** Completion for subcommand arguments: the old options, keyword values in the player's language. */
    List<String> suggest(CommandContext ctx, String prefix) {
        String[] args = canonical(ctx);
        Map<Integer, List<String>> keywords = KEYWORDS.getOrDefault(ctx.path().isEmpty() ? "" : ctx.path().get(0), Map.of());
        List<String> words = keywords.getOrDefault(args.length - 1, List.of());
        List<String> out = new ArrayList<>();
        String typed = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String option : complete(ctx.sender(), args)) {
            if (!words.contains(option)) { out.add(option); continue; }
            String shown = ctx.valueName(option);
            out.add(shown);
            if (!typed.isEmpty() && option.startsWith(typed) && !shown.toLowerCase(Locale.ROOT).startsWith(typed)) out.add(option);
        }
        return out;
    }

    /** {@code /worlds WORLD}: the loaded, visible worlds (subcommands are offered by Core). */
    List<String> rootWorlds() {
        List<String> out = new ArrayList<>();
        for (WorldSettings w : plugin.registry().all()) if (!w.hidden && WorldManager.loaded(w.name) != null) out.add(w.name);
        return out;
    }
}
