// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.command.sub.RatesSubcommand;
import dev.raskol.vault.offline.OfflinePlayerRegistry;
import dev.raskol.vault.util.Formatter;
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

/**
 * /rv — корневой роутер. 1.1.0-a: /rv balance только для админов (через admin balance),
 * /rv rates возвращён, /rv confirm исполняет отложенный обмен.
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final PaySubcommand paySubcommand;
    private final ConvertSubcommand convertSubcommand;
    private final RatesSubcommand ratesSubcommand;
    private final AdminSubcommand adminSubcommand;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convertSubcommand) {
        this.plugin = plugin;
        this.convertSubcommand = convertSubcommand;
        this.paySubcommand = new PaySubcommand(plugin);
        this.ratesSubcommand = new RatesSubcommand(plugin);
        this.adminSubcommand = new AdminSubcommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "help", "?" -> help(sender);
            case "version" -> sender.sendMessage(prefix() + "&fRaskolVault &6" + plugin.getPluginMeta().getVersion());
            case "balance" -> redirectBalance(sender, args);
            case "pay" -> paySubcommand.execute(sender, args);
            case "convert" -> convertSubcommand.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "rates" -> ratesSubcommand.execute(sender);
            case "nation" -> adminSubcommand.execute(sender, prepend("nation", args));
            case "admin" -> adminSubcommand.execute(sender, args);
            default -> sender.sendMessage(prefix() + "&7Неизвестная команда. &f/rv help");
        }
        return true;
    }

    /** 1.1.0-a: /rv balance доступен только админам и маршрутизируется в /rv admin balance. */
    private void redirectBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.view")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.balance-admin-only", null));
            return;
        }
        adminSubcommand.execute(sender, prepend("balance", args));
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        var pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("confirm.none", null));
            return;
        }
        convertSubcommand.runConfirmed(player, pending.get(), sender);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(prefix() + "&6=== RaskolVault ===");
        sender.sendMessage("&f /rv pay <ник> <валюта> <сумма> [причина]");
        sender.sendMessage("&f /rv convert <из> <в> <сумма> &7→ &f/rv confirm");
        sender.sendMessage("&f /rv rates &7— курсы валют");
        sender.sendMessage("&f /rv nation &7— твоя нация и казна");
        if (sender.hasPermission("raskolvault.admin")) {
            sender.sendMessage("&f /rv admin … &7— админ-блок (balance, give, take, audit, …)");
        }
    }

    /** ["rv", X, ...rest] → ["X", ...rest] для передачи в sub-команды. */
    private String[] prepend(String sub, String[] args) {
        String[] out = new String[args.length];
        out[0] = sub;
        if (args.length > 1) {
            System.arraycopy(args, 1, out, 1, args.length - 1);
        }
        return out;
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("help", "version", "convert", "confirm", "rates", "nation", "pay"));
            if (sender.hasPermission("raskolvault.admin")) {
                subs.add("admin");
            }
            String p = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(p)).toList();
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayerRegistry offline = plugin.getOfflinePlayerRegistry();
        if (args.length == 2) {
            switch (op) {
                case "pay" -> {
                    return offline == null ? Collections.emptyList() : offline.matchNames(args[1], 50);
                }
                case "convert", "rates" -> currencyTab(args[1]);
                case "admin" -> {
                    if (!sender.hasPermission("raskolvault.admin")) {
                        return Collections.emptyList();
                    }
                    return filter(Arrays.asList("health", "balance", "give", "take", "set", "mint", "burn",
                            "currency", "simulate", "simulate-load", "stress", "audit", "reload", "backup", "restore"), args[1]);
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 3) {
            switch (op) {
                case "pay", "convert" -> currencyTab(args[2]);
                case "admin" -> {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("give") || sub.equals("take") || sub.equals("set")
                            || sub.equals("audit") || sub.equals("balance")) {
                        return offline == null ? Collections.emptyList() : offline.matchNames(args[2], 50);
                    }
                    if (sub.equals("mint") || sub.equals("burn")) {
                        return currencyTab(args[2]);
                    }
                    if (sub.equals("currency")) {
                        return filter(Arrays.asList("list", "rename", "create", "remove"), args[2]);
                    }
                    return Collections.emptyList();
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 4 && op.equals("admin")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("take") || sub.equals("set")) {
                return currencyTab(args[3]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> currencyTab(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return plugin.getCurrencies().all().stream()
                .map(Currency::id)
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                .toList();
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.startsWith(p)).toList();
    }
}
