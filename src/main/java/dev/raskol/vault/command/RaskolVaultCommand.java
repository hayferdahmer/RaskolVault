// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command;

import dev.raskol.vault.RaskolVault;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * Корневая команда /rv. Этап 0: help / version / debug.
 * Остальные подкоманды (balance, pay, convert, rates, admin) добавляются на этапах 2–4.
 */
public final class RaskolVaultCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "§8[§6RaskolVault§8]§r ";

    private final RaskolVault plugin;

    public RaskolVaultCommand(RaskolVault plugin) {
        this.plugin = plugin;
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
            case "version" -> sender.sendMessage(PREFIX + "Версия: " + plugin.getPluginMeta().getVersion());
            case "debug" -> {
                if (!sender.hasPermission("raskolvault.admin.debug")) {
                    sender.sendMessage(PREFIX + "Недостаточно прав");
                    return true;
                }
                sendDebug(sender);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "Команды:");
        sender.sendMessage("§e/rv help§7 — эта справка");
        sender.sendMessage("§e/rv version§7 — версия плагина");
        if (sender.hasPermission("raskolvault.admin.debug")) {
            sender.sendMessage("§e/rv debug§7 — состояние хуков и конфигурации");
        }
    }

    private void sendDebug(CommandSender sender) {
        sender.sendMessage(PREFIX + "Хуки: Core=" + yn(plugin.isCorePresent())
                + " Essentials=" + yn(plugin.isEssentialsPresent())
                + " Towny=" + yn(plugin.isTownyPresent())
                + " LP=" + yn(plugin.isLuckPermsPresent())
                + " PAPI=" + yn(plugin.isPlaceholderPresent()));
        sender.sendMessage(PREFIX + "Глобальная валюта: "
                + plugin.getConfig().getString("global-currency.id", "gold")
                + " " + plugin.getConfig().getString("global-currency.symbol", "⚜"));
        sender.sendMessage(PREFIX + "Обмен: " + yn(plugin.getConfig().getBoolean("exchange.enabled", true))
                + " · комиссия по умолчанию: " + plugin.getConfig().getDouble("exchange.default-fee", 0.02));
        sender.sendMessage(PREFIX + "SQLite: " + plugin.getConfig().getString("storage.sqlite.file", "data/ledger.sqlite"));
    }

    private String yn(boolean value) {
        return value ? "§aon§r" : "§coff§r";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = sender.hasPermission("raskolvault.admin.debug")
                    ? List.of("help", "version", "debug")
                    : List.of("help", "version");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subs.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
