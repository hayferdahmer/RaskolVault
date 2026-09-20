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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /rv admin (1.0.6: + `currency rename <old> <new>`).
 */
public final class AdminSubcommand {

    private final RaskolVault plugin;

    public AdminSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin <sub> ...");
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "health" -> health(sender);
            case "give" -> give(sender, args);
            case "take" -> take(sender, args);
            case "set" -> set(sender, args);
            case "mint" -> mint(sender, args);
            case "burn" -> burn(sender, args);
            case "currency" -> currency(sender, args);
            case "simulate" -> simulate(sender, args);
            case "simulate-load" -> simulateLoad(sender, args);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "backup" -> backup(sender);
            default -> sender.sendMessage(plugin.getMessages().prefix() + "§cНеизвестная подкоманда: " + op);
        }
    }

    private void health(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.health")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§e╔══ §fRaskolVault Health §e══╗");

        String tps1 = "-";
        String tps5 = "-";
        String tps15 = "-";
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length >= 3) {
                tps1 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[0]));
                tps5 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[1]));
                tps15 = String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[2]));
            }
        } catch (Throwable ignored) {
        }
        sender.sendMessage("§7TPS (1/5/15m): §f" + tps1 + " / " + tps5 + " / " + tps15);
        sender.sendMessage("§7Online: §f" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        sender.sendMessage("§7JVM memory: §f" + usedMb + " / " + maxMb + " MB");

        sender.sendMessage("§7SQLite pool: §f" + plugin.getLedger().poolIdle()
                + " idle / " + plugin.getLedger().poolSize() + " total"
                + " · wait " + plugin.getLedger().poolWaiting());

        sender.sendMessage("§7Writer: §fqueue " + plugin.getWriter().queueSize()
                + " · applied " + plugin.getWriter().applied()
                + " · failed " + (plugin.getWriter().failed() == 0 ? "§a0§r" : "§c" + plugin.getWriter().failed()));

        sender.sendMessage("§7Cache: §frows " + plugin.getWallets().cachedRows()
                + " · hit-rate §e" + String.format(Locale.ROOT, "%.1f", plugin.getWallets().cacheHitRate()) + "%§r"
                + " · H/M " + plugin.getWallets().cacheHits() + "/" + plugin.getWallets().cacheMisses());

        sender.sendMessage("§7Tx/min (60s window): §f" + plugin.getTxCounter().count());

        File wal = new File(plugin.getLedger().dbFile().getAbsolutePath() + "-wal");
        long walMb = wal.exists() ? wal.length() / (1024L * 1024L) : -1L;
        sender.sendMessage("§7WAL size: §f" + (walMb < 0 ? "—" : walMb + " MB"));

        long lastTs = plugin.getLedger().lastTransactionTimestamp();
        if (lastTs > 0) {
            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastTs));
            long agoSec = (System.currentTimeMillis() - lastTs) / 1000L;
            sender.sendMessage("§7Last tx: §f" + formatted + " §7(" + agoSec + "s ago)");
        } else {
            sender.sendMessage("§7Last tx: §8(none)");
        }

        int loops = plugin.getArbitrage().scan().size();
        sender.sendMessage("§7Arbitrage loops: " + (loops == 0 ? "§a0 ✓§r" : "§c" + loops + " ⚠"));

        sender.sendMessage("§7Offline-registry: §f"
                + (plugin.getOfflinePlayerRegistry() == null ? "null" : plugin.getOfflinePlayerRegistry().size()));

        sender.sendMessage(plugin.getMessages().prefix() + "§e╚═════════════════════════╝");
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.give")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin give <ник> <валюта> <сумма> [причина]");
            return;
        }
        mutateBalance(sender, args, true, false);
    }

    private void take(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.take")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin take <ник> <валюта> <сумма> [причина]");
            return;
        }
        mutateBalance(sender, args, false, false);
    }

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.set")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin set <ник> <валюта> <сумма> [причина]");
            return;
        }
        mutateBalance(sender, args, false, true);
    }

    private void mint(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.mint")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin mint <валюта> <сумма> [причина]");
            return;
        }
        treasuryOp(sender, args, true);
    }

    private void burn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.burn")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin burn <валюта> <сумма> [причина]");
            return;
        }
        treasuryOp(sender, args, false);
    }

    private void mutateBalance(CommandSender sender, String[] args, boolean positive, boolean absolute) {
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
            return;
        }
        String currencyId = args[3].toUpperCase(Locale.ROOT);
        Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
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
        if (!(amount > 0.0D) && !absolute) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", args[4])));
            return;
        }
        String reason = args.length > 5
                ? String.join(" ", Arrays.copyOfRange(args, 5, args.length))
                : "admin";
        UUID uuid = target.getUniqueId();
        double old = plugin.getWallets().getBalance(uuid, currency.id());
        boolean ok;
        if (absolute) {
            double delta = amount - old;
            if (Math.abs(delta) < 1.0E-9D) {
                sender.sendMessage(plugin.getMessages().prefix()
                        + "§eБаланс уже равен " + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
                return;
            }
            ok = delta > 0
                    ? plugin.getWallets().deposit(uuid, currency.id(), delta,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, reason)
                    : plugin.getWallets().withdraw(uuid, currency.id(), -delta,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, reason);
        } else {
            ok = positive
                    ? plugin.getWallets().deposit(uuid, currency.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_GIVE, reason)
                    : plugin.getWallets().withdraw(uuid, currency.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_TAKE, reason);
        }
        if (!ok) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
            return;
        }
        double neu = plugin.getWallets().getBalance(uuid, currency.id());
        sender.sendMessage(plugin.getMessages().prefix() + "§a" + (absolute ? "set" : (positive ? "give" : "take"))
                + " §f" + target.getName() + ": §f"
                + Formatter.withSymbol(old, currency.decimals(), currency.symbol()) + " → "
                + Formatter.withSymbol(neu, currency.decimals(), currency.symbol()));
    }

    private void treasuryOp(CommandSender sender, String[] args, boolean mint) {
        String currencyId = args[2].toUpperCase(Locale.ROOT);
        Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", args[2])));
            return;
        }
        if (currency.type() != dev.raskol.vault.api.currency.CurrencyType.NATIONAL) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§cMint/burn доступны только для национальных валют (NATIONAL).");
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
        String reason = args.length > 4
                ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                : "admin";
        UUID treasuryUuid = UUID.nameUUIDFromBytes(
                ("nation:" + currency.nationId()).getBytes(StandardCharsets.UTF_8));
        boolean ok = mint
                ? plugin.getWallets().deposit(treasuryUuid, currency.id(), amount,
                dev.raskol.vault.api.transaction.TransactionType.MINT, reason)
                : plugin.getWallets().withdraw(treasuryUuid, currency.id(), amount,
                dev.raskol.vault.api.transaction.TransactionType.BURN, reason);
        if (!ok) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§a" + (mint ? "mint" : "burn")
                + " §fв казну " + currency.nationId() + ": "
                + Formatter.withSymbol(amount, currency.decimals(), currency.symbol()));
    }

    private void currency(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.currency")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin currency <list|rename|create|remove> ...");
            return;
        }
        String op = args[2].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                sender.sendMessage(plugin.getMessages().prefix() + "§eВалюты в реестре:");
                for (Currency c : plugin.getCurrencies().all()) {
                    sender.sendMessage("  §f" + c.id() + " §7(" + c.displayName() + ", " + c.symbol()
                            + ", " + c.type() + (c.nationId() != null ? ", nation=" + c.nationId() : "")
                            + ", tradeable=" + c.tradeable() + ")");
                }
            }
            case "rename" -> currencyRename(sender, args);
            case "create", "remove" -> sender.sendMessage(plugin.getMessages().prefix()
                    + "§cСоздание/удаление валют через CLI в 1.0.x не поддерживается. Правь currencies.yml и /rv admin reload.");
            default -> sender.sendMessage(plugin.getMessages().prefix() + "§cОперация: " + op);
        }
    }

    /**
     * 1.0.6: /rv admin currency rename <old> <new>.
     * Атомарно переименовывает валюту: в currencies, balances, transactions (в БД) и в реестре в памяти.
     * Требует простоя (никто не делает операции с этой валютой в момент переименования).
     */
    private void currencyRename(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§e/rv admin currency rename <old-id> <new-id>");
            return;
        }
        String oldId = args[3].toUpperCase(Locale.ROOT);
        String newId = args[4].toUpperCase(Locale.ROOT);
        if (oldId.equals(newId)) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cID совпадают: " + oldId);
            return;
        }
        Currency old = plugin.getCurrencies().get(oldId).orElse(null);
        if (old == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", oldId)));
            return;
        }
        if (plugin.getCurrencies().get(newId).isPresent()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§cВалюта '" + newId + "' уже существует — выбери другой ID");
            return;
        }
        try {
            plugin.getLedger().renameCurrency(oldId, newId);
        } catch (LedgerException e) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cОшибка БД: " + e.getMessage());
            return;
        }
        boolean renamed = plugin.getCurrencies().rename(oldId, newId);
        if (!renamed) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§cБД обновлена, но реестр не обновился — перезагрузи плагин");
            return;
        }
        try {
            plugin.getLedger().upsertCurrency(plugin.getCurrencies().get(newId).orElseThrow());
        } catch (LedgerException ignored) {
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + "§aВалюта переименована: §f" + oldId + " §7→ §f" + newId);
        sender.sendMessage(plugin.getMessages().prefix()
                + "§7Все балансы и история транзакций перенесены на новый ID.");
    }

    private void simulate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + "Симуляция арбитража: петель найдено §f" + plugin.getArbitrage().scan().size() + "§r");
        plugin.getArbitrage().logReport();
    }

    private void simulateLoad(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        int players = 50;
        int txs = 5000;
        try {
            if (args.length >= 3) players = Integer.parseInt(args[2]);
            if (args.length >= 4) txs = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cНеверный формат числа");
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§eЗапуск нагрузочного теста: "
                + players + " игроков × " + txs + " tx...");
        plugin.getLoadSimulator().run(sender, players, txs);
    }

    private void audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.audit")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin audit <ник> [лимит]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.player-not-found", Map.of("name", args[2])));
            return;
        }
        int limit = 10;
        try {
            if (args.length >= 4) limit = Integer.parseInt(args[3]);
        } catch (NumberFormatException ignored) {
        }
        List<Transaction> history;
        try {
            history = plugin.getLedger().queryTransactions(target.getUniqueId(), Math.max(1, limit));
        } catch (LedgerException e) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.storage", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§eАудит " + target.getName()
                + " (последние " + history.size() + "):");
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        for (Transaction tx : history) {
            String time = fmt.format(new Date(tx.timestampMillis()));
            String from = tx.from() == null ? "mint/system" : shortUuid(tx.from());
            String to = tx.to() == null ? "burn/system" : shortUuid(tx.to());
            Currency currency = plugin.getCurrencies().get(tx.currencyId()).orElse(null);
            String symbol = currency == null ? "" : currency.symbol();
            sender.sendMessage("§7[" + time + "] §f" + tx.type()
                    + " §7" + from + "→" + to
                    + " §e" + Formatter.amount(tx.amount(), currency == null ? 2 : currency.decimals())
                    + " " + symbol + " " + tx.currencyId()
                    + " §7· " + tx.reason());
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.reload")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        plugin.reloadConfig();
        plugin.getMessages().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("messages.file", "messages.yml")));
        plugin.getRates().load(new File(plugin.getDataFolder(),
                plugin.getConfig().getString("exchange.rates-file", "rates.yml")));
        plugin.getArbitrage().logReport();
        sender.sendMessage(plugin.getMessages().prefix() + "§aКонфиги перезагружены.");
    }

    private void backup(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.backup")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (plugin.getBackups() == null) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cБекап-сервис не активен.");
            return;
        }
        try {
            long pages = plugin.getLedger().checkpoint();
            File src = plugin.getLedger().dbFile();
            File dst = new File(plugin.getDataFolder(),
                    "backups/manual-" + System.currentTimeMillis() + ".sqlite");
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                sender.sendMessage(plugin.getMessages().prefix() + "§cНе могу создать папку backups/");
                return;
            }
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§aWAL checkpoint (" + pages + " стр.) + копия БД: §f"
                    + dst.getPath());
        } catch (Exception e) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cБекап не удался: " + e.getMessage());
        }
    }

    private String shortUuid(UUID uuid) {
        Optional<? extends Player> player = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getUniqueId().equals(uuid)).findFirst();
        if (player.isPresent()) {
            return player.get().getName();
        }
        if (plugin.getOfflinePlayerRegistry() != null) {
            String name = plugin.getOfflinePlayerRegistry().resolveName(uuid);
            if (name != null) {
                return name;
            }
        }
        String s = uuid.toString();
        return s.substring(0, 8);
    }
}
