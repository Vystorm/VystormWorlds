package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupSwitchTest {
    static final Set<PlayerState.Part> ALL = EnumSet.allOf(PlayerState.Part.class);

    @TempDir Path dir;

    /** In-memory player: "inventory" is a plain string standing for the encoded items. */
    static class FakePlayer implements GroupSwitcher.LivePlayer {
        final UUID id = UUID.randomUUID();
        String stamp, inventory = "", enderChest = "";
        int level;
        double health = 20;
        int food = 20;
        List<PlayerState.Effect> effects = List.of();
        int persisted;

        @Override public UUID id() { return id; }
        @Override public String stamp() { return stamp; }
        @Override public void stamp(String group) { stamp = group; }

        @Override public PlayerState capture(String group, Set<PlayerState.Part> parts) {
            return new PlayerState(group, 0, parts, inventory, 3, enderChest, level, 0.5f, level * 10, health, food, 4f, 1f, effects);
        }

        @Override public void apply(PlayerState s, Set<PlayerState.Part> parts) {
            PlayerState fresh = PlayerState.fresh(s.group());
            if (parts.contains(PlayerState.Part.INVENTORY)) inventory = (s.has(PlayerState.Part.INVENTORY) ? s : fresh).inventory();
            if (parts.contains(PlayerState.Part.ENDER_CHEST)) enderChest = (s.has(PlayerState.Part.ENDER_CHEST) ? s : fresh).enderChest();
            if (parts.contains(PlayerState.Part.EXPERIENCE)) level = (s.has(PlayerState.Part.EXPERIENCE) ? s : fresh).level();
            if (parts.contains(PlayerState.Part.HEALTH)) health = (s.has(PlayerState.Part.HEALTH) ? s : fresh).health();
            if (parts.contains(PlayerState.Part.HUNGER)) food = (s.has(PlayerState.Part.HUNGER) ? s : fresh).food();
            if (parts.contains(PlayerState.Part.EFFECTS)) effects = (s.has(PlayerState.Part.EFFECTS) ? s : fresh).effects();
        }

        @Override public void persist() { persisted++; }
    }

    private GroupSwitcher switcher() { return new GroupSwitcher(new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT)); }

    @Test
    void firstContactAdoptsTheCurrentInventory() {
        FakePlayer p = new FakePlayer();
        p.inventory = "diamonds";
        GroupSwitcher.Result r = switcher().switchTo(p, "default", ALL, 1);
        assertEquals(GroupSwitcher.Outcome.ADOPTED, r.outcome());
        assertEquals("default", p.stamp);
        assertEquals("diamonds", p.inventory);
        assertEquals(GroupSwitcher.Outcome.SAME, switcher().switchTo(p, "default", ALL, 2).outcome());
    }

    @Test
    void switchingThereAndBackRestoresEverything() {
        GroupSwitcher switcher = switcher();
        FakePlayer p = new FakePlayer();
        p.stamp = "survival";
        p.inventory = "diamonds";
        p.enderChest = "elytra";
        p.level = 30;
        p.health = 7;
        p.food = 3;
        p.effects = List.of(new PlayerState.Effect("minecraft:speed", 200, 1, false, true, true));
        GroupSwitcher.Result first = switcher.switchTo(p, "creative", ALL, 1);
        assertEquals(GroupSwitcher.Outcome.SWITCHED_FIRST_VISIT, first.outcome());
        assertEquals("", p.inventory);
        assertEquals("", p.enderChest);
        assertEquals(0, p.level);
        assertEquals(20, p.health);
        assertEquals(20, p.food);
        assertTrue(p.effects.isEmpty());
        assertEquals("creative", p.stamp);
        assertEquals(1, p.persisted);

        p.inventory = "creative-stuff";
        GroupSwitcher.Result back = switcher.switchTo(p, "survival", ALL, 2);
        assertEquals(GroupSwitcher.Outcome.SWITCHED, back.outcome());
        assertEquals("diamonds", p.inventory);
        assertEquals("elytra", p.enderChest);
        assertEquals(30, p.level);
        assertEquals(7, p.health);
        assertEquals(3, p.food);
        assertEquals("minecraft:speed", p.effects.getFirst().type());

        switcher.switchTo(p, "creative", ALL, 3);
        assertEquals("creative-stuff", p.inventory);
    }

    @Test
    void onlyTheSeparatedPartsAreSwapped() {
        GroupSwitcher switcher = switcher();
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        p.level = 12;
        Set<PlayerState.Part> parts = EnumSet.of(PlayerState.Part.INVENTORY);
        switcher.switchTo(p, "b", parts, 1);
        assertEquals("", p.inventory);
        assertEquals(12, p.level, "XP is shared when not separated");
    }

    @Test
    void failedSaveChangesNothing() throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a folder");
        GroupSwitcher switcher = new GroupSwitcher(new StateStore(blocked, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        GroupSwitcher.Result r = switcher.switchTo(p, "b", ALL, 1);
        assertEquals(GroupSwitcher.Outcome.FAILED_SAVE, r.outcome());
        assertTrue(r.failed());
        assertEquals("sword", p.inventory);
        assertEquals("a", p.stamp);
        assertEquals(0, p.persisted);
    }

    @Test
    void newerFileFormatIsRefusedAndLeftAlone() throws Exception {
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        Path file = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).file(p.id, "b");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "format: 99\ngroup: b\n", StandardCharsets.UTF_8);
        GroupSwitcher.Result r = switcher().switchTo(p, "b", ALL, 1);
        assertEquals(GroupSwitcher.Outcome.FAILED_NEWER, r.outcome());
        assertEquals("sword", p.inventory);
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).file(p.id, "a")), "nothing written when the swap is refused");
    }

    @Test
    void brokenTargetFileIsSetAsideNotDeleted() throws Exception {
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        Path file = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).file(p.id, "b");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "format: 1\ngroup: b\nparts: [inventory]\n", StandardCharsets.UTF_8);   // inventory missing
        GroupSwitcher.Result r = switcher().switchTo(p, "b", ALL, 1);
        assertTrue(r.brokenSetAside());
        assertEquals(GroupSwitcher.Outcome.SWITCHED_FIRST_VISIT, r.outcome());
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("b.yml.broken-")));
        }
        assertEquals("sword", new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).load(p.id, "a").state().inventory(), "the old group was saved before the swap");
    }

    @Test
    void brokenTargetFileThatCannotBeSetAsideRefusesTheSwap() throws Exception {
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        Path file = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).file(p.id, "b");
        Files.createDirectories(file.getParent());
        String broken = "format: 1\ngroup: b\nparts: [inventory]\n";
        Files.writeString(file, broken, StandardCharsets.UTF_8);
        // java.io streams lock the file against renaming on Windows; elsewhere the move works and this case cannot happen
        try (java.io.FileInputStream lock = new java.io.FileInputStream(file.toFile())) {
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
            GroupSwitcher.Result r = switcher().switchTo(p, "b", ALL, 1);
            assertEquals(GroupSwitcher.Outcome.FAILED_NEWER, r.outcome());
            assertEquals("sword", p.inventory);
            assertEquals("a", p.stamp, "the player stays in the old group, so the fresh state is never saved over the file");
        }
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void crashBeforeTheSwapIsAppliedLosesNothing() {
        // The leaving group's file is written first. If the server dies before the new state reaches the player file,
        // the player comes back with the old inventory and the old stamp: consistent, and the file holds the same items.
        StateStore store = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        FakePlayer p = new FakePlayer();
        p.stamp = "a";
        p.inventory = "sword";
        FakePlayer crashed = new FakePlayer() {
            @Override public void apply(PlayerState s, Set<PlayerState.Part> parts) { throw new IllegalStateException("crash"); }
        };
        crashed.stamp = "a";
        crashed.inventory = "sword";
        try { new GroupSwitcher(store).switchTo(crashed, "b", ALL, 1); } catch (IllegalStateException expected) { }
        assertEquals("a", crashed.stamp, "stamp only changes after the new state is applied");
        assertEquals("sword", crashed.inventory);
        StateStore.Loaded saved = store.load(crashed.id, "a");
        assertNotNull(saved.state());
        assertEquals("sword", saved.state().inventory());
        // next attempt (e.g. on join) works normally and does not duplicate: b is fresh, a holds the sword once
        new GroupSwitcher(store).switchTo(p, "b", ALL, 2);
        assertEquals("", p.inventory);
    }

    @Test
    void partlyAppliedStateIsRolledBackSoNothingIsDuplicated() throws Exception {
        // The target file reads fine, but part of it cannot be applied (e.g. items that no longer decode). The swap must
        // not leave the player with half of the new group under the old stamp: the next swap would then save the new
        // group's items into the old group's file (duplicate) and overwrite the old group's real items (loss).
        StateStore store = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        GroupSwitcher switcher = new GroupSwitcher(store);
        FakePlayer p = new FakePlayer() {
            @Override public void apply(PlayerState s, Set<PlayerState.Part> parts) {
                super.apply(s, parts);
                if (s.enderChest().equals("corrupt")) throw new IllegalArgumentException("cannot decode ender chest");
            }
        };
        p.stamp = "a";
        p.inventory = "sword";
        p.enderChest = "elytra";
        store.save(p.id, new PlayerState("b", 1, ALL, "b-diamonds", 0, "corrupt", 0, 0f, 0, 20, 20, 5f, 0f, List.of()));
        GroupSwitcher.Result r = switcher.switchTo(p, "b", ALL, 2);
        assertTrue(r.failed());
        assertEquals("a", p.stamp);
        assertEquals("sword", p.inventory, "the old group's items are back in the live inventory");
        assertEquals("elytra", p.enderChest);
        assertEquals("sword", store.load(p.id, "a").state().inventory());
        assertEquals("b-diamonds", store.load(p.id, "b").state().inventory(), "the target file is left as it was");
    }

    @Test
    void storeRoundTripAndMissingFile() throws Exception {
        StateStore store = new StateStore(dir, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID id = UUID.randomUUID();
        assertNull(store.load(id, "x").state());
        PlayerState s = new PlayerState("x", 5, ALL, "inv", 4, "ender", 3, 0.25f, 40, 12.5, 8, 2f, 0.5f,
                List.of(new PlayerState.Effect("minecraft:haste", 100, 2, true, false, true)));
        store.save(id, s);
        PlayerState back = store.load(id, "x").state();
        assertEquals(s, back);
        assertEquals(List.of("x"), store.groups(id));
        PlayerState partial = new PlayerState("y", 5, EnumSet.of(PlayerState.Part.EXPERIENCE), "", 0, "", 9, 0f, 90, 20, 20, 5f, 0f, List.of());
        store.save(id, partial);
        PlayerState partialBack = store.load(id, "y").state();
        assertFalse(partialBack.has(PlayerState.Part.INVENTORY));
        assertEquals(9, partialBack.level());
    }

    @Test
    void partsParseFromConfigNames() {
        assertEquals(PlayerState.Part.ENDER_CHEST, PlayerState.Part.parse("ender-chest"));
        assertEquals(PlayerState.Part.EXPERIENCE, PlayerState.Part.parse("EXPERIENCE"));
        assertNull(PlayerState.Part.parse("gamemode"));
    }
}
