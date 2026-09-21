// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.util.Formatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PAPI-экспаншн RaskolVault.
 * 1.1.0-d: добавлены плейсхолдеры курсов/покрытия/цены.
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
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        String lower = params.toLowerCase(Locale.ROOT);

        switch (lower) {
            case "tps":
                return tps(0);
            case "tps_5m":
                return tps(1);
            case "tps_15m":
                return tps(2);
            case "ledger_queue":
                return Integer.toString(plugin.getWriter().queueSize());
            case "writer_applied":
                return Long.toString(plugin.getWriter().applied());
            case "writer_failed":
                return Long.toString(plugin.getWriter().failed());
            case "pool_idle":
                return Integer.toString(plugin.getLedger().poolIdle());
            case "pool_wait":
                return Integer.toString(plugin.getLedger().poolWaiting());
            case "cache_hit":
                return String.format(Locale.ROOT, "%.1f", plugin.getWallets().cacheHitRate());
            case "currencies_count":
                return Integer.toString(plugin.getCurrencies().all().size());
            case "rates_count":
                return Integer.toString(plugin.getRates().allRates().size());
            default:
                break;
        }

        // Плейсхолдеры курсов: %raskolvault_rate_<from>_<to>%
        if (lower.startsWith("rate_")) {
            String[] parts = lower.substring(5).split("_");
            if (parts.length == 2) {
                String from = parts[0].toUpperCase(Locale.ROOT);
                String to = parts[1].toUpperCase(Locale.ROOT);
                double rate = plugin.getConvertEngine().rate(from, to);
                return String.format(Locale.ROOT, "%.4f", rate);
            }
        }

        // Плейсхолдеры цены: %raskolvault_price_<currency>%
        if (lower.startsWith("price_")) {
            String id = lower.substring(6).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(id).orElse(null);
            if (currency == null) {
                return "?";
            }
            return String.format(Locale.ROOT, "%.4f", plugin.getReserveBank().priceOf(currency));
        }

        // Плейсхолдеры покрытия: %raskolvault_coverage_<nation>%
        if (lower.startsWith("coverage_")) {
            String nation = lower.substring(9);
            Currency national = null;
            for (Currency c : plugin.getCurrencies().all()) {
                if (c.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(c.nationId())) {
                    national = c;
                }
            }
            if (national == null) {
                return "?";
            }
            double coverage = plugin.getReserveBank().coverageOf(nation, national.id());
            return String.format(Locale.ROOT, "%.1f%%", coverage * 100.0D);
        }

        if (player == null) {
            return "";
        }

        if (lower.equals("nation")) {
            String nation = plugin.getTownyHook().isAvailable()
                    ? plugin.getTownyHook().nationOf(player.getUniqueId())
                    : null;
            return nation == null ? "" : nation;
        }
        if (lower.startsWith("balance_raw_")) {
            String id = lower.substring("balance_raw_".length()).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(id).orElse(null);
            if (currency == null) {
                return "?";
            }
            return Formatter.amount(
                    plugin.getWallets().getBalance(player.getUniqueId(), currency.id()),
                    currency.decimals());
        }
        if (lower.startsWith("balance_")) {
            String id = lower.substring("balance_".length()).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(id).orElse(null);
            if (currency == null) {
                return "?";
            }
            return Formatter.withSymbol(
                    plugin.getWallets().getBalance(player.getUniqueId(), currency.id()),
                    currency.decimals(), currency.symbol());
        }
        if (lower.startsWith("treasury_")) {
            String id = lower.substring("treasury_".length()).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(id).orElse(null);
            if (currency == null || currency.type() != CurrencyType.NATIONAL) {
                return "?";
            }
            return Formatter.withSymbol(
                    plugin.getTreasury().balance(currency.nationId(), currency.id()),
                    currency.decimals(), currency.symbol());
        }
        if (lower.startsWith("symbol_")) {
            String id = lower.substring("symbol_".length()).toUpperCase(Locale.ROOT);
            return plugin.getCurrencies().get(id).map(Currency::symbol).orElse("?");
        }
        return null;
    }

    private String tps(int index) {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps == null || index < 0 || index >= tps.length) {
                return "-";
            }
            return String.format(Locale.ROOT, "%.2f", Math.min(20.0D, tps[index]));
        } catch (Throwable t) {
            return "-";
        }
    }
}
