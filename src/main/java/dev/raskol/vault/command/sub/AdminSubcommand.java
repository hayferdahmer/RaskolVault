// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
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
    private final CurrencySubcommand currency;

    public AdminSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
        this.currency = new CurrencySubcommand(plugin);
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
            case "mint" -> mint(sender, args);
            case "burn" -> burn(sender, args);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "simulate" -> simulate(sender);
            case "simulate-load" -> simulateLoad(sender, args);
            case "backup" -> backup(sender);
            case "currency" -> currency.execute(sender, args);
            default -> sendHelp(sender);
        }
    }

    // ---------- mint / burn (казна нации, только король) ----------

    private void mint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-mint", null));
            return;
        }
        if (!sender.hasPermission("raskolvault.admin.mint")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin mint <валюта> <сумма>");
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String currencyId = args[2].toUpperCase(Locale.ROOT);
        Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
        if (currency == null || currency.type() != CurrencyType.NATIONAL) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.not-national-currency", Map.of("id", currencyId)));
            return;
        }
        double amount = parseAmount(args[3]);
        if (!(amount > 0.0D)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String nationId = currency.nationId();
        if (!plugin.getTownyHook().isKing(player.getUniqueId(), nationId)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.not-king", Map.of("nation", nationId)));
            return;
        }
        boolean ok = plugin.getTreasury().deposit(nationId, currencyId, amount,
                "mint-by-king:" + player.getName());
        if (ok) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aМинт в казну " + nationId + ": "
                    + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "§cМинт не прошёл (см. лог)");
        }
    }

    private void burn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-mint", null));
            return;
        }
        if (!sender.hasPermission("raskolvault.admin.burn")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin burn <валюта> <сумма>");
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String currencyId = args[2].toUpperCase(Locale.ROOT);
        Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
        if (currency == null || currency.type() != CurrencyType.NATIONAL) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.not-national-currency", Map.of("id", currencyId)));
            return;
        }
        double amount = parseAmount(args[3]);
        if (!(amount > 0.0D)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String nationId = currency.nationId();
        if (!plugin.getTownyHook().isKing(player.getUniqueId(), nationId)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.not-king", Map.of("nation", nationId)));
            return;
        }
        boolean ok = plugin.getTreasury().withdraw(nationId, currencyId, amount,
                "burn-by-king:" + player.getName());
        if (ok) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aБёрн из казны " + nationId + ": "
                    + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "§cНедостаточно средств в казне");
        }
    }

    // ---------- арбитраж и нагрузка ----------

    private void simulate(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "Запуск арбитражного сканера...");
        var loops = plugin.getArbitrage().scan();
        if (loops.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aПетель не найдено ✓");
            plugin.getArbitrage().logReport();
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§cНайдено " + loops.size() + " петель:");
        for (var loop : loops) {
            sender.sendMessage("  §e" + loop.describe());
        }
        plugin.getArbitrage().logReport();
    }

    private void simulateLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length >= 3 && "cleanup".equalsIgnoreCase(args[2])) {
            plugin.getLoadSimulator().cleanup(sender);
            return;
        }
        int players = 50;
        int txs = 1000;
        if (args.length >= 3) {
            try { players = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { }
        }
        if (args.length >= 4) {
            try { txs = Integer.parseInt(args[3]); } catch (NumberFormatException ignored) { }
        }
        plugin.getLoadSimulator().run(sender, players, txs);
    }

    // ---------- бекап ----------

    private void backup(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§eСоздаю бекап SQLite...");
        File file = plugin.getBackups().runBackupNow();
        if (file != null) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aБекап создан: §f"
                    + file.getName() + " (" + humanSize(file.length()) + ")");
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "§cБекап не удался (см. лог)");
        }
    }

    // ---------- give / take / set ----------

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
        Currency currency = plugin.getCurrencies().get(args[3].toUpperCase(Locale.ROOT)).orElse(null);
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
        Currency currency = plugin.getCurrencies().get(args[3].toUpperCase(Locale.ROOT)).orElse(null);
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
        Currency currency = plugin.getCurrencies().get(args[3].toUpperCase(Locale.ROOT)).orElse(null);
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
        boolean ok;
        if (Math.abs(old - amount) < 1.0E-9D) {
            ok = true;
        } else if (amount > old) {
            ok = plugin.getWallets().deposit(uuid, currency.id(), amount - old,
                    TransactionType.ADMIN_SET, "admin-set:" + senderName(sender));
        } else {
            ok = plugin.getWallets().withdraw(uuid, currency.id(), old - amount,
                    TransactionType.ADMIN_SET, "admin-set:" + senderName(sender));
        }
        if (ok) {
            sender.sendMessage(plugin.getMessages().prefix() + "§aБаланс " + target.getName()
                    + " установлен в " + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "§cОперация не прошла (см. лог)");
        }
    }

    // ---------- аудит ----------

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
                } catch (NumberFormatException ignored) { }
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

    // ---------- reload ----------

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        plugin.getRates().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("exchange.rates-file", "rates.yml")));
        plugin.getMessages().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("messages.file", "messages.yml")));
        plugin.getArbitrage().logReport();
        sender.sendMessage(plugin.getMessages().prefix()
                + "§aКонфиги перезагружены (rates + messages), арбитражный сканер запущен");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "Админ-команды:");
        sender.sendMessage("§e/rv admin give|take|set <ник> <валюта> <сумма>");
        sender.sendMessage("§e/rv admin mint|burn <нац. валюта> <сумма> — только король нации");
        sender.sendMessage("§e/rv admin currency list|create|remove");
        sender.sendMessage("§e/rv admin audit [ник] [лимит]");
        sender.sendMessage("§e/rv admin simulate — арбитражный сканер");
        sender.sendMessage("§e/rv admin simulate-load [игроки] [транзакции] — нагрузочный тест");
        sender.sendMessage("§e/rv admin simulate-load cleanup — очистить виртуальные кошельки");
        sender.sendMessage("§e/rv admin backup — ручной бекап SQLite");
        sender.sendMessage("§e/rv admin reload");
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

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
