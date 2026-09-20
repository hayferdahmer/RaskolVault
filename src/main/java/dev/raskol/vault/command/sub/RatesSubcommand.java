// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/**
 * /rv rates — цены Валютного совета и матрица курсов (1.1.0-b).
 */
public final class RatesSubcommand {

    private final RaskolVault plugin;

    public RatesSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        String prefix = plugin.getMessages().prefix();
        sender.sendMessage(prefix + plugin.getMessages().get("rates.header",
                java.util.Map.of("fee", String.format(Locale.ROOT, "%.1f%%",
                        plugin.getConfig().getDouble("exchange.default-fee", 0.02) * 100.0D))));
        for (Currency c : plugin.getCurrencies().all()) {
            double price = plugin.getReserveBank().priceOf(c);
            String line = "&7 " + c.id() + " " + c.symbol() + ": &f" + String.format(Locale.ROOT, "%.4f", price) + " GLD";
            if (c.type() == CurrencyType.NATIONAL && c.nationId() != null) {
                double coverage = plugin.getReserveBank().coverageOf(c.nationId(), c.id());
                line += " &7· покрытие &f" + String.format(Locale.ROOT, "%.0f%%", coverage * 100.0D)
                        + " · налог &f" + String.format(Locale.ROOT, "%.1f%%", plugin.getReserveBank().taxOf(c.nationId()) * 100.0D);
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(prefix + "&7Курсы пар:");
        for (Currency a : plugin.getCurrencies().all()) {
            for (Currency b : plugin.getCurrencies().all()) {
                if (a.id().equals(b.id())) {
                    continue;
                }
                double rate = plugin.getConvertEngine().rate(a.id(), b.id());
                sender.sendMessage("&7 " + a.id() + " → " + b.id() + ": &f" + String.format(Locale.ROOT, "%.4f", rate));
            }
        }
    }
}
