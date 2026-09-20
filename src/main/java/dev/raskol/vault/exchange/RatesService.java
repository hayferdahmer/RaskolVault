// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Курсы обмена: rates.yml → immutable-карта.
 * Ключи нормализуются в lower-case; обратная пара не выводится автоматически —
 * задаётся явно (gold_denarius ≠ denarius_gold), это осознанный антиарбитражный ход.
 */
public final class RatesService {

    private final Plugin plugin;
    private final double defaultFee;
    private Map<String, Double> rates = Map.of();
    private Map<String, Double> pairFees = Map.of();

    public RatesService(Plugin plugin, double defaultFee) {
        this.plugin = plugin;
        this.defaultFee = Math.max(0.0D, Math.min(0.99D, defaultFee));
    }

    public void load(File file) {
        Map<String, Double> newRates = new LinkedHashMap<>();
        Map<String, Double> newFees = new LinkedHashMap<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("rates");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String normalized = key.toLowerCase(Locale.ROOT).trim();
                if (!normalized.matches("[a-z0-9]+_[a-z0-9]+")) {
                    plugin.getLogger().warning("RaskolVault: курс '" + key + "' пропущен: "
                            + "ожидался формат <from>_<to>");
                    continue;
                }
                double rate = section.getDouble(key, 0.0D);
                if (!(rate > 0.0D)) {
                    plugin.getLogger().warning("RaskolVault: курс '" + key + "' пропущен: некорректное значение " + rate);
                    continue;
                }
                newRates.put(normalized, rate);
            }
        }
        ConfigurationSection feesSection = yaml.getConfigurationSection("fees");
        if (feesSection != null) {
            for (String key : feesSection.getKeys(false)) {
                String normalized = key.toLowerCase(Locale.ROOT).trim();
                double fee = feesSection.getDouble(key, defaultFee);
                newFees.put(normalized, Math.max(0.0D, Math.min(0.99D, fee)));
            }
        }
        this.rates = Map.copyOf(newRates);
        this.pairFees = Map.copyOf(newFees);
        plugin.getLogger().info("RaskolVault: загружено курсов: " + this.rates.size()
                + " · индивидуальных комиссий: " + this.pairFees.size()
                + " · дефолтная комиссия: " + defaultFee);
    }

    public Optional<Double> rate(String from, String to) {
        return Optional.ofNullable(rates.get(pairKey(from, to)));
    }

    public double fee(String from, String to) {
        Double specific = pairFees.get(pairKey(from, to));
        return specific != null ? specific : defaultFee;
    }

    public Map<String, Double> allRates() {
        return Collections.unmodifiableMap(rates);
    }

    public double defaultFee() {
        return defaultFee;
    }

    private static String pairKey(String from, String to) {
        return from.toLowerCase(Locale.ROOT) + "_" + to.toLowerCase(Locale.ROOT);
    }
}
