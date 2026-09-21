// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * /rv pay (1.1.3): + rate-limit против спам-переводов.
 */
public final class PaySubcommand {

    private final RaskolVault plugin;

    public PaySubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-pay", null));
            return;
        }
        if (!player.hasPermission("raskolvault.use")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (!plugin.getPayRateLimiter().tryConsume(player.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "&f/rv pay <ник> <валюта> <сумма> [причина]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.self-pay", null));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[2].toUpperCase(Locale.ROOT)).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "pay";
        boolean ok = plugin.getWallets().transfer(player.getUniqueId(), target.getUniqueId(),
                currency.id(), amount, reason);
        if (!ok) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.insufficient", Map.of(
                    "symbol", currency.symbol(),
                    "needed", Formatter.amount(amount, currency.decimals()),
                    "balance", Formatter.amount(plugin.getWallets().getBalance(player.getUniqueId(), currency.id()),
                            currency.decimals()))));
            return;
        }
        String formatted = Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("pay.sent", Map.of("amount", formatted, "player", target.getName())));
        target.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("pay.received", Map.of("amount", formatted, "player", player.getName())));
    }
}
