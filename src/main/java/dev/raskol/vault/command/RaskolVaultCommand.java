// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.ExchangeSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.command.sub.RatesSubcommand;
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
 * /rv — роутер (1.2.4.2): /rv rates РАБОТАЕТ (для PAPI/плагинов), но СКРЫТ
 * из tab-комплита и /rv help, чтобы не мозолить глаза игрокам.
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final PaySubcommand paySubcommand;
    private final ConvertSubcommand convertSubcommand;
    private final ExchangeSubcommand exchangeSubcommand;
    private final RatesSubcommand ratesSubcommand;
    private final AdminSubcommand adminSubcommand;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convertSubcommand) {
        this.plugin = plugin;
        this.convertSubcommand = convertSubcommand;
        this.paySubcommand = new PaySubcommand(plugin);
        this.exchangeSubcommand = new ExchangeSubcommand(plugin,
                new dev.raskol.vault.exchange.ExchangeOrderService(plugin, plugin.getLedger(),
                        plugin.getWallets(), plugin.getCurrencies(), plugin.getEscrow()));
        this.ratesSubcommand = new RatesSubcommand(plugin);
        this.adminSubcommand = new AdminSubcommand(plugin);
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void send(CommandSender s, String raw) { s.sendMessage(c(raw)); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender); return true; }
        String op = args[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "help", "?" -> help(sender);
            case "version" -> send(sender, "&fRaskolVault &6" + plugin.getPluginMeta().getVersion());
            case "wallet" -> openWallet(sender);
            case "exchange" -> openExchange(sender, args);
            case "cabinet" -> openCabinet(sender);
            case "guide" -> openGuide(sender);
            case "rates" -> ratesSubcommand.execute(sender); // рабочий, но скрыт из tab/help
            case "pay" -> paySubcommand.execute(sender, args);
            case "convert" -> convertSubcommand.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "nation" -> adminSubcommand.execute(sender, prepend("nation", args));
            case "admin" -> adminSubcommand.execute(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void openWallet(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender, "&cКошелёк только в игре"); return; }
        if (!player.hasPermission("raskolvault.use")) { send(sender, plugin.getMessages().get("error.no-permission", null)); return; }
        WalletGui.openMain(plugin, player);
    }
    private void openExchange(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { exchangeSubcommand.execute(sender, args); return; }
        plugin.getExchangeGui().openBook(player, 0);
    }
    private void openCabinet(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender, "&cКабинет только в игре"); return; }
        String nation = plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(player.getUniqueId()) : null;
        if (nation == null || !plugin.getTownyHook().isKing(player.getUniqueId(), nation)) { send(sender, "&cТолько король"); return; }
        plugin.getCabinetGui().openHome(player, nation);
    }
    private void openGuide(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender, "&cСправка только в игре"); return; }
        WalletGui.openGuide(plugin, player, 0);
    }
    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender, plugin.getMessages().get("error.console-cannot-convert", null)); return; }
        var pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) { send(sender, plugin.getMessages().get("confirm.none", null)); return; }
        convertSubcommand.runConfirmed(player, pending.get(), sender);
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== RaskolVault ===");
        send(sender, "&f/rv wallet &7— кошелёк (валюты/обмен/курсы/перевод/история)");
        send(sender, "&f/rv exchange &7— биржа");
        send(sender, "&f/rv cabinet &7— кабинет государя (короли)");
        send(sender, "&f/rv guide &7— справка по кошельку");
        send(sender, "&f/rv convert <из> <в> <сумма> &7→ &f/rv confirm");
        send(sender, "&f/rv admin … &7— админ-блок");
        // /rv rates намеренно НЕ показан здесь (скрыт), но команда работает
    }

    private String[] prepend(String sub, String[] args) {
        String[] out = new String[args.length]; out[0] = sub;
        if (args.length > 1) System.arraycopy(args, 1, out, 1, args.length - 1);
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // "rates" намеренно отсутствует в подсказке
            List<String> subs = new ArrayList<>(Arrays.asList(
                    "wallet", "exchange", "cabinet", "guide", "convert", "confirm", "nation", "admin", "help"));
            if (!sender.hasPermission("raskolvault.admin")) subs.remove("admin");
            return filter(subs, args[0]);
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayerRegistry offline = plugin.getOfflinePlayerRegistry();
        if (args.length == 2) {
            if (op.equals("convert")) return currencyTab(args[1]);
            if (op.equals("admin") && sender.hasPermission("raskolvault.admin"))
                return filter(Arrays.asList("health", "selftest", "balance", "give", "take", "set", "mint", "burn",
                        "audit", "reload", "backup"), args[1]);
            return Collections.emptyList();
        }
        if (args.length == 3 && op.equals("convert")) return currencyTab(args[2]);
        if (args.length == 3 && op.equals("admin")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("take") || sub.equals("set") || sub.equals("audit") || sub.equals("balance"))
                return offline == null ? Collections.emptyList() : offline.matchNames(args[2], 50);
            if (sub.equals("mint") || sub.equals("burn")) return currencyTab(args[2]);
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
        for (Currency cur : plugin.getCurrencies().all())
            if (cur.id().toLowerCase(Locale.ROOT).startsWith(p)) out.add(cur.id().toLowerCase(Locale.ROOT));
        return out;
    }
    private List<String> filter(List<String> cand, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : cand) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
