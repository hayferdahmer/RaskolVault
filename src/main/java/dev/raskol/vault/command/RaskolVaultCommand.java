// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.ExchangeSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.command.sub.RatesSubcommand;
import dev.raskol.vault.exchange.ExchangeOrderService;
import dev.raskol.vault.gui.WalletGui;
import dev.raskol.vault.offline.OfflinePlayerRegistry;
import org.bukkit.ChatColor;
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

/**
 * /rv — корневой роутер (1.2.1): + /rv exchange (биржа королей).
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final PaySubcommand paySubcommand;
    private final ConvertSubcommand convertSubcommand;
    private final RatesSubcommand ratesSubcommand;
    private final AdminSubcommand adminSubcommand;
    private final ExchangeSubcommand exchangeSubcommand;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convertSubcommand) {
        this.plugin = plugin;
        this.convertSubcommand = convertSubcommand;
        this.paySubcommand = new PaySubcommand(plugin);
        this.ratesSubcommand = new RatesSubcommand(plugin);
        this.adminSubcommand = new AdminSubcommand(plugin);
        // 1.2.1: биржа королей (ордера хранятся в exchange_orders, заморозка через escrow)
        ExchangeOrderService orders = new ExchangeOrderService(
                plugin,
                plugin.getLedger(),
                plugin.getWallets(),
                plugin.getCurrencies(),
                plugin.getEscrow());
        this.exchangeSubcommand = new ExchangeSubcommand(plugin, orders);
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
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
            case "version" -> send(sender, "&fRaskolVault &6" + plugin.getPluginMeta().getVersion());
            case "wallet" -> openWallet(sender);
            case "pay" -> paySubcommand.execute(sender, args);
            case "convert" -> convertSubcommand.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "rates" -> ratesSubcommand.execute(sender);
            case "exchange" -> exchangeSubcommand.execute(sender, args);
            case "nation" -> adminSubcommand.execute(sender, prepend("nation", args));
            case "admin" -> adminSubcommand.execute(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void openWallet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cКошелёк доступен только в игре");
            return;
        }
        if (!player.hasPermission("raskolvault.use")) {
            send(sender, plugin.getMessages().get("error.no-permission", null));
            return;
        }
        // FIX: WalletGui.openMain требует (plugin, player)
        WalletGui.openMain(plugin, player);
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        var pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) {
            send(sender, plugin.getMessages().get("confirm.none", null));
            return;
        }
        convertSubcommand.runConfirmed(player, pending.get(), sender);
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== RaskolVault ===");
        send(sender, "&f/rv wallet &7— GUI кошелька");
        send(sender, "&f/rv pay <ник> <валюта> <сумма> [причина]");
        send(sender, "&f/rv convert <из> <в> <сумма> &7→ &f/rv confirm");
        send(sender, "&f/rv rates &7— курсы");
        send(sender, "&f/rv exchange … &7— биржа королей");
        send(sender, "&f/rv admin … &7— админ-блок");
    }

    private String[] prepend(String sub, String[] args) {
        String[] out = new String[args.length];
        out[0] = sub;
        if (args.length > 1) {
            System.arraycopy(args, 1, out, 1, args.length - 1);
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList(
                    "wallet", "pay", "convert", "confirm", "rates", "exchange", "nation", "admin", "help"));
            if (!sender.hasPermission("raskolvault.admin")) {
                subs.remove("admin");
            }
            return filter(subs, args[0]);
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayerRegistry offline = plugin.getOfflinePlayerRegistry();
        if (args.length == 2) {
            switch (op) {
                case "pay" -> {
                    return offline == null ? Collections.emptyList() : offline.matchNames(args[1], 50);
                }
                case "convert", "rates" -> currencyTab(args[1]);
                case "exchange" -> filter(Arrays.asList("list", "my", "sell", "buy", "cancel", "take"), args[1]);
                case "admin" -> {
                    if (!sender.hasPermission("raskolvault.admin")) return Collections.emptyList();
                    return filter(Arrays.asList("health", "balance", "give", "take", "set", "mint", "burn",
                            "currency", "simulate-load", "audit", "reload", "backup", "restore"), args[1]);
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 3) {
            switch (op) {
                case "pay", "convert" -> currencyTab(args[2]);
                case "exchange" -> {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("sell") || sub.equals("buy")) return currencyTab(args[2]);
                    return Collections.emptyList();
                }
                case "admin" -> {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("give") || sub.equals("take") || sub.equals("set")
                            || sub.equals("audit") || sub.equals("balance")) {
                        return offline == null ? Collections.emptyList() : offline.matchNames(args[2], 50);
                    }
                    if (sub.equals("mint") || sub.equals("burn")) return currencyTab(args[2]);
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
        if (args.length == 4 && op.equals("exchange")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("sell") || sub.equals("buy")) return Collections.emptyList();
        }
        if (args.length == 4 && op.equals("admin")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("take") || sub.equals("set")) return currencyTab(args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> currencyTab(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.id().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(cur.id().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : candidates) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
