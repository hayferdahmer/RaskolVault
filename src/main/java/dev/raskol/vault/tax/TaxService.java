// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.tax;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.storage.SafeStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Налоговые ставки наций (1.2.5-a.2): + auctionRate (налог с продаж на аукционе).
 */
public final class TaxService {

    private final RaskolVault plugin;
    private final File file;
    private final Map<String, Double> convertRates = new ConcurrentHashMap<>();
    private final Map<String, Double> exchangeRates = new ConcurrentHashMap<>();
    private final Map<String, Double> marketRates = new ConcurrentHashMap<>();
    private final Map<String, Double> auctionRates = new ConcurrentHashMap<>();

    public TaxService(RaskolVault plugin, Object unused) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/nation-taxes.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("nations");
        if (root == null) return;
        for (String nation : root.getKeys(false)) {
            ConfigurationSection n = root.getConfigurationSection(nation);
            if (n == null) continue;
            convertRates.put(nation.toLowerCase(), n.getDouble("convert", 0.0D));
            exchangeRates.put(nation.toLowerCase(), n.getDouble("exchange", 0.0D));
            marketRates.put(nation.toLowerCase(), n.getDouble("market", 0.0D));
            auctionRates.put(nation.toLowerCase(), n.getDouble("auction", 0.02D));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("nations");
        for (String nation : convertRates.keySet()) {
            ConfigurationSection n = root.createSection(nation);
            n.set("convert", convertRates.getOrDefault(nation, 0.0D));
            n.set("exchange", exchangeRates.getOrDefault(nation, 0.0D));
            n.set("market", marketRates.getOrDefault(nation, 0.0D));
            n.set("auction", auctionRates.getOrDefault(nation, 0.02D));
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public double getConvertRate(String nation) { return convertRates.getOrDefault(nation.toLowerCase(), 0.0D); }
    public double getExchangeRate(String nation) { return exchangeRates.getOrDefault(nation.toLowerCase(), 0.0D); }
    public double getMarketRate(String nation) { return marketRates.getOrDefault(nation.toLowerCase(), 0.0D); }
    public double getAuctionRate(String nation) { return auctionRates.getOrDefault(nation.toLowerCase(), 0.02D); }

    public void setConvertRate(String nation, double rate) { convertRates.put(nation.toLowerCase(), rate); save(); }
    public void setExchangeRate(String nation, double rate) { exchangeRates.put(nation.toLowerCase(), rate); save(); }
    public void setMarketRate(String nation, double rate) { marketRates.put(nation.toLowerCase(), rate); save(); }
    public void setAuctionRate(String nation, double rate) { auctionRates.put(nation.toLowerCase(), rate); save(); }
}
