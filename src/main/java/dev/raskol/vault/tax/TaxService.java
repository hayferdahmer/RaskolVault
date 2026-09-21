// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.tax;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Налоговая система наций (1.2.2-b).
 *
 * Три налога (ставка 0..5%):
 *  - convert: конверсионный (взимается при входе в валюту нации, в дополнение к общему baseFee)
 *  - exchange: торговый (сделки на бирже между нациями)
 *  - market: рыночный (ChestShop/ESGUI-сделки жителей в черте нации)
 *
 * Все налоги аккумулируются в казне нации (UUID = nation + ":treasury").
 * Хранение: plugins/RaskolVault/data/nation-taxes.yml.
 */
public final class TaxService {

    public record TaxSnapshot(String nation, double convert, double exchange, double market, long periodStart, long periodEnd) {}

    public record DailyReport(String nation, double convert, double exchange, double market, double total) {}

    private static final double MAX_RATE = 0.05D;

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final Map<String, Double> convertRates = new HashMap<>();
    private final Map<String, Double> exchangeRates = new HashMap<>();
    private final Map<String, Double> marketRates = new HashMap<>();
    private final Map<String, DailyReport> today = new HashMap<>();
    private File file;

    public TaxService(RaskolVault plugin, WalletService wallets) {
        this.plugin = plugin;
        this.wallets = wallets;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "data/nation-taxes.yml");
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("nations");
        if (root == null) return;
        for (String nation : root.getKeys(false)) {
            ConfigurationSection n = root.getConfigurationSection(nation);
            if (n == null) continue;
            convertRates.put(nation, clamp(n.getDouble("convert", 0.0D)));
            exchangeRates.put(nation, clamp(n.getDouble("exchange", 0.0D)));
            marketRates.put(nation, clamp(n.getDouble("market", 0.0D)));
            today.put(nation, new DailyReport(nation,
                    n.getDouble("today.convert", 0.0D),
                    n.getDouble("today.exchange", 0.0D),
                    n.getDouble("today.market", 0.0D),
                    n.getDouble("today.total", 0.0D)));
        }
    }

    public void save() {
        if (file == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("nations");
        for (String nation : knownNations()) {
            ConfigurationSection n = root.createSection(nation);
            n.set("convert", rate(convertRates, nation));
            n.set("exchange", rate(exchangeRates, nation));
            n.set("market", rate(marketRates, nation));
            DailyReport r = today.getOrDefault(nation, new DailyReport(nation, 0, 0, 0, 0));
            n.set("today.convert", r.convert());
            n.set("today.exchange", r.exchange());
            n.set("today.market", r.market());
            n.set("today.total", r.total());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public double getConvertRate(String nation) { return rate(convertRates, nation); }
    public double getExchangeRate(String nation) { return rate(exchangeRates, nation); }
    public double getMarketRate(String nation) { return rate(marketRates, nation); }

    public double setConvertRate(String nation, double rate) {
        double r = clamp(rate);
        convertRates.put(nation, r);
        save();
        return r;
    }
    public double setExchangeRate(String nation, double rate) {
        double r = clamp(rate);
        exchangeRates.put(nation, r);
        save();
        return r;
    }
    public double setMarketRate(String nation, double rate) {
        double r = clamp(rate);
        marketRates.put(nation, r);
        save();
        return r;
    }

    /**
     * Собирает налог nationTax со сделки amount в валюте toCurrency и зачисляет в казну нации.
     * Возвращает фактически собранный налог. 0, если ставка 0 или валюта не национальной.
     */
    public double collect(String nation, String toCurrency, double amount, String reason) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        Currency cur = plugin.getCurrencies().get(toCurrency).orElse(null);
        if (cur == null || cur.type() != CurrencyType.NATIONAL) return 0.0D;
        if (!nation.equals(cur.nationId())) return 0.0D;
        double rate = getConvertRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = Math.round(amount * rate * 100.0D) / 100.0D;
        if (!(tax > 0.0D)) return 0.0D;
        java.util.UUID treasury = dev.raskol.vault.reserve.ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, toCurrency, tax, TransactionType.PAY, "tax:" + reason)) {
            return 0.0D;
        }
        recordTax(nation, "convert", tax);
        return tax;
    }

    public double collectExchange(String nation, String currency, double amount) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        double rate = getExchangeRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = Math.round(amount * rate * 100.0D) / 100.0D;
        if (!(tax > 0.0D)) return 0.0D;
        java.util.UUID treasury = dev.raskol.vault.reserve.ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency, tax, TransactionType.PAY, "tax:exchange")) return 0.0D;
        recordTax(nation, "exchange", tax);
        return tax;
    }

    public double collectMarket(String nation, String currency, double amount) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        double rate = getMarketRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = Math.round(amount * rate * 100.0D) / 100.0D;
        if (!(tax > 0.0D)) return 0.0D;
        java.util.UUID treasury = dev.raskol.vault.reserve.ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency, tax, TransactionType.PAY, "tax:market")) return 0.0D;
        recordTax(nation, "market", tax);
        return tax;
    }

    public DailyReport todayReport(String nation) {
        return today.getOrDefault(nation, new DailyReport(nation, 0, 0, 0, 0));
    }

    public void resetDaily() {
        today.clear();
        save();
    }

    private void recordTax(String nation, String kind, double amount) {
        DailyReport cur = today.getOrDefault(nation, new DailyReport(nation, 0, 0, 0, 0));
        double newConvert = "convert".equals(kind) ? cur.convert() + amount : cur.convert();
        double newExchange = "exchange".equals(kind) ? cur.exchange() + amount : cur.exchange();
        double newMarket = "market".equals(kind) ? cur.market() + amount : cur.market();
        double newTotal = newConvert + newExchange + newMarket;
        today.put(nation, new DailyReport(nation, newConvert, newExchange, newMarket, newTotal));
        save();
    }

    private java.util.Set<String> knownNations() {
        java.util.Set<String> out = new java.util.HashSet<>();
        out.addAll(convertRates.keySet());
        out.addAll(exchangeRates.keySet());
        out.addAll(marketRates.keySet());
        out.addAll(today.keySet());
        return out;
    }

    private static double clamp(double v) {
        if (!Double.isFinite(v) || v < 0.0D) return 0.0D;
        return Math.min(v, MAX_RATE);
    }

    private static double rate(Map<String, Double> map, String nation) {
        Double v = map.get(nation);
        return v == null ? 0.0D : v;
    }
}
