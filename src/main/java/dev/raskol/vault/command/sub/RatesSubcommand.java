// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class RatesSubcommand {

    private final RaskolVault plugin;

    public RatesSubcommand(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender) {
        if (!sender.hasPermission("raskolvault.convert")) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.no-permission", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("rates.header",
                Map.of("fee", String.format("%.1f%%", plugin.getRates().defaultFee() * 100.0))));
        Map<String, Double> all = plugin.getRates().allRates();
        if (all.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("rates.empty", null));
            return;
        }
        for (Map.Entry<String, Double> entry : all.entrySet()) {
            sender.sendMessage("  §e" + entry.getKey() + " §7= §f" + String.format("%.4f", entry.getValue()));
        }
    }
}
