// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/**
 * /rv rates (1.2.2-a): FIX — весь вывод прогоняется через транслятор &-кодов.
 */
public final class RatesSubcommand {

    private final RaskolVault plugin;

    public RatesSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
    }

    public void execute(CommandSender sender) {
        send(sender, "&6=== Курсы валют (золотой эквивалент) ===");
        for (Currency cur : plugin.getCurrencies().all()) {
            double price = plugin.getReserveBank().priceOf(cur);
            String line = "&7 " + cur.id() + " &f" + String.format(Locale.ROOT, "%.4f", price) + " GLD";
            if (cur.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL && cur.nationId() != null) {
                double cov = plugin.getReserveBank().coverageOf(cur.nationId(), cur.id());
                double tax = plugin.getReserveBank().taxOf(cur.nationId());
                line += " &7· покрытие &f" + String.format(Locale.ROOT, "%.0f%%", cov * 100.0)
                        + " &7· налог &f" + String.format(Locale.ROOT, "%.1f%%", tax * 100.0);
            }
            send(sender, line);
        }
        send(sender, "&6=== Курсы пар ===");
        for (Currency a : plugin.getCurrencies().all()) {
            for (Currency b : plugin.getCurrencies().all()) {
                if (a.id().equals(b.id())) continue;
                double r = plugin.getConvertEngine().rate(a.id(), b.id());
                send(sender, "&7 " + a.id() + " → " + b.id() + ": &f" + String.format(Locale.ROOT, "%.4f", r));
            }
        }
    }
}
