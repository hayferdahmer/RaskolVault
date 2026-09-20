// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.util.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class NationSubcommand {

    private final RaskolVault plugin;

    public NationSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.console-cannot-nation", null));
            return;
        }
        if (!plugin.getTownyHook().isAvailable()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.towny-unavailable", null));
            return;
        }
        String nationId = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nationId == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("nation.not-in-nation", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("nation.header", Map.of("nation", nationId)));
        boolean isKing = plugin.getTownyHook().isKing(player.getUniqueId(), nationId);
        sender.sendMessage(plugin.getMessages().prefix() + "  §eРоль: §f"
                + (isKing ? "Король" : "Резидент"));
        Currency nationalCurrency = plugin.getCurrencies().get(nationId).orElse(null);
        if (nationalCurrency != null && nationalCurrency.type().name().equals("NATIONAL")) {
            double treasury = plugin.getTreasury().balance(nationId, nationalCurrency.id());
            sender.sendMessage(plugin.getMessages().prefix() + "  §eКазна: §f"
                    + Formatter.withSymbol(treasury, nationalCurrency.decimals(), nationalCurrency.symbol()));
        } else {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "  §7Валюта нации не создана");
        }
    }
}
