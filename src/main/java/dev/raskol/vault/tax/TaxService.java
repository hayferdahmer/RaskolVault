// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.tax;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Налоговая система наций (1.2.5-a.2 fix): ставки (convert/exchange/market/auction)
 * + методы сбора (collect/collectExchange/collectMarket) + дневные отчёты (DailyReport).
 * Конструктор (plugin, wallets) — wallets нужен для зачисления в казну.
 */
public final class TaxService {

    public record DailyReport(String nation, double convert, double exchange, double market, double total) {}

    private static final double MAX_RATE = 0.05D;

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final File file;
    private final Map<String, Double> convertRates = new ConcurrentHashMap<>();
    private final Map<String, Double> exchangeRates = new ConcurrentHashMap<>();
    private final Map<String, Double> marketRates = new ConcurrentHashMap<>();
    private final Map<String, Double> auctionRates = new ConcurrentHashMap<>();
    private final Map<String, DailyReport> today = new ConcurrentHashMap<>();

    public TaxService(RaskolVault plugin, WalletService wallets) {
        this.plugin = plugin;
        this.wallets = wallets;
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
            String key = nation.toLowerCase();
            convertRates.put(key, clamp(n.getDouble("convert", 0.0D)));
            exchangeRates.put(key, clamp(n.getDouble("exchange", 0.0D)));
            marketRates.put(key, clamp(n.getDouble("market", 0.0D)));
            auctionRates.put(key, clamp(n.getDouble("auction", 0.02D)));
            today.put(key, new DailyReport(key,
                    n.getDouble("today.convert", 0.0D),
                    n.getDouble("today.exchange", 0.0D),
                    n.getDouble("today.market", 0.0D),
                    n.getDouble("today.total", 0.0D)));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("nations");
        for (String key : knownNations()) {
            ConfigurationSection n = root.createSection(key);
            n.set("convert", convertRates.getOrDefault(key, 0.0D));
            n.set("exchange", exchangeRates.getOrDefault(key, 0.0D));
            n.set("market", marketRates.getOrDefault(key, 0.0D));
            n.set("auction", auctionRates.getOrDefault(key, 0.02D));
            DailyReport r = today.getOrDefault(key, new DailyReport(key, 0, 0, 0, 0));
            n.set("today.convert", r.convert());
            n.set("today.exchange", r.exchange());
            n.set("today.market", r.market());
            n.set("today.total", r.total());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    // ---------- ставки ----------
    public double getConvertRate(String nation) { return convertRates.getOrDefault(low(nation), 0.0D); }
    public double getExchangeRate(String nation) { return exchangeRates.getOrDefault(low(nation), 0.0D); }
    public double getMarketRate(String nation) { return marketRates.getOrDefault(low(nation), 0.0D); }
    public double getAuctionRate(String nation) { return auctionRates.getOrDefault(low(nation), 0.02D); }

    public void setConvertRate(String nation, double rate) { convertRates.put(low(nation), clamp(rate)); save(); }
    public void setExchangeRate(String nation, double rate) { exchangeRates.put(low(nation), clamp(rate)); save(); }
    public void setMarketRate(String nation, double rate) { marketRates.put(low(nation), clamp(rate)); save(); }
    public void setAuctionRate(String nation, double rate) { auctionRates.put(low(nation), clamp(rate)); save(); }

    // ---------- сбор налогов ----------

    /** Конверсионный налог: при входе в национальную валюту нации. */
    public double collect(String nation, String toCurrency, double amount, String reason) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        Currency cur = plugin.getCurrencies().get(toCurrency).orElse(null);
        if (cur == null || cur.type() != CurrencyType.NATIONAL) return 0.0D;
        if (!nation.equalsIgnoreCase(cur.nationId())) return 0.0D;
        double rate = getConvertRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = round2(amount * rate);
        if (!(tax > 0.0D)) return 0.0D;
        UUID treasury = ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, toCurrency, tax, TransactionType.PAY, "tax:" + reason)) return 0.0D;
        recordTax(nation, "convert", tax);
        return tax;
    }

    /** Торговый налог: сделки на бирже ордеров. */
    public double collectExchange(String nation, String currency, double amount) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        double rate = getExchangeRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = round2(amount * rate);
        if (!(tax > 0.0D)) return 0.0D;
        UUID treasury = ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency, tax, TransactionType.PAY, "tax:exchange")) return 0.0D;
        recordTax(nation, "exchange", tax);
        return tax;
    }

    /** Рыночный налог: ChestShop/ESGUI-сделки в черте нации. */
    public double collectMarket(String nation, String currency, double amount) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        double rate = getMarketRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = round2(amount * rate);
        if (!(tax > 0.0D)) return 0.0D;
        UUID treasury = ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency, tax, TransactionType.PAY, "tax:market")) return 0.0D;
        recordTax(nation, "market", tax);
        return tax;
    }

    /** Аукционный налог: продажи на аукционе (используется AuctionService). */
    public double collectAuction(String nation, String currency, double amount) {
        if (nation == null || amount <= 0.0D) return 0.0D;
        double rate = getAuctionRate(nation);
        if (!(rate > 0.0D)) return 0.0D;
        double tax = round2(amount * rate);
        if (!(tax > 0.0D)) return 0.0D;
        UUID treasury = ReserveBank.treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency, tax, TransactionType.PAY, "tax:auction")) return 0.0D;
        recordTax(nation, "exchange", tax);
        return tax;
    }

    // ---------- отчёты ----------
    public DailyReport todayReport(String nation) {
        return today.getOrDefault(low(nation), new DailyReport(low(nation), 0, 0, 0, 0));
    }

    public void resetDaily() {
        today.clear();
        save();
    }

    private void recordTax(String nation, String kind, double amount) {
        String key = low(nation);
        DailyReport cur = today.getOrDefault(key, new DailyReport(key, 0, 0, 0, 0));
        double c = "convert".equals(kind) ? cur.convert() + amount : cur.convert();
        double x = "exchange".equals(kind) ? cur.exchange() + amount : cur.exchange();
        double m = "market".equals(kind) ? cur.market() + amount : cur.market();
        today.put(key, new DailyReport(key, c, x, m, c + x + m));
        save();
    }

    private Set<String> knownNations() {
        Set<String> out = new HashSet<>();
        out.addAll(convertRates.keySet());
        out.addAll(exchangeRates.keySet());
        out.addAll(marketRates.keySet());
        out.addAll(auctionRates.keySet());
        out.addAll(today.keySet());
        return out;
    }

    private static String low(String s) { return s == null ? "" : s.toLowerCase(); }
    private static double clamp(double v) {
        if (!Double.isFinite(v) || v < 0.0D) return 0.0D;
        return Math.min(v, MAX_RATE);
    }
    private static double round2(double v) { return Math.round(v * 100.0D) / 100.0D; }
}
