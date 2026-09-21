// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.wallet.WalletService;

/**
 * Legacy ExchangeService (1.1.3): stub-класс для совместимости.
 * Вся логика конвертов теперь в ConvertEngine.
 */
public final class ExchangeService {

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final RatesService rates;

    public ExchangeService(RaskolVault plugin, WalletService wallets,
                           CurrencyRegistry currencies, RatesService rates) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.rates = rates;
    }

    // Stub methods for compilation compatibility
    public double rate(String from, String to) {
        return rates.staticRate(from, to);
    }

    public double fee(String from, String to) {
        return rates.feeFor(from, to);
    }
}
