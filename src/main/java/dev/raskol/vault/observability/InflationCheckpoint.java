// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.reserve.ReserveBank;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Почасовой чекпоинт покрытия (1.1.2): кризис (покрытие<пола) и ре-вальвация
 * (покрытие вернулось ≥100%) дают broadcast нации; еженедельный бюллетень экономики.
 */
public final class InflationCheckpoint implements Runnable {

    private final RaskolVault plugin;
    private final ReserveBank bank;
    private final CurrencyRegistry currencies;
    private final TownyHook towny;
    private final Map<String, Double> prevCoverage = new ConcurrentHashMap<>();
    private long runCount = 0;

    public InflationCheckpoint(RaskolVault plugin, ReserveBank bank,
                               CurrencyRegistry currencies, TownyHook towny) {
        this.plugin = plugin;
        this.bank = bank;
        this.currencies = currencies;
        this.towny = towny;
    }

    @Override
    public void run() {
        runCount++;
        for (Currency c : currencies.all()) {
            if (c.type() != CurrencyType.NATIONAL || c.nationId() == null) {
                continue;
            }
            String nation = c.nationId();
            double cov = bank.coverageOf(nation, c.id());
            Double prev = prevCoverage.put(nation, cov);
            if (prev != null) {
                if (prev >= 1.0D && cov < bank.coverageFloor()) {
                    broadcast(nation, "&c⚠ КРИЗИС: покрытие " + pct(cov)
                            + " ниже пола. Валюта " + c.id() + " девальвирована до резерв/эмиссия.");
                } else if (prev >= 1.0D && cov < 1.0D) {
                    broadcast(nation, "&eВнимание: покрытие " + pct(cov)
                            + " < 100%. Валюта " + c.id() + " девальвирована.");
                } else if (prev < 1.0D && cov >= 1.0D) {
                    broadcast(nation, "&aРе-вальвация: покрытие " + pct(cov)
                            + " ≥ 100%. Валюта " + c.id() + " вернулась к паритету.");
                }
            }
        }
        long bulletinEvery = Math.max(1L,
                plugin.getConfig().getLong("reserve.bulletin.interval-minutes", 10080L)
                        / Math.max(1L, plugin.getConfig().getLong("reserve.check-interval-minutes", 60L)));
        if (plugin.getConfig().getBoolean("reserve.bulletin.enabled", true)
                && runCount % bulletinEvery == 0) {
            bulletin();
        }
    }

    private void bulletin() {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.translateAlternateColorCodes('&', "&6=== Экономический бюллетень ==="));
        for (Currency c : currencies.all()) {
            if (c.type() != CurrencyType.NATIONAL || c.nationId() == null) {
                continue;
            }
            String nation = c.nationId();
            sb.append("\n").append(ChatColor.translateAlternateColorCodes('&',
                    "&7 " + nation + " (" + c.id() + "): эмиссия &f"
                            + fmt(bank.supplyOf(c.id())) + " &7· резерв &f" + fmt(bank.reserveOf(nation))
                            + " &7· покрытие &f" + pct(bank.coverageOf(nation, c.id()))
                            + " &7· цена &f" + String.format(Locale.ROOT, "%.4f", bank.priceOf(c)) + " GLD"));
        }
        String msg = sb.toString();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.sendMessage(msg);
        }
    }

    private void broadcast(String nation, String raw) {
        String msg = plugin.getMessages().prefix() + ChatColor.translateAlternateColorCodes('&', raw);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (nation.equalsIgnoreCase(towny.nationOf(p.getUniqueId()))) {
                p.sendMessage(msg);
            }
        }
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100.0D);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
