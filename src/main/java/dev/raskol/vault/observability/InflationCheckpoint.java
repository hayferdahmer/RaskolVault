// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.security.TokenBucket;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Почасовой инфляционный чекпоинт (1.0.5).
 *
 * Инвариант закрытой экономики для каждой НЕ-глобальной валюты:
 *   SUM(balances) == SIGNSUM(transactions)
 * где SIGNSUM = +amount для tx с (from=NULL, to!=NULL) [эмиссия: MINT, ADMIN_GIVE,
 * CONVERT-deposit], −amount для (from!=NULL, to=NULL) [сжигание: BURN, ADMIN_TAKE,
 * CONVERT-withdraw], 0 для PAY (перевод сохраняет сумму).
 *
 * GLOBAL не проверяется: источник правды — Essentials, наших строк в balances нет.
 * Расхождение > 0.01 = SEVERE + счётчик аномалий (PAPI %raskolvault_inflation_anomalies%).
 * Побочно: purgeIdle для TokenBucket.
 */
public final class InflationCheckpoint implements Runnable {

    private static final double TOLERANCE = 0.01D;

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final CurrencyRegistry currencies;
    private final TokenBucket rateLimiter;
    private final AtomicLong anomalies = new AtomicLong(0L);
    private final AtomicLong runs = new AtomicLong(0L);

    public InflationCheckpoint(Plugin plugin, SQLiteLedger ledger,
                               CurrencyRegistry currencies, TokenBucket rateLimiter) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.currencies = currencies;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void run() {
        runs.incrementAndGet();
        if (rateLimiter != null) {
            rateLimiter.purgeIdle(5L * 60L * 1000L);
        }
        for (Currency currency : currencies.all()) {
            if (currency.isGlobal()) {
                continue;
            }
            try {
                double balancesSum = ledger.sumBalances(currency.id());
                double expected = ledger.expectedSupply(currency.id());
                double diff = balancesSum - expected;
                if (Math.abs(diff) > TOLERANCE) {
                    anomalies.incrementAndGet();
                    plugin.getLogger().severe(String.format(Locale.ROOT,
                            "RaskolVault: ИНФЛЯЦИОННАЯ АНОМАЛИЯ %s: балансы=%.2f, ожидалось=%.2f, дельта=%.2f "
                                    + "(возможна ручная правка БД, restore или дюп) — расследуй через /rv admin audit",
                            currency.id(), balancesSum, expected, diff));
                } else if (plugin.getConfig().getBoolean("general.debug", false)) {
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "RaskolVault: чекпоинт %s OK: балансы=%.2f, ожидалось=%.2f",
                            currency.id(), balancesSum, expected));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("RaskolVault: чекпоинт " + currency.id()
                        + " не выполнен: " + e.getMessage());
            }
        }
    }

    public long anomalies() {
        return anomalies.get();
    }

    public long runs() {
        return runs.get();
    }
}
