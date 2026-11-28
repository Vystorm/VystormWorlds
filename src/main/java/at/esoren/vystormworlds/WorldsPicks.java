package at.esoren.vystormworlds;

import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.PlayerSource;
import Vystorm.vystorm_core.picker.WorldPick;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Everything of the world menus that names a type of Vystorm Core 0.71.0 (player picker, world pick, pick return, chest
 * fallback with context). Only reached after {@code CoreApi.has("player-picker")}, {@code has("world-pick")} or
 * {@code has("panel-fallback-context")} ({@link WorldPanelMenus#picks()}), so the plugin still loads on Core 0.66.0 to
 * 0.70.x; there the world page offers "Set spawn here" and a typed player name instead.
 */
final class WorldsPicks {
    /** Session key of the world page's tab (screen and chest read the same). */
    static final String TAB = WorldPage.TAB_KEY;
    /** Session key of the pending changes ({@code Map<String, String>}). */
    static final String PENDING = WorldPage.PENDING_KEY;

    private WorldsPicks() {}

    /** Core's pickers are switched on ({@code pickers.enabled}). */
    static boolean available(WorldsPlugin plugin) { return plugin.core().gui().pickers().available(); }

    /** A chest fallback that gets the panel argument and the context (kept as Object by the chest code). */
    @FunctionalInterface interface Chest { void open(Player player, String arg, Object ctx); }

    static void contextFallback(PanelSpec.Builder spec, Chest chest) {
        spec.contextFallback((player, ctx) -> chest.open(player, ctx.arg(), ctx));
    }

    /** The context's session value, or null without a context. */
    static Object get(Object ctx, String key) { return ctx instanceof FallbackContext c ? c.session().get(key) : null; }

    static void put(Object ctx, String key, Object value) { if (ctx instanceof FallbackContext c) c.session().put(key, value); }

    /** Shows a returning pick's result in the chest (no chat line then); call right after the chest opened. */
    static void result(Object ctx, Consumer<Result> show) {
        if (!(ctx instanceof FallbackContext c)) return;
        c.result().ifPresent(r -> { show.accept(r); c.resultShown(); });
    }

    private static PickReturn back(WorldMenu menu, String world, Object ctx) {
        return ctx instanceof FallbackContext c ? PickReturn.panel(c) : PickReturn.panel(menu.plugin(), WorldPanelMenus.PAGE, world);
    }

    // ------------------------------------------------------------------ send players into the world

    static Result send(WorldMenu menu, PanelAction a, String world) {
        return menu.plugin().core().gui().pickers().players(a, sendPick(menu, world));
    }

    static void send(WorldMenu menu, Player player, String world, Object ctx) {
        menu.plugin().core().gui().pickers().players(menu.plugin(), player, sendPick(menu, world), back(menu, world, ctx));
    }

    private static PlayerPick sendPick(WorldMenu menu, String world) {
        var lang = menu.plugin().lang();
        return PlayerPick.builder(lang.ref("page-send-title")).subtitle(lang.ref("page-send-desc"))
                .sources(PlayerSource.ONLINE, PlayerSource.NEARBY, PlayerSource.SEARCH)
                .includeSelf(true)
                .filter((viewer, c) -> c.online())
                .multiple(20).confirmLabel(lang.ref("page-send"))
                .onPick((viewer, picks) -> {
                    List<String> names = picks.chosen().stream().filter(PickedPlayer::online).map(PickedPlayer::name).toList();
                    return menu.send(viewer, world, names);
                })
                .build();
    }

    // ------------------------------------------------------------------ spawn and border centre

    /** What a picked spot does: the viewer, the spot and the carried session of the calling panel. */
    @FunctionalInterface interface Done { Result apply(Player viewer, Location at, PanelSession session); }

    private static WorldPick spot(WorldMenu menu, String world, String title, PickKind kind, Done done) {
        var lang = menu.plugin().lang();
        return WorldPick.builder(lang.ref(title)).subtitle(lang.ref(title + "-desc")).target(kind).reach(32)
                .worlds(w -> w.getName().equalsIgnoreCase(world))
                .onPick((viewer, c) -> {
                    World w = menu.plugin().getServer().getWorld(c.target().world());
                    if (w == null) return Result.error(lang.plain(viewer, "stand-in-world", "world", world));
                    Location at = switch (c.target()) {
                        case Picked.Position p -> new Location(w, p.at().x(), p.at().y(), p.at().z(), p.yaw(), p.pitch());
                        default -> {
                            var cell = c.target().cell();
                            yield new Location(w, cell.x() + .5, cell.y() + 1, cell.z() + .5, viewer.getLocation().getYaw(), 0f);
                        }
                    };
                    return done.apply(viewer, at, c.session());
                }).build();
    }

    /** The spawn from a picked spot (screen). */
    static Result spawn(WorldMenu menu, PanelAction a, String world) {
        return menu.plugin().core().gui().pickers().world(a, spot(menu, world, "page-spawn-pick", PickKind.POSITION, (p, at, s) -> menu.spawnAt(p, world, at)));
    }

    static void spawn(WorldMenu menu, Player player, String world, Object ctx) {
        menu.plugin().core().gui().pickers().world(menu.plugin(), player,
                spot(menu, world, "page-spawn-pick", PickKind.POSITION, (p, at, s) -> menu.spawnAt(p, world, at)), back(menu, world, ctx));
    }

    /** The border centre from a picked block: a pending change on the screen (the page's Apply saves it). */
    static Result center(WorldMenu menu, PanelAction a, String world) {
        return menu.plugin().core().gui().pickers().world(a, spot(menu, world, "page-center-pick", PickKind.BLOCK, (p, at, session) -> {
            Map<String, String> pending = WorldMenu.pending(session.get(PENDING));
            Result staged = menu.stage(p, world, pending, "border-center", WorldMenu.center(at.getX(), at.getZ()));
            session.put(PENDING, pending);
            return staged;
        }));
    }

    /** The border centre from a picked block in the chest: saved at once, as every chest click. */
    static void center(WorldMenu menu, Player player, String world, Object ctx) {
        menu.plugin().core().gui().pickers().world(menu.plugin(), player, spot(menu, world, "page-center-pick", PickKind.BLOCK,
                (p, at, s) -> menu.setNow(p, world, "border-center", WorldMenu.center(at.getX(), at.getZ()))), back(menu, world, ctx));
    }

}
