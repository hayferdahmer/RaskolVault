// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Слушатель NewNationEvent Towny через рефлексию (без compile-зависимости).
 * При создании нации автоматически создаёт национальную валюту:
 * id = lowercase(имя нации), decimals = 2, tradeable = true.
 *
 * Символ берётся из ТОЧНОЙ карты hooks.towny.nation-symbols (rassvet → ☀,
 * valradis → ☾), для прочих наций — hooks.towny.default-symbol.
 * Семантики «свет/тьма» нет: только фракции Рассвет (Лайтрис) и Вальрадис (Драгос).
 */
public final class NationAutoCurrencyListener implements Listener {

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;
    private final Map<String, String> symbols;
    private final String defaultSymbol;

    public NationAutoCurrencyListener(Plugin plugin, CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.ledger = ledger;
        this.symbols = loadSymbols();
        this.defaultSymbol = plugin.getConfig().getString("hooks.towny.default-symbol", "¤");
    }

    private Map<String, String> loadSymbols() {
        Map<String, String> out = new HashMap<>();
        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("hooks.towny.nation-symbols");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                out.put(key.toLowerCase(Locale.ROOT), section.getString(key, "¤"));
            }
        }
        return out;
    }

    /** Регистрирует слушатель NewNationEvent через рефлексию. */
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
                    + e.getMessage() + " — валюты наций создаются вручную (/rv admin currency create)");
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
                        + "' уже существует, авто-создание пропущено");
                return;
            }
            String symbol = symbols.getOrDefault(id, defaultSymbol);
            Currency currency = new Currency(
                    id,
                    "Валюта " + name,
                    symbol,
                    CurrencyType.NATIONAL,
                    id,
                    2,
                    true);
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
