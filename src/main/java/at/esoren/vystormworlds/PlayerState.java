package at.esoren.vystormworlds;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The part of a player that belongs to one world group. Items are already encoded (Base64 of Paper's item bytes),
 * so this record can be stored and tested without a server. Only the parts in {@link #parts} were captured.
 */
record PlayerState(String group, long savedAt, Set<Part> parts, String inventory, int heldSlot, String enderChest,
                   int level, float progress, int totalExperience, double health, int food, float saturation, float exhaustion,
                   List<Effect> effects) {

    enum Part {
        INVENTORY, ENDER_CHEST, EXPERIENCE, HEALTH, HUNGER, EFFECTS;

        String id() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

        static Part parse(String raw) {
            if (raw == null) return null;
            for (Part part : values()) if (part.id().equalsIgnoreCase(raw.strip()) || part.name().equalsIgnoreCase(raw.strip())) return part;
            return null;
        }
    }

    record Effect(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}

    PlayerState {
        parts = parts == null || parts.isEmpty() ? EnumSet.noneOf(Part.class) : EnumSet.copyOf(parts);
        inventory = inventory == null ? "" : inventory;
        enderChest = enderChest == null ? "" : enderChest;
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    /** What a player gets on the first visit to a group: nothing in the inventory, full health and food. */
    static PlayerState fresh(String group) {
        return new PlayerState(group, 0, EnumSet.allOf(Part.class), "", 0, "", 0, 0f, 0, 20.0, 20, 5f, 0f, List.of());
    }

    boolean has(Part part) { return parts.contains(part); }
}
