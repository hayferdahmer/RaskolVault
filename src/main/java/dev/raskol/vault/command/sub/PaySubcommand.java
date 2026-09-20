// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Map;

/**
 * /rv pay <ник> <валюта> <сумма> [причина].
 * Вызов кошелька — сигнатура репозитория: transfer(from, to, currencyId, amount, reason).
 */
public final class PaySubcommand {

    private final RaskolVault plugin;

    public PaySubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-pay", null));
            return;
        }
        if (!player.hasPermission("raskolvault.use")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(prefix() + "&f/rv pay <ник> <валюта> <сумма> [причина]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.self-pay", null));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[2]).orElse(null);
        if (currency == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (!(amount > 0.0D)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = args.length > 4
                ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                : "pay";

        boolean ok = plugin.getWallets().transfer(
                player.getUniqueId(), target.getUniqueId(), currency.id(), amount, reason);
        if (!ok) {
            double bal = plugin.getWallets().getBalance(player.getUniqueId(), currency.id());
            if (bal < amount) {
                sender.sendMessage(prefix() + plugin.getMessages().get("error.insufficient", Map.of(
                        "needed", Formatter.amount(amount, currency.decimals()),
                        "balance", Formatter.amount(bal, currency.decimals()),
                        "symbol", currency.symbol())));
            } else {
                sender.sendMessage(prefix() + plugin.getMessages().get("error.storage", null));
            }
            return;
        }
        String formatted = Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        sender.sendMessage(prefix() + plugin.getMessages().get("pay.sent",
                Map.of("amount", formatted, "player", target.getName())));
        target.sendMessage(prefix() + plugin.getMessages().get("pay.received",
                Map.of("amount", formatted, "player", player.getName())));
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }
}
