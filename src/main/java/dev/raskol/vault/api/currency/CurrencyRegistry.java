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
                        plugin.getLogger().warning("RaskolVault: неверный тип валюты " + id + ": " + type);
                        continue;
                    }
                    Currency c = new Currency(
                            id,
                            entry.getString("display-name", id),
                            entry.getString("symbol", "?"),
                            ctype,
                            entry.getString("nation-id"),
                            entry.getInt("decimals", 2),
                            entry.getBoolean("tradeable", true)
                    );
                    byId.put(c.id(), c);
                }
            }
        }
        plugin.getLogger().info("RaskolVault: валют в реестре: " + byId.size() + " (global=" + this.globalId + ")");
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
            if (nationId.equals(c.nationId())) {
                count++;
            }
        }
        return count;
    }

    public int updateNationId(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName)) {
            return 0;
        }
        List<String> keysToReplace = new ArrayList<>();
        List<Currency> replaced = new ArrayList<>();
        for (Map.Entry<String, Currency> e : byId.entrySet()) {
            Currency c = e.getValue();
            if (oldName.equals(c.nationId())) {
                keysToReplace.add(e.getKey());
                replaced.add(new Currency(c.id(), c.displayName(), c.symbol(), c.type(),
                        newName, c.decimals(), c.tradeable()));
            }
        }
        for (int i = 0; i < keysToReplace.size(); i++) {
            byId.put(keysToReplace.get(i), replaced.get(i));
        }
        return replaced.size();
    }

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
        Currency renamed = new Currency(upperNew, c.displayName(), c.symbol(), c.type(),
                c.nationId(), c.decimals(), c.tradeable());
        byId.put(upperNew, renamed);
        if (upperOld.equals(globalId)) {
            globalId = upperNew;
        }
        return true;
    }

    /** 1.0.7: добавить валюту в реестр (используется NationAutoCurrencyListener). */
    public void addCurrency(Currency currency) {
        byId.put(currency.id(), currency);
    }

    /** 1.0.7: удалить валюту из реестра. */
    public void removeCurrency(String id) {
        byId.remove(id.toUpperCase(Locale.ROOT));
    }
}
