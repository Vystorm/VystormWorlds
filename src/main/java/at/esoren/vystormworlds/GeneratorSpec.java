package at.esoren.vystormworlds;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A world generator reference as stored in worlds.yml: empty (vanilla or the world type's own generator),
 * {@code Plugin} / {@code Plugin:id} (Bukkit's {@code getDefaultWorldGenerator(world, id)}, Multiverse style, the id may
 * contain further colons as parameters) or {@code preset:<id>} for a preset another plugin registered through
 * {@link VystormWorldsApi}.
 */
record GeneratorSpec(Type type, String plugin, String id) {
    enum Type { NONE, PLUGIN, PRESET }

    static final GeneratorSpec NONE = new GeneratorSpec(Type.NONE, "", "");
    static final String PRESET_PREFIX = "preset:";
    private static final Pattern PLUGIN = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    static final Pattern PRESET_ID = Pattern.compile("[a-z0-9_.\\-]{1,32}:[a-z0-9_./\\-]{1,64}");

    /**
     * @throws IllegalArgumentException for malformed input (bad plugin name, empty id after a colon, bad preset id)
     */
    static GeneratorSpec parse(String raw) {
        if (raw == null) return NONE;
        String text = raw.strip();
        if (text.isEmpty() || text.equalsIgnoreCase("none") || text.equalsIgnoreCase("vanilla")) return NONE;
        if (text.regionMatches(true, 0, PRESET_PREFIX, 0, PRESET_PREFIX.length())) {
            String id = text.substring(PRESET_PREFIX.length()).toLowerCase(Locale.ROOT);
            if (!PRESET_ID.matcher(id).matches()) throw new IllegalArgumentException("bad preset id: " + id);
            return new GeneratorSpec(Type.PRESET, "", id);
        }
        int colon = text.indexOf(':');
        String plugin = colon < 0 ? text : text.substring(0, colon);
        String id = colon < 0 ? "" : text.substring(colon + 1);
        if (!PLUGIN.matcher(plugin).matches()) throw new IllegalArgumentException("bad plugin name: " + plugin);
        if (colon >= 0 && id.isBlank()) throw new IllegalArgumentException("empty generator id after ':'");
        return new GeneratorSpec(Type.PLUGIN, plugin, id);
    }

    static GeneratorSpec preset(String id) { return parse(PRESET_PREFIX + id); }

    boolean none() { return type == Type.NONE; }

    /** The id passed to the plugin, or null when none was given. */
    String idOrNull() { return id.isEmpty() ? null : id; }

    /** Text as stored in worlds.yml and accepted by {@link #parse}. */
    @Override public String toString() {
        return switch (type) {
            case NONE -> "";
            case PRESET -> PRESET_PREFIX + id;
            case PLUGIN -> id.isEmpty() ? plugin : plugin + ":" + id;
        };
    }
}
