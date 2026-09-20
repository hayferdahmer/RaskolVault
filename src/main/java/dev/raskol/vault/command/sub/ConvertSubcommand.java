// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.confirm.PendingExchange;
import dev.raskol.vault.exchange.ConvertEngine;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * /rv convert <из> <в> <сумма> → preview → /rv confirm (1.1.0-b).
 * Курсы и налог — из ConvertEngine (Валютный совет). Rate-limit на игрока.
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
        if (!plugin.getRateLimiter().tryConsume(player.getUniqueId())) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        Optional<ConvertEngine.Quote> quoted =
                plugin.getConvertEngine().quote(player.getUniqueId(), fromId, toId, amount);
        if (quoted.isEmpty()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", "нет курса или недостаточно средств", "from", fromId, "to", toId)));
            return;
        }
        ConvertEngine.Quote q = quoted.get();
        long ttl = plugin.getConfig().getLong("exchange.confirm-timeout-seconds", 30) * 1000L;
        plugin.getConfirms().store(new PendingExchange(
                player.getUniqueId(), q.fromId(), q.toId(), q.amount(),
                q.net(), q.feeBase() + q.feeTax(), q.rate(),
                System.currentTimeMillis() + ttl));

        Currency from = plugin.getCurrencies().get(fromId).orElse(null);
        Currency to = plugin.getCurrencies().get(toId).orElse(null);
        sender.sendMessage(prefix() + plugin.getMessages().get("convert.preview", Map.of(
                "from", Formatter.withSymbol(q.amount(), from == null ? 2 : from.decimals(), from == null ? fromId : from.symbol()),
                "to", Formatter.withSymbol(q.net(), to == null ? 2 : to.decimals(), to == null ? toId : to.symbol()),
                "rate", String.format(Locale.ROOT, "%.4f", q.rate()),
                "fee", Formatter.withSymbol(q.feeBase() + q.feeTax(), from == null ? 2 : from.decimals(), from == null ? fromId : from.symbol()))));
        if (q.taxNation() != null && q.feeTax() > 0.0D) {
            sender.sendMessage(prefix() + "&7 Налог нации " + q.taxNation() + ": "
                    + Formatter.withSymbol(q.taxInTo(), to == null ? 2 : to.decimals(), to == null ? toId : to.symbol())
                    + " &7→ в её казну");
        }
        sender.sendMessage(prefix() + plugin.getMessages().get("convert.confirm-hint", null));
    }

    /** Вызывается из /rv confirm: исполняет по текущему курсу. */
    public void runConfirmed(Player player, PendingExchange pe, CommandSender sender) {
        Optional<ConvertEngine.Quote> executed = plugin.getConvertEngine()
                .execute(player.getUniqueId(), pe.fromId(), pe.toId(), pe.amount());
        if (executed.isEmpty()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", "не исполнено (курс/средства изменились)", "from", pe.fromId(), "to", pe.toId())));
            return;
        }
        ConvertEngine.Quote q = executed.get();
        Currency to = plugin.getCurrencies().get(q.toId()).orElse(null);
        sender.sendMessage(prefix() + plugin.getMessages().get("convert.done", Map.of(
                "amount", Formatter.withSymbol(q.net(), to == null ? 2 : to.decimals(), to == null ? q.toId() : to.symbol()))));
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }
}
