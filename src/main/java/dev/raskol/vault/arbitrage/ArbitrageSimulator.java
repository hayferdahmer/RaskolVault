// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.arbitrage;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.exchange.RatesService;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Симулятор арбитражных петель: BFS по графу курсов, ищет циклы длины 2–4,
 * где product(rate × (1-fee)) > 1.0.
 *
 * 1.0.1: в граф попадают только tradeable-валюты; пустой граф/пустые курсы
 * дают информативный пропуск вместо ложного отчёта.
 */
public final class ArbitrageSimulator {

    public record Loop(List<String> path, double product) {
        public String describe() {
            return String.join(" → ", path) + " → " + path.get(0)
                    + "  (множитель " + String.format("%.4f", product) + ")";
        }
    }

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final RatesService rates;
    private final int maxDepth;

    public ArbitrageSimulator(Plugin plugin, CurrencyRegistry currencies, RatesService rates) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.rates = rates;
        this.maxDepth = 4;
    }

    public List<Loop> scan() {
        List<Loop> loops = new ArrayList<>();
        if (rates.allRates().isEmpty()) {
            return loops;
        }
        Set<String> seen = new HashSet<>();
        for (Currency start : currencies.all()) {
            if (!start.tradeable()) {
                continue;
            }
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(new Node(start.id(), 1.0D, List.of(start.id())));
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                if (n.depth() > maxDepth) {
                    continue;
                }
                for (Currency other : currencies.all()) {
                    if (!other.tradeable()) {
                        continue;
                    }
                    if (other.id().equals(n.last())) {
                        continue;
                    }
                    var rateOpt = rates.rate(n.last(), other.id());
                    if (rateOpt.isEmpty()) {
                        continue;
                    }
                    double fee = rates.fee(n.last(), other.id());
                    double next = n.product() * rateOpt.get() * (1.0D - fee);
                    List<String> nextPath = new ArrayList<>(n.path());
                    nextPath.add(other.id());
                    if (other.id().equals(start.id()) && nextPath.size() >= 3) {
                        if (next > 1.0D + 1.0E-9D) {
                            String key = canonicalize(nextPath);
                            if (seen.add(key)) {
                                loops.add(new Loop(nextPath, next));
                            }
                        }
                    } else if (!other.id().equals(start.id()) && n.depth() + 1 <= maxDepth) {
                        stack.push(new Node(other.id(), next, nextPath));
                    }
                }
            }
        }
        return loops;
    }

    public void logReport() {
        long tradeable = currencies.all().stream().filter(Currency::tradeable).count();
        if (tradeable < 2 || rates.allRates().isEmpty()) {
            plugin.getLogger().info("RaskolVault: арбитражный сканер — нет торгуемых пар, пропускаю");
            return;
        }
        List<Loop> loops = scan();
        if (loops.isEmpty()) {
            plugin.getLogger().info("RaskolVault: арбитражный сканер — петель не найдено ✓");
            return;
        }
        plugin.getLogger().warning("RaskolVault: арбитражный сканер — найдено " + loops.size()
                + " прибыльных петель (пересмотри rates.yml):");
        for (Loop loop : loops) {
            plugin.getLogger().warning("  · " + loop.describe());
        }
    }

    private String canonicalize(List<String> path) {
        int min = 0;
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i).compareTo(path.get(min)) < 0) {
                min = i;
            }
        }
        List<String> canonical = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            canonical.add(path.get((min + i) % path.size()));
        }
        return String.join(",", canonical);
    }

    private record Node(String last, double product, List<String> path) {
        int depth() {
            return path.size() - 1;
        }
    }
}
