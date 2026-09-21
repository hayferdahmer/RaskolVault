// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.util.Formatter;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * /rv bank … (FIX 1.1.1.1): сдвиг префикса "bank" + трансляция &-кодов.
 */
public final class NationBankSubcommand {

    private final RaskolVault plugin;

    public NationBankSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("bank")) {
            args = java.util.Arrays.copyOfRange(args, 1, args.length);
        }
        String op = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + "&cБанк нации — только в игре");
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("nation.not-in-nation", null));
            return;
        }
        var bank = plugin.getReserveBank();
        switch (op) {
            case "info" -> {
                send(sender, plugin.getMessages().prefix() + "&6Банк нации " + nation);
                send(sender, "&7 Резерв: &f" + Formatter.amount(bank.reserveOf(nation), 2) + " GLD");
                Currency national = nationalOf(nation);
                if (national != null) {
                    send(sender, "&7 Покрытие: &f" + String.format(Locale.ROOT, "%.1f%%",
                            bank.coverageOf(nation, national.id()) * 100.0D));
                    send(sender, "&7 Цена: &f" + String.format(Locale.ROOT, "%.4f", bank.priceOf(national)) + " GLD");
                    send(sender, "&7 Паритет: &f" + String.format(Locale.ROOT, "%.2f", bank.parityOf(nation))
                            + " &7· Налог: &f" + String.format(Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D));
                }
            }
            case "deposit", "withdraw" -> {
                if (!plugin.getTownyHook().isKing(player.getUniqueId(), nation)) {
                    send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("bank.not-king", null));
                    return;
                }
                if (args.length < 2) {
                    send(sender, plugin.getMessages().prefix() + "&f/rv bank " + op + " <сумма>");
                    return;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[1])));
                    return;
                }
                boolean ok = op.equals("deposit")
                        ? bank.depositToReserve(player.getUniqueId(), nation, amount, "bank")
                        : bank.withdrawFromReserve(player.getUniqueId(), nation, amount, "bank");
                send(sender, plugin.getMessages().prefix() + (ok
                        ? "&a" + (op.equals("deposit") ? "Внесено" : "Выведено") + " " + Formatter.amount(amount, 2)
                        + " GLD (резерв: " + Formatter.amount(bank.reserveOf(nation), 2) + ")"
                        : "&c✖ Операция отклонена"));
            }
            case "parity", "tax" -> {
                if (!plugin.getTownyHook().isKing(player.getUniqueId(), nation)) {
                    send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("bank.not-king", null));
                    return;
                }
                if (args.length < 2) {
                    send(sender, plugin.getMessages().prefix() + "&f/rv bank " + op + " <значение>");
                    return;
                }
                double value;
                try {
                    value = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[1])));
                    return;
                }
                boolean ok = op.equals("parity") ? bank.setParity(nation, value) : bank.setTax(nation, value);
                send(sender, plugin.getMessages().prefix() + (ok
                        ? "&a" + (op.equals("parity") ? "Паритет" : "Налог") + " установлен: "
                        + String.format(Locale.ROOT, "%.2f", value)
                        : "&c✖ Значение вне границ"));
            }
            default -> send(sender, plugin.getMessages().prefix() + "&f/rv bank info|deposit|withdraw|parity|tax");
        }
    }

    private Currency nationalOf(String nation) {
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) {
                return cur;
            }
        }
        return null;
    }
}
