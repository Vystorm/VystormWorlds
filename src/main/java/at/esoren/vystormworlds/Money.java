package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._integrations.vault.VaultInterface;
import org.bukkit.OfflinePlayer;

/** Entry prices through Vystorm Core's Vault bridge. Without an economy every world is free. */
final class Money {
    private Money() {}

    private static VaultInterface vault() {
        try {
            VaultInterface vault = CoreApi.api().map(api -> api.vault()).orElse(null);
            return vault != null && vault.isEconomyAvailable() ? vault : null;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    static boolean available() { return vault() != null; }

    static boolean has(OfflinePlayer player, double amount) {
        VaultInterface vault = vault();
        try { return vault != null && vault.has(player, amount); } catch (RuntimeException failure) { return false; }
    }

    static boolean withdraw(OfflinePlayer player, double amount) {
        VaultInterface vault = vault();
        if (vault == null) return false;
        try { return vault.withdraw(player, amount).successful(); } catch (RuntimeException failure) { return false; }
    }

    static String format(double amount) {
        VaultInterface vault = vault();
        try { if (vault != null) return vault.format(amount); } catch (RuntimeException ignored) { }
        return String.format(java.util.Locale.ROOT, "%.2f", amount);
    }
}
