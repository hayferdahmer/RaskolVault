// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.command.sub.RatesSubcommand;
import dev.raskol.vault.exchange.ExchangeResult;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Роутер /rv. Этапы 0–4: help, version, balance, pay, convert, confirm, rates, admin, debug.
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final PaySubcommand pay;
    private final ConvertSubcommand convert;
    private final RatesSubcommand rates;
    private final AdminSubcommand admin;

    public RaskolVaultCommand(RaskolVault plugin) {
        this.plugin = plugin;
        this.pay = new PaySubcommand(plugin);
        this.convert = new ConvertSubcommand(plugin);
        this.rates = new RatesSubcommand(plugin);
        this.admin = new AdminSubcommand(plugin);
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "version" -> sender.sendMessage(prefix() + "Версия: " + plugin.getPluginMeta().getVersion());
            case "balance" -> handleBalance(sender, args);
            case "pay" -> pay.execute(sender, args);
            case "convert" -> convert.execute(sender, args);
            case "confirm" -> handleConfirm(sender);
            case "rates" -> rates.execute(sender);
            case "admin" -> admin.execute(sender, args);
            case "debug" -> {
                if (!sender.hasPermission("raskolvault.admin.debug")) {
                    sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
                    return true;
                }
                sendDebug(sender);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        var pending = plugin.getConfirms().consume(player.getUniqueId());
        if (pending.isEmpty()) {
            sender.sendMessage(prefix() + plugin.getMessages().get("confirm.none", null));
            return;
        }
        ExchangeResult preview = pending.get().preview();
        ExchangeResult result = plugin.getExchange().execute(player.getUniqueId(),
                preview.fromId(), preview.toId(), preview.gross());
        if (result.applied()) {
            Currency to = plugin.getCurrencies().get(result.toId()).orElse(null);
            sender.sendMessage(prefix() + plugin.getMessages().get("convert.done",
                    Map.of("amount", to == null
                            ? Formatter.amount(result.net(), 2)
                            : Formatter.withSymbol(result.net(), to.decimals(), to.symbol()))));
        } else {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", result.reason(), "from", result.fromId(), "to", result.toId())));
        }
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.use")) {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        Player target;
        if (args.length > 1) {
            if (!sender.hasPermission("raskolvault.admin.view")) {
                sender.sendMessage(prefix() + plugin.getMessages().get("error.no-permission", null));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(prefix() + plugin.getMessages().get("error.player-not-found",
                        Map.of("name", args[1])));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(prefix() + plugin.getMessages().get("error.console-needs-nick", null));
            return;
        }
        UUID uuid = target.getUniqueId();
        sender.sendMessage(prefix() + plugin.getMessages().get("balance.header",
                Map.of("player", target.getName())));
        boolean any = false;
        for (Currency currency : plugin.getCurrencies().all()) {
            double amount;
            try {
                amount = plugin.getWallets().getBalance(uuid, currency.id());
            } catch (LedgerException e) {
                sender.sendMessage(prefix() + plugin.getMessages().get("error.storage", null));
                return;
            }
            any = true;
            sender.sendMessage(plugin.getMessages().get("balance.line", Map.of(
                    "symbol", currency.symbol(),
                    "display", currency.displayName(),
                    "amount", Formatter.withSymbol(amount, currency.decimals(), currency.symbol()))));
        }
        if (!any) {
            sender.sendMessage(prefix() + plugin.getMessages().get("balance.empty", null));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(prefix() + "Команды:");
        sender.sendMessage("§e/rv help§7 — эта справка");
        sender.sendMessage("§e/rv version§7 — версия плагина");
        sender.sendMessage("§e/rv balance [ник]§7 — кошелёк");
        if (sender.hasPermission("raskolvault.convert")) {
            sender.sendMessage("§e/rv rates§7 — курсы обмена");
            sender.sendMessage("§e/rv convert <из> <в> <сумма>§7 — обмен валют");
            sender.sendMessage("§e/rv confirm§7 — подтвердить обмен");
        }
        if (sender.hasPermission("raskolvault.use")) {
            sender.sendMessage("§e/rv pay <ник> <валюта> <сумма> [причина]§7 — перевод игроку");
        }
        if (sender.hasPermission("raskolvault.admin")) {
            sender.sendMessage("§e/rv admin <give|take|set|audit|reload>§7 — админ-команды");
        }
        if (sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage("§e/rv debug§7 — состояние хуков, леджера и кэшей");
        }
    }

    private void sendDebug(CommandSender sender) {
        sender.sendMessage(prefix() + "Хуки: Core=" + yn(plugin.isCorePresent())
                + " Essentials=" + yn(plugin.isEssentialsPresent())
                + " Towny=" + yn(plugin.isTownyPresent())
                + " LP=" + yn(plugin.isLuckPermsPresent())
                + " PAPI=" + yn(plugin.isPlaceholderPresent()));
        sender.sendMessage(prefix() + "Essentials-хук кошелька: "
                + yn(plugin.getEssentialsHook().isAvailable()));
        RaskolCoreHook coreHook = plugin.getCoreHook();
        sender.sendMessage(prefix() + "RaskolCore-провайдер: "
                + (coreHook != null && coreHook.isRegistered() ? "§aregistered§r" : "§coff§r"));
        sender.sendMessage(prefix() + "Валют в реестре: " + plugin.getCurrencies().all().size()
                + " (global: " + plugin.getCurrencies().globalId() + ")");
        sender.sendMessage(prefix() + "Курсов: " + plugin.getRates().allRates().size()
                + " · default-fee: " + String.format("%.1f%%", plugin.getRates().defaultFee() * 100.0));
        sender.sendMessage(prefix() + "Pending-обменов: " + plugin.getConfirms().size());
        if (plugin.getLedger() != null) {
            sender.sendMessage(prefix() + "Леджер: " + plugin.getLedger().describeStats()
                    + " · кэш кошельков: " + plugin.getWallets().cachedRows());
        } else {
            sender.sendMessage(prefix() + "Леджер: §cне инициализирован§r");
        }
    }

    private String yn(boolean value) {
        return value ? "§aon§r" : "§coff§r";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            subs.add("version");
            subs.add("balance");
            if (sender.hasPermission("raskolvault.use")) subs.add("pay");
            if (sender.hasPermission("raskolvault.convert")) {
                subs.add("convert");
                subs.add("confirm");
                subs.add("rates");
            }
            if (sender.hasPermission("raskolvault.admin")) subs.add("admin");
            if (sender.hasPermission("raskolvault.admin.debug")) subs.add("debug");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("balance".equals(sub) && args.length == 2 && sender.hasPermission("raskolvault.admin.view")) {
            return null; // ники онлайна
        }
        if ("pay".equals(sub)) {
            if (args.length == 2 && sender.hasPermission("raskolvault.use")) return null;
            if (args.length == 3 && sender.hasPermission("raskolvault.use")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return plugin.getCurrencies().all().stream()
                        .map(Currency::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
        }
        if ("convert".equals(sub)) {
            if ((args.length == 2 || args.length == 3) && sender.hasPermission("raskolvault.convert")) {
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return plugin.getCurrencies().all().stream()
                        .map(Currency::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
        }
        if ("admin".equals(sub)) {
            if (args.length == 2 && sender.hasPermission("raskolvault.admin")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return List.of("give", "take", "set", "mint", "burn", "audit", "reload")
                        .stream().filter(s -> s.startsWith(prefix)).toList();
            }
            String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            if ((op.equals("give") || op.equals("take") || op.equals("set")) && args.length == 3
                    && sender.hasPermission("raskolvault.admin")) {
                return null; // ники
            }
            if ((op.equals("give") || op.equals("take") || op.equals("set")) && args.length == 4
                    && sender.hasPermission("raskolvault.admin")) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return plugin.getCurrencies().all().stream()
                        .map(Currency::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (op.equals("audit") && args.length == 3 && sender.hasPermission("raskolvault.admin.audit")) {
                return null; // ники
            }
        }
        return List.of();
    }
}
