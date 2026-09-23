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
 * Доли и Ужиток (1.2.4.1-fix).
 * FIX: эмиссия больше НЕ списывает GLD лично с короля. Король выпускает
 * сертификаты НА РЫНОК (owner=null), обеспеченные резервом. Игроки покупают
 * их с рынка (платят в казну). Погашение (redeem) — только для купленных (owner!=null).
 *
 * Балансировка: totalGrams = все сертификаты (проданные+непроданные) <= резерва.
 * pricePerGram = резерв / totalGrams (пол золотника = 1.0).
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
            String ownerStr = s.getString("owner", "");
            UUID owner = (ownerStr == null || ownerStr.isEmpty()) ? null : UUID.fromString(ownerStr);
            shares.put(id, new Share(id, s.getString("nation", ""), owner,
                    s.getDouble("grams", 0.0D), s.getLong("issuedAt", 0L), s.getLong("lastPayout", 0L)));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("shares");
        for (Share s : shares.values()) {
            ConfigurationSection sec = root.createSection(s.id());
            sec.set("nation", s.nation());
            sec.set("owner", s.owner() == null ? "" : s.owner().toString());
            sec.set("grams", s.grams());
            sec.set("issuedAt", s.issuedAt());
            sec.set("lastPayout", s.lastPayout());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    /** Все сертификаты (проданные + непроданные) — суммарное требование к резерву. */
    public double totalGrams(String nation) {
        double sum = 0;
        for (Share s : shares.values()) if (nation.equalsIgnoreCase(s.nation())) sum += s.grams();
        return sum;
    }

    /** Только купленные игроками (owner!=null). */
    public double ownedGrams(String nation) {
        double sum = 0;
        for (Share s : shares.values())
            if (nation.equalsIgnoreCase(s.nation()) && s.owner() != null) sum += s.grams();
        return sum;
    }

    /** Максимум новой эмиссии, обеспеченной резервом. */
    public double maxIssueGrams(String nation) {
        return Math.max(0.0D, bank.reserveOf(nation) - totalGrams(nation));
    }

    /** Цена одного золотника в GLD. */
    public double pricePerGram(String nation) {
        double total = totalGrams(nation);
        if (total <= 0.0D) return 1.0D;
        return Math.max(1.0D, bank.reserveOf(nation) / total);
    }

    /** Король выпускает сертификаты НА РЫНОК (без личного списания). */
    public Share issueToMarket(String nation, double grams) {
        if (!(grams > 0.0D)) return null;
        if (grams > maxIssueGrams(nation) + 1e-9D) return null;
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Share s = new Share(id, nation, null, grams, now, now);
        shares.put(id, s);
        save();
        return s;
    }

    public List<Share> listUnsold(String nation) {
        List<Share> out = new ArrayList<>();
        for (Share s : shares.values())
            if (nation.equalsIgnoreCase(s.nation()) && s.owner() == null) out.add(s);
        return out;
    }

    /** Игрок покупает непроданный сертификат с рынка (платит в казну). */
    public boolean buyFromMarket(String shareId, UUID buyer) {
        Share s = shares.get(shareId);
        if (s == null || s.owner() != null) return false;
        double price = round2(s.grams() * pricePerGram(s.nation()));
        if (!(price > 0.0D)) return false;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(s.nation());
        if (!wallets.withdraw(buyer, glb, price, TransactionType.PAY, "share:buy:" + shareId)) return false;
        if (!wallets.deposit(treasury, glb, price, TransactionType.PAY, "share:buy:" + shareId)) {
            wallets.deposit(buyer, glb, price, TransactionType.PAY, "share:buy:rollback:" + shareId);
            return false;
        }
        shares.put(shareId, s.withOwner(buyer));
        save();
        return true;
    }

    /** Держатель продаёт долю обратно в казну (только купленные). */
    public boolean redeem(String shareId) {
        Share s = shares.get(shareId);
        if (s == null || s.owner() == null) return false;
        double payout = round2(s.grams() * pricePerGram(s.nation()));
        if (!(payout > 0.0D)) return false;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(s.nation());
        if (!wallets.withdraw(treasury, glb, payout, TransactionType.PAY, "share:redeem:" + shareId)) return false;
        if (!wallets.deposit(s.owner(), glb, payout, TransactionType.PAY, "share:redeem:" + shareId)) {
            wallets.deposit(treasury, glb, payout, TransactionType.PAY, "share:redeem:rollback:" + shareId);
            return false;
        }
        shares.remove(shareId);
        save();
        return true;
    }

    /** Ужиток: король раздаёт % казны держателям пропорционально их Долям. */
    public double distributeUzhitok(String nation, double sharePercent) {
        if (!(sharePercent > 0.0D) || sharePercent > 100.0D) return 0.0D;
        double owned = ownedGrams(nation);
        if (owned <= 0.0D) return 0.0D;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(nation);
        double pool = round2(wallets.getBalance(treasury, glb) * (sharePercent / 100.0D));
        if (!(pool > 0.0D)) return 0.0D;
        double distributed = 0.0D;
        long now = System.currentTimeMillis();
        for (Share s : shares.values()) {
            if (!nation.equalsIgnoreCase(s.nation()) || s.owner() == null) continue;
            double portion = round2(pool * (s.grams() / owned));
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

    public Share get(String id) { return shares.get(id); }

    public List<Share> listByHolder(UUID holder) {
        List<Share> out = new ArrayList<>();
        for (Share s : shares.values()) if (holder.equals(s.owner())) out.add(s);
        return out;
    }

    public Map<UUID, Double> holdersOf(String nation) {
        Map<UUID, Double> out = new HashMap<>();
        for (Share s : shares.values())
            if (nation.equalsIgnoreCase(s.nation()) && s.owner() != null)
                out.merge(s.owner(), s.grams(), Double::sum);
        return out;
    }

    private static double round2(double v) { return Math.round(v * 100.0D) / 100.0D; }
}
