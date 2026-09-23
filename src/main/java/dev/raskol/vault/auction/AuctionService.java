// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аукцион (1.2.5): создание лотов, ставки, buyout, отмена, истечение, возврат.
 * Комиссии:
 *  - listingFee: 1% от buyout (мин 0.1 GLD), взимается сразу
 *  - sellFee: 3% от финальной цены при продаже
 * Формула минимального инкремента: max(1.0 GLD, 5% от текущей ставки)
 *
 * Хранение:
 *  - data/auction-lots.yml (мета-данные)
 *  - data/auction-items.yml (физические ItemStacks в base64)
 */
public final class AuctionService {

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final File lotsFile;
    private final File itemsFile;
    private final Map<String, AuctionLot> lots = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> items = new ConcurrentHashMap<>();

    public AuctionService(RaskolVault plugin, WalletService wallets) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.lotsFile = new File(plugin.getDataFolder(), "data/auction-lots.yml");
        this.itemsFile = new File(plugin.getDataFolder(), "data/auction-items.yml");
        load();
    }

    // ---------- Конфигурация ----------
    private double listingFeeRate() { return plugin.getConfig().getDouble("auction.listing-fee-rate", 0.01D); }
    private double listingFeeMin() { return plugin.getConfig().getDouble("auction.listing-fee-min", 0.1D); }
    private double sellFeeRate() { return plugin.getConfig().getDouble("auction.sell-fee-rate", 0.03D); }
    private int maxDurationHours() { return plugin.getConfig().getInt("auction.max-duration-hours", 72); }
    private int maxLotsPerPlayer() { return plugin.getConfig().getInt("auction.max-lots-per-player", 10); }

    // ---------- Персистентность ----------
    public void load() {
        if (!lotsFile.getParentFile().exists()) lotsFile.getParentFile().mkdirs();
        if (!itemsFile.exists()) { save(); return; }
        YamlConfiguration itemsYaml = YamlConfiguration.loadConfiguration(itemsFile);
        for (String id : itemsYaml.getKeys(false)) {
            String base64 = itemsYaml.getString(id);
            if (base64 == null || base64.isEmpty()) continue;
            ItemStack item = deserialize(base64);
            if (item != null) items.put(id, item);
        }
        if (!lotsFile.exists()) { save(); return; }
        YamlConfiguration lotsYaml = YamlConfiguration.loadConfiguration(lotsFile);
        ConfigurationSection root = lotsYaml.getConfigurationSection("lots");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                AuctionLot.LotType type = AuctionLot.LotType.valueOf(s.getString("type", "BUYOUT"));
                AuctionLot.Status status = AuctionLot.Status.valueOf(s.getString("status", "ACTIVE"));
                ItemStack item = items.get(id);
                if (item == null) continue;
                AuctionLot lot = new AuctionLot(
                        id,
                        UUID.fromString(s.getString("seller")),
                        s.getString("sellerName", ""),
                        item,
                        type,
                        s.getDouble("startPrice", 0),
                        s.getDouble("buyoutPrice", 0),
                        s.getLong("createdAt", 0L),
                        s.getLong("expiresAt", 0L));
                lot.currentBid = s.getDouble("currentBid", 0);
                String bidderStr = s.getString("currentBidder", "");
                if (bidderStr != null && !bidderStr.isEmpty()) {
                    lot.currentBidder = UUID.fromString(bidderStr);
                    lot.currentBidderName = s.getString("currentBidderName", "");
                }
                lot.status = status;
                String buyerStr = s.getString("buyer", "");
                if (buyerStr != null && !buyerStr.isEmpty()) {
                    lot.buyerName = s.getString("buyerName", "");
                    lot.finalPrice = s.getDouble("finalPrice", 0);
                }
                ConfigurationSection hist = s.getConfigurationSection("history");
                if (hist != null) {
                    for (String hId : hist.getKeys(false)) {
                        ConfigurationSection h = hist.getConfigurationSection(hId);
                        if (h != null) {
                            lot.bidHistory().add(new AuctionLot.BidHistoryEntry(
                                    UUID.fromString(h.getString("bidder")),
                                    h.getString("bidderName", ""),
                                    h.getDouble("amount", 0),
                                    h.getLong("timestamp", 0L)));
                        }
                    }
                }
                lots.put(id, lot);
            } catch (Exception e) {
                plugin.getLogger().warning("RaskolVault auction: не удалось загрузить лот " + id + ": " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration itemsYaml = new YamlConfiguration();
        for (Map.Entry<String, ItemStack> e : items.entrySet()) {
            String b64 = serialize(e.getValue());
            if (b64 != null) itemsYaml.set(e.getKey(), b64);
        }
        SafeStorage.saveAtomic(itemsYaml, itemsFile, plugin);

        YamlConfiguration lotsYaml = new YamlConfiguration();
        ConfigurationSection root = lotsYaml.createSection("lots");
        for (AuctionLot l : lots.values()) {
            ConfigurationSection s = root.createSection(l.id());
            s.set("seller", l.seller().toString());
            s.set("sellerName", l.sellerName());
            s.set("type", l.type().name());
            s.set("startPrice", l.startPrice());
            s.set("buyoutPrice", l.buyoutPrice());
            s.set("createdAt", l.createdAt());
            s.set("expiresAt", l.expiresAt());
            s.set("currentBid", l.currentBid());
            s.set("currentBidder", l.currentBidder() == null ? "" : l.currentBidder().toString());
            s.set("currentBidderName", l.currentBidderName() == null ? "" : l.currentBidderName());
            s.set("status", l.status().name());
            s.set("buyer", l.buyerName() == null ? "" : "");
            s.set("buyerName", l.buyerName() == null ? "" : l.buyerName());
            s.set("finalPrice", l.finalPrice());
            ConfigurationSection hist = s.createSection("history");
            int idx = 0;
            for (AuctionLot.BidHistoryEntry e : l.bidHistory()) {
                ConfigurationSection h = hist.createSection(String.valueOf(idx++));
                h.set("bidder", e.bidder().toString());
                h.set("bidderName", e.bidderName());
                h.set("amount", e.amount());
                h.set("timestamp", e.timestamp());
            }
        }
        SafeStorage.saveAtomic(lotsYaml, lotsFile, plugin);
    }

    // ---------- Создание ----------
    /** Создать лот. Возвращает лот или null, если отказ. Предмет уходит в аукцион-эскроу. */
    public AuctionLot create(UUID seller, ItemStack item, AuctionLot.LotType type,
                             double startPrice, double buyoutPrice, int durationHours) {
        if (item == null || item.getType().isAir()) return null;
        if (!(startPrice > 0.0D)) return null;
        if (durationHours < 1 || durationHours > maxDurationHours()) return null;
        if (type != AuctionLot.LotType.AUCTION && !(buyoutPrice > 0.0D)) return null;
        if (type == AuctionLot.LotType.AUCTION_BUYOUT && buyoutPrice <= startPrice) return null;

        // Лимит лотов на игрока (только ACTIVE)
        int activeCount = 0;
        for (AuctionLot l : lots.values())
            if (l.seller().equals(seller) && l.status() == AuctionLot.Status.ACTIVE) activeCount++;
        if (activeCount >= maxLotsPerPlayer()) return null;

        // Комиссия листинга = max(listingFeeMin, startPrice × listingFeeRate) для аукциона
        // или buyoutPrice × listingFeeRate для buyout
        double refPrice = (type == AuctionLot.LotType.AUCTION) ? startPrice : buyoutPrice;
        double listingFee = Math.max(listingFeeMin(), round2(refPrice * listingFeeRate()));
        String glb = plugin.getCurrencies().globalId();
        if (!wallets.withdraw(seller, glb, listingFee, TransactionType.PAY, "auction:listing:" + "fee")) {
            return null;
        }
        // Деньги за листинг сжигаются (сток)

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + durationHours * 3600_000L;
        String sellerName = nameOf(seller);
        AuctionLot lot = new AuctionLot(id, seller, sellerName, item.clone(), type,
                startPrice, buyoutPrice, now, expires);
        lots.put(id, lot);
        items.put(id, item.clone());
        save();
        return lot;
    }

    // ---------- Ставка ----------
    /** Сделать ставку. Возвращает null при успехе, строку-причину отказа при ошибке. */
    public String bid(UUID bidder, String lotId, double amount) {
        AuctionLot lot = lots.get(lotId);
        if (lot == null) return "лот не найден";
        if (lot.status() != AuctionLot.Status.ACTIVE) return "лот не активен";
        if (lot.type() == AuctionLot.LotType.BUYOUT) return "этот лот только для buyout";
        if (lot.seller().equals(bidder)) return "нельзя ставить на свой лот";
        if (lot.isExpired(System.currentTimeMillis())) return "лот истёк";
        double minNext = lot.minNextBid();
        if (amount < minNext - 1e-9D) return "минимальная ставка: " + fmt(minNext);

        String glb = plugin.getCurrencies().globalId();
        // Проверка баланса (включая возвращаемую предыдущую ставку)
        UUID prevBidder = lot.currentBidder();
        double available = wallets.getBalance(bidder, glb);
        if (prevBidder != null && prevBidder.equals(bidder)) available += lot.currentBid();
        if (available < amount - 1e-9D) return "недостаточно средств (нужно " + fmt(amount) + ")";

        // Возврат предыдущей ставки прежнему лидеру
        if (prevBidder != null && !prevBidder.equals(bidder)) {
            if (!wallets.deposit(prevBidder, glb, lot.currentBid(), TransactionType.PAY, "auction:bid:return:" + lotId))
                return "системная ошибка возврата ставки";
        }
        // Списание новой ставки
        if (!wallets.withdraw(bidder, glb, amount, TransactionType.PAY, "auction:bid:" + lotId))
            return "не удалось списать ставку";

        lot.placeBid(bidder, nameOf(bidder), amount);
        save();
        return null;
    }

    // ---------- Buyout ----------
    /** Немедленная покупка по buyout-цене. */
    public String buyout(UUID buyer, String lotId) {
        AuctionLot lot = lots.get(lotId);
        if (lot == null) return "лот не найден";
        if (lot.status() != AuctionLot.Status.ACTIVE) return "лот не активен";
        if (lot.type() == AuctionLot.LotType.AUCTION) return "у этого лота нет buyout";
        if (lot.seller().equals(buyer)) return "нельзя купить свой лот";
        if (lot.isExpired(System.currentTimeMillis())) return "лот истёк";

        String glb = plugin.getCurrencies().globalId();
        double price = lot.buyoutPrice();
        if (!wallets.withdraw(buyer, glb, price, TransactionType.PAY, "auction:buyout:" + lotId))
            return "недостаточно средств";

        // Возврат предыдущей ставки (если была)
        if (lot.currentBidder() != null) {
            wallets.deposit(lot.currentBidder(), glb, lot.currentBid(),
                    TransactionType.PAY, "auction:buyout:return:" + lotId);
        }

        // Передача предмета покупателю
        if (!giveItem(buyer, lot.item())) {
            wallets.deposit(buyer, glb, price, TransactionType.PAY, "auction:buyout:rollback:" + lotId);
            return "не удалось передать предмет (инвентарь полон)";
        }
        // Деньги продавцу минус комиссия
        double sellFee = round2(price * sellFeeRate());
        double net = round2(price - sellFee);
        wallets.deposit(lot.seller(), glb, net, TransactionType.PAY, "auction:sold:" + lotId);

        lot.markSold(buyer, nameOf(buyer), price);
        items.remove(lotId);
        save();
        return null;
    }

    // ---------- Отмена ----------
    /** Продавец отменяет свой активный лот. */
    public String cancel(UUID seller, String lotId) {
        AuctionLot lot = lots.get(lotId);
        if (lot == null) return "лот не найден";
        if (lot.status() != AuctionLot.Status.ACTIVE) return "лот не активен";
        if (!lot.seller().equals(seller)) return "это не ваш лот";

        // Возврат текущей ставки лидеру
        if (lot.currentBidder() != null) {
            String glb = plugin.getCurrencies().globalId();
            wallets.deposit(lot.currentBidder(), glb, lot.currentBid(),
                    TransactionType.PAY, "auction:cancel:return:" + lotId);
        }
        lot.markCancelled();
        save();
        return null;
    }

    // ---------- Забрать возвращённый предмет ----------
    public boolean collect(UUID owner, String lotId) {
        AuctionLot lot = lots.get(lotId);
        if (lot == null) return false;
        if (!lot.seller().equals(owner)) return false;
        if (lot.status() != AuctionLot.Status.EXPIRED && lot.status() != AuctionLot.Status.CANCELLED) return false;
        ItemStack item = items.get(lotId);
        if (item == null) return false;
        if (!giveItem(owner, item)) return false;
        items.remove(lotId);
        lots.remove(lotId);
        save();
        return true;
    }

    // ---------- Периодика: истечение ----------
    /** Обрабатывает все просроченные активные лоты. Возвращает количество обработанных. */
    public int expireAll() {
        long now = System.currentTimeMillis();
        int count = 0;
        String glb = plugin.getCurrencies().globalId();
        for (AuctionLot lot : new ArrayList<>(lots.values())) {
            if (lot.status() != AuctionLot.Status.ACTIVE) continue;
            if (!lot.isExpired(now)) continue;
            count++;
            if (lot.type() == AuctionLot.LotType.AUCTION && lot.currentBidder() != null) {
                // Аукцион со ставкой: продажа лидеру по текущей ставке
                double price = lot.currentBid();
                double sellFee = round2(price * sellFeeRate());
                double net = round2(price - sellFee);
                if (giveItem(lot.currentBidder(), lot.item())) {
                    wallets.deposit(lot.seller(), glb, net, TransactionType.PAY, "auction:won:" + lot.id());
                    lot.markSold(lot.currentBidder(), lot.currentBidderName(), price);
                    items.remove(lot.id());
                } else {
                    // Инвентарь победителя полон — возврат денег победителю, предмет остаётся у продавца (EXPIRED)
                    wallets.deposit(lot.currentBidder(), glb, price, TransactionType.PAY, "auction:expired:return:" + lot.id());
                    lot.markExpired();
                }
            } else {
                // Нет ставки или был buyout без покупки — истечение
                lot.markExpired();
            }
        }
        if (count > 0) save();
        return count;
    }

    // ---------- Запросы ----------
    public List<AuctionLot> listActive() {
        List<AuctionLot> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AuctionLot l : lots.values())
            if (l.status() == AuctionLot.Status.ACTIVE && !l.isExpired(now)) out.add(l);
        out.sort((a, b) -> Long.compare(a.expiresAt(), b.expiresAt()));
        return out;
    }

    public List<AuctionLot> myListings(UUID seller) {
        List<AuctionLot> out = new ArrayList<>();
        for (AuctionLot l : lots.values()) if (l.seller().equals(seller)) out.add(l);
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    public List<AuctionLot> myBids(UUID bidder) {
        List<AuctionLot> out = new ArrayList<>();
        for (AuctionLot l : lots.values())
            if (l.status() == AuctionLot.Status.ACTIVE && bidder.equals(l.currentBidder())) out.add(l);
        return out;
    }

    public List<AuctionLot> myCollectable(UUID owner) {
        List<AuctionLot> out = new ArrayList<>();
        for (AuctionLot l : lots.values())
            if (l.seller().equals(owner)
                    && (l.status() == AuctionLot.Status.EXPIRED || l.status() == AuctionLot.Status.CANCELLED)
                    && items.containsKey(l.id())) out.add(l);
        return out;
    }

    public AuctionLot get(String id) { return lots.get(id); }

    // ---------- Хелперы ----------
    private boolean giveItem(UUID player, ItemStack item) {
        org.bukkit.entity.Player p = Bukkit.getPlayer(player);
        if (p == null) return false;
        HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(item.clone());
        if (overflow.isEmpty()) return true;
        // Инвентарь полон — выкидываем остаток у ног
        for (ItemStack left : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        return true;
    }

    private String nameOf(UUID uuid) {
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String n = op.getName();
        return n == null ? uuid.toString().substring(0, 8) : n;
    }

    private static String serialize(ItemStack item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) { return null; }
    }

    private static ItemStack deserialize(String base64) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) { return null; }
    }

    private static double round2(double v) { return Math.round(v * 100.0D) / 100.0D; }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
