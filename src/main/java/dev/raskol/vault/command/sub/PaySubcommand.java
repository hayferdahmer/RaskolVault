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
 * /rv pay (1.2.3): перевод ТОЛЬКО при дистанции ≤ 6 блоков (реализм средневековья).
 * В 1.2.3-b команда будет удалена — переводы перейдут в GUI кошелька.
 */
public final class PaySubcommand {

    private static final double MAX_DISTANCE = 6.0D;

    private final RaskolVault plugin;

    public PaySubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private String c(String s) { return org.bukkit.ChatColor.translateAlternateColorCodes('&', s); }
    private void send(CommandSender s, String raw) { s.sendMessage(c(raw)); }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.console-cannot-pay", null));
            return;
        }
        if (!player.hasPermission("raskolvault.use")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            send(sender, plugin.getMessages().prefix() + "&f/rv pay <ник> <валюта> <сумма> [причина]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.self-pay", null));
            return;
        }
        // Реализм: передача из рук в руки — не далее 6 блоков и тот же мир
        if (!player.getWorld().equals(target.getWorld())
                || player.getLocation().distance(target.getLocation()) > MAX_DISTANCE) {
            send(sender, plugin.getMessages().prefix()
                    + "&cСлишком далеко. Подойдите ближе (≤ 6 блоков) для передачи из рук в руки.");
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[2].toUpperCase(Locale.ROOT)).orElse(null);
        if (currency == null) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        double amount;
        try { amount = Double.parseDouble(args[3]); }
        catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (!(amount > 0.0D) || !Double.isFinite(amount)) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "pay";
        boolean ok = plugin.getWallets().transfer(player.getUniqueId(), target.getUniqueId(), currency.id(), amount, reason);
        if (!ok) {
            double bal = plugin.getWallets().getBalance(player.getUniqueId(), currency.id());
            if (bal < amount) {
                send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.insufficient", Map.of(
                        "needed", Formatter.amount(amount, currency.decimals()),
                        "balance", Formatter.amount(bal, currency.decimals()),
                        "symbol", currency.symbol())));
            } else {
                send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.storage", null));
            }
            return;
        }
        String formatted = Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("pay.sent", Map.of("amount", formatted, "player", target.getName())));
        target.sendMessage(c(plugin.getMessages().prefix() + plugin.getMessages().get("pay.received", Map.of("amount", formatted, "player", player.getName()))));
    }
}
