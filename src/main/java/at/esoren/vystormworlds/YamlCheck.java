package at.esoren.vystormworlds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Strict YAML check of a config or data file. Bukkit's {@code reloadConfig()}/{@code loadConfiguration()} only log a
 * syntax error and hand back an empty configuration, so a later save would overwrite the file with almost nothing.
 * Callers ask {@link #problem(File)} first and never save over a file that failed to parse.
 */
final class YamlCheck {
    private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+)");

    private YamlCheck() {}

    /** Null when the file is missing or valid YAML, else {@code "line L, column C: <problem>"}. */
    static String problem(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (InvalidConfigurationException invalid) {
            return describe(invalid.getMessage());
        } catch (IOException | RuntimeException unreadable) {
            return "unreadable: " + unreadable.getMessage();
        }
    }

    /** The last "line L, column C" mark of a SnakeYAML message (the problem position) and the problem text before it. */
    static String describe(String message) {
        if (message == null || message.isBlank()) return "invalid YAML";
        Matcher mark = MARK.matcher(message);
        String where = null;
        int at = -1;
        while (mark.find()) { where = "line " + mark.group(1) + ", column " + mark.group(2); at = mark.start(); }
        String problem = null;
        String[] lines = (at < 0 ? message : message.substring(0, at)).split("\\R");
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || line.startsWith(" ") || text.equals("^") || text.startsWith("in '")) continue;
            problem = text;
        }
        if (problem == null) problem = lines.length > 0 && !lines[0].isBlank() ? lines[0].strip() : "invalid YAML";
        return where == null ? problem : where + ": " + problem;
    }

    /** Copies a broken file to {@code <name>.broken-<yyyyMMdd-HHmmss>} before it is rewritten; logs the copy. */
    static File backup(File file, Logger logger) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File copy = new File(file.getParentFile(), file.getName() + ".broken-" + stamp);
        Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        if (logger != null) logger.warning(file.getName() + " had a YAML error; the old file was copied to " + copy.getName() + " before it was written again.");
        return copy;
    }
}
