// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.arbitrage;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.exchange.ConvertEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Симулятор арбитража (1.1.3): использует ConvertEngine вместо RatesService.
 */
public final class ArbitrageSimulator {

    public record Loop(String path, double profitFactor) {
    }

    private final RaskolVault plugin;
    private final ConvertEngine convertEngine;

    public ArbitrageSimulator(RaskolVault plugin, ConvertEngine convertEngine) {
        this.plugin = plugin;
        this.convertEngine = convertEngine;
    }

    public List<Loop> scan() {
        List<Loop> loops = new ArrayList<>();
        List<Currency> currencies = plugin.getCurrencies().all();
        for (Currency a : currencies) {
            for (Currency b : currencies) {
                if (a.id().equals(b.id())) {
                    continue;
                }
                for (Currency c : currencies) {
                    if (c.id().equals(a.id()) || c.id().equals(b.id())) {
                        continue;
                    }
                    double rateAB = convertEngine.rate(a.id(), b.id());
                    double rateBC = convertEngine.rate(b.id(), c.id());
                    double rateCA = convertEngine.rate(c.id(), a.id());
                    if (rateAB > 0.0D && rateBC > 0.0D && rateCA > 0.0D) {
                        double product = rateAB * rateBC * rateCA;
                        if (product > 1.01D) {
                            loops.add(new Loop(a.id() + "→" + b.id() + "→" + c.id() + "→" + a.id(), product));
                        }
                    }
                }
            }
        }
        return loops;
    }

    public void logReport() {
        List<Loop> loops = scan();
        if (loops.isEmpty()) {
            plugin.getLogger().info("RaskolVault: арбитражных петель не найдено");
            return;
        }
        plugin.getLogger().warning("RaskolVault: найдено арбитражных петель: " + loops.size());
        for (Loop loop : loops) {
            plugin.getLogger().warning("  " + loop.path() + " profit=" + String.format(Locale.ROOT, "%.4f", loop.profitFactor()));
        }
    }
}
