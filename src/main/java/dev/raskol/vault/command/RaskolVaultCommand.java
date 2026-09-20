// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Корневая команда /rv. Этапы 0–3: help / version / debug / balance.
 * debug расширен: показывает статус регистрации EconomyProvider в RaskolCore.
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private final RaskolVault plugin;

    public RaskolVaultCommand(RaskolVault plugin) {
        this.plugin = plugin;
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
        sender.sendMessage("§e/rv balance [ник]§7 — кошелёк (свой или чужой с правом просмотра)");
        if (sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage("§e/rv debug§7 — состояние хуков, конфигурации, леджера и Core-провайдера");
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
        sender.sendMessage(prefix() + "Глобальная валюта: "
                + plugin.getConfig().getString("global-currency.id", "gold")
                + " " + plugin.getConfig().getString("global-currency.symbol", "⚜"));
        sender.sendMessage(prefix() + "Обмен: " + yn(plugin.getConfig().getBoolean("exchange.enabled", true))
                + " · комиссия по умолчанию: " + plugin.getConfig().getDouble("exchange.default-fee", 0.02));
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
            List<String> subs = sender.hasPermission("raskolvault.admin.debug")
                    ? List.of("help", "version", "balance", "debug")
                    : List.of("help", "version", "balance");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "balance".equals(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission("raskolvault.admin.view")) {
            return null; // дефолт: ники онлайна
        }
        return List.of();
    }
}
