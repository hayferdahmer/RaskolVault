// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.currency;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Реестр валют: currencies.yml → immutable-модели → синхронизация в леджер.
 * Инвариант: ровно одна GLOBAL-валюта. Дубли и битые записи не роняют старт,
 * а пропускаются с warning — деньги не должны блокировать сервер из-за опечатки.
 */
public final class CurrencyRegistry {

    private final Plugin plugin;
    private final Map<String, Currency> byId = new LinkedHashMap<>();
    private String globalId;

    public CurrencyRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(File currenciesFile, String fallbackId, String fallbackName,
                     String fallbackSymbol, int fallbackDecimals) {
        byId.clear();
        globalId = null;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(currenciesFile);
        ConfigurationSection section = yaml.getConfigurationSection("currencies");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection cs = section.getConfigurationSection(id);
                if (cs == null) {
                    plugin.getLogger().warning("RaskolVault: валюта '" + id + "' пропущена: нет секции");
                    continue;
                }
                String typeRaw = cs.getString("type", "WORLD").toUpperCase(Locale.ROOT);
                CurrencyType type;
                try {
                    type = CurrencyType.valueOf(typeRaw);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("RaskolVault: валюта '" + id + "' пропущена: неизвестный type " + typeRaw);
                    continue;
                }
                try {
                    Currency currency = new Currency(
                            id,
                            cs.getString("display-name", id),
                            cs.getString("symbol", "¤"),
                            type,
                            cs.getString("nation-id"),
                            cs.getInt("decimals", 2),
                            cs.getBoolean("tradeable", true));
                    if (type == CurrencyType.GLOBAL) {
                        if (globalId != null) {
                            plugin.getLogger().warning("RaskolVault: вторая GLOBAL-валюта '" + id
                                    + "' пропущена (global уже " + globalId + ")");
                            continue;
                        }
                        globalId = id;
                    }
                    byId.put(id, currency);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("RaskolVault: валюта '" + id + "' пропущена: " + e.getMessage());
                }
            }
        }
        if (globalId == null) {
            Currency fallback = new Currency(fallbackId, fallbackName, fallbackSymbol,
                    CurrencyType.GLOBAL, null, fallbackDecimals, true);
            byId.put(fallback.id(), fallback);
            globalId = fallback.id();
            plugin.getLogger().warning("RaskolVault: в currencies.yml нет GLOBAL-валюты — "
                    + "создана дефолтная " + fallback.id());
        }
        plugin.getLogger().info("RaskolVault: загружено валют: " + byId.size() + " (global: " + globalId + ")");
    }

    /** Строки валют в леджер (FK для balances должен существовать до первых балансов). */
    public void syncToLedger(SQLiteLedger ledger) {
        for (Currency currency : byId.values()) {
            ledger.upsertCurrency(currency);
        }
    }

    public Optional<Currency> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Currency> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<Currency> nationalOf(String nationId) {
        List<Currency> out = new ArrayList<>();
        for (Currency currency : byId.values()) {
            if (currency.type() == CurrencyType.NATIONAL && nationId.equalsIgnoreCase(currency.nationId())) {
                out.add(currency);
            }
        }
        return out;
    }

    public String globalId() {
        return globalId;
    }

    public Currency global() {
        return byId.get(globalId);
    }
}
