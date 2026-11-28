package at.esoren.vystormworlds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Who may use which menu entry (1.21.0). Each entry names the {@code /worlds} subcommand it runs, so it needs exactly
 * the node and the feature switches of that command: the menu never offers more than the command, and a player who may
 * not run the command does not see the entry. Nodes follow the family rule through Bukkit's own permission check:
 * without a node set, {@code vystormworlds.admin} is held by operators and {@code vystormworlds.use} by everyone; a node
 * that is set (granted or negated) decides, also for an operator.
 */
final class MenuAccess {
    /** A menu entry, the subcommand whose rules it follows, and the arguments that select the switch (tp with a player). */
    record Entry(String id, String sub, String... args) {}

    /** Every entry of the selector, the management overview and the world page, in menu order. */
    static final List<Entry> ENTRIES = List.of(
            new Entry("selector", "menu"), new Entry("travel", "tp", "WORLD"), new Entry("spawn", "spawn"),
            new Entry("arrival", "arrival"), new Entry("favorite", "favorite"), new Entry("visit", "visit"), new Entry("leave", "leave"),
            new Entry("overview", "admin"), new Entry("list", "list"), new Entry("groups", "groups"), new Entry("reload", "reload"),
            new Entry("new-world", "create"), new Entry("import", "import"), new Entry("templates", "template"),
            new Entry("world-page", "edit"), new Entry("settings-dialog", "settings"), new Entry("info", "info"),
            new Entry("apply", "set"), new Entry("link", "link"), new Entry("set-spawn", "setspawn"),
            new Entry("go", "tp", "WORLD"), new Entry("send", "tp", "WORLD", "PLAYER"),
            new Entry("load", "load"), new Entry("unload", "unload"), new Entry("clone", "clone"),
            new Entry("regen", "regen"), new Entry("delete", "delete"), new Entry("border-start", "edit"),
            new Entry("game-rules", "edit"));

    private MenuAccess() {}

    static Entry entry(String id) {
        for (Entry e : ENTRIES) if (e.id().equals(id)) return e;
        throw new IllegalArgumentException("unknown menu entry " + id);
    }

    /** The node the entry needs: the one {@code /worlds <sub>} checks. */
    static String node(String id) {
        Entry e = entry(id);
        // /worlds tp WORLD PLAYER sends someone else: the command asks for the admin node for that form
        return e.sub().equals("tp") && e.args().length > 1 ? WorldsCommand.ADMIN_NODE : WorldsCommand.nodeOf(e.sub());
    }

    /** The feature switches the entry needs: the ones {@code /worlds <sub>} checks. */
    static List<String> switches(String id) {
        Entry e = entry(id);
        String[] args = new String[e.args().length + 1];
        args[0] = e.sub();
        System.arraycopy(e.args(), 0, args, 1, e.args().length);
        return WorldsCommand.switchesOf(e.sub(), args);
    }

    /** Null when the entry may be used, else the language key of the reason ({@code denied} or {@link Features#OFF}). */
    static String refusal(String id, Predicate<String> hasPermission, ConfigurationSection config) {
        if (!hasPermission.test(node(id))) return "denied";
        for (String key : switches(id)) if (!Features.on(config, key)) return Features.OFF;
        return null;
    }

    static boolean allowed(String id, Predicate<String> hasPermission, ConfigurationSection config) {
        return refusal(id, hasPermission, config) == null;
    }

    /** The table for the report and the tests: entry -> node, switches. */
    static Map<String, String> table() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : ENTRIES) out.put(e.id(), node(e.id()) + " " + switches(e.id()));
        return out;
    }
}
