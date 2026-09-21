// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/**
 * /rv bank — кабинет короля/мэра (1.1.0-b): резерв, паритет, налог, интервенции.
 * Доступ: только король нации (Towny). Резидент видит только info.
 */
public final class NationBankSubcommand {

    private final RaskolVault plugin;

    public NationBankSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-nation", null));
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("bank.not-in-nation", null));
            return;
        }
        if (args.length < 2) {
            info(sender, nation);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender, nation);
            case "deposit" -> deposit(sender, player, nation, args);
            case "withdraw" -> withdraw(sender, player, nation, args);
            case "parity" -> parity(sender, player, nation, args);
            case "tax" -> tax(sender, player, nation, args);
            default -> help(sender);
        }
    }

    private void info(CommandSender sender, String nation) {
        Currency national = nationalCurrency(nation);
        double reserve = plugin.getReserveBank().reserveOf(nation);
        sender.sendMessage(prefix() + plugin.getMessages().get("bank.info", Map.of("nation", nation)));
        sender.sendMessage("§7 Резерв (GLD): §f" + Formatter.amount(reserve, 2));
        if (national != null) {
            double supply = plugin.getReserveBank().supplyOf(national.id());
            double coverage = plugin.getReserveBank().coverageOf(nation, national.id());
            double price = plugin.getReserveBank().priceOf(national);
            sender.sendMessage("§7 Эмиссия " + national.id() + ": §f" + Formatter.amount(supply, national.decimals()));
            sender.sendMessage("§7 Покрытие: §f" + String.format(Locale.ROOT, "%.1f%%", coverage * 100.0D)
                    + (coverage < plugin.getReserveBank().coverageFloor() ? " §c⚠ КРИЗИС" : " §a✓"));
            sender.sendMessage("§7 Цена в золоте: §f" + String.format(Locale.ROOT, "%.4f", price));
            sender.sendMessage("§7 Лимит минта сейчас: §f"
                    + Formatter.amount(plugin.getReserveBank().maxMint(nation, national.id()), national.decimals()));
            sender.sendMessage("§7 Лимит вывода резерва сегодня: §f"
                    + Formatter.amount(plugin.getReserveBank().dailyWithdrawLimit(nation), 2));
        }
        sender.sendMessage("§7 Паритет: §f" + String.format(Locale.ROOT, "%.2f", plugin.getReserveBank().parityOf(nation))
                + " §7· Налог конвертации: §f" + String.format(Locale.ROOT, "%.2f%%", plugin.getReserveBank().taxOf(nation) * 100.0D));
    }

    private void deposit(CommandSender sender, Player player, String nation, String[] args) {
        if (!requireKing(sender, player, nation)) {
            return;
        }
        double amount = parseAmount(args, 2);
        if (Double.isNaN(amount) || !(amount > 0.0D)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount",
                    Map.of("value", args.length > 2 ? args[2] : "?")));
            return;
        }
        boolean ok = plugin.getReserveBank().depositToReserve(player.getUniqueId(), nation, amount, "king");
        sender.sendMessage(prefix() + (ok
                ? plugin.getMessages().get("bank.deposited", Map.of("amount", Formatter.amount(amount, 2) + " GLD"))
                : plugin.getMessages().get("error.storage", null)));
    }

    private void withdraw(CommandSender sender, Player player, String nation, String[] args) {
        if (!requireKing(sender, player, nation)) {
            return;
        }
        double amount = parseAmount(args, 2);
        if (Double.isNaN(amount) || !(amount > 0.0D)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount",
                    Map.of("value", args.length > 2 ? args[2] : "?")));
            return;
        }
        boolean ok = plugin.getReserveBank().withdrawFromReserve(player.getUniqueId(), nation, amount, "king");
        sender.sendMessage(prefix() + (ok
                ? plugin.getMessages().get("bank.withdrawn", Map.of("amount", Formatter.amount(amount, 2) + " GLD"))
                : plugin.getMessages().get("bank.limit", Map.of(
                "limit", Formatter.amount(plugin.getReserveBank().dailyWithdrawLimit(nation), 2)))));
    }

    private void parity(CommandSender sender, Player player, String nation, String[] args) {
        if (!requireKing(sender, player, nation)) {
            return;
        }
        double value = parseAmount(args, 2);
        if (Double.isNaN(value)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount",
                    Map.of("value", args.length > 2 ? args[2] : "?")));
            return;
        }
        boolean ok = plugin.getReserveBank().setParity(nation, value);
        sender.sendMessage(prefix() + (ok
                ? plugin.getMessages().get("bank.parity-set", Map.of("value", String.format(Locale.ROOT, "%.2f", value)))
                : plugin.getMessages().get("error.invalid-amount", Map.of("value", args.length > 2 ? args[2] : "?"))));
    }

    private void tax(CommandSender sender, Player player, String nation, String[] args) {
        if (!requireKing(sender, player, nation)) {
            return;
        }
        double value = parseAmount(args, 2);
        if (Double.isNaN(value)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount",
                    Map.of("value", args.length > 2 ? args[2] : "?")));
            return;
        }
        boolean ok = plugin.getReserveBank().setTax(nation, value);
        sender.sendMessage(prefix() + (ok
                ? plugin.getMessages().get("bank.tax-set", Map.of("value", String.format(Locale.ROOT, "%.2f%%", value * 100.0D)))
                : plugin.getMessages().get("error.invalid-amount", Map.of("value", args.length > 2 ? args[2] : "?"))));
    }

    private boolean requireKing(CommandSender sender, Player player, String nation) {
        if (!plugin.getTownyHook().isKing(player.getUniqueId(), nation)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("bank.not-king", null));
            return false;
        }
        return true;
    }

    private Currency nationalCurrency(String nation) {
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(c.nationId())) {
                return c;
            }
        }
        return null;
    }

    private double parseAmount(String[] args, int index) {
        if (args.length <= index) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(args[index]);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(prefix() + "&6=== Банк нации ===");
        sender.sendMessage("&f /rv bank info — резерв, покрытие, цена, лимиты");
        sender.sendMessage("&f /rv bank deposit <сумма> — внести своё золото в резерв (король)");
        sender.sendMessage("&f /rv bank withdraw <сумма> — вывести из резерва (король, лимит/сутки)");
        sender.sendMessage("&f /rv bank parity <0.5–2.0> — официальный курс (король)");
        sender.sendMessage("&f /rv bank tax <0–0.05> — налог конвертации в вашу валюту (король)");
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }
}
