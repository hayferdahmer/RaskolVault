// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.command.sub.AdminSubcommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.command.sub.NationSubcommand;
import dev.raskol.vault.command.sub.PaySubcommand;
import dev.raskol.vault.command.sub.RatesSubcommand;
import dev.raskol.vault.confirm.PendingConfirm;
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
import java.util.Optional;

public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;
    private final PaySubcommand pay;
    private final ConvertSubcommand convert;
    private final RatesSubcommand rates;
    private final AdminSubcommand admin;
    private final NationSubcommand nation;

    public RaskolVaultCommand(RaskolVault plugin, ConvertSubcommand convert) {
        this.plugin = plugin;
        this.pay = new PaySubcommand(plugin);
        this.convert = convert;
        this.rates = new RatesSubcommand(plugin);
        this.admin = new AdminSubcommand(plugin);
        this.nation = new NationSubcommand(plugin);
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
            case "version" -> sendVersion(sender);
            case "debug" -> sendDebug(sender);
            case "balance" -> balance(sender, args);
            case "pay" -> pay.execute(sender, args);
            case "convert" -> convert.execute(sender, args);
            case "confirm" -> confirm(sender);
            case "rates" -> rates.execute(sender);
            case "nation" -> nation.execute(sender);
            case "admin" -> {
                if (!sender.hasPermission("raskolvault.admin")) {
                    sender.sendMessage(plugin.getMessages().prefix()
                            + plugin.getMessages().get("error.no-permission", null));
                    return true;
                }
                admin.execute(sender, args);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void balance(CommandSender sender, String[] args) {
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
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-needs-nick", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("balance.header", Map.of("player", target.getName())));
        boolean any = false;
        for (Currency currency : plugin.getCurrencies().all()) {
            double amount;
            try {
                amount = plugin.getWallets().getBalance(target.getUniqueId(), currency.id());
            } catch (LedgerException e) {
                sender.sendMessage(plugin.getMessages().prefix()
                        + plugin.getMessages().get("error.storage", null));
                return;
            }
            any = true;
            sender.sendMessage(plugin.getMessages().get("balance.line", Map.of(
                    "symbol", currency.symbol(),
                    "display", currency.displayName(),
                    "amount", Formatter.withSymbol(amount, currency.decimals(), currency.symbol()))));
        }
        if (!any) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("balance.empty", null));
        }
    }

    /** /rv confirm — берёт pending-preview из ConfirmManager и отправляет в асинхронный коммит. */
    private void confirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-convert", null));
            return;
        }
        if (!sender.hasPermission("raskolvault.convert")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        Optional<PendingConfirm> pending = plugin.getConfirms().take(player.getUniqueId());
        if (pending.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("confirm.none", null));
            return;
        }
        convert.runConfirmed(player, pending.get().preview(), sender);
    }

    private void sendVersion(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix()
                + "§eRaskolVault §fv" + plugin.getPluginMeta().getVersion()
                + " §7· Paper/MC §f" + plugin.getServer().getVersion());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "Команды:");
        sender.sendMessage("§e/rv help§7 — эта справка");
        sender.sendMessage("§e/rv version§7 — версия плагина");
        sender.sendMessage("§e/rv balance [ник]§7 — кошелёк");
        if (sender.hasPermission("raskolvault.use") && plugin.getTownyHook().isAvailable()) {
            sender.sendMessage("§e/rv nation§7 — моя нация и её казна");
        }
        if (sender.hasPermission("raskolvault.convert")) {
            sender.sendMessage("§e/rv rates§7 — курсы обмена");
            sender.sendMessage("§e/rv convert <из> <в> <сумма>§7 — обмен валют");
            sender.sendMessage("§e/rv confirm§7 — подтвердить обмен");
        }
        if (sender.hasPermission("raskolvault.use")) {
            sender.sendMessage("§e/rv pay <ник> <валюта> <сумма> [причина]§7 — перевод игроку");
        }
        if (sender.hasPermission("raskolvault.admin")) {
            sender.sendMessage("§e/rv admin <give|take|set|mint|burn|currency|simulate|audit|reload|backup|simulate-load>");
        }
        if (sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage("§e/rv debug§7 — состояние хуков, леджера и кэшей");
        }
    }

    private void sendDebug(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "Хуки: Core=" + yn(plugin.isCorePresent())
                + " Essentials=" + yn(plugin.isEssentialsPresent())
                + " Towny=" + yn(plugin.isTownyPresent()) + "/" + yn(plugin.getTownyHook().isAvailable())
                + " LP=" + yn(plugin.isLuckPermsPresent())
                + " PAPI=" + yn(plugin.isPlaceholderPresent()));
        sender.sendMessage(plugin.getMessages().prefix() + "Essentials-хук кошелька: "
                + yn(plugin.getEssentialsHook().isAvailable()));
        RaskolCoreHook coreHook = plugin.getCoreHook();
        sender.sendMessage(plugin.getMessages().prefix() + "RaskolCore-провайдер: "
                + (coreHook != null && coreHook.isRegistered() ? "§aregistered§r" : "§coff§r"));
        sender.sendMessage(plugin.getMessages().prefix() + "Валют в реестре: " + plugin.getCurrencies().all().size()
                + " (global: " + plugin.getCurrencies().globalId() + ")");
        sender.sendMessage(plugin.getMessages().prefix() + "Курсов: " + plugin.getRates().allRates().size()
                + " · default-fee: " + String.format("%.1f%%", plugin.getRates().defaultFee() * 100.0));
        sender.sendMessage(plugin.getMessages().prefix() + "Pending-обменов: " + plugin.getConfirms().size());
        if (plugin.getLedger() != null) {
            sender.sendMessage(plugin.getMessages().prefix() + "Леджер: " + plugin.getLedger().describeStats()
                    + " · кэш кошельков: " + plugin.getWallets().cachedRows()
                    + " · писатель: applied " + plugin.getWriter().applied()
                    + ", failed " + plugin.getWriter().failed()
                    + ", queue " + plugin.getWriter().queueSize());
        } else {
            sender.sendMessage(plugin.getMessages().prefix() + "Леджер: §cне инициализирован§r");
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
            if (sender.hasPermission("raskolvault.use") && plugin.getTownyHook().isAvailable()) {
                subs.add("nation");
            }
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
            return null;
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
                return List.of("give", "take", "set", "mint", "burn", "currency",
                                "simulate", "audit", "reload", "backup", "simulate-load")
                        .stream().filter(s -> s.startsWith(prefix)).toList();
            }
            String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            if ((op.equals("give") || op.equals("take") || op.equals("set")) && args.length == 3
                    && sender.hasPermission("raskolvault.admin")) {
                return null;
            }
            if ((op.equals("give") || op.equals("take") || op.equals("set")
                    || op.equals("mint") || op.equals("burn")) && args.length == 4
                    && sender.hasPermission("raskolvault.admin")) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return plugin.getCurrencies().all().stream()
                        .map(Currency::id)
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (op.equals("currency") && args.length == 3 && sender.hasPermission("raskolvault.admin.currency")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return List.of("list", "create", "remove")
                        .stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (op.equals("audit") && args.length == 3 && sender.hasPermission("raskolvault.admin.audit")) {
                return null;
            }
            if (op.equals("simulate-load") && args.length == 3
                    && sender.hasPermission("raskolvault.admin.debug")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return List.of("50", "100", "cleanup").stream()
                        .filter(s -> s.startsWith(prefix)).toList();
            }
        }
        return List.of();
    }
}
