// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import com.palmergames.bukkit.towny.event.nation.NewNationEvent;
import com.palmergames.bukkit.towny.object.Nation;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Авто-создание национальной валюты при NewNationEvent (1.2.0).
 * ID валюты = первые 3 буквы имени нации (uppercase) + цифровой суффикс при коллизиях.
 */
public final class NationAutoCurrencyListener implements Listener {

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    public NationAutoCurrencyListener(Plugin plugin, CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("RaskolVault: авто-создание национальных валют активно (NewNationEvent)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewNation(NewNationEvent event) {
        Nation nation = event.getNation();
        if (nation == null) {
            return;
        }
        String nationName = nation.getName();
        if (nationName == null || nationName.isBlank()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("hooks.towny.auto-currency.enabled", true)) {
            return;
        }
        String explicit = plugin.getConfig().getString(
                "hooks.towny.nation-currency." + nationName, "");
        if (explicit != null && !explicit.isBlank()) {
            createCurrencyIfAbsent(explicit.toUpperCase(Locale.ROOT), nationName);
            return;
        }
        String base = sanitizeId(nationName);
        if (base.isEmpty()) {
            plugin.getLogger().warning("RaskolVault: имя нации '" + nationName
                    + "' не даёт валидного ID — валюта не создана");
            return;
        }
        String prefix = base.length() >= 3 ? base.substring(0, 3) : base;
        String id = prefix;
        int suffix = 0;
        while (currencies.get(id).isPresent()) {
            suffix++;
            id = prefix + suffix;
        }
        createCurrencyIfAbsent(id, nationName);
    }

    private void createCurrencyIfAbsent(String currencyId, String nationName) {
        if (currencies.get(currencyId).isPresent()) {
            return;
        }
        Currency c = new Currency(
                currencyId,
                "Динар " + nationName,
                currencyId,
                CurrencyType.NATIONAL,
                nationName,
                2,
                true);
        currencies.addCurrency(c);
        ledger.upsertCurrency(c);
        plugin.getLogger().info("RaskolVault: создана национальная валюта " + currencyId
                + " для нации " + nationName);
    }

    private String sanitizeId(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toUpperCase(ch));
            }
        }
        return sb.toString();
    }
}
