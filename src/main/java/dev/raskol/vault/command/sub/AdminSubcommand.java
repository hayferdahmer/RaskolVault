// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
 * /rv admin … (1.1.2): минт/бёрн через ReserveBank (минт с сеньоражем).
 */
public final class AdminSubcommand {

    private final RaskolVault plugin;

    public AdminSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            args = Arrays.copyOfRange(args, 1, args.length);
        }
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
            case "reserve" -> reserveOp(sender, args);
            case "currency" -> new CurrencySubcommand(plugin).execute(sender, args);
            case "simulate-load" -> simulateLoad(sender, args);
            case "stress" -> send(sender, "&7Стресс с ботами — см. /rv admin simulate-load");
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "backup" -> backup(sender);
            case "restore" -> restore(sender, args);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, plugin.getMessages().prefix() + "&6=== RaskolVault admin ===");
        send(sender, "&f balance <ник> · give|take|set <ник> <валюта> <сумма>");
        send(sender, "&f mint|burn <валюта> <сумма> · reserve set|add <нация> <сумма>");
        send(sender, "&f currency list|rename|create|remove · audit <ник> [лимит]");
        send(sender, "&f health · reload · backup · restore <файл> · simulate-load [игроки] [tx]");
    }

    private void treasuryOp(CommandSender sender, String[] args, boolean mint) {
        String perm = mint ? "raskolvault.admin.mint" : "raskolvault.admin.burn";
        if (!sender.hasPermission(perm)) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 3) {
            send(sender, plugin.getMessages().prefix()
                    + "&f/rv admin " + (mint ? "mint" : "burn") + " <валюта> <сумма>");
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[1]).orElse(null);
        if (currency == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[1])));
            return;
        }
        if (currency.type() != dev.raskol.vault.api.currency.CurrencyType.NATIONAL) {
            send(sender, plugin.getMessages().prefix() + "&cТолько национальные валюты (NATIONAL)");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[2])));
            return;
        }
        if (!(amount > 0.0D)) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[2])));
            return;
        }
        String nation = currency.nationId();
        var bank = plugin.getReserveBank();
        boolean ok = mint
                ? bank.mintToTreasury(nation, currency, amount)
                : bank.burnFromTreasury(nation, currency, amount);
        if (ok && mint) {
            double fee = amount * bank.seigniorageRate();
            send(sender, plugin.getMessages().prefix() + "&aМинт &f"
                    + Formatter.amount(amount, currency.decimals()) + " " + currency.id()
                    + " &7в казну " + nation + " &7(сеньораж сожжён: &f"
                    + Formatter.amount(fee, currency.decimals()) + "&7)");
        } else if (ok) {
            send(sender, plugin.getMessages().prefix() + "&aБёрн &f"
                    + Formatter.amount(amount, currency.decimals()) + " " + currency.id()
                    + " &7из казны " + nation);
        } else {
            send(sender, plugin.getMessages().prefix() + "&cОперация не прошла (лимит покрытия или недостаточно средств)");
        }
    }

    private void reserveOp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            send(sender, plugin.getMessages().prefix() + "&f/rv admin reserve set|add <нация> <сумма>");
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        String nation = args[2];
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        if (!Double.isFinite(amount) || amount < 0.0D) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
            return;
        }
        var bank = plugin.getReserveBank();
        if (mode.equals("set")) {
            bank.reserveSet(nation, amount);
            send(sender, plugin.getMessages().prefix() + "&aРезерв " + nation + " установлен: "
                    + Formatter.amount(amount, 2) + " GLD");
        } else if (mode.equals("add")) {
            boolean ok = bank.reserveCredit(nation, amount);
            send(sender, plugin.getMessages().prefix() + (ok
                    ? "&aРезерв " + nation + " пополнен на " + Formatter.amount(amount, 2)
                    + " (итого " + Formatter.amount(bank.reserveOf(nation), 2) + ")"
                    : "&c✖ Резерв не пополнен"));
        } else {
            send(sender, plugin.getMessages().prefix() + "&f/rv admin reserve set|add <нация> <сумма>");
        }
    }

    private void health(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.health")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        send(sender, plugin.getMessages().prefix() + "&6=== RaskolVault Health ===");
        String tps1 = "-", tps5 = "-", tps15 = "-";
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length >= 3) {
                tps1 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[0]));
                tps5 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[1]));
                tps15 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[2]));
            }
        } catch (Throwable ignored) {
        }
        send(sender, "&7 TPS (1/5/15m): &f" + tps1 + " / " + tps5 + " / " + tps15);
        send(sender, "&7 Online: &f" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        send(sender, "&7 JVM memory: &f" + usedMb + " / " + maxMb + " MB");
        send(sender, "&7 SQLite pool: &f" + plugin.getLedger().poolIdle() + " idle / "
                + plugin.getLedger().poolSize() + " total · wait " + plugin.getLedger().poolWaiting());
        send(sender, "&7 Writer: &fqueue " + plugin.getWriter().queueSize()
                + " · applied " + plugin.getWriter().applied()
                + " · failed " + (plugin.getWriter().failed() == 0 ? "&a0&r" : "&c" + plugin.getWriter().failed()));
        send(sender, "&7 Cache: &frows " + plugin.getWallets().cachedRows()
                + " · hit-rate &e" + String.format(Locale.ROOT, "%.1f", plugin.getWallets().cacheHitRate()) + "%");
        send(sender, "&7 Tx/min (60s): &f" + plugin.getTxCounter().perMinute());
        send(sender, plugin.getMessages().prefix() + "&6======================");
    }

    private void adminBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.view")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            send(sender, plugin.getMessages().prefix() + "&f/rv admin balance <ник>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        send(sender, plugin.getMessages().prefix()
                + plugin.getMessages().get("balance.header", Map.of("player", args[1])));
        for (Currency cur : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(uuid, cur.id());
            send(sender, plugin.getMessages().get("balance.line", Map.of(
                    "display", cur.displayName(),
                    "amount", Formatter.withSymbol(bal, cur.decimals(), cur.symbol()))));
        }
    }

    private void nationInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-nation", null));
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("nation.not-in-nation", null));
            return;
        }
        var bank = plugin.getReserveBank();
        Currency national = null;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL
                    && nation.equalsIgnoreCase(cur.nationId())) {
                national = cur;
            }
        }
        send(sender, plugin.getMessages().prefix()
                + plugin.getMessages().get("nation.header", Map.of("nation", nation)));
        send(sender, "&7 Резерв: &f" + Formatter.amount(bank.reserveOf(nation), 2) + " GLD");
        if (national != null) {
            send(sender, "&7 Покрытие: &f" + String.format(Locale.ROOT, "%.1f%%",
                    bank.coverageOf(nation, national.id()) * 100.0D));
            send(sender, "&7 Цена: &f" + String.format(Locale.ROOT, "%.4f", bank.priceOf(national)) + " GLD");
        }
        send(sender, "&7 Паритет: &f" + String.format(Locale.ROOT, "%.2f", bank.parityOf(nation))
                + " &7· Налог: &f" + String.format(Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D));
    }

    private void mutate(CommandSender sender, String[] args, String kind) {
        if (!sender.hasPermission("raskolvault.admin." + kind)) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            send(sender, plugin.getMessages().prefix() + "&f/rv admin " + kind + " <ник> <валюта> <сумма>");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
            return;
        }
        Currency currency = plugin.getCurrencies().get(args[2]).orElse(null);
        if (currency == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[3])));
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
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.storage", null));
            return;
        }
        double neu = plugin.getWallets().getBalance(uuid, currency.id());
        send(sender, plugin.getMessages().prefix() + "&a" + kind + " &f" + args[1] + ": &f"
                + Formatter.withSymbol(neu, currency.decimals(), currency.symbol()));
    }

    private void simulateLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        int players = 50;
        int txs = 5000;
        try {
            if (args.length >= 2) players = Integer.parseInt(args[1]);
            if (args.length >= 3) txs = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            send(sender, plugin.getMessages().prefix() + "&cНеверный формат числа");
            return;
        }
        send(sender, plugin.getMessages().prefix() + "&eНагрузочный тест: " + players + " × " + txs + " …");
        plugin.getLoadSimulator().run(sender, players, txs);
    }

    private void audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.audit")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            send(sender, plugin.getMessages().prefix() + "&f/rv admin audit <ник> [лимит]");
            return;
        }
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
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
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.storage", null));
            return;
        }
        send(sender, plugin.getMessages().prefix() + "&6Аудит " + args[1] + " (&f" + history.size() + "&6):");
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        for (Transaction tx : history) {
            Currency cur = plugin.getCurrencies().get(tx.currencyId()).orElse(null);
            send(sender, "&7 " + fmt.format(new Date(tx.timestampMillis()))
                    + " &f" + tx.type() + " " + Formatter.amount(tx.amount(), cur == null ? 2 : cur.decimals())
                    + " " + tx.currencyId() + " &7· " + tx.reason());
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        plugin.reloadConfig();
        plugin.getMessages().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("messages.file", "messages.yml")));
        plugin.getRates().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("exchange.rates-file", "rates.yml")));
        send(sender, plugin.getMessages().prefix() + "&aКонфиги перезагружены");
    }

    private void backup(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.backup")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        try {
            long pages = plugin.getLedger().checkpoint();
            File src = plugin.getLedger().dbFile();
            File dst = new File(plugin.getDataFolder(), "backups/manual-" + System.currentTimeMillis() + ".sqlite");
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                send(sender, plugin.getMessages().prefix() + "&cНе могу создать папку backups/");
                return;
            }
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            send(sender, plugin.getMessages().prefix() + "&aБекап: &f" + dst.getName()
                    + " &7(WAL " + pages + " стр.)");
        } catch (Exception e) {
            send(sender, plugin.getMessages().prefix() + "&cБекап не удался: " + e.getMessage());
        }
    }

    private void restore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.backup")) {
            send(sender, plugin.getMessages().prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) {
            send(sender, plugin.getMessages().prefix()
                    + "&f/rv admin restore <файл.sqlite> &7(из plugins/RaskolVault/backups/)");
            return;
        }
        File backupFile = new File(plugin.getDataFolder(), "backups/" + args[1]);
        if (!backupFile.exists()) {
            send(sender, plugin.getMessages().prefix() + "&cФайл не найден: backups/" + args[1]);
            return;
        }
        send(sender, plugin.getMessages().prefix() + "&eRestore требует стопа сервера:");
        send(sender, "&7 1) /stop");
        send(sender, "&7 2) cp plugins/RaskolVault/backups/" + args[1]
                + " plugins/RaskolVault/data/ledger.sqlite");
        send(sender, "&7 3) start");
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
}
