// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Курсы/комиссии/эмбарго из rates.yml (1.1.3).
 * rates: статические пары (legacy, справочно); fees: комиссии пар; embargo: закрытые пары.
 * Движок конвертов использует цены резерва (ReserveBank), fees/embargo — отсюда.
 */
public final class RatesService {

    private final RaskolVault plugin;
    private final double defaultFee;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, Double> fees = new ConcurrentHashMap<>();
    private final Set<String> embargo = ConcurrentHashMap.newKeySet();

    public RatesService(RaskolVault plugin, double defaultFee) {
        this.plugin = plugin;
        this.defaultFee = defaultFee;
    }

    public void load(File file) {
        rates.clear();
        fees.clear();
        embargo.clear();
        if (!file.exists()) {
            plugin.getLogger().info("RaskolVault: rates.yml не найден — комиссии по умолчанию " + defaultFee);
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection rs = yaml.getConfigurationSection("rates");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                rates.put(norm(key), rs.getDouble(key, 0.0D));
            }
        }
        ConfigurationSection fs = yaml.getConfigurationSection("fees");
        if (fs != null) {
            for (String key : fs.getKeys(false)) {
                fees.put(norm(key), fs.getDouble(key, defaultFee));
            }
        }
        for (String e : yaml.getStringList("embargo")) {
            embargo.add(norm(e));
        }
        plugin.getLogger().info("RaskolVault: загружено курсов: " + rates.size()
                + " · комиссий: " + fees.size() + " · эмбарго: " + embargo.size());
    }

    private static String norm(String pair) {
        return pair.toUpperCase(Locale.ROOT).replace("-", "_");
    }

    private static String key(String from, String to) {
        return from.toUpperCase(Locale.ROOT) + "_" + to.toUpperCase(Locale.ROOT);
    }

    /** Комиссия пары (база, сжигается). Дефолт, если пара не задана. */
    public double feeFor(String from, String to) {
        return fees.getOrDefault(key(from, to), defaultFee);
    }

    /** Эмбарго направленно-симметричное: закрыта пара в обе стороны. */
    public boolean isEmbargoed(String from, String to) {
        String k = key(from, to);
        return embargo.contains(k) || embargo.contains(key(to, from));
    }

    public double staticRate(String from, String to) {
        return rates.getOrDefault(key(from, to), 0.0D);
    }

    public Map<String, Double> allRates() {
        return rates;
    }

    public double defaultFee() {
        return defaultFee;
    }
}
