package at.esoren.vystormworlds;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Swaps a player's group state when they cross into a world of another group.
 *
 * <p>The live player carries a stamp (in its persistent data, saved in the same player file as the inventory) naming the
 * group its current inventory belongs to. That makes every crash point safe:</p>
 * <ol>
 *   <li>The target group's file is read first; a file from a newer version stops the swap.</li>
 *   <li>The current state is written durably to the old group's file. If that fails, nothing changes.</li>
 *   <li>Only then the target state is applied and the stamp set, both in the live player object, and the player file is
 *       saved. The player file therefore always holds an inventory together with the stamp of its own group.</li>
 * </ol>
 * A group's file is only read when a player enters that group and is always rewritten when they leave it, so a stale
 * copy left behind is never applied twice. On join the stamp is compared with the group of the player's world and a
 * missing swap is made up.
 */
final class GroupSwitcher {
    /** The live player as seen by the switcher; implemented on top of Bukkit and by fakes in tests. */
    interface LivePlayer {
        UUID id();

        /** Group of the state the player currently holds; null if never stamped. */
        String stamp();

        void stamp(String group);

        /** Captures the given parts (closing open inventories first, so the cursor is back in the inventory). */
        PlayerState capture(String group, Set<PlayerState.Part> parts);

        /** Replaces the given parts with the state; parts missing in the state are reset to the fresh defaults. */
        void apply(PlayerState state, Set<PlayerState.Part> parts);

        /** Persists the player file now (best effort). */
        void persist();
    }

    enum Outcome { SAME, ADOPTED, SWITCHED, SWITCHED_FIRST_VISIT, FAILED_SAVE, FAILED_NEWER, FAILED_APPLY }

    record Result(Outcome outcome, String from, String to, boolean brokenSetAside, String detail) {
        boolean failed() { return outcome == Outcome.FAILED_SAVE || outcome == Outcome.FAILED_NEWER || outcome == Outcome.FAILED_APPLY; }
    }

    private final StateStore store;

    GroupSwitcher(StateStore store) { this.store = store; }

    Result switchTo(LivePlayer player, String target, Set<PlayerState.Part> parts, long now) {
        String current = player.stamp();
        if (current == null || current.isBlank()) {
            player.stamp(target);
            return new Result(Outcome.ADOPTED, null, target, false, null);
        }
        if (current.equals(target)) return new Result(Outcome.SAME, current, target, false, null);
        StateStore.Loaded next = store.load(player.id(), target);
        if (next.refused() != null) return new Result(Outcome.FAILED_NEWER, current, target, false, next.refused());
        PlayerState leaving = player.capture(current, parts);
        leaving = new PlayerState(current, now, leaving.parts(), leaving.inventory(), leaving.heldSlot(), leaving.enderChest(), leaving.level(),
                leaving.progress(), leaving.totalExperience(), leaving.health(), leaving.food(), leaving.saturation(), leaving.exhaustion(), leaving.effects());
        try {
            store.save(player.id(), leaving);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.FAILED_SAVE, current, target, next.setAside(), failure.toString());
        }
        PlayerState entering = next.state() == null ? PlayerState.fresh(target) : next.state();
        try {
            player.apply(entering, parts);
        } catch (RuntimeException failure) {
            // never keep half of the new group under the old stamp: the next swap would save it into the old group's file
            try { player.apply(leaving, parts); } catch (RuntimeException ignored) { /* leaving was just captured from this player */ }
            return new Result(Outcome.FAILED_APPLY, current, target, next.setAside(), failure.toString());
        }
        player.stamp(target);
        player.persist();
        return new Result(next.state() == null ? Outcome.SWITCHED_FIRST_VISIT : Outcome.SWITCHED, current, target, next.setAside(), null);
    }
}
