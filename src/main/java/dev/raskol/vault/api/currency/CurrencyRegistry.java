// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.currency;

import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Реестр валют. Источники: currencies.yml (приоритет) + merge из БД
 * (валюты, созданные в рантайме, переживают рестарт).
 */
public final class CurrencyRegistry {

    private final Plugin plugin;
    private String globalId;
    private final Map<String, Currency> byId = new LinkedHashMap<>();

    public CurrencyRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(File file, String globalId, String globalName, String globalSymbol, int globalDecimals) {
        this.globalId = globalId.toUpperCase(Locale.ROOT);
        byId.clear();

        Currency global = new Currency(this.globalId, globalName, globalSymbol,
                CurrencyType.GLOBAL, null, globalDecimals, true);
        byId.put(global.id(), global);

        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("currencies");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(key);
                    if (entry == null) {
                        continue;
                    }
                    String id = entry.getString("id", key).toUpperCase(Locale.ROOT);
                    if (id.equals(this.globalId)) {
                        continue;
                    }
                    String type = entry.getString("type", "NATIONAL");
                    CurrencyType ctype;
                    try {
                        ctype = CurrencyType.valueOf(type.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("RaskolVault: валюта '" + id + "' пропущена: неизвестный type " + type);
                        continue;
                    }
                    try {
                        Currency c = new Currency(
                                id,
                                entry.getString("display-name", id),
                                entry.getString("symbol", id),
                                ctype,
                                entry.getString("nation-id"),
                                entry.getInt("decimals", 2),
                                entry.getBoolean("tradeable", true));
                        byId.put(c.id(), c);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("RaskolVault: валюта '" + id + "' пропущена: " + e.getMessage());
                    }
                }
            }
        }
        plugin.getLogger().info("RaskolVault: валют в реестре: " + byId.size() + " (global=" + this.globalId + ")");
    }

    /**
     * Подтягивает из БД валюты, которых нет в currencies.yml
     * (созданные авто-лушнером в рантайме). YML остаётся приоритетным источником.
     */
    public int mergeFromLedger(SQLiteLedger ledger) {
        int added = 0;
        for (Currency db : ledger.loadCurrencies()) {
            if (!byId.containsKey(db.id())) {
                byId.put(db.id(), db);
                added++;
            }
        }
        if (added > 0) {
            plugin.getLogger().info("RaskolVault: из БД подтянуто валют: " + added);
        }
        return added;
    }

    public void syncToLedger(SQLiteLedger ledger) {
        for (Currency c : byId.values()) {
            ledger.upsertCurrency(c);
        }
    }

    public List<Currency> all() {
        return new ArrayList<>(byId.values());
    }

    public Optional<Currency> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id.toUpperCase(Locale.ROOT)));
    }

    public String globalId() {
        return globalId;
    }

    public int size() {
        return byId.size();
    }

    public int countByNation(String nationId) {
        if (nationId == null) {
            return 0;
        }
        int count = 0;
        for (Currency c : byId.values()) {
            if (nationId.equalsIgnoreCase(c.nationId())) {
                count++;
            }
        }
        return count;
    }

    /** Переименование нации: обновляет nationId у всех её валют. */
    public int updateNationId(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equalsIgnoreCase(newName)) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, Currency> e : byId.entrySet()) {
            Currency c = e.getValue();
            if (oldName.equalsIgnoreCase(c.nationId())) {
                byId.put(e.getKey(), new Currency(c.id(), c.displayName(), c.symbol(),
                        c.type(), newName, c.decimals(), c.tradeable()));
                n++;
            }
        }
        return n;
    }

    /**
     * Нация удалена: её валюты помечаются неторгуемыми (tradeable=false).
     * Балансы игроков и казны НЕ удаляются (FK CASCADE не срабатывает).
     */
    public int disableNationCurrencies(String nation) {
        if (nation == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, Currency> e : byId.entrySet()) {
            Currency c = e.getValue();
            if (nation.equalsIgnoreCase(c.nationId()) && c.tradeable()) {
                byId.put(e.getKey(), new Currency(c.id(), c.displayName(), c.symbol(),
                        c.type(), c.nationId(), c.decimals(), false));
                n++;
            }
        }
        return n;
    }

    /** Переименование валюты по ID (атомарно в реестре; БД — через ledger.renameCurrency). */
    public boolean rename(String oldId, String newId) {
        String upperOld = oldId.toUpperCase(Locale.ROOT);
        String upperNew = newId.toUpperCase(Locale.ROOT);
        if (upperOld.equals(upperNew)) {
            return false;
        }
        Currency c = byId.remove(upperOld);
        if (c == null) {
            return false;
        }
        if (byId.containsKey(upperNew)) {
            byId.put(upperOld, c);
            return false;
        }
        byId.put(upperNew, new Currency(upperNew, c.displayName(), c.symbol(), c.type(),
                c.nationId(), c.decimals(), c.tradeable()));
        if (upperOld.equals(globalId)) {
            globalId = upperNew;
        }
        return true;
    }

    public void addCurrency(Currency currency) {
        byId.put(currency.id(), currency);
    }

    public void removeCurrency(String id) {
        byId.remove(id.toUpperCase(Locale.ROOT));
    }
}
