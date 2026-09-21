// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PAPI-экспаншн (1.1.4): полный набор плейсхолдеров.
 *
 * Персональные (требуют игрока):
 *   %raskolvault_balance_<cur>%
 *
 * Глобальные (не требуют игрока):
 *   %raskolvault_reserve_<nation>%
 *   %raskolvault_price_<cur>%
 *   %raskolvault_coverage_<nation>%
 *   %raskolvault_parity_<nation>%
 *   %raskolvault_tax_<nation>%
 *   %raskolvault_supply_<cur>%
 *   %raskolvault_writer_queue%
 *   %raskolvault_writer_failed%
 *   %raskolvault_tx_per_min%
 */
public final class PlaceholderApiHook extends PlaceholderExpansion {

    private final RaskolVault plugin;

    public PlaceholderApiHook(RaskolVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "raskolvault";
    }

    @Override
    public @NotNull String getAuthor() {
        return "hayferdahmer";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        return resolve(player, identifier);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String identifier) {
        return resolve(player == null ? null : player.getPlayer(), identifier);
    }

    private String resolve(@Nullable Player player, String identifier) {
        String id = identifier.toLowerCase(Locale.ROOT);
        try {
            // Глобальные: writer/tx
            if (id.equals("writer_queue")) return String.valueOf(plugin.getWriter().queueSize());
            if (id.equals("writer_failed")) return String.valueOf(plugin.getWriter().failed());
            if (id.equals("tx_per_min")) return String.valueOf(plugin.getTxCounter().perMinute());

            int underscore = id.indexOf('_');
            if (underscore < 0) return null;
            String kind = id.substring(0, underscore);
            String arg = id.substring(underscore + 1);
            if (arg.isEmpty()) return null;

            switch (kind) {
                case "balance" -> {
                    if (player == null) return "0.00";
                    Currency cur = plugin.getCurrencies().get(arg.toUpperCase(Locale.ROOT)).orElse(null);
                    if (cur == null) return "0.00";
                    return String.format(Locale.ROOT, "%." + cur.decimals() + "f",
                            plugin.getWallets().getBalance(player.getUniqueId(), cur.id()));
                }
                case "reserve" -> {
                    return String.format(Locale.ROOT, "%.2f", plugin.getReserveBank().reserveOf(arg));
                }
                case "price" -> {
                    Currency cur = plugin.getCurrencies().get(arg.toUpperCase(Locale.ROOT)).orElse(null);
                    if (cur == null) return "0.0000";
                    return String.format(Locale.ROOT, "%.4f", plugin.getReserveBank().priceOf(cur));
                }
                case "coverage" -> {
                    Currency national = nationalOf(arg);
                    if (national == null) return "100.0%";
                    double cov = plugin.getReserveBank().coverageOf(arg, national.id());
                    return String.format(Locale.ROOT, "%.1f%%", cov * 100.0D);
                }
                case "parity" -> {
                    return String.format(Locale.ROOT, "%.2f", plugin.getReserveBank().parityOf(arg));
                }
                case "tax" -> {
                    return String.format(Locale.ROOT, "%.1f%%", plugin.getReserveBank().taxOf(arg) * 100.0D);
                }
                case "supply" -> {
                    Currency cur = plugin.getCurrencies().get(arg.toUpperCase(Locale.ROOT)).orElse(null);
                    if (cur == null) return "0.00";
                    return String.format(Locale.ROOT, "%.2f", plugin.getReserveBank().supplyOf(cur.id()));
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("RaskolVault: PAPI ошибка для %" + identifier + "%: " + t.getMessage());
            return null;
        }
    }

    private Currency nationalOf(String nation) {
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(c.nationId())) {
                return c;
            }
        }
        return null;
    }
}
