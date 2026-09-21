// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.NationBankSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
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
 * /rv — корневой роутер (FIX 1.1.1.2): PaySubcommand инстанцируется локально,
 * без обращения к plugin.getPaySubcommand().
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final ConvertSubcommand convertSubcommand;
    private final PaySubcommand paySubcommand;
    private final AdminSubcommand adminSubcommand;
    private final NationBankSubcommand nationBank;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convertSubcommand) {
        this.plugin = plugin;
        this.convertSubcommand = convertSubcommand;
        this.paySubcommand = new PaySubcommand(plugin);
        this.adminSubcommand = new AdminSubcommand(plugin);
        this.nationBank = new NationBankSubcommand(plugin);
    }

    private static String c(String s) {
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
            case "version" -> send(sender, plugin.getMessages().prefix()
                    + "&fRaskolVault &6" + plugin.getPluginMeta().getVersion());
            case "wallet" -> openWallet(sender);
            case "cabinet" -> openCabinet(sender);
            case "guide" -> openGuide(sender);
            case "balance" -> adminSubcommand.execute(sender, prepend("balance", args));
            case "pay" -> pay(sender, args);
            case "convert" -> convertSubcommand.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "rates" -> rates(sender);
            case "bank" -> nationBank.execute(sender, args);
            case "nation" -> adminSubcommand.execute(sender, prepend("nation", args));
            case "admin" -> adminSubcommand.execute(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private String[] prepend(String sub, String[] args) {
        String[] out = new String[args.length];
        out[0] = sub;
        if (args.length > 1) {
            System.arraycopy(args, 1, out, 1, args.length - 1);
        }
        return out;
    }

    private void openWallet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + "&cКошелёк — только в игре");
            return;
        }
        if (!player.hasPermission("raskolvault.use")) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        WalletGui.openMain(plugin, player);
    }

    private void openCabinet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + "&cКабинет — только в игре");
            return;
        }
        if (!WalletGui.isKing(plugin, player)) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("bank.not-king", null));
            return;
        }
        WalletGui.openCabinet(plugin, player);
    }

    private void openGuide(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix() + "&cКодекс — только в игре");
            return;
        }
        WalletGui.openCodex(plugin, player, 0);
    }

    private void pay(CommandSender sender, String[] args) {
        // FIX: PaySubcommand инстанцирован в конструкторе роутера,
        // а не запрашивается через plugin.getPaySubcommand() (которого нет).
        paySubcommand.execute(sender, args);
    }

    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        var pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) {
            send(sender, plugin.getMessages().prefix()
                    + plugin.getMessages().get("confirm.none", null));
            return;
        }
        convertSubcommand.runConfirmed(player, pending.get(), sender);
    }

    private void rates(CommandSender sender) {
        send(sender, plugin.getMessages().prefix() + "&6Курсы валют:");
        for (Currency a : plugin.getCurrencies().all()) {
            for (Currency b : plugin.getCurrencies().all()) {
                if (a.id().equals(b.id())) {
                    continue;
                }
                double r = plugin.getConvertEngine().rate(a.id(), b.id());
                send(sender, "&7 " + a.id() + " → " + b.id() + ": &f"
                        + String.format(Locale.ROOT, "%.4f", r));
            }
        }
    }

    private void help(CommandSender sender) {
        send(sender, plugin.getMessages().prefix() + "&6=== RaskolVault ===");
        send(sender, "&f /rv wallet &7— GUI кошелька");
        send(sender, "&f /rv pay <ник> <валюта> <сумма> [причина]");
        send(sender, "&f /rv convert <из> <в> <сумма> &7→ &f/rv confirm");
        send(sender, "&f /rv rates &7— курсы");
        send(sender, "&f /rv bank &7— банк нации");
        if (sender.hasPermission("raskolvault.admin")) {
            send(sender, "&f /rv admin … &7— админ-блок");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList(
                    "help", "version", "wallet", "cabinet", "guide",
                    "convert", "confirm", "rates", "bank", "nation", "pay"));
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
                    return offline == null ? Collections.emptyList()
                            : offline.matchNames(args[1], 50);
                }
                case "convert", "rates" -> {
                    return currencyTab(args[1]);
                }
                case "bank" -> {
                    return filter(Arrays.asList("info", "deposit", "withdraw", "parity", "tax"), args[1]);
                }
                case "admin" -> {
                    if (!sender.hasPermission("raskolvault.admin")) {
                        return Collections.emptyList();
                    }
                    return filter(Arrays.asList("health", "balance", "give", "take", "set", "mint", "burn",
                            "reserve", "currency", "simulate-load", "audit", "reload", "backup", "restore"), args[1]);
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 3) {
            switch (op) {
                case "pay", "convert" -> {
                    return currencyTab(args[2]);
                }
                case "admin" -> {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("give") || sub.equals("take") || sub.equals("set")
                            || sub.equals("audit") || sub.equals("balance")) {
                        return offline == null ? Collections.emptyList()
                                : offline.matchNames(args[2], 50);
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
