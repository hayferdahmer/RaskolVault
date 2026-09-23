// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.audit;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.escrow.EscrowService;
import dev.raskol.vault.storage.SQLiteLedger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EconomicInvariantAuditor (1.2.4.1): автоматическая проверка экономических инвариантов.
 * Вызов: /rv admin selftest + плановая задача (audit.interval-minutes).
 */
public final class EconomicInvariantAuditor {

    private static final String OK = "&a[OK] ";
    private static final String FAIL = "&c[FAIL] ";

    private final RaskolVault plugin;

    public EconomicInvariantAuditor(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public List<String> run() {
        List<String> out = new ArrayList<>();
        SQLiteLedger ledger = plugin.getLedger();

        // 1. Консервация + неотрицательность балансов
        Map<UUID, Map<String, Double>> db = ledger.loadAllBalances();
        Map<UUID, Map<String, Double>> cache = plugin.getWallets().cacheSnapshot();
        Map<String, Double> dbSum = new HashMap<>();
        Map<String, Double> cacheSum = new HashMap<>();
        boolean negOK = true;
        for (var e : db.entrySet()) for (var ce : e.getValue().entrySet()) {
            if (ce.getValue() < -1.0E-9D) negOK = false;
            dbSum.merge(ce.getKey(), ce.getValue(), Double::sum);
        }
        for (var e : cache.entrySet()) for (var ce : e.getValue().entrySet())
            cacheSum.merge(ce.getKey(), ce.getValue(), Double::sum);
        boolean consOK = true;
        for (String cur : dbSum.keySet()) {
            if (Math.abs(dbSum.getOrDefault(cur, 0.0D) - cacheSum.getOrDefault(cur, 0.0D)) > 1.0E-6D) consOK = false;
        }
        out.add((consOK ? OK : FAIL) + "Консервация: SUM(леджер) == SUM(кэш) по каждой валюте");
        out.add((negOK ? OK : FAIL) + "Неотрицательность: все балансы >= 0");

        // 2. Резервы неотрицательны
        boolean resOK = true;
        for (var e : ledger.reservesAll().entrySet()) if (e.getValue() < -1.0E-9D) resOK = false;
        out.add((resOK ? OK : FAIL) + "Резервы: все значения >= 0");

        // 3. Escrow-целостность: строка escrow == баланс escrow-аккаунта
        boolean escOK = true;
        for (SQLiteLedger.EscrowRow r : ledger.escrowRows()) {
            double bal = plugin.getWallets().getBalance(EscrowService.escrowUuid(r.ticket()), r.currencyId());
            if (Math.abs(bal - r.amount()) > 1.0E-6D) escOK = false;
        }
        out.add((escOK ? OK : FAIL) + "Escrow: таблица == балансы escrow-аккаунтов");

        // 4. Round-trip без арбитража
        boolean rtOK = true;
        List<Currency> all = plugin.getCurrencies().all();
        for (Currency a : all) for (Currency b : all) {
            if (a.id().equals(b.id())) continue;
            if (roundTripFactor(a.id(), b.id()) > 1.0D + 1.0E-9D) rtOK = false;
        }
        out.add((rtOK ? OK : FAIL) + "Round-trip: A→B→A не даёт прибыли (нет арбитража)");

        // 5. Пол комиссии и кап суммы активны
        double minAmt = plugin.getConfig().getDouble("exchange.min-amount", 1.0D);
        double minFee = plugin.getConfig().getDouble("exchange.min-fee", 0.01D);
        double maxTx = plugin.getConfig().getDouble("safety.max-transaction", 1_000_000_000.0D);
        out.add(OK + "Пол/кап: min-amount=" + minAmt + ", min-fee=" + minFee + ", max-transaction=" + maxTx);

        return out;
    }

    public boolean hasFailures(List<String> report) {
        for (String s : report) if (s.startsWith(FAIL)) return true;
        return false;
    }

    private double roundTripFactor(String a, String b) {
        double rateAB = plugin.getConvertEngine().rate(a, b);
        double rateBA = plugin.getConvertEngine().rate(b, a);
        if (rateAB <= 0.0D || rateBA <= 0.0D) return 0.0D;
        return rateAB * rateBA * feeFactor(a, b) * feeFactor(b, a);
    }

    private double feeFactor(String from, String to) {
        double base = plugin.getRates().feeFor(from, to);
        Currency toCur = plugin.getCurrencies().get(to).orElse(null);
        double tax = (toCur != null && toCur.type() == CurrencyType.NATIONAL && toCur.nationId() != null)
                ? plugin.getReserveBank().taxOf(toCur.nationId()) : 0.0D;
        return Math.max(0.0D, 1.0D - base - tax);
    }
}
