package at.esoren.vystormworlds;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.List;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The ranking {@code worlds:explored} (Core 0.67.0, capability {@code placeholder-rankings}): different worlds each
 * player has entered, from {@link ExploredWorlds}. The only class that mentions Core's ranking types; it is loaded only
 * after {@link WorldsPlaceholders#rankings} checked the capability, so the plugin still loads on an older Core.
 */
final class WorldsRankings {
    static final String EXPLORED = "explored";

    private WorldsRankings() {}

    static void apply(Plugin owner, PlaceholderInterface registry, boolean on, ExploredWorlds explored, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(WorldsPlaceholders.NAMESPACE).ifPresent(ns -> ns.removeRanking(EXPLORED));
            return;
        }
        registry.register(owner, WorldsPlaceholders.NAMESPACE).ranking(EXPLORED,
                RankingInfo.of(ref.apply("rankings.explored.label"), ref.apply("rankings.explored.description")),
                n -> rows(explored, n));
    }

    private static List<RankingSource.Row> rows(ExploredWorlds explored, int n) {
        return explored.top(n).stream()
                .map(e -> new RankingSource.Row(e.getKey(), e.getValue().name, e.getValue().count()))
                .toList();
    }
}
