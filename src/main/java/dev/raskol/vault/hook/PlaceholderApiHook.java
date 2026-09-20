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
 * PlaceholderAPI-экспаншн RaskolVault.
 *
 * Балансы и нация (1.0):
 *   %raskolvault_balance_<ID>%      — баланс игрока (с символом)
 *   %raskolvault_balance_raw_<ID>%  — баланс без символа (для вычислений)
 *   %raskolvault_nation%            — ID текущей нации игрока или ""
 *   %raskolvault_treasury_<ID>%     — баланс казны национальной валюты
 *   %raskolvault_symbol_<ID>%       — символ валюты
 *
 * Observability (1.0.4):
 *   %raskolvault_tps%               — TPS за последнюю минуту (Paper)
 *   %raskolvault_tps_5m%            — TPS за 5 минут
 *   %raskolvault_tps_15m%           — TPS за 15 минут
 *   %raskolvault_ledger_queue%      — размер очереди писателя леджера
 *   %raskolvault_cache_hit%         — cache hit rate (%)
 *   %raskolvault_tx_per_min%        — транзакций в минуту
 *   %raskolvault_writer_applied%    — успешно применённых записей всего
 *   %raskolvault_writer_failed%     — проваленных записей всего
 *   %raskolvault_pool_idle%         — свободных соединений в пуле
 *   %raskolvault_pool_wait%         — потоков, ждущих соединения из пула
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

        // Observability-плейсхолдеры (игрок не обязателен)
        if (lower.equals("tps")) return formatTps(0);
        if (lower.equals("tps_5m")) return formatTps(1);
        if (lower.equals("tps_15m")) return formatTps(2);
        if (lower.equals("ledger_queue")) return Integer.toString(plugin.getWriter().queueSize());
        if (lower.equals("cache_hit")) return String.format(Locale.ROOT, "%.1f", plugin.getWallets().cacheHitRate());
        if (lower.equals("tx_per_min")) return Integer.toString(plugin.getTxCounter().count());
        if (lower.equals("writer_applied")) return Long.toString(plugin.getWriter().applied());
        if (lower.equals("writer_failed")) return Long.toString(plugin.getWriter().failed());
        if (lower.equals("pool_idle")) return Integer.toString(plugin.getLedger().poolIdle());
        if (lower.equals("pool_wait")) return Integer.toString(plugin.getLedger().poolWaiting());
        if (lower.equals("currencies_count")) return Integer.toString(plugin.getCurrencies().all().size());
        if (lower.equals("rates_count")) return Integer.toString(plugin.getRates().allRates().size());

        // Игрок-зависимые плейсхолдеры (требуют player)
        if (player == null) {
            return "";
        }

        if (lower.equals("nation")) {
            String nation = plugin.getTownyHook().isAvailable()
                    ? plugin.getTownyHook().nationOf(player.getUniqueId()) : null;
            return nation == null ? "" : nation;
        }

        if (lower.startsWith("balance_")) {
            String rest = lower.substring("balance_".length());
            boolean raw = rest.startsWith("raw_");
            String currencyId = (raw ? rest.substring(4) : rest).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
            if (currency == null) {
                return "?";
            }
            double amount = plugin.getWallets().getBalance(player.getUniqueId(), currency.id());
            return raw
                    ? Formatter.amount(amount, currency.decimals())
                    : Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        }

        if (lower.startsWith("treasury_")) {
            String currencyId = lower.substring("treasury_".length()).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
            if (currency == null || currency.type() != CurrencyType.NATIONAL) {
                return "?";
            }
            double amount = plugin.getTreasury().balance(currency.nationId(), currency.id());
            return Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        }

        if (lower.startsWith("symbol_")) {
            String currencyId = lower.substring("symbol_".length()).toUpperCase(Locale.ROOT);
            return plugin.getCurrencies().get(currencyId)
                    .map(Currency::symbol).orElse("?");
        }

        return null;
    }

    /**
     * Bukkit.getTPS() на Paper возвращает double[] {1m, 5m, 15m}.
     * Если API недоступен (Spigot-фолбэк), возвращаем "-".
     */
    private String formatTps(int index) {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps == null || index < 0 || index >= tps.length) {
                return "-";
            }
            double v = tps[index];
            // Ограничиваем сверху 20.0 для отображения (Paper может отдавать >20 при catchup)
            double display = Math.min(20.0D, v);
            return String.format(Locale.ROOT, "%.2f", display);
        } catch (Throwable t) {
            return "-";
        }
    }
}
