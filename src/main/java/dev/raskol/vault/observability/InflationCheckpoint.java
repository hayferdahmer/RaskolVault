// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.reserve.ReserveBank;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Почасовой чекпоинт инфляции (1.1.0-b): для каждой национальной валюты
 * проверяет покрытие резервом. Покрытие < coverage-floor → кризисный broadcast
 * жителям нации + счётчик аномалий (%raskolvault_inflation_anomalies%).
 */
public final class InflationCheckpoint implements Runnable {

    private final Plugin plugin;
    private final ReserveBank reserveBank;
    private final CurrencyRegistry currencies;
    private final TownyHook townyHook;
    private final AtomicLong anomalies = new AtomicLong(0L);

    public InflationCheckpoint(Plugin plugin, ReserveBank reserveBank,
                               CurrencyRegistry currencies, TownyHook townyHook) {
        this.plugin = plugin;
        this.reserveBank = reserveBank;
        this.currencies = currencies;
        this.townyHook = townyHook;
    }

    @Override
    public void run() {
        for (Currency currency : currencies.all()) {
            if (currency.type() != CurrencyType.NATIONAL || currency.nationId() == null) {
                continue;
            }
            String nation = currency.nationId();
            double coverage = reserveBank.coverageOf(nation, currency.id());
            if (coverage >= reserveBank.coverageFloor()) {
                continue;
            }
            anomalies.incrementAndGet();
            String percent = String.format(Locale.ROOT, "%.0f%%", coverage * 100.0D);
            plugin.getLogger().warning("RaskolVault: КРИЗИС ПОКРЫТИЯ " + nation
                    + ": покрытие " + percent + " < floor "
                    + String.format(Locale.ROOT, "%.0f%%", reserveBank.coverageFloor() * 100.0D)
                    + " — цена " + currency.id() + " снижена до резерв/эмиссия");
            broadcastToNation(nation, percent);
        }
    }

    private void broadcastToNation(String nation, String percent) {
        if (!townyHook.isAvailable()) {
            return;
        }
        String message = plugin.getMessages().prefix()
                + plugin.getMessages().get("crisis.devalued", Map.of("nation", nation, "coverage", percent));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (nation.equalsIgnoreCase(townyHook.nationOf(player.getUniqueId()))) {
                player.sendMessage(message);
            }
        }
    }

    public long anomalies() {
        return anomalies.get();
    }
}
