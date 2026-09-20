// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.confirm.PendingExchange;
import dev.raskol.vault.exchange.ExchangeResult;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/**
 * /rv convert <из> <в> <сумма> — preview обмена с подтверждением /rv confirm.
 * Работает через ExchangeService.preview/execute (API этапа 4).
 */
public final class ConvertSubcommand {

    private final RaskolVault plugin;

    public ConvertSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        if (!player.hasPermission("raskolvault.convert")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(prefix() + "&f/rv convert <из> <в> <сумма>");
            return;
        }
        String fromId = args[1].toUpperCase(Locale.ROOT);
        String toId = args[2].toUpperCase(Locale.ROOT);
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }

        ExchangeResult preview = plugin.getExchange().preview(player.getUniqueId(), fromId, toId, amount);
        if (!"preview".equals(preview.reason())) {
            showFailure(sender, preview, fromId, toId);
            return;
        }

        long ttl = plugin.getConfig().getLong("exchange.confirm-timeout-seconds", 30) * 1000L;
        PendingExchange pe = new PendingExchange(
                player.getUniqueId(), fromId, toId, amount,
                preview.net(), preview.feeAmount(), preview.rate(),
                System.currentTimeMillis() + ttl);
        plugin.getConfirms().store(pe);

        Currency from = plugin.getCurrencies().get(fromId).orElse(null);
        Currency to = plugin.getCurrencies().get(toId).orElse(null);
        sender.sendMessage(prefix() + plugin.getMessages().get("convert.preview", Map.of(
                "from", Formatter.withSymbol(amount, from == null ? 2 : from.decimals(), from == null ? fromId : from.symbol()),
                "to", Formatter.withSymbol(preview.net(), to == null ? 2 : to.decimals(), to == null ? toId : to.symbol()),
                "rate", String.format(Locale.ROOT, "%.4f", preview.rate()),
                "fee", Formatter.withSymbol(preview.feeAmount(), from == null ? 2 : from.decimals(), from == null ? fromId : from.symbol()))));
        sender.sendMessage(prefix() + plugin.getMessages().get("convert.confirm-hint", null));
    }

    /** Вызывается из /rv confirm: исполняет отложенный обмен. */
    public void runConfirmed(Player player, PendingExchange pe, CommandSender sender) {
        ExchangeResult result = plugin.getExchange().execute(
                player.getUniqueId(), pe.fromId(), pe.toId(), pe.amount());
        if (result.applied()) {
            Currency to = plugin.getCurrencies().get(pe.toId()).orElse(null);
            sender.sendMessage(prefix() + plugin.getMessages().get("convert.done", Map.of(
                    "amount", Formatter.withSymbol(result.net(),
                            to == null ? 2 : to.decimals(),
                            to == null ? pe.toId() : to.symbol()))));
        } else {
            showFailure(sender, result, pe.fromId(), pe.toId());
        }
    }

    private void showFailure(CommandSender sender, ExchangeResult result, String fromId, String toId) {
        String reason = result.reason();
        String key;
        Map<String, String> params;
        if (reason.startsWith("unknown-currency:")) {
            key = "error.unknown-currency";
            params = Map.of("id", reason.substring("unknown-currency:".length()));
        } else {
            key = "error.convert." + reason;
            params = Map.of("from", fromId, "to", toId);
        }
        String text = plugin.getMessages().get(key, params);
        if (text.equals(key)) {
            text = plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", reason, "from", fromId, "to", toId));
        }
        sender.sendMessage(prefix() + text);
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }
}
