// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.storage.SafeStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Статистика аукциона (1.2.5-a.3): топ продавцов, топ покупок, объём торгов.
 * Хранение: data/auction-stats.yml.
 *
 * saleHistory — последние N сделок (кольцевой буфер).
 * sellerTotals — сумма продаж по UUID продавца.
 * totalVolume — общий объём торгов в GLD.
 */
public final class AuctionStats {

    public record SaleRecord(
            String lotId,
            UUID seller,
            String sellerName,
            UUID buyer,
            String buyerName,
            String itemName,
            double amount,
            String currencyId,
            long timestamp
    ) {}

    public record SellerTotal(UUID seller, String name, int salesCount, double totalVolume) {}

    private static final int MAX_HISTORY = 200;

    private final RaskolVault plugin;
    private final File file;
    private final List<SaleRecord> saleHistory = new ArrayList<>();
    private final Map<UUID, SellerTotal> sellerTotals = new ConcurrentHashMap<>();
    private double totalVolume = 0.0D;
    private int totalSales = 0;

    public AuctionStats(RaskolVault plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/auction-stats.yml");
        load();
    }

    public synchronized void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        totalVolume = yaml.getDouble("total-volume", 0.0D);
        totalSales = yaml.getInt("total-sales", 0);

        ConfigurationSection sellers = yaml.getConfigurationSection("sellers");
        if (sellers != null) {
            for (String key : sellers.getKeys(false)) {
                try {
                    ConfigurationSection s = sellers.getConfigurationSection(key);
                    if (s == null) continue;
                    UUID uuid = UUID.fromString(key);
                    sellerTotals.put(uuid, new SellerTotal(
                            uuid, s.getString("name", ""),
                            s.getInt("count", 0), s.getDouble("volume", 0.0D)));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        ConfigurationSection hist = yaml.getConfigurationSection("history");
        if (hist != null) {
            for (String key : hist.getKeys(false)) {
                try {
                    ConfigurationSection s = hist.getConfigurationSection(key);
                    if (s == null) continue;
                    String sellerStr = s.getString("seller", "");
                    String buyerStr = s.getString("buyer", "");
                    saleHistory.add(new SaleRecord(
                            s.getString("lotId", ""),
                            sellerStr.isEmpty() ? null : UUID.fromString(sellerStr),
                            s.getString("sellerName", ""),
                            buyerStr.isEmpty() ? null : UUID.fromString(buyerStr),
                            s.getString("buyerName", ""),
                            s.getString("item", ""),
                            s.getDouble("amount", 0.0D),
                            s.getString("currency", "GLD"),
                            s.getLong("timestamp", 0L)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("total-volume", totalVolume);
        yaml.set("total-sales", totalSales);

        ConfigurationSection sellers = yaml.createSection("sellers");
        for (SellerTotal t : sellerTotals.values()) {
            ConfigurationSection s = sellers.createSection(t.seller().toString());
            s.set("name", t.name());
            s.set("count", t.salesCount());
            s.set("volume", t.totalVolume());
        }

        ConfigurationSection hist = yaml.createSection("history");
        int idx = 0;
        int start = Math.max(0, saleHistory.size() - MAX_HISTORY);
        for (int i = start; i < saleHistory.size(); i++) {
            SaleRecord r = saleHistory.get(i);
            ConfigurationSection s = hist.createSection(String.valueOf(idx++));
            s.set("lotId", r.lotId());
            s.set("seller", r.seller() == null ? "" : r.seller().toString());
            s.set("sellerName", r.sellerName());
            s.set("buyer", r.buyer() == null ? "" : r.buyer().toString());
            s.set("buyerName", r.buyerName());
            s.set("item", r.itemName());
            s.set("amount", r.amount());
            s.set("currency", r.currencyId());
            s.set("timestamp", r.timestamp());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public synchronized void recordSale(SaleRecord r) {
        saleHistory.add(r);
        while (saleHistory.size() > MAX_HISTORY) saleHistory.remove(0);
        sellerTotals.compute(r.seller(), (k, cur) -> {
            int count = (cur == null ? 0 : cur.salesCount()) + 1;
            double vol = (cur == null ? 0.0D : cur.totalVolume()) + r.amount();
            return new SellerTotal(r.seller(), r.sellerName(), count, vol);
        });
        totalVolume += r.amount();
        totalSales += 1;
        save();
    }

    public double totalVolume() { return totalVolume; }
    public int totalSales() { return totalSales; }

    public synchronized List<SellerTotal> topSellers(int limit) {
        List<SellerTotal> list = new ArrayList<>(sellerTotals.values());
        list.sort(Comparator.comparingDouble(SellerTotal::totalVolume).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    public synchronized List<SaleRecord> topLots(int limit) {
        List<SaleRecord> list = new ArrayList<>(saleHistory);
        list.sort(Comparator.comparingDouble(SaleRecord::amount).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    public synchronized List<SaleRecord> recentSales(int limit) {
        List<SaleRecord> list = new ArrayList<>(saleHistory);
        list.sort(Comparator.comparingLong(SaleRecord::timestamp).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    public SellerTotal sellerStats(UUID seller) { return sellerTotals.get(seller); }
}
