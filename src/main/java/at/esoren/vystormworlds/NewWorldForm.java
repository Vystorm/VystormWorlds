package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The "New world" form of the menu (1.21.0): the same options as {@code /worlds create NAME ...} and the create dialog.
 * Pure: turns the typed fields into the command's option words, so the menu, the dialog and the command share one
 * parser ({@link CreateRequest#parse}) and the menu can show the exact command ("copy as command").
 */
record NewWorldForm(String name, String type, String generator, String generatorId, String seed, boolean structures,
                    boolean nether, boolean end, String group, String minY, String height) {

    /** Field id of the lowest build level (the custom height pair). */
    static final String HEIGHT_FIELD = "min-y";

    /** The field the problem belongs to, the language key and the offending word; {@code request} when it is fine. */
    record Check(String field, String error, String token, CreateRequest request) {
        boolean ok() { return error == null; }
    }

    NewWorldForm {
        name = clean(name);
        type = clean(type).isEmpty() ? "normal" : clean(type).toLowerCase(Locale.ROOT);
        generator = clean(generator).isEmpty() ? "none" : clean(generator);
        generatorId = clean(generatorId);
        seed = clean(seed);
        group = clean(group);
        minY = clean(minY);
        height = clean(height);
    }

    static NewWorldForm defaults(String group) {
        return new NewWorldForm("", "normal", "none", "", "", true, false, false, group, "", "");
    }

    private static String clean(String s) { return s == null ? "" : s.strip(); }

    /**
     * The form after a submit or a single changed field: {@code values} holds the screen's field ids ({@code name},
     * {@code type}, ...); missing fields keep the current value.
     */
    NewWorldForm with(Map<String, ?> values) {
        return new NewWorldForm(text(values, "name", name), text(values, "type", type), text(values, "generator", generator),
                text(values, "generator-id", generatorId), text(values, "seed", seed), bool(values, "structures", structures),
                bool(values, "nether", nether), bool(values, "end", end), text(values, "group", group),
                number(values, "min-y", minY), number(values, "height", height));
    }

    private static String text(Map<String, ?> values, String key, String fallback) {
        Object v = values.get(key);
        return v == null ? fallback : v.toString();
    }

    private static boolean bool(Map<String, ?> values, String key, boolean fallback) {
        Object v = values.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        Boolean parsed = Properties.bool(v.toString().toLowerCase(Locale.ROOT));
        return parsed == null ? fallback : parsed;
    }

    /** Screen number fields send doubles ("-128.0"): keep whole numbers as integers, the rest as typed. */
    private static String number(Map<String, ?> values, String key, String fallback) {
        Object v = values.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue() == Math.rint(n.doubleValue()) ? Long.toString(n.longValue()) : n.toString();
        return v.toString();
    }

    /** The option words of {@code /worlds create} for this form, in the order the create dialog has always used. */
    List<String> tokens() {
        List<String> tokens = new ArrayList<>();
        tokens.add(type);
        if (!generator.isEmpty() && !generator.equals("none"))
            tokens.add("generator:" + generator + (generatorId.isEmpty() || generator.startsWith(GeneratorSpec.PRESET_PREFIX) ? "" : ":" + generatorId));
        else if (!generatorId.isEmpty()) tokens.add("generator:" + generatorId);
        if (!seed.isEmpty()) tokens.add("seed:" + seed);
        if (!structures) tokens.add("structures:false");
        if (nether) tokens.add("with-nether");
        if (end) tokens.add("with-end");
        if (!group.isEmpty()) tokens.add("group:" + group);
        if (!minY.isEmpty()) tokens.add("min-y:" + minY);
        if (!height.isEmpty()) tokens.add("height:" + height);
        return tokens;
    }

    /** The exact command this form runs: {@code /worlds create farm normal seed:42 with-nether group:default}. */
    String command() {
        return "/worlds create " + (name.isEmpty() ? "NAME" : name.toLowerCase(Locale.ROOT)) + " " + String.join(" ", tokens());
    }

    /**
     * Checks the form like the command would, before anything is created.
     *
     * @param nameProblem the manager's name check ({@code bad-name}, {@code exists}, {@code folder-exists}) or null
     * @param buildHeight whether {@code build-height.enabled} is on
     */
    Check check(String defaultGroup, boolean buildHeight, Function<String, String> nameProblem) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 32 || !WorldSettings.validName(lower)) return new Check("name", "bad-name", name, null);
        if (WorldKind.parse(type) == null) return new Check("type", "bad-option", type, null);
        if (seed.contains(" ")) return new Check("seed", "bad-option", "seed:" + seed, null);
        if ((!minY.isEmpty() || !height.isEmpty()) && !buildHeight) return new Check(HEIGHT_FIELD, Features.OFF, "", null);
        CreateRequest.Parsed parsed = CreateRequest.parse(lower, tokens(), defaultGroup);
        if (parsed.error() != null) return new Check(fieldOf(parsed.error(), parsed.token()), parsed.error(), parsed.token(), null);
        String problem = nameProblem.apply(lower);
        if (problem == null && nether) problem = nameProblem.apply(lower + "_nether");
        if (problem == null && end) problem = nameProblem.apply(lower + "_the_end");
        if (problem != null) return new Check("name", problem, lower, null);
        return new Check(null, null, null, parsed.request());
    }

    /** Which field a parser problem points at, so the screen can mark it. */
    static String fieldOf(String error, String token) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        if (error.startsWith("height") || t.startsWith("min-y") || t.startsWith("height")) return HEIGHT_FIELD;
        if (error.equals("bad-group") || t.startsWith("group:")) return "group";
        if (t.startsWith("seed:")) return "seed";
        if (t.startsWith("generator:") || t.startsWith("biomes:")) return "generator";
        if (error.equals("links-overworld")) return "type";
        return "type";
    }
}
