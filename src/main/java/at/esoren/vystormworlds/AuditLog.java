package at.esoren.vystormworlds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One line per admin action that changes worlds or files (1.21.0), from the menu and from {@code /worlds}: when, who
 * (name and UUID, or the console), the world and the exact command. Lines go to
 * {@code plugins/VystormWorlds/log/<yyyy-MM>.log}, appended off the server thread in the order they were made; the last
 * line of each world stays in memory for the "Last:" line of the world page. Switch {@code audit.enabled}.
 */
final class AuditLog {
    /** A logged action: time text, actor name and the command or action. */
    record Line(String time, String actor, String world, String action) {
        String text() { return time + " " + actor + " " + (world.isEmpty() ? "-" : world) + " " + action; }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
    private final Path folder;
    private final Supplier<ZonedDateTime> clock;
    private final Consumer<Runnable> async;
    private final Map<String, Line> last = new HashMap<>();
    private final Object writing = new Object();

    AuditLog(Path folder, Supplier<ZonedDateTime> clock, Consumer<Runnable> async) {
        this.folder = folder;
        this.clock = clock;
        this.async = async;
    }

    static AuditLog of(WorldsPlugin plugin) {
        return new AuditLog(plugin.getDataFolder().toPath().resolve("log"), () -> ZonedDateTime.now(ZoneId.systemDefault()),
                work -> plugin.tasks().async(work));
    }

    /**
     * Records an action; server thread. {@code actorId} is null for the console.
     *
     * @return the line (also when the switch is off: then nothing is written or kept)
     */
    Line record(boolean enabled, String actorName, UUID actorId, String world, String action) {
        ZonedDateTime now = clock.get();
        String actor = actorId == null ? "CONSOLE" : actorName + "(" + actorId + ")";
        Line line = new Line(TIME.format(now), actor, world == null ? "" : world, clean(action));
        if (!enabled) return line;
        if (!line.world().isEmpty()) last.put(WorldRegistry.key(line.world()), new Line(line.time(), actorName == null ? "CONSOLE" : actorName, line.world(), line.action()));
        Path file = folder.resolve(MONTH.format(now) + ".log");
        String text = line.text() + System.lineSeparator();
        async.accept(() -> append(file, text));
        return line;
    }

    private void append(Path file, String text) {
        synchronized (writing) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                java.util.logging.Logger.getLogger("VystormWorlds").warning("Could not write the audit log " + file + ": " + failure.getMessage());
            }
        }
    }

    /** The newest line about a world since the start (actor by name only), or null. */
    Line last(String world) { return world == null ? null : last.get(WorldRegistry.key(world)); }

    /** No line breaks or control characters in a log line (a typed name cannot start a fake line). */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) out.append(Character.isISOControl(c) ? ' ' : c);
        return out.toString().strip();
    }

    /** The world an audited command is about: the word after the subcommand, except for reload and template. */
    static String worldOf(String sub, String[] args) {
        if (args.length < 2 || sub.equals("reload") || sub.equals("template")) return "";
        return args[1];
    }
}
