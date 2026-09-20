// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class CurrencySubcommand {

    private final RaskolVault plugin;

    public CurrencySubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin.currency")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }
        String op = args[2].toLowerCase(java.util.Locale.ROOT);
        switch (op) {
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            default -> sendHelp(sender);
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§e/rv admin currency create <id> <nationId> <display> <symbol> [decimals]");
            return;
        }
        String id = args[3].toLowerCase(java.util.Locale.ROOT);
        String nationId = args[4].toLowerCase(java.util.Locale.ROOT);
        String display = args[5];
        String symbol = args[6];
        int decimals = args.length >= 8 ? parseInt(args[7], 2) : 2;
        if (plugin.getCurrencies().get(id).isPresent()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.currency.exists", Map.of("id", id)));
            return;
        }
        try {
            Currency currency = new Currency(id, display, symbol,
                    CurrencyType.NATIONAL, nationId, decimals, true);
            plugin.getCurrencies().addCurrency(currency);
            plugin.getLedger().upsertCurrency(currency);
            sender.sendMessage(plugin.getMessages().prefix() + "§aСоздана национальная валюта: §f"
                    + symbol + " " + display + " (" + id + ", nation=" + nationId + ")");
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.getMessages().prefix() + "§cОшибка: " + e.getMessage());
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin currency remove <id>");
            return;
        }
        String id = args[3].toLowerCase(java.util.Locale.ROOT);
        Currency currency = plugin.getCurrencies().get(id).orElse(null);
        if (currency == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.unknown-currency", Map.of("id", id)));
            return;
        }
        if (currency.isGlobal()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§cНельзя удалить глобальную валюту");
            return;
        }
        plugin.getCurrencies().removeCurrency(id);
        plugin.getLedger().deleteCurrency(id);
        sender.sendMessage(plugin.getMessages().prefix() + "§aУдалена валюта: §f" + id
                + " (балансы сброшены каскадом FK)");
    }

    private void list(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "Валюты:");
        for (Currency c : plugin.getCurrencies().all()) {
            sender.sendMessage(String.format("  §e%s §7[%s] §f%s %s §7nation=%s decimals=%d",
                    c.id(), c.type(), c.symbol(), c.displayName(),
                    c.nationId() == null ? "-" : c.nationId(), c.decimals()));
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "§e/rv admin currency list");
        sender.sendMessage(plugin.getMessages().prefix()
                + "§e/rv admin currency create <id> <nationId> <display> <symbol> [decimals]");
        sender.sendMessage(plugin.getMessages().prefix()
                + "§e/rv admin currency remove <id>");
    }
}
