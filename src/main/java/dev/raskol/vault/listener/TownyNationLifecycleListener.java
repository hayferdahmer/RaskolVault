// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Слушатель переименования/удаления наций (фикс 1.1.0.2).
 * Использует ТОЛЬКО существующие методы реестра: updateNationId / disableNationCurrencies.
 * currencies.yml перезаписывается как источник правды (на рестарте syncToLedger
 * приведёт таблицу currencies в БД к тому же состоянию).
 */
public final class TownyNationLifecycleListener implements Listener {

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    public TownyNationLifecycleListener(Plugin plugin, CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    public void register() {
        registerEvent("com.palmergames.bukkit.towny.event.nation.RenameNationEvent", this::onRename);
        registerEvent("com.palmergames.bukkit.towny.event.nation.DeleteNationEvent", this::onDelete);
    }

    private void registerEvent(String className, EventExecutor executor) {
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(className);
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().info("RaskolVault: подписан на " + className);
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("RaskolVault: событие " + className
                    + " не найдено (Towny другой версии?) — авто-обновление валют отключено");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: не удалось подписаться на " + className + ": " + e.getMessage());
        }
    }

    private void onRename(Listener listener, Event event) {
        try {
            Method getOldName = event.getClass().getMethod("getOldName");
            Method getNation = event.getClass().getMethod("getNation");
            Object nation = getNation.invoke(event);
            if (nation == null) {
                return;
            }
            Method getName = nation.getClass().getMethod("getName");
            String oldName = (String) getOldName.invoke(event);
            String newName = (String) getName.invoke(nation);
            if (oldName == null || newName == null || oldName.equals(newName)) {
                return;
            }
            int n = currencies.updateNationId(oldName, newName);
            rewriteCurrenciesYml();
            plugin.getLogger().info("RaskolVault: нация переименована '" + oldName + "' → '" + newName
                    + "', валют обновлено: " + n + " (currencies.yml перезаписан)");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: обработка переименования нации провалена: " + e.getMessage());
        }
    }

    private void onDelete(Listener listener, Event event) {
        try {
            Method getNation = event.getClass().getMethod("getNation");
            Object nation = getNation.invoke(event);
            if (nation == null) {
                return;
            }
            Method getName = nation.getClass().getMethod("getName");
            String nationName = (String) getName.invoke(nation);
            if (nationName == null) {
                return;
            }
            int n = currencies.disableNationCurrencies(nationName);
            rewriteCurrenciesYml();
            plugin.getLogger().warning("RaskolVault: нация '" + nationName + "' удалена: "
                    + n + " валют(ы) помечены неторгуемыми. Балансы игроков сохранены. "
                    + "Вернуть торговлю: /rv admin currency + tradeable в currencies.yml");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: обработка удаления нации провалена: " + e.getMessage());
        }
    }

    /** Перезаписывает currencies.yml из текущего состояния реестра (источник правды). */
    private void rewriteCurrenciesYml() {
        try {
            File file = new File(plugin.getDataFolder(), "currencies.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("currencies");
            if (section == null) {
                return;
            }
            for (String key : section.getKeys(false)) {
                Currency c = currencies.get(key).orElse(null);
                if (c == null) {
                    continue;
                }
                if (c.nationId() != null) {
                    section.set(key + ".nation-id", c.nationId());
                }
                section.set(key + ".tradeable", c.tradeable());
            }
            SafeStorage.saveAtomic(yaml, file, plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: не удалось перезаписать currencies.yml: " + e.getMessage());
        }
    }
}
