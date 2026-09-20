// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Map;

/**
 * /rv rates — таблица курсов. 1.1.0-a: команда возвращена в корневой роутер.
 */
public final class RatesSubcommand {

    private final RaskolVault plugin;

    public RatesSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        String prefix = plugin.getMessages().prefix();
        sender.sendMessage(prefix + plugin.getMessages().get("rates.header", null));
        Map<String, Double> rates = plugin.getRates().allRates();
        if (rates.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("rates.empty", null));
            return;
        }
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            String pair = e.getKey().replace("_", " → ").toUpperCase(Locale.ROOT);
            sender.sendMessage(plugin.getMessages().get("rates.line", Map.of(
                    "pair", pair,
                    "rate", String.format(Locale.ROOT, "%.4f", e.getValue()))));
        }
        sender.sendMessage(prefix + "&7Комиссия по умолчанию: &f"
                + String.format(Locale.ROOT, "%.1f%%", plugin.getRates().defaultFee() * 100.0));
    }
}
