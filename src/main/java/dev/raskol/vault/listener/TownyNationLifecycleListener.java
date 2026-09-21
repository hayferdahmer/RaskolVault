// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import com.palmergames.bukkit.towny.event.nation.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.nation.RenameNationEvent;
import com.palmergames.bukkit.towny.object.Nation;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Слушатель переименования/удаления наций (1.2.0).
 * Переименование → обновляет nation_id валют в реестре + currencies.yml.
 * Удаление → tradeable=false для валют + сброс резерва нации в 0.
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
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("RaskolVault: подписан на RenameNationEvent + DeleteNationEvent");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(RenameNationEvent event) {
        Nation nation = event.getNation();
        if (nation == null) {
            return;
        }
        String oldName = event.getOldName();
        String newName = nation.getName();
        if (oldName == null || newName == null || oldName.equals(newName)) {
            return;
        }
        int n = currencies.updateNationId(oldName, newName);
        rewriteCurrenciesYml();
        plugin.getLogger().info("RaskolVault: нация переименована '" + oldName + "' → '" + newName
                + "', валют обновлено: " + n + " (currencies.yml перезаписан)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDelete(DeleteNationEvent event) {
        Nation nation = event.getNation();
        if (nation == null) {
            return;
        }
        String nationName = nation.getName();
        if (nationName == null) {
            return;
        }
        int n = currencies.disableNationCurrencies(nationName);
        // Сбрасываем резерв нации в 0 — иначе при пересоздании нации с тем же именем
        // новый игрок унаследует старый резерв (дыра экономики)
        try {
            ledger.reserveSet(nationName, 0.0D);
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: не удалось сбросить резерв нации '"
                    + nationName + "': " + e.getMessage());
        }
        rewriteCurrenciesYml();
        plugin.getLogger().warning("RaskolVault: нация '" + nationName + "' удалена: "
                + n + " валют(ы) помечены неторгуемыми, резерв обнулён. "
                + "Балансы игроков сохранены. Вернуть торговлю: /rv admin currency + tradeable");
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
