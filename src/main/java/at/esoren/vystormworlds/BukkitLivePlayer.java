package at.esoren.vystormworlds;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** {@link GroupSwitcher.LivePlayer} for a real player; the stamp lives in the player's persistent data. */
final class BukkitLivePlayer implements GroupSwitcher.LivePlayer {
    private final Player player;
    private final NamespacedKey stampKey;

    private final Vystorm.vystorm_core.compat.CompatInterface compat;

    /** @param compat Core's compatibility manager, {@code api.compat()} */
    BukkitLivePlayer(Player player, NamespacedKey stampKey, Vystorm.vystorm_core.compat.CompatInterface compat) { this.player = player; this.stampKey = stampKey; this.compat = compat; }

    static String encode(Vystorm.vystorm_core.compat.ItemCompatInterface codec, ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? new ItemStack(org.bukkit.Material.AIR) : items[i];
        return Base64.getEncoder().encodeToString(codec.serialize(copy));
    }

    static ItemStack[] decode(Vystorm.vystorm_core.compat.ItemCompatInterface codec, String data) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        return codec.deserialize(Base64.getDecoder().decode(data));
    }

    @Override public UUID id() { return player.getUniqueId(); }

    @Override public String stamp() { return player.getPersistentDataContainer().get(stampKey, PersistentDataType.STRING); }

    @Override public void stamp(String group) { player.getPersistentDataContainer().set(stampKey, PersistentDataType.STRING, group); }

    @Override
    public PlayerState capture(String group, Set<PlayerState.Part> parts) {
        player.closeInventory();   // cursor and crafting grid go back into the inventory
        List<PlayerState.Effect> effects = new ArrayList<>();
        for (PotionEffect e : player.getActivePotionEffects())
            effects.add(new PlayerState.Effect(e.getType().getKey().toString(), e.getDuration(), e.getAmplifier(), e.isAmbient(), e.hasParticles(), e.hasIcon()));
        return new PlayerState(group, System.currentTimeMillis(), parts,
                parts.contains(PlayerState.Part.INVENTORY) ? encode(compat.items(), player.getInventory().getContents()) : "",
                player.getInventory().getHeldItemSlot(),
                parts.contains(PlayerState.Part.ENDER_CHEST) ? encode(compat.items(), player.getEnderChest().getContents()) : "",
                player.getLevel(), player.getExp(), player.getTotalExperience(), player.getHealth(), player.getFoodLevel(),
                player.getSaturation(), player.getExhaustion(), effects);
    }

    @Override
    public void apply(PlayerState state, Set<PlayerState.Part> parts) {
        PlayerState fresh = PlayerState.fresh(state.group());
        // decode everything before changing anything, so unreadable items fail the swap without a half-applied state
        PlayerState inventoryState = state.has(PlayerState.Part.INVENTORY) ? state : fresh;
        PlayerState enderState = state.has(PlayerState.Part.ENDER_CHEST) ? state : fresh;
        ItemStack[] savedInventory = parts.contains(PlayerState.Part.INVENTORY) ? decode(compat.items(), inventoryState.inventory()) : null;
        ItemStack[] savedEnder = parts.contains(PlayerState.Part.ENDER_CHEST) ? decode(compat.items(), enderState.enderChest()) : null;
        if (parts.contains(PlayerState.Part.INVENTORY)) {
            PlayerState s = inventoryState;
            ItemStack[] saved = savedInventory;
            ItemStack[] contents = new ItemStack[player.getInventory().getSize()];
            for (int i = 0; i < contents.length && i < saved.length; i++) contents[i] = saved[i] == null || compat.platform().isEmpty(saved[i]) ? null : saved[i];
            player.setItemOnCursor(null);
            player.getInventory().setContents(contents);
            player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, s.heldSlot())));
        }
        if (parts.contains(PlayerState.Part.ENDER_CHEST)) {
            ItemStack[] saved = savedEnder;
            ItemStack[] contents = new ItemStack[player.getEnderChest().getSize()];
            for (int i = 0; i < contents.length && i < saved.length; i++) contents[i] = saved[i] == null || compat.platform().isEmpty(saved[i]) ? null : saved[i];
            player.getEnderChest().setContents(contents);
        }
        if (parts.contains(PlayerState.Part.EXPERIENCE)) {
            PlayerState s = state.has(PlayerState.Part.EXPERIENCE) ? state : fresh;
            player.setLevel(Math.max(0, s.level()));
            player.setExp(Math.max(0f, Math.min(0.9999f, s.progress())));
            player.setTotalExperience(Math.max(0, s.totalExperience()));
        }
        if (parts.contains(PlayerState.Part.HEALTH)) {
            PlayerState s = state.has(PlayerState.Part.HEALTH) ? state : fresh;
            player.setHealth(Math.max(0.5, Math.min(maxHealth(), s.health())));
        }
        if (parts.contains(PlayerState.Part.HUNGER)) {
            PlayerState s = state.has(PlayerState.Part.HUNGER) ? state : fresh;
            player.setFoodLevel(Math.max(0, Math.min(20, s.food())));
            player.setSaturation(Math.max(0f, s.saturation()));
            player.setExhaustion(Math.max(0f, s.exhaustion()));
        }
        if (parts.contains(PlayerState.Part.EFFECTS)) {
            PlayerState s = state.has(PlayerState.Part.EFFECTS) ? state : fresh;
            for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
            for (PlayerState.Effect e : s.effects()) {
                NamespacedKey key = NamespacedKey.fromString(e.type());
                PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
                if (type != null) player.addPotionEffect(new PotionEffect(type, e.duration(), e.amplifier(), e.ambient(), e.particles(), e.icon()));
            }
        }
    }

    private double maxHealth() {
        try {
            var attribute = player.getAttribute(compat.server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH"));
            return attribute == null ? 20 : attribute.getValue();
        } catch (RuntimeException | LinkageError unavailable) { return 20; }
    }

    @Override public void persist() {
        try { player.saveData(); } catch (RuntimeException ignored) { /* the server saves the player later anyway */ }
    }
}
