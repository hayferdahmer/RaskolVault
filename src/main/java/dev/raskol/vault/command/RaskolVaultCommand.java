// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.offline.OfflinePlayerRegistry;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of(
            "balance", "pay", "convert", "confirm", "debug", "admin", "help", "version");
    private static final List<String> ADMIN_SUB = List.of(
            "health", "give", "take", "set", "mint", "burn",
            "currency", "simulate", "simulate-load", "audit", "reload", "backup", "restore", "stress");
    private static final List<String> CURRENCY_OPS = List.of("list", "rename", "create", "remove");

    private final RaskolVault plugin;
    private final ConvertSubcommand convertSubcommand;
    private final PaySubcommand paySubcommand;
    private final AdminSubcommand adminSubcommand;
    private final OfflinePlayerRegistry offlineRegistry;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convertSubcommand) {
        this.plugin = plugin;
        this.convertSubcommand = convertSubcommand;
        this.paySubcommand = new PaySubcommand(plugin);
        this.adminSubcommand = new AdminSubcommand(plugin);
        this.offlineRegistry = plugin.getOfflinePlayerRegistry();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "balance", "bal" -> balance(sender, args);
            case "pay" -> paySubcommand.execute(sender, args);
            case "convert" -> convertSubcommand.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "debug" -> debug(sender);
            case "admin" -> adminSubcommand.execute(sender, args);
            case "version" -> sender.sendMessage(plugin.getMessages().prefix()
                    + "§fRaskolVault §e" + plugin.getPluginMeta().getVersion());
            case "help", "?" -> help(sender);
            default -> sender.sendMessage(plugin.getMessages().prefix() + "§cНеизвестная команда: " + op);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "§e╔══ §fКоманды RaskolVault §e══╗");
        sender.sendMessage("§f/rv balance [ник] §7— свой или чужой кошелёк");
        sender.sendMessage("§f/rv pay <ник> <валюта> <сумма> [причина]");
        sender.sendMessage("§f/rv convert <из> <в> <сумма> §7— preview обмена");
        sender.sendMessage("§f/rv confirm §7— подтвердить ожидающий обмен");
        sender.sendMessage("§f/rv admin health §7— живая сводка");
        sender.sendMessage("§f/rv admin stress <players> <txs> §7— нагрузочный тест");
        sender.sendMessage("§f/rv admin <give|take|set|mint|burn|currency|audit|reload|backup|restore|simulate>");
        sender.sendMessage(plugin.getMessages().prefix() + "§e╚════════════════════════╝");
    }

    private void balance(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) && args.length < 2) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv balance <ник>");
            return;
        }
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("raskolvault.admin.view")) {
                sender.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("error.no-permission", null));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("error.player-not-found", Map.of("name", args[1])));
                return;
            }
        } else {
            target = player;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("balance.header", Map.of("player", target.getName())));
        int shown = 0;
        for (Currency currency : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(target.getUniqueId(), currency.id());
            sender.sendMessage(plugin.getMessages().get("balance.line", Map.of(
                    "symbol", currency.symbol(),
                    "display", currency.displayName(),
                    "amount", Formatter.amount(bal, currency.decimals()))));
            shown++;
        }
        if (shown == 0) {
            sender.sendMessage(plugin.getMessages().get("balance.empty", null));
        }
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        var pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("confirm.none", null));
            return;
        }
        convertSubcommand.runConfirmed(player, pending.get().preview(), sender);
    }

    private void debug(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§e=== DEBUG ===");
        sender.sendMessage("§7stats: §f" + plugin.getLedger().describeStats());
        sender.sendMessage("§7writer: §fqueue=" + plugin.getWriter().queueSize()
                + " applied=" + plugin.getWriter().applied() + " failed=" + plugin.getWriter().failed());
        sender.sendMessage("§7cache: §frows=" + plugin.getWallets().cachedRows()
                + " hit=" + plugin.getWallets().cacheHits()
                + " miss=" + plugin.getWallets().cacheMisses()
                + " rate=" + String.format(Locale.ROOT, "%.1f", plugin.getWallets().cacheHitRate()) + "%");
        sender.sendMessage("§7tx/min: §f" + plugin.getTxCounter().count());
        sender.sendMessage("§7offline-registry: §f" + (offlineRegistry == null ? "null" : offlineRegistry.size()));
        sender.sendMessage("§7arbitrage loops: §f" + plugin.getArbitrage().scan().size());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (op) {
                case "balance" -> {
                    return filter(offlineRegistry.matchNames(args[1], 50), args[1]);
                }
                case "pay" -> {
                    return filter(offlineRegistry.matchNames(args[1], 50), args[1]);
                }
                case "convert" -> {
                    return filter(currencyIds(), args[1]);
                }
                case "admin" -> {
                    return filter(ADMIN_SUB, args[1]);
                }
            }
            return Collections.emptyList();
        }
        if (args.length == 3) {
            switch (op) {
                case "pay" -> {
                    return filter(currencyIds(), args[2]);
                }
                case "convert" -> {
                    return filter(currencyIds(), args[2]);
                }
                case "admin" -> {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    switch (sub) {
                        case "give", "take", "set", "audit" -> {
                            return filter(offlineRegistry.matchNames(args[2], 50), args[2]);
                        }
                        case "mint", "burn" -> {
                            return filter(currencyIds(), args[2]);
                        }
                        case "currency" -> {
                            return filter(CURRENCY_OPS, args[2]);
                        }
                    }
                }
            }
            return Collections.emptyList();
        }
        if (args.length == 4) {
            String op0 = args[0].toLowerCase(Locale.ROOT);
            if ("admin".equals(op0)) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if ("currency".equals(sub) && "rename".equalsIgnoreCase(args[2])) {
                    return filter(currencyIds(), args[3]);
                }
            }
            return Collections.emptyList();
        }
        if (args.length == 5) {
            String op0 = args[0].toLowerCase(Locale.ROOT);
            if ("admin".equals(op0)) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if ("currency".equals(sub) && "rename".equalsIgnoreCase(args[2])) {
                    return Collections.emptyList(); // new id — пользователь вводит сам
                }
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private List<String> currencyIds() {
        List<String> out = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) {
            out.add(c.id().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : candidates) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(s);
            }
        }
        return out;
    }
}
