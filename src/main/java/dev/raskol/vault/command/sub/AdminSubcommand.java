// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AdminSubcommand {

    private static final DateTimeFormatter DT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RaskolVault plugin;

    public AdminSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "give" -> give(sender, args);
            case "take" -> take(sender, args);
            case "set" -> set(sender, args);
            case "mint" -> sender.sendMessage(plugin.getMessages().prefix()
                    + "§c/mint и /burn требуют Towny-интеграции (этап 5)");
            case "burn" -> sender.sendMessage(plugin.getMessages().prefix()
                    + "§c/mint и /burn требуют Towny-интеграции (этап 5)");
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.give")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin give <ник> <валюта> <сумма>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[3]).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[3])));
            return;
        }
        double amount = parseAmount(args[4]);
        if (!(amount > 0.0D)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[4])));
            return;
        }
        try {
            boolean ok = plugin.getWallets().deposit(target.getUniqueId(), currency.id(),
                    amount, TransactionType.ADMIN_GIVE, "admin:" + senderName(sender));
            if (ok) {
                sender.sendMessage(plugin.getMessages().prefix() + "§aВыдано "
                        + Formatter.withSymbol(amount, currency.decimals(), currency.symbol())
                        + " игроку " + target.getName());
            } else {
                sender.sendMessage(plugin.getMessages().prefix() + "§cОперация не прошла (см. лог)");
            }
        } catch (LedgerException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
        }
    }

    private void take(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.take")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin take <ник> <валюта> <сумма>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[3]).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[3])));
            return;
        }
        double amount = parseAmount(args[4]);
        if (!(amount > 0.0D)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[4])));
            return;
        }
        try {
            boolean ok = plugin.getWallets().withdraw(target.getUniqueId(), currency.id(),
                    amount, TransactionType.ADMIN_TAKE, "admin:" + senderName(sender));
            if (ok) {
                sender.sendMessage(plugin.getMessages().prefix() + "§aСписано "
                        + Formatter.withSymbol(amount, currency.decimals(), currency.symbol())
                        + " у " + target.getName());
            } else {
                sender.sendMessage(plugin.getMessages().prefix() + "§cНедостаточно средств");
            }
        } catch (LedgerException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.set")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin set <ник> <валюта> <сумма>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[3]).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[3])));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[4])));
            return;
        }
        if (amount < 0.0D) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cБаланс не может быть отрицательным");
            return;
        }
        UUID uuid = target.getUniqueId();
        double old = plugin.getWallets().getBalance(uuid, currency.id());
        TransactionType type = amount >= old ? TransactionType.ADMIN_SET : TransactionType.ADMIN_SET;
        // Жёсткая установка: снимаем разницу или начисляем через deposit/withdraw
        boolean ok;
        if (Math.abs(old - amount) < 1.0E-9D) {
            ok = true;
        } else if (amount > old) {
            ok = plugin.getWallets().deposit(uuid, currency.id(), amount - old, type,
                    "admin-set:" + senderName(sender));
        } else {
            ok = plugin.getWallets().withdraw(uuid, currency.id(), old - amount, type,
                    "admin-set:" + senderName(sender));
        }
        if (ok) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aБаланс " + target.getName()
                    + " установлен в " + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "§cОперация не прошла (см. лог)");
        }
    }

    private void audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.audit")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        Player target;
        int limit = 10;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
                return;
            }
            if (args.length >= 4) {
                try {
                    limit = Math.max(1, Math.min(100, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-needs-nick", null));
            return;
        }
        List<Transaction> history = plugin.getLedger().queryTransactions(target.getUniqueId(), limit);
        sender.sendMessage(plugin.getMessages().prefix()
                + "Аудит " + target.getName() + " (последние " + history.size() + "):");
        for (Transaction tx : history) {
            String when = DT.format(Instant.ofEpochMilli(tx.timestampMillis()));
            String who = describeSide(tx, target.getUniqueId());
            Currency currency = plugin.getCurrencies().get(tx.currencyId()).orElse(null);
            String amountStr = Formatter.amount(tx.amount(), currency == null ? 2 : currency.decimals());
            String symbol = currency == null ? "" : " " + currency.symbol();
            sender.sendMessage(String.format("§7%s §f%s §e%s§f %s%s §7· %s §7· %s",
                    when, tx.type().name(), tx.currencyId(),
                    amountStr, symbol, who, tx.reason()));
        }
    }

    private String describeSide(Transaction tx, UUID focus) {
        if (tx.from() != null && tx.from().equals(focus)) {
            return "→ " + shortUuid(tx.to());
        }
        if (tx.to() != null && tx.to().equals(focus)) {
            return "← " + shortUuid(tx.from());
        }
        return shortUuid(tx.from()) + " → " + shortUuid(tx.to());
    }

    private String shortUuid(UUID uuid) {
        if (uuid == null) {
            return "server";
        }
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString().substring(0, 8);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        plugin.getRates().load(new java.io.File(plugin.getDataFolder(),
                plugin.getConfig().getString("exchange.rates-file", "rates.yml")));
        plugin.getMessages().load(new java.io.File(plugin.getDataFolder(),
                plugin.getConfig().getString("messages.file", "messages.yml")));
        sender.sendMessage(plugin.getMessages().prefix() + "§aКонфиги перезагружены (rates + messages)");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "Админ-команды:");
        sender.sendMessage("§e/rv admin give <ник> <валюта> <сумма>");
        sender.sendMessage("§e/rv admin take <ник> <валюта> <сумма>");
        sender.sendMessage("§e/rv admin set <ник> <валюта> <сумма>");
        sender.sendMessage("§e/rv admin audit [ник] [лимит]");
        sender.sendMessage("§e/rv admin reload");
        sender.sendMessage("§7mint/burn — этап 5 с Towny-интеграцией");
    }

    private double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String senderName(CommandSender sender) {
        return sender instanceof Player p ? p.getName() : "console";
    }
}
