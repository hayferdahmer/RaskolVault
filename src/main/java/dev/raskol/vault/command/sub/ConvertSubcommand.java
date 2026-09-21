// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.confirm.ConfirmManager;
import dev.raskol.vault.util.Formatter;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/**
 * /rv convert (FIX 1.1.1.1): трансляция &-кодов во всех отправках.
 */
public final class ConvertSubcommand {

    private final RaskolVault plugin;

    public ConvertSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        if (!player.hasPermission("raskolvault.convert")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            send(sender, plugin.getMessages().prefix() + "&f/rv convert <из> <в> <сумма>");
            return;
        }
        String fromId = args[1].toUpperCase(Locale.ROOT);
        String toId = args[2].toUpperCase(Locale.ROOT);
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = plugin.getConvertEngine().blockReason(player.getUniqueId(), fromId, toId, amount);
        if (!reason.isEmpty()) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", reason, "from", fromId, "to", toId)));
            return;
        }
        var quote = plugin.getConvertEngine().quote(player.getUniqueId(), fromId, toId, amount);
        if (quote.isEmpty()) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", "неизвестно", "from", fromId, "to", toId)));
            return;
        }
        var q = quote.get();
        Currency from = plugin.getCurrencies().get(fromId).orElse(null);
        Currency to = plugin.getCurrencies().get(toId).orElse(null);
        send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("convert.preview", Map.of(
                "from", fmtSym(q.amount(), from, fromId),
                "to", fmtSym(q.net(), to, toId),
                "rate", String.format(Locale.ROOT, "%.4f", q.rate()),
                "fee", fmtSym(q.feeBase() + q.feeTax(), from, fromId))));
        send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("convert.confirm-hint", null));
        plugin.getConfirms().store(player.getUniqueId(), quote.get());
    }

    public void runConfirmed(Player player, ConfirmManager.PendingExchange pending, CommandSender sender) {
        if (!plugin.getRateLimiter().tryConsume(player.getUniqueId())) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        var q = pending.quote();
        String reason = plugin.getConvertEngine().blockReason(player.getUniqueId(), q.fromId(), q.toId(), q.amount());
        if (!reason.isEmpty()) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", reason, "from", q.fromId(), "to", q.toId())));
            return;
        }
        var executed = plugin.getConvertEngine().execute(player.getUniqueId(), q.fromId(), q.toId(), q.amount());
        if (executed.isEmpty()) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", "курс или средства изменились", "from", q.fromId(), "to", q.toId())));
            return;
        }
        var done = executed.get();
        Currency to = plugin.getCurrencies().get(done.toId()).orElse(null);
        send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("convert.done",
                Map.of("amount", fmtSym(done.net(), to, done.toId()))));
    }

    private String fmtSym(double value, Currency cur, String fallback) {
        if (cur == null) {
            return Formatter.amount(value, 2) + " " + fallback;
        }
        return Formatter.withSymbol(value, cur.decimals(), cur.symbol());
    }
}
