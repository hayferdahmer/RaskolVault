// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.exchange.ExchangeOrderService;
import dev.raskol.vault.storage.SQLiteLedger.ExchangeOrderRow;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /rv exchange (1.2.1): межгосударственная биржа королей.
 *  list [лимит]      — стакан открытых ордеров (публично)
 *  my                — мои ордера
 *  sell <валюта> <кол-во> <цена>  — продать валюту нации за GLD (только король)
 *  buy  <валюта> <кол-во> <цена>  — купить валюту нации за GLD (только король)
 *  cancel <id>       — отменить свой ордер
 *  take <id>         — исполнить чужой ордер целиком (только король)
 */
public final class ExchangeSubcommand {

    private final RaskolVault plugin;
    private final ExchangeOrderService orders;

    public ExchangeSubcommand(RaskolVault plugin, ExchangeOrderService orders) {
        this.plugin = plugin;
        this.orders = orders;
    }

    private String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(CommandSender s, String raw) {
        s.sendMessage(c(raw));
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            help(sender);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender, args);
            case "my" -> my(sender);
            case "sell" -> sell(sender, args);
            case "buy" -> buy(sender, args);
            case "cancel" -> cancel(sender, args);
            case "take" -> take(sender, args);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== Биржа королей ===");
        send(sender, "&f/rv exchange list [лимит] &7— стакан ордеров");
        send(sender, "&f/rv exchange my &7— мои ордера");
        send(sender, "&f/rv exchange sell <валюта> <кол-во> <цена> &7— продать (король)");
        send(sender, "&f/rv exchange buy <валюта> <кол-во> <цена> &7— купить (король)");
        send(sender, "&f/rv exchange cancel <id> &7— отменить свой ордер");
        send(sender, "&f/rv exchange take <id> &7— исполнить чужой ордер (король)");
    }

    private void list(CommandSender sender, String[] args) {
        int limit = 10;
        try {
            if (args.length >= 3) limit = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
        }
        List<ExchangeOrderRow> open = orders.openOrders(Math.max(1, Math.min(50, limit)));
        if (open.isEmpty()) {
            send(sender, "&7Стакан пуст");
            return;
        }
        send(sender, "&6=== Стакан биржи (" + open.size() + ") ===");
        for (ExchangeOrderRow o : open) {
            send(sender, "&7[" + o.id().substring(0, 8) + "] &f" + o.nation()
                    + " &7продаёт &f" + fmt(o.sellAmount()) + " " + o.sellCurrency()
                    + " &7за &f" + fmt(o.price()) + " " + o.buyCurrency() + "/шт"
                    + " &7(итого &f" + fmt(o.buyAmount()) + "&7)");
        }
    }

    private void my(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cТолько в игре");
            return;
        }
        List<ExchangeOrderRow> mine = orders.myOrders(player.getUniqueId());
        if (mine.isEmpty()) {
            send(sender, "&7У вас нет ордеров");
            return;
        }
        send(sender, "&6=== Мои ордера (" + mine.size() + ") ===");
        for (ExchangeOrderRow o : mine) {
            send(sender, "&7[" + o.id().substring(0, 8) + "] &f" + o.status()
                    + " &7" + fmt(o.sellAmount()) + " " + o.sellCurrency()
                    + " → &f" + fmt(o.buyAmount()) + " " + o.buyCurrency()
                    + " &7@ " + fmt(o.price()));
        }
    }

    private void sell(CommandSender sender, String[] args) {
        Player king = requireKing(sender);
        if (king == null) return;
        if (args.length < 5) {
            send(sender, "&f/rv exchange sell <валюта> <кол-во> <цена>");
            return;
        }
        String nation = plugin.getTownyHook().nationOf(king.getUniqueId());
        double amount = parse(args[3]);
        double price = parse(args[4]);
        if (amount <= 0 || price <= 0) {
            send(sender, "&cНекорректные числа");
            return;
        }
        var res = orders.createSellOrder(king.getUniqueId(), nation, args[2].toUpperCase(Locale.ROOT), amount, price);
        send(sender, res.success()
                ? "&aОрдер создан: &f" + res.orderId().substring(0, 8)
                : "&cОтказ: " + res.error());
    }

    private void buy(CommandSender sender, String[] args) {
        Player king = requireKing(sender);
        if (king == null) return;
        if (args.length < 5) {
            send(sender, "&f/rv exchange buy <валюта> <кол-во> <цена>");
            return;
        }
        String nation = plugin.getTownyHook().nationOf(king.getUniqueId());
        double amount = parse(args[3]);
        double price = parse(args[4]);
        if (amount <= 0 || price <= 0) {
            send(sender, "&cНекорректные числа");
            return;
        }
        var res = orders.createBuyOrder(king.getUniqueId(), nation, args[2].toUpperCase(Locale.ROOT), amount, price);
        send(sender, res.success()
                ? "&aОрдер создан: &f" + res.orderId().substring(0, 8)
                : "&cОтказ: " + res.error());
    }

    private void cancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cТолько в игре");
            return;
        }
        if (args.length < 3) {
            send(sender, "&f/rv exchange cancel <id>");
            return;
        }
        var res = orders.cancelOrder(resolveId(args[2]), player.getUniqueId());
        send(sender, res.success() ? "&aОрдер отменён, заморозка возвращена" : "&cОтказ: " + res.error());
    }

    private void take(CommandSender sender, String[] args) {
        Player king = requireKing(sender);
        if (king == null) return;
        if (args.length < 3) {
            send(sender, "&f/rv exchange take <id>");
            return;
        }
        var res = orders.takeOrder(resolveId(args[2]), king.getUniqueId());
        send(sender, res.success() ? "&aОрдер исполнен целиком" : "&cОтказ: " + res.error());
    }

    private Player requireKing(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cТолько в игре");
            return null;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            send(sender, "&cTowny неактивен");
            return null;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null || !plugin.getTownyHook().isKing(player.getUniqueId(), nation)) {
            send(sender, "&cТолько короли наций могут оперировать биржей (временное ограничение)");
            return null;
        }
        return player;
    }

    /** Разрешает короткий префикс id (8 символов) в полный UUID-строку. */
    private String resolveId(String raw) {
        if (raw.length() >= 36) return raw;
        for (ExchangeOrderRow o : orders.openOrders(200)) {
            if (o.id().startsWith(raw)) return o.id();
        }
        return raw;
    }

    private double parse(String s) {
        try {
            return Double.parseDouble(s.replace(",", "."));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
