// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Авто-создание национальной валюты при NewNationEvent (фикс 1.1.0.1).
 * Имена наций кириллические, поэтому ID валюты берётся из карты
 * hooks.towny.nation-currency («Рассвет: RAS», «Вальрадис: VLR»).
 * Нация вне карты — пропускаем с warning (никаких кириллических ID-валют).
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
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>)
                    Class.forName("com.palmergames.bukkit.towny.event.nation.NewNationEvent");
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, this::onNewNation, plugin, true);
            plugin.getLogger().info("RaskolVault: авто-создание национальных валют активно");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("RaskolVault: NewNationEvent не найден (Towny другой версии?)");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: не удалось подписаться на NewNationEvent: " + e.getMessage());
        }
    }

    private void onNewNation(Listener listener, Event event) {
        try {
            Method getNation = event.getClass().getMethod("getNation");
            Object nation = getNation.invoke(event);
            if (nation == null) {
                return;
            }
            Method getName = nation.getClass().getMethod("getName");
            String nationName = (String) getName.invoke(nation);
            if (nationName == null || nationName.isBlank()) {
                return;
            }
            String currencyId = plugin.getConfig()
                    .getString("hooks.towny.nation-currency." + nationName, "");
            if (currencyId.isBlank()) {
                plugin.getLogger().warning("RaskolVault: нация '" + nationName
                        + "' вне карты hooks.towny.nation-currency — валюта не создана автоматически");
                return;
            }
            currencyId = currencyId.toUpperCase(Locale.ROOT);
            if (currencies.get(currencyId).isPresent()) {
                return;
            }
            Currency newCurrency = new Currency(
                    currencyId,
                    "Динар " + nationName,
                    currencyId,
                    CurrencyType.NATIONAL,
                    nationName,
                    2,
                    true);
            currencies.addCurrency(newCurrency);
            ledger.upsertCurrency(newCurrency);
            plugin.getLogger().info("RaskolVault: создана национальная валюта " + currencyId
                    + " для нации " + nationName);
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: авто-создание валюты провалено: " + e.getMessage());
        }
    }
}
