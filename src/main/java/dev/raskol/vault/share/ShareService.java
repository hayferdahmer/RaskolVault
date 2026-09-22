// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.share;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис Долей и Ужитка (1.2.4).
 *
 * Семантика:
 *  - Король выпускает Доли, обеспечивая их резервом нации (1 золотник = 1 GLD резерва).
 *  - Игроки покупают/продают Доли через рынок.
 *  - Король раздаёт Ужиток — распределяет часть казны пропорционально Долям.
 *
 * Персистентность: data/shares.yml.
 */
public final class ShareService {

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final ReserveBank bank;
    private final File file;
    private final Map<String, Share> shares = new ConcurrentHashMap<>();

    public ShareService(RaskolVault plugin, WalletService wallets, ReserveBank bank) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.bank = bank;
        this.file = new File(plugin.getDataFolder(), "data/shares.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("shares");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String ownerStr = s.getString("owner");
            if (ownerStr == null || ownerStr.isEmpty()) continue;
            shares.put(id, new Share(
                    id,
                    s.getString("nation", ""),
                    UUID.fromString(ownerStr),
                    s.getDouble("grams", 0.0D),
                    s.getLong("issuedAt", 0L),
                    s.getLong("lastPayout", 0L)));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("shares");
        for (Share s : shares.values()) {
            ConfigurationSection sec = root.createSection(s.id());
            sec.set("nation", s.nation());
            sec.set("owner", s.owner().toString());
            sec.set("grams", s.grams());
            sec.set("issuedAt", s.issuedAt());
            sec.set("lastPayout", s.lastPayout());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    /** Суммарная масса Долей нации в золотниках. */
    public double totalGrams(String nation) {
        double sum = 0.0D;
        for (Share s : shares.values()) {
            if (nation.equalsIgnoreCase(s.nation())) sum += s.grams();
        }
        return sum;
    }

    /** Максимум Долей, который можно эмитировать (обеспечено резервом). */
    public double maxIssueGrams(String nation) {
        double reserve = bank.reserveOf(nation);
        return Math.max(0.0D, reserve - totalGrams(nation));
    }

    /** Король выпускает новую Долю на имя держателя. Цена = grams GLD. */
    public Share issue(String nation, UUID holder, double grams) {
        if (!(grams > 0.0D)) return null;
        if (grams > maxIssueGrams(nation) + 1e-9D) return null;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(nation);
        // Держатель платит в казну
        if (!wallets.withdraw(holder, glb, grams, TransactionType.PAY, "share:issue:" + nation)) return null;
        if (!wallets.deposit(treasury, glb, grams, TransactionType.PAY, "share:issue:" + nation)) {
            wallets.deposit(holder, glb, grams, TransactionType.PAY, "share:issue:rollback");
            return null;
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Share s = new Share(id, nation, holder, grams, now, now);
        shares.put(id, s);
        save();
        return s;
    }

    /** Держатель продаёт Долю обратно в казну. Выплата = grams × (резерв/общая_масса). */
    public boolean redeem(String shareId) {
        Share s = shares.get(shareId);
        if (s == null) return false;
        double total = totalGrams(s.nation());
        double reserve = bank.reserveOf(s.nation());
        double pricePerGram = total > 0 ? reserve / total : 0.0D;
        double payout = round2(s.grams() * pricePerGram);
        if (!(payout > 0.0D)) return false;

        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(s.nation());
        if (!wallets.withdraw(treasury, glb, payout, TransactionType.PAY, "share:redeem:" + shareId)) return false;
        if (!wallets.deposit(s.owner(), glb, payout, TransactionType.PAY, "share:redeem:" + shareId)) {
            wallets.deposit(treasury, glb, payout, TransactionType.PAY, "share:redeem:rollback");
            return false;
        }
        shares.remove(shareId);
        save();
        return true;
    }

    /**
     * Ужиток (дивиденд): король раздаёт часть казны держателям пропорционально их Долям.
     * @param share процент от казны (0..100)
     * @return фактически розданная сумма в GLD
     */
    public double distributeUzhitok(String nation, double share) {
        if (!(share > 0.0D) || share > 100.0D) return 0.0D;
        double total = totalGrams(nation);
        if (total <= 0.0D) return 0.0D;

        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(nation);
        double treasuryBalance = wallets.getBalance(treasury, glb);
        double pool = round2(treasuryBalance * (share / 100.0D));
        if (!(pool > 0.0D)) return 0.0D;

        double distributed = 0.0D;
        long now = System.currentTimeMillis();
        for (Share s : shares.values()) {
            if (!nation.equalsIgnoreCase(s.nation())) continue;
            double portion = round2(pool * (s.grams() / total));
            if (!(portion > 0.0D)) continue;
            if (wallets.withdraw(treasury, glb, portion, TransactionType.PAY, "uzhitok:" + nation)) {
                if (wallets.deposit(s.owner(), glb, portion, TransactionType.PAY, "uzhitok:" + nation)) {
                    shares.put(s.id(), s.withLastPayout(now));
                    distributed += portion;
                } else {
                    wallets.deposit(treasury, glb, portion, TransactionType.PAY, "uzhitok:rollback");
                }
            }
        }
        save();
        return distributed;
    }

    /** Цена 1 золотника Доли в GLD. */
    public double pricePerGram(String nation) {
        double total = totalGrams(nation);
        if (total <= 0.0D) return 1.0D;
        return bank.reserveOf(nation) / total;
    }

    public Share get(String id) { return shares.get(id); }

    public List<Share> listByNation(String nation) {
        List<Share> out = new ArrayList<>();
        for (Share s : shares.values()) if (nation.equalsIgnoreCase(s.nation())) out.add(s);
        return out;
    }

    public List<Share> listByHolder(UUID holder) {
        List<Share> out = new ArrayList<>();
        for (Share s : shares.values()) if (holder.equals(s.owner())) out.add(s);
        return out;
    }

    /** Сводка по держателям нации: сколько у каждого граммов. */
    public Map<UUID, Double> holdersOf(String nation) {
        Map<UUID, Double> out = new HashMap<>();
        for (Share s : shares.values()) {
            if (nation.equalsIgnoreCase(s.nation())) {
                out.merge(s.owner(), s.grams(), Double::sum);
            }
        }
        return out;
    }

    private static double round2(double v) { return Math.round(v * 100.0D) / 100.0D; }
}
