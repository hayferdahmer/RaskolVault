// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * Слушатель NewNationEvent Towny через рефлексию.
 * Регистрируется через PluginManager.registerEvent() с EventExecutor-лямбдой,
 * что позволяет ловить событие без compile-time класса события.
 *
 * При создании нации автоматически создаёт национальную валюту:
 * - id = lowercase(nationId)
 * - display = "Валюта " + nationName
 * - symbol = ☀ для светлых (Svet*, Ozar*), иначе ☾
 * - decimals = 2
 * - tradeable = true
 *
 * Конфиг-флаг hooks.towny.auto-create-national позволяет выключить.
 */
public final class NationAutoCurrencyListener implements Listener {

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;
    private final String defaultLightPrefix;
    private final String defaultDarkPrefix;

    public NationAutoCurrencyListener(Plugin plugin, CurrencyRegistry currencies,
                                      SQLiteLedger ledger,
                                      String defaultLightPrefix, String defaultDarkPrefix) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.ledger = ledger;
        this.defaultLightPrefix = defaultLightPrefix;
        this.defaultDarkPrefix = defaultDarkPrefix;
    }

    /**
     * Регистрирует слушатель NewNationEvent через рефлексию.
     * Возвращает true при успехе, false если Towny недоступен.
     */
    public boolean register() {
        Plugin towny = plugin.getServer().getPluginManager().getPlugin("Towny");
        if (towny == null) {
            return false;
        }
        try {
            Class<?> eventClass = Class.forName(
                    "com.palmergames.bukkit.towny.event.NewNationEvent",
                    true, towny.getClass().getClassLoader());
            Method getNation = eventClass.getMethod("getNation");

            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                handle(event, getNation);
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().info("RaskolVault: авто-создание национальных валют включено "
                    + "(слушатель NewNationEvent зарегистрирован)");
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("RaskolVault: не удалось подписаться на NewNationEvent: "
                    + e.getMessage() + " — валюты наций придётся создавать вручную "
                    + "(/rv admin currency create)");
            return false;
        }
    }

    private void handle(Event event, Method getNation) {
        try {
            Object nation = getNation.invoke(event);
            if (nation == null) {
                return;
            }
            Method getName = nation.getClass().getMethod("getName");
            String name = (String) getName.invoke(nation);
            if (name == null || name.isBlank()) {
                return;
            }
            String id = name.toLowerCase(Locale.ROOT);
            Optional<Currency> existing = currencies.get(id);
            if (existing.isPresent()) {
                plugin.getLogger().info("RaskolVault: национальная валюта '" + id
                        + "' уже существует, пропускаю авто-создание");
                return;
            }
            String symbol = id.startsWith(defaultLightPrefix) ? "☀" : "☾";
            if (id.startsWith(defaultDarkPrefix)) {
                symbol = "☾";
            }
            Currency currency = new Currency(
                    id,
                    "Валюта " + name,
                    symbol,
                    CurrencyType.NATIONAL,
                    id,
                    2,
                    true);
            // Registry.addCurrency добавляет в память + леджер (см. обновление CurrencyRegistry)
            currencies.addCurrency(currency);
            ledger.upsertCurrency(currency);
            plugin.getLogger().info("RaskolVault: создана национальная валюта '" + id
                    + "' (" + symbol + ") для нации " + name);
        } catch (ReflectiveOperationException | LedgerException e) {
            plugin.getLogger().warning("RaskolVault: авто-создание валюты для новой нации "
                    + "провалено: " + e.getMessage());
        }
    }
}
