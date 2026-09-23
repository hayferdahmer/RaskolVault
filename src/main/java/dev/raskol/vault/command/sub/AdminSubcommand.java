// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /rv admin (1.2.4.1): + selftest (EconomicInvariantAuditor).
 */
public final class AdminSubcommand {

    private final RaskolVault plugin;

    public AdminSubcommand(RaskolVault plugin) { this.plugin = plugin; }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void send(CommandSender s, String raw) { s.sendMessage(c(raw)); }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) {
            send(sender, plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) { help(sender); return; }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "health" -> health(sender);
            case "selftest" -> selftest(sender);
            case "balance" -> balance(sender, args);
            case "give" -> mutate(sender, args, "give");
            case "take" -> mutate(sender, args, "take");
            case "set" -> mutate(sender, args, "set");
            case "mint" -> mintBurn(sender, args, true);
            case "burn" -> mintBurn(sender, args, false);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            case "backup" -> backup(sender);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== RaskolVault admin ===");
        send(sender, "&f/rv admin health &7— метрики");
        send(sender, "&f/rv admin selftest &7— самопроверка инвариантов");
        send(sender, "&f/rv admin balance <ник>");
        send(sender, "&f/rv admin give|take|set <ник> <валюта> <сумма>");
        send(sender, "&f/rv admin mint|burn <валюта> <сумма>");
        send(sender, "&f/rv admin audit <ник> [лимит]");
        send(sender, "&f/rv admin reload | backup");
    }

    private void selftest(CommandSender sender) {
        var report = plugin.getAuditor().run();
        for (String line : report) send(sender, line);
        send(sender, plugin.getAuditor().hasFailures(report)
                ? "&cSelftest: есть нарушения — смотри FAIL-строки"
                : "&aSelftest: все инварианты держатся");
    }

    private void health(CommandSender sender) {
        send(sender, "&6=== Health ===");
        send(sender, "&7Writer queue: &f" + plugin.getWriter().queueSize()
                + " &7applied: &f" + plugin.getWriter().applied()
                + " &7failed: &f" + plugin.getWriter().failed());
        send(sender, "&7Cache rows: &f" + plugin.getWallets().cachedRows());
        send(sender, "&7Tx/min: &f" + plugin.getTxCounter().perMinute());
    }

    private void balance(CommandSender sender, String[] args) {
        if (args.length < 3) { send(sender, "&f/rv admin balance <ник>"); return; }
        UUID uuid = resolveUuid(args[2]);
        if (uuid == null) { send(sender, "&cИгрок не найден"); return; }
        for (Currency cur : plugin.getCurrencies().all()) {
            send(sender, "&7" + cur.id() + ": &f"
                    + Formatter.withSymbol(plugin.getWallets().getBalance(uuid, cur.id()), cur.decimals(), cur.symbol()));
        }
    }

    private void mutate(CommandSender sender, String[] args, String kind) {
        if (args.length < 5) { send(sender, "&f/rv admin " + kind + " <ник> <валюта> <сумма>"); return; }
        UUID uuid = resolveUuid(args[2]);
        if (uuid == null) { send(sender, "&cИгрок не найден"); return; }
        Currency cur = plugin.getCurrencies().get(args[3].toUpperCase(Locale.ROOT)).orElse(null);
        if (cur == null) { send(sender, "&cВалюта не найдена"); return; }
        double amount;
        try { amount = Double.parseDouble(args[4]); } catch (NumberFormatException e) { send(sender, "&cСумма"); return; }
        boolean ok = switch (kind) {
            case "give" -> plugin.getWallets().deposit(uuid, cur.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_GIVE, "admin");
            case "take" -> plugin.getWallets().withdraw(uuid, cur.id(), amount,
                    dev.raskol.vault.api.transaction.TransactionType.ADMIN_TAKE, "admin");
            default -> {
                double old = plugin.getWallets().getBalance(uuid, cur.id());
                double delta = amount - old;
                yield Math.abs(delta) < 1.0E-9D || (delta > 0
                        ? plugin.getWallets().deposit(uuid, cur.id(), delta, dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, "admin")
                        : plugin.getWallets().withdraw(uuid, cur.id(), -delta, dev.raskol.vault.api.transaction.TransactionType.ADMIN_SET, "admin"));
            }
        };
        send(sender, ok ? "&aГотово" : "&cОтказ");
    }

    private void mintBurn(CommandSender sender, String[] args, boolean mint) {
        if (args.length < 4) { send(sender, "&f/rv admin " + (mint ? "mint" : "burn") + " <валюта> <сумма>"); return; }
        Currency cur = plugin.getCurrencies().get(args[2].toUpperCase(Locale.ROOT)).orElse(null);
        if (cur == null || cur.type() != CurrencyType.NATIONAL) { send(sender, "&cТолько национальные"); return; }
        double amount;
        try { amount = Double.parseDouble(args[3]); } catch (NumberFormatException e) { send(sender, "&cСумма"); return; }
        boolean ok = mint
                ? plugin.getReserveBank().mint(cur.nationId(), cur.id(), amount, "admin")
                : plugin.getReserveBank().burnFromTreasury(cur.nationId(), cur, amount);
        send(sender, ok ? "&aГотово" : "&cОтказ");
    }

    private void audit(CommandSender sender, String[] args) {
        if (args.length < 3) { send(sender, "&f/rv admin audit <ник> [лимит]"); return; }
        UUID uuid = resolveUuid(args[2]);
        if (uuid == null) { send(sender, "&cИгрок не найден"); return; }
        int limit = args.length > 3 ? parseInt(args[3], 10) : 10;
        var txs = plugin.getLedger().queryTransactions(uuid, limit);
        for (var tx : txs) {
            send(sender, "&7" + tx.type() + " &f" + Formatter.amount(tx.amount(), 2) + " " + tx.currencyId() + " &7" + tx.reason());
        }
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        send(sender, "&aКонфиг перезагружен");
    }

    private void backup(CommandSender sender) {
        send(sender, "&7Бэкап выполняется фоновой задачей; смотри plugins/RaskolVault/backups/");
    }

    private UUID resolveUuid(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        var reg = plugin.getOfflinePlayerRegistry();
        return reg == null ? null : reg.resolveUuid(name);
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private enum CurrencyType { NATIONAL }
}
