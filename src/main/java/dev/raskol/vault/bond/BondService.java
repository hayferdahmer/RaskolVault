// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bond;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Королевская рента / Заёмная грамота (1.2.3).
 * Хранение: data/bonds.yml (масштаб сервера мал, SQL-таблица bonds оставлена под будущую миграцию).
 * Жизненный цикл: issue (казна) → buy (игрок платит face в казну) → redeem (погашение face+купон).
 */
public final class BondService {

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final File file;
    private final Map<String, Bond> bonds = new ConcurrentHashMap<>();

    public BondService(RaskolVault plugin, WalletService wallets) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.file = new File(plugin.getDataFolder(), "data/bonds.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bonds");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection b = root.getConfigurationSection(id);
            if (b == null) continue;
            String holderStr = b.getString("holder", "");
            UUID holder = (holderStr == null || holderStr.isEmpty()) ? null : UUID.fromString(holderStr);
            bonds.put(id, new Bond(
                    id,
                    b.getString("nation", ""),
                    b.getDouble("face", 0.0D),
                    b.getDouble("couponRate", 0.0D),
                    b.getLong("issuedAt", 0L),
                    b.getLong("maturesAt", 0L),
                    holder));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("bonds");
        for (Bond b : bonds.values()) {
            ConfigurationSection s = root.createSection(b.id());
            s.set("nation", b.nation());
            s.set("face", b.face());
            s.set("couponRate", b.couponRate());
            s.set("issuedAt", b.issuedAt());
            s.set("maturesAt", b.maturesAt());
            s.set("holder", b.holder() == null ? "" : b.holder().toString());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    /** Эмиссия облигации казной (holder=null). */
    public Bond issue(String nation, double face, double couponRate, long termDays) {
        if (!(face > 0.0D) || termDays <= 0) return null;
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        Bond b = new Bond(id, nation, face, clampCoupon(couponRate), now,
                now + termDays * 24L * 60 * 60 * 1000, null);
        bonds.put(id, b);
        save();
        return b;
    }

    /** Игрок покупает облигацию: платит face в казну нации, становится держателем. */
    public boolean buy(String bondId, UUID buyer) {
        Bond b = bonds.get(bondId);
        if (b == null || b.holder() != null) return false;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(b.nation());
        if (!wallets.has(buyer, glb, b.face())) return false;
        if (!wallets.withdraw(buyer, glb, b.face(), TransactionType.PAY, "bond:buy:" + bondId)) return false;
        if (!wallets.deposit(treasury, glb, b.face(), TransactionType.PAY, "bond:buy:" + bondId)) {
            wallets.deposit(buyer, glb, b.face(), TransactionType.PAY, "bond:buy:rollback:" + bondId);
            return false;
        }
        bonds.put(bondId, new Bond(b.id(), b.nation(), b.face(), b.couponRate(),
                b.issuedAt(), b.maturesAt(), buyer));
        save();
        return true;
    }

    /** Погашение по сроку: держатель получает face + начисленный купон из казны. */
    public boolean redeem(String bondId, UUID holder) {
        Bond b = bonds.get(bondId);
        if (b == null || b.holder() == null || !b.holder().equals(holder)) return false;
        long now = System.currentTimeMillis();
        if (!b.isMatured(now)) return false;
        double coupon = b.accruedCoupon(now);
        double total = b.face() + coupon;
        String glb = plugin.getCurrencies().globalId();
        UUID treasury = ReserveBank.treasuryUuid(b.nation());
        if (!wallets.withdraw(treasury, glb, total, TransactionType.PAY, "bond:redeem:" + bondId)) return false;
        if (!wallets.deposit(holder, glb, total, TransactionType.PAY, "bond:redeem:" + bondId)) {
            wallets.deposit(treasury, glb, total, TransactionType.PAY, "bond:redeem:rollback:" + bondId);
            return false;
        }
        bonds.remove(bondId);
        save();
        return true;
    }

    public Bond get(String id) { return bonds.get(id); }

    public List<Bond> listOpen() {
        List<Bond> out = new ArrayList<>();
        for (Bond b : bonds.values()) if (b.holder() == null) out.add(b);
        return out;
    }

    public List<Bond> listByHolder(UUID holder) {
        List<Bond> out = new ArrayList<>();
        for (Bond b : bonds.values()) if (holder.equals(b.holder())) out.add(b);
        return out;
    }

    public List<Bond> listByNation(String nation) {
        List<Bond> out = new ArrayList<>();
        for (Bond b : bonds.values()) if (nation.equalsIgnoreCase(b.nation())) out.add(b);
        return out;
    }

    private static double clampCoupon(double v) {
        if (!Double.isFinite(v) || v < 0.0D) return 0.0D;
        return Math.min(v, 0.20D); // кап 20% годовых
    }
}
