// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.exchange.ExchangeResult;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * /rv convert + /rv confirm (1.0.5: + token-bucket rate-limit на preview).
 */
public final class ConvertSubcommand {

    private final RaskolVault plugin;

    public ConvertSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        if (!sender.hasPermission("raskolvault.convert")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        // 1.0.5: rate-limit до preview (защита от спама проекций)
        if (!plugin.getRateLimiter().tryConsume(player.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§e/rv convert <из> <в> <сумма>");
            return;
        }
        String fromId = args[1].toUpperCase(java.util.Locale.ROOT);
        String toId = args[2].toUpperCase(java.util.Locale.ROOT);
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (plugin.getConfig().getBoolean("exchange.require-confirm", true)) {
            ExchangeResult preview = plugin.getExchange().preview(player.getUniqueId(), fromId, toId, amount);
            if (!"preview".equals(preview.reason())) {
                sendFailure(sender, preview);
                return;
            }
            plugin.getConfirms().put(player.getUniqueId(), preview);
            Currency from = plugin.getCurrencies().get(preview.fromId()).orElse(null);
            Currency to = plugin.getCurrencies().get(preview.toId()).orElse(null);
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("convert.preview",
                    Map.of(
                            "from", formatAmount(preview.gross(), from),
                            "to", formatAmount(preview.net(), to),
                            "rate", String.format("%.4f", preview.rate()),
                            "fee", formatAmount(preview.feeAmount(), from))));
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("convert.confirm-hint", null));
        } else {
            plugin.getExchange().executeAsync(player.getUniqueId(), fromId, toId, amount,
                    result -> sendResult(sender, result));
        }
    }

    /** Вызывается из /rv confirm (RaskolVaultCommand): асинхронное исполнение pending-preview. */
    public void runConfirmed(Player player, ExchangeResult preview, CommandSender sender) {
        plugin.getExchange().executeAsync(player.getUniqueId(), preview.fromId(), preview.toId(),
                preview.gross(), result -> sendResult(sender, result));
    }

    private void sendResult(CommandSender sender, ExchangeResult result) {
        if (result.applied()) {
            Currency to = plugin.getCurrencies().get(result.toId()).orElse(null);
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("convert.done",
                    Map.of("amount", formatAmount(result.net(), to))));
        } else {
            sendFailure(sender, result);
        }
    }

    private void sendFailure(CommandSender sender, ExchangeResult result) {
        String key = "error.convert." + result.reason().replace(':', '.');
        String msg = plugin.getMessages().get(key, Map.of("from", result.fromId(), "to", result.toId()));
        if (msg.equals(key)) {
            msg = plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", result.reason(), "from", result.fromId(), "to", result.toId()));
        }
        sender.sendMessage(plugin.getMessages().prefix() + msg);
    }

    private String formatAmount(double amount, Currency currency) {
        if (currency == null) {
            return Formatter.amount(amount, 2);
        }
        return Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
    }
}
