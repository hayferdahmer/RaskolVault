// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.confirm.PendingExchange;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

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
        if (!player.hasPermission("raskolvault.convert")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§e/rv convert <из> <в> <сумма>");
            return;
        }
        Currency from = plugin.getCurrencies().get(args[1]).orElse(null);
        Currency to = plugin.getCurrencies().get(args[2]).orElse(null);
        if (from == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[1])));
            return;
        }
        if (to == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        if (from.id().equals(to.id())) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.convert.same-currency", null));
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
        double balance = plugin.getWallets().getBalance(player.getUniqueId(), from.id());
        if (balance < amount) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.insufficient", Map.of(
                    "needed", Formatter.amount(amount, from.decimals()),
                    "balance", Formatter.amount(balance, from.decimals()),
                    "symbol", from.symbol())));
            return;
        }
        var preview = plugin.getExchange().preview(from, to, amount);
        if (preview.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.convert.no-rate", Map.of(
                    "from", from.id(), "to", to.id())));
            return;
        }
        PendingExchange pe = new PendingExchange(player.getUniqueId(), from.id(), to.id(),
                amount, preview.get().grossOut(), preview.get().netIn(), preview.get().feeAmount());
        plugin.getConfirms().store(pe);
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("convert.preview", Map.of(
                "from", Formatter.amount(amount, from.decimals()) + " " + from.symbol(),
                "to", Formatter.amount(preview.get().netIn(), to.decimals()) + " " + to.symbol(),
                "rate", String.format(Locale.ROOT, "%.4f", preview.get().rate()),
                "fee", Formatter.amount(preview.get().feeAmount(), from.decimals()) + " " + from.symbol())));
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("convert.confirm-hint", null));
    }

    public void runConfirmed(Player player, PendingExchange preview, CommandSender sender) {
        Currency from = plugin.getCurrencies().get(preview.fromCurrencyId()).orElse(null);
        Currency to = plugin.getCurrencies().get(preview.toCurrencyId()).orElse(null);
        if (from == null || to == null) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cВалюта исчезла");
            return;
        }
        var result = plugin.getExchange().execute(player, from, to, preview.amount());
        if (result.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", "не удалось выполнить")));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("convert.done", Map.of(
                "amount", Formatter.amount(result.get().netIn(), to.decimals()) + " " + to.symbol())));
    }
}
