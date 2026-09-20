// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /rv admin … — админ-блок. 1.1.0-a: добавлен `balance <ник>` (смотрелка балансов),
 * `nation` проброшен из корневого роутера.
 */
public final class AdminSubcommand {

    private final RaskolVault plugin;

    public AdminSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            help(sender);
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "health" -> health(sender);
            case "balance" -> adminBalance(sender, args);
            case "nation" -> nationInfo(sender, args);
            case "give" -> mutate(sender, args, "give");
            case "take" -> mutate(sender, args, "take");
            case "set" -> mutate(sender, args, "set");
            case "mint" -> treasuryOp(sender, args, true);
            case "burn" -> treasuryOp(sender, args, false);
            case "currency" -> new CurrencySubcommand(plugin).execute(sender, prependAdmin(args));
            case "simulate" -> simulate(sender);
            case "simulate-load" -> simulateLoad(sender, args);
            case "stress" -> stress(sender);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "backup" -> backup(sender);
            case "restore" -> restore(sender, args);
            default -> help(sender);
        }
    }

    /** ["admin", X, ...] → ["X", ...] для CurrencySubcommand, который ждёт args[0]=подкоманду. */
    private String[] prependAdmin(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(prefix() + "&6=== RaskolVault admin ===");
        sender.sendMessage("&f balance <ник> · give|take|set <ник> <валюта> <сумма>");
        sender.sendMessage("&f mint|burn <валюта> <сумма> · currency list|rename|create|remove");
        sender.sendMessage("&f audit <ник> [лимит] · health · reload · backup · restore <файл>");
    }

    /** 1.1.0-a: админская смотрелка балансов (онлайн и оффлайн). */
    private void adminBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.view")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + "&f/rv admin balance <ник>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        sender.sendMessage(prefix() + plugin.getMessages().get("balance.header", Map.of("player", args[1])));
        for (Currency c : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(uuid, c.id());
            sender.sendMessage(plugin.getMessages().get("balance.line", Map.of(
                    "display", c.displayName(),
                    "amount", Formatter.withSymbol(bal, c.decimals(), c.symbol())));
        }
    }

    private void nationInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-nation", null));
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String nationId = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nationId == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("nation.not-in-nation", null));
            return;
        }
        sender.sendMessage(prefix() + plugin.getMessages().get("nation.header", Map.of("nation", nationId)));
        boolean isKing = plugin.getTownyHook().isKing(player.getUniqueId(), nationId);
        sender.sendMessage(prefix() + "&7 Роль: &f" + (isKing ? "Король" : "Резидент"));
        Currency national = plugin.getCurrencies().get(nationId).orElse(null);
        if (national != null && national.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL) {
            double treasury = plugin.getTreasury().balance(nationId, national.id());
            sender.sendMessage(prefix() + "&7 Казна: &f"
                    + Formatter.withSymbol(treasury, national.decimals(), national.symbol()));
        } else {
            sender.sendMessage(prefix() + "&7 Валюта нации не создана");
        }
    }

    private void mutate(CommandSender sender, String[] args, String kind) {
        if (!sender.hasPermission("raskolvault.admin." + kind)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(prefix() + "&f/rv admin " + kind + " <ник> <валюта> <сумма>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[2]).orElse(null);
        if (currency == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        String reason = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "admin";
        boolean ok;
        switch (kind) {
            case "give" -> ok = plugin.getWallets().deposit(uuid, currency.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_GIVE, reason);
            case "take" -> ok = amount > 0 && plugin.getWallets().withdraw(uuid, currency.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_TAKE, reason);
            default -> {
                double old = plugin.getWallets().getBalance(uuid, currency.id());
                double delta = amount - old;
                ok = Math.abs(delta) < 1.0E-9D || (delta > 0
                        ? plugin.getWallets().deposit(uuid, currency.id(), delta,
                        dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, reason)
                        : plugin.getWallets().withdraw(uuid, currency.id(), -delta,
                        dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, reason));
            }
        }
        if (!ok) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.storage", null));
            return;
        }
        double neu = plugin.getWallets().getBalance(uuid, currency.id());
        sender.sendMessage(prefix() + "&a" + kind + " &f" + args[1] + ": &f"
                + Formatter.withSymbol(neu, currency.decimals(), currency.symbol()));
    }

    private void treasuryOp(CommandSender sender, String[] args, boolean mint) {
        String perm = mint ? "raskolvault.admin.mint" : "raskolvault.admin.burn";
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(prefix() + "&f/rv admin " + (mint ? "mint" : "burn") + " <валюта> <сумма>");
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[1]).orElse(null);
        if (currency == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[1])));
            return;
        }
        if (currency.type() != dev.raskol.vault.api.currency.CurrencyType.NATIONAL) {
            sender.sendMessage(prefix() + "&cТолько национальные валюты (NATIONAL)");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[2])));
            return;
        }
        if (!(amount > 0.0D)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[2])));
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "admin";
        boolean ok = mint
                ? plugin.getTreasury().deposit(currency.nationId(), currency.id(), amount, reason)
                : plugin.getTreasury().withdraw(currency.nationId(), currency.id(), amount, reason);
        sender.sendMessage(prefix() + (ok
                ? "&a" + (mint ? "mint" : "burn") + " &f" + Formatter.withSymbol(amount, currency.decimals(), currency.symbol())
                : "&cОперация не прошла"));
    }

    private void simulate(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        int loops = plugin.getArbitrage().scan().size();
        sender.sendMessage(prefix() + "&7Арбитражных петель: &f" + loops);
        plugin.getArbitrage().logReport();
    }

    private void simulateLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        int players = 50;
        int txs = 5000;
        try {
            if (args.length >= 2) players = Integer.parseInt(args[1]);
            if (args.length >= 3) txs = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(prefix() + "&cНеверный формат числа");
            return;
        }
        sender.sendMessage(prefix() + "&eНагрузочный тест: " + players + " × " + txs + " …");
        plugin.getLoadSimulator().run(sender, players, txs);
    }

    private void stress(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(prefix() + "&7Стресс с ботами — этап 1.1.0-d (Citizens). Сейчас: /rv admin simulate-load");
    }

    private void audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.audit")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + "&f/rv admin audit <ник> [лимит]");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        int limit = 10;
        try {
            if (args.length >= 3) limit = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
        }
        List<Transaction> history;
        try {
            history = plugin.getLedger().queryTransactions(uuid, Math.max(1, limit));
        } catch (LedgerException e) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.storage", null));
            return;
        }
        sender.sendMessage(prefix() + "&6Аудит " + args[1] + " (&f" + history.size() + "&6):");
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        for (Transaction tx : history) {
            Currency c = plugin.getCurrencies().get(tx.currencyId()).orElse(null);
            sender.sendMessage("&7 " + fmt.format(new Date(tx.timestampMillis()))
                    + " &f" + tx.type() + " " + Formatter.amount(tx.amount(), c == null ? 2 : c.decimals())
                    + " " + tx.currencyId() + " &7· " + tx.reason());
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        plugin.reloadConfig();
        plugin.getMessages().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("messages.file", "messages.yml")));
        plugin.getRates().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("exchange.rates-file", "rates.yml")));
        plugin.getArbitrage().logReport();
        sender.sendMessage(prefix() + "&aКонфиги перезагружены");
    }

    private void backup(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.backup")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        try {
            long pages = plugin.getLedger().checkpoint();
            File src = plugin.getLedger().dbFile();
            File dst = new File(plugin.getDataFolder(), "backups/manual-" + System.currentTimeMillis() + ".sqlite");
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                sender.sendMessage(prefix() + "&cНе могу создать папку backups/");
                return;
            }
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            sender.sendMessage(prefix() + "&aБекап: &f" + dst.getName() + " &7(WAL " + pages + " стр.)");
        } catch (Exception e) {
            sender.sendMessage(prefix() + "&cБекап не удался: " + e.getMessage());
        }
    }

    private void restore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.backup")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + "&f/rv admin restore <файл.sqlite> &7(из plugins/RaskolVault/backups/)");
            return;
        }
        File backupFile = new File(plugin.getDataFolder(), "backups/" + args[1]);
        if (!backupFile.exists()) {
            sender.sendMessage(prefix() + "&cФайл не найден: backups/" + args[1]);
            return;
        }
        sender.sendMessage(prefix() + "&eRestore требует стопа сервера:");
        sender.sendMessage("&7 1) /stop");
        sender.sendMessage("&7 2) cp plugins/RaskolVault/backups/" + args[1] + " plugins/RaskolVault/data/ledger.sqlite");
        sender.sendMessage("&7 3) start");
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        if (plugin.getOfflinePlayerRegistry() != null) {
            return plugin.getOfflinePlayerRegistry().resolveUuid(name);
        }
        return null;
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }
}
