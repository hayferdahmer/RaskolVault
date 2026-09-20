// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class PaySubcommand {

    private final RaskolVault plugin;

    public PaySubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player from)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-pay", null));
            return;
        }
        if (!sender.hasPermission("raskolvault.use")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§e/rv pay <ник> <валюта> <сумма> [причина]");
            return;
        }
        Player to = Bukkit.getPlayerExact(args[1]);
        if (to == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        if (to.getUniqueId().equals(from.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.self-pay", null));
            return;
        }
        String currencyId = args[2];
        Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", currencyId)));
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
        if (!(amount > 0.0D)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "pay";
        UUID fromUuid = from.getUniqueId();
        UUID toUuid = to.getUniqueId();
        try {
            boolean ok = plugin.getWallets().transfer(fromUuid, toUuid, currency.id(), amount, reason);
            if (ok) {
                String formatted = Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
                from.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("pay.sent",
                        Map.of("player", to.getName(), "amount", formatted)));
                to.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("pay.received",
                        Map.of("player", from.getName(), "amount", formatted)));
            } else {
                sender.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("error.insufficient",
                        Map.of("symbol", currency.symbol(),
                                "needed", Formatter.amount(amount, currency.decimals()),
                                "balance", Formatter.amount(plugin.getWallets().getBalance(fromUuid, currency.id()), currency.decimals()))));
            }
        } catch (LedgerException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
            plugin.getLogger().severe("RaskolVault /rv pay: " + e.getMessage());
        }
    }
}
