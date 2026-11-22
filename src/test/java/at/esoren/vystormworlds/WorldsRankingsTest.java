package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ranking worlds:explored (1.19.0, Core 0.67.0) and its store explored.yml. */
class WorldsRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID KIM = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    @TempDir Path dir;
    private ExploredWorlds explored;
    private PlaceholderTesting placeholders;
    private Plugin plugin;

    @BeforeEach void fixture() throws Exception {
        explored = new ExploredWorlds(dir.resolve("explored.yml"), AtomicFilesInterface.DEFAULT);
        explored.record(ALEX, "Alex", "world");
        explored.record(ALEX, "Alex", "Lobby");
        explored.record(ALEX, "Alex", "lobby");          // the same world again counts once
        explored.record(SAM, "Sam", "world");
        explored.record(KIM, "Kim", "world");
        explored.record(KIM, "Kim", "nether");
        explored.record(KIM, "Kim", "mining");
        explored.write(explored.takeChanges());
        // a restart: nobody is online, the counts come from the file
        explored = new ExploredWorlds(dir.resolve("explored.yml"), AtomicFilesInterface.DEFAULT);
        placeholders = PlaceholderTesting.detached();
        plugin = PlaceholderTesting.plugin("VystormWorlds");
    }

    private void apply(boolean core, boolean on) {
        WorldsPlaceholders.rankings(placeholders.placeholders(), plugin, core, on, explored, null, null);
    }

    private List<String> ids() { return placeholders.rankings().stream().map(PlaceholderRanking::id).toList(); }

    @Test void givenTheFixture_whenDeclared_thenOrderValuesAndOfflinePlayersFromTheFile() {
        apply(true, true);
        assertEquals(List.of("worlds:explored"), ids());
        List<RankingSource.Row> rows = placeholders.top("worlds:explored", 10);
        assertEquals(List.of(KIM, ALEX, SAM), rows.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(3.0, 2.0, 1.0), rows.stream().map(RankingSource.Row::value).toList());
        assertEquals(List.of("Kim", "Alex", "Sam"), rows.stream().map(RankingSource.Row::name).toList());
        assertEquals("Kim", placeholders.resolve(null, "%vystorm_worlds_top_explored_1_name%"));
        assertEquals("3", placeholders.resolve(null, "{worlds:top_explored:1:value}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_worlds_top_explored_4_name%"));
        assertEquals("rankings.explored.label", placeholders.ranking("worlds:explored").orElseThrow().info().label());
        assertEquals("DESCENDING", placeholders.ranking("worlds:explored").orElseThrow().info().order().name());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenANewWorld_whenRecorded_thenTheRankingFollowsAndOnlyANewWorldMarksAChange() {
        apply(true, true);
        assertNull(explored.takeChanges(), "nothing changed since the restart");
        assertFalse(explored.record(SAM, "Sam", "world"));
        assertNull(explored.takeChanges(), "a world entered before writes nothing");
        assertTrue(explored.record(SAM, "Sam", "end"));
        assertTrue(explored.record(SAM, "Sam", "lobby"));
        assertTrue(explored.record(SAM, "Sam", "mining"));
        placeholders.advanceTicks(1);
        assertEquals(SAM, placeholders.top("worlds:explored", 1).getFirst().player());
        assertNotNull(explored.takeChanges());
        assertNull(explored.takeChanges(), "one write for many changes");
    }

    @Test void givenRecords_whenNothingIsFlushed_thenTheFileIsNotWrittenPerEvent() throws Exception {
        String before = Files.readString(dir.resolve("explored.yml"), StandardCharsets.UTF_8);
        explored.record(UUID.randomUUID(), "New", "world");
        assertEquals(before, Files.readString(dir.resolve("explored.yml"), StandardCharsets.UTF_8));
    }

    @Test void givenAnOlderSnapshot_whenWrittenAfterANewerOne_thenItIsSkipped() throws Exception {
        explored.record(SAM, "Sam", "end");
        ExploredWorlds.Snapshot older = explored.takeChanges();
        explored.record(SAM, "Sam", "lobby");
        explored.write(explored.takeChanges());
        explored.write(older);
        assertEquals(3, new ExploredWorlds(dir.resolve("explored.yml"), AtomicFilesInterface.DEFAULT).count(SAM));
    }

    @Test void givenRankingsSwitchedOff_whenApplied_thenNotDeclaredAndAReloadRemovesIt() {
        apply(true, false);
        assertEquals(List.of(), ids());
        apply(true, true);
        assertEquals(1, ids().size());
        apply(true, false);
        assertEquals(List.of(), ids());
    }

    @Test void givenACoreWithoutTheCapability_whenApplied_thenNoDeclarationAndNoError() {
        assertDoesNotThrow(() -> apply(false, true));
        assertEquals(List.of(), ids());
        assertDoesNotThrow(() -> WorldsPlaceholders.rankings(null, plugin, true, true, explored, null, null));
    }

    @Test void givenTheNamespaceOwnedByAnotherPlugin_whenApplied_thenRefusedQuietly() {
        placeholders.register(PlaceholderTesting.plugin("Other"), "worlds");
        assertDoesNotThrow(() -> apply(true, true));
        assertEquals(List.of(), ids());
    }

    @Test void givenABrokenFile_whenLoaded_thenSetAsideNeverOverwrittenAndNothingCounted() throws Exception {
        Path file = dir.resolve("broken").resolve("explored.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "players: [unclosed", StandardCharsets.UTF_8);
        ExploredWorlds store = new ExploredWorlds(file, AtomicFilesInterface.DEFAULT);
        assertNotNull(store.problem());
        assertFalse(store.record(ALEX, "Alex", "world"));
        assertNull(store.takeChanges());
        try (var listing = Files.list(file.getParent())) { assertEquals(1, listing.count(), "the broken file is kept"); }
    }

    @Test void givenTheFile_whenRead_thenItHoldsNamesAndWorldNamesButNoPositions() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dir.resolve("explored.yml").toFile());
        assertEquals(1, yaml.getInt("format"));
        assertEquals(List.of("lobby", "world"), yaml.getStringList("players." + ALEX + ".worlds"));
        assertEquals("Alex", yaml.getString("players." + ALEX + ".name"));
        assertEquals(java.util.Set.of("name", "worlds"), yaml.getConfigurationSection("players." + ALEX).getKeys(false));
    }

    @Test void givenEveryLanguage_whenRead_thenTheRankingAndItsSwitchHaveTexts() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("rankings.explored.label", "").isBlank(), code + " label");
            assertFalse(lang.getString("rankings.explored.description", "").isBlank(), code + " description");
            assertFalse(lang.getString(Features.labelKey(Features.RANKINGS), "").isBlank(), code + " switch name");
            assertFalse(lang.getString(Features.descriptionKey(Features.RANKINGS), "").isBlank(), code + " switch description");
        }
        assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).getBoolean(Features.RANKINGS, false));
    }
}
