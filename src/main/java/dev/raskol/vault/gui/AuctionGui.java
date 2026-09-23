// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionLot;
import dev.raskol.vault.auction.AuctionService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionGui implements Listener {

    private final Map<UUID, CreateWizard> wizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> pendingItem = new ConcurrentHashMap<>();

    private static final class CreateWizard {
        ItemStack item;
        AuctionLot.LotType type = AuctionLot.LotType.BUYOUT;
        double startPrice = 0;
        double buyoutPrice = 0;
        int durationHours = 24;
    }

    public static final class MarketHolder implements InventoryHolder {
        final int page;
        MarketHolder(int page) { this.page = page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyListingsHolder implements InventoryHolder {
        final int page;
        MyListingsHolder(int page) { this.page = page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyBidsHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CollectHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class DetailHolder implements InventoryHolder {
        final String lotId;
        DetailHolder(String lotId) { this.lotId = lotId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateTypeHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateConfirmHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final RaskolVault plugin;
    private final AuctionService auctions;

    public AuctionGui(RaskolVault plugin, AuctionService auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(Player p, String raw) { p.sendMessage(c(plugin.getMessages().prefix() + raw)); }
    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(ChatColor.translateAlternateColorCodes('&', x));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String timeLeft(long expiresAt) {
        long ms = expiresAt - System.currentTimeMillis();
        if (ms <= 0) return "истёк";
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        return h + "ч " + m + "мин";
    }

    private void frame(Inventory inv) {
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
    }

    public void openMarket(Player p, int page) {
        MarketHolder h = new MarketHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Аукцион · рынок &8▌"));
        h.inv = inv;
        frame(inv);

        List<AuctionLot> active = auctions.listActive();
        int perPage = 28;
        int start = page * perPage;
        int[] grid = {
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25,
                28,29,30,31,32,33,34,
                37,38,39,40,41,42,43
        };
        for (int i = 0; i < grid.length && (start + i) < active.size(); i++) {
            AuctionLot lot = active.get(start + i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(c("&6" + (meta.hasDisplayName() ? meta.getDisplayName() : lot.item().getType().name())));
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(c("&7Продавец: &f" + lot.sellerName()));
                lore.add(c("&7Тип: &f" + lot.type().name()));
                if (lot.type() != AuctionLot.LotType.AUCTION)
                    lore.add(c("&7Buyout: &f" + fmt(lot.buyoutPrice()) + " GLD"));
                if (lot.type() != AuctionLot.LotType.BUYOUT)
                    lore.add(c("&7Текущая ставка: &f" + (lot.currentBid() > 0 ? fmt(lot.currentBid()) : fmt(lot.startPrice())) + " GLD"));
                lore.add(c("&7Осталось: &e" + timeLeft(lot.expiresAt())));
                lore.add(c("&eКлик — подробности"));
                meta.setLore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                show.setItemMeta(meta);
            }
            inv.setItem(grid[i], show);
        }
        for (int i = Math.max(0, active.size() - start); i < grid.length; i++) inv.setItem(grid[i], pane());

        inv.setItem(2, item(Material.GOLD_BLOCK, "&aМои лоты", List.of("&7Выставленные мной товары", "&eКлик — открыть")));
        inv.setItem(4, item(Material.ENDER_CHEST, "&6Мои ставки", List.of("&7Лоты где я лидер", "&eКлик — открыть")));
        inv.setItem(6, item(Material.HOPPER, "&bЗабрать", List.of("&7Истёкшие/отменённые предметы", "&eКлик — открыть")));
        inv.setItem(8, item(Material.EMERALD, "&aВыставить предмет", List.of("&7Создать новый лот", "&eКлик → мастер")));

        if (page > 0) inv.setItem(45, item(Material.ARROW, "&7◀ Пред.", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        if ((page + 1) * perPage < active.size()) inv.setItem(53, item(Material.ARROW, "&7След. ▶", List.of()));
        p.openInventory(inv);
    }

    public void openMyListings(Player p, int page) {
        MyListingsHolder h = new MyListingsHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои лоты &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> mine = auctions.myListings(p.getUniqueId());
        int perPage = 28;
        int start = page * perPage;
        int[] grid = {
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25,
                28,29,30,31,32,33,34,
                37,38,39,40,41,42,43
        };
        for (int i = 0; i < grid.length && (start + i) < mine.size(); i++) {
            AuctionLot lot = mine.get(start + i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(c("&6" + (meta.hasDisplayName() ? meta.getDisplayName() : lot.item().getType().name())));
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(c("&7Статус: &f" + lot.status()));
                if (lot.status() == AuctionLot.Status.ACTIVE)
                    lore.add(c("&7Осталось: &e" + timeLeft(lot.expiresAt())));
                else if (lot.status() == AuctionLot.Status.SOLD)
                    lore.add(c("&7Продано: &f" + fmt(lot.finalPrice()) + " GLD → " + lot.buyerName()));
                lore.add(c("&eКлик — подробности"));
                meta.setLore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                show.setItemMeta(meta);
            }
            inv.setItem(grid[i], show);
        }
        for (int i = Math.max(0, mine.size() - start); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(45, item(Material.ARROW, "&7◀ Рынок", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        if ((page + 1) * perPage < mine.size()) inv.setItem(53, item(Material.ARROW, "&7След. ▶", List.of()));
        p.openInventory(inv);
    }

    public void openMyBids(Player p) {
        MyBidsHolder h = new MyBidsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои ставки &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> bids = auctions.myBids(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < bids.size(); i++) {
            AuctionLot lot = bids.get(i);
            inv.setItem(grid[i], item(Material.PAPER, "&6" + lot.item().getType().name(), List.of(
                    "&7Моя ставка: &f" + fmt(lot.currentBid()) + " GLD",
                    "&7Осталось: &e" + timeLeft(lot.expiresAt()),
                    "&eКлик — подробности")));
        }
        for (int i = bids.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7◀ Рынок", List.of()));
        p.openInventory(inv);
    }

    public void openCollect(Player p) {
        CollectHolder h = new CollectHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Забрать предметы &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> collectable = auctions.myCollectable(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < collectable.size(); i++) {
            AuctionLot lot = collectable.get(i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(c("&7Статус: &f" + lot.status()));
                lore.add(c("&eКлик — забрать в инвентарь"));
                meta.setLore(lore);
                show.setItemMeta(meta);
            }
            inv.setItem(grid[i], show);
        }
        for (int i = collectable.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        if (collectable.isEmpty())
            inv.setItem(22, item(Material.BARRIER, "&7Нет предметов", List.of("&7Все возвращённые предметы уже забраны")));
        inv.setItem(49, item(Material.ARROW, "&7◀ Рынок", List.of()));
        p.openInventory(inv);
    }

    public void openDetails(Player p, String lotId) {
        AuctionLot lot = auctions.get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); openMarket(p, 0); return; }
        DetailHolder h = new DetailHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Лот &7" + lotId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        frame(inv);
        inv.setItem(4, lot.item().clone());

        List<String> info = new ArrayList<>();
        info.add("&7Продавец: &f" + lot.sellerName());
        info.add("&7Тип: &f" + lot.type().name());
        info.add("&7Статус: &f" + lot.status());
        if (lot.type() != AuctionLot.LotType.AUCTION) info.add("&7Buyout: &f" + fmt(lot.buyoutPrice()) + " GLD");
        if (lot.type() != AuctionLot.LotType.BUYOUT)
            info.add("&7Стартовая: &f" + fmt(lot.startPrice()) + " GLD");
        if (lot.type() != AuctionLot.LotType.BUYOUT && lot.currentBid() > 0)
            info.add("&7Текущая ставка: &f" + fmt(lot.currentBid()) + " GLD");
        if (lot.currentBidderName() != null) info.add("&7Лидер: &f" + lot.currentBidderName());
        if (lot.status() == AuctionLot.Status.ACTIVE) info.add("&7Осталось: &e" + timeLeft(lot.expiresAt()));
        if (lot.type() != AuctionLot.LotType.BUYOUT && lot.status() == AuctionLot.Status.ACTIVE)
            info.add("&7Мин. след. ставка: &f" + fmt(lot.minNextBid()) + " GLD");
        if (lot.status() == AuctionLot.Status.SOLD)
            info.add("&7Продано: &f" + fmt(lot.finalPrice()) + " GLD → " + lot.buyerName());
        inv.setItem(13, item(Material.BOOK, "&6Информация", info));

        boolean own = lot.seller().equals(p.getUniqueId());
        boolean active = lot.status() == AuctionLot.Status.ACTIVE;

        if (active) {
            if (lot.type() != AuctionLot.LotType.AUCTION && !own) {
                inv.setItem(21, item(Material.EMERALD, "&aКупить сейчас",
                        List.of("&7Цена: &f" + fmt(lot.buyoutPrice()) + " GLD",
                                "&7Мгновенная покупка",
                                "&eКлик — подтвердить")));
            }
            if (lot.type() != AuctionLot.LotType.BUYOUT && !own) {
                inv.setItem(23, item(Material.GOLD_NUGGET, "&6Сделать ставку",
                        List.of("&7Мин: &f" + fmt(lot.minNextBid()) + " GLD",
                                "&eКлик → ввод суммы в чат")));
            }
            if (own) {
                inv.setItem(25, item(Material.RED_CONCRETE, "&cОтменить лот",
                        List.of("&7Вернёт предмет и ставку лидеру",
                                "&7Комиссия листинга НЕ возвращается")));
            }
        }

        if (!lot.bidHistory().isEmpty()) {
            List<String> hist = new ArrayList<>();
            hist.add("&7Последние ставки:");
            int shown = Math.min(5, lot.bidHistory().size());
            for (int i = lot.bidHistory().size() - 1; i >= lot.bidHistory().size() - shown; i--) {
                AuctionLot.BidHistoryEntry e = lot.bidHistory().get(i);
                hist.add("&7- &f" + e.bidderName() + "&7: &f" + fmt(e.amount()) + " GLD");
            }
            inv.setItem(31, item(Material.PAPER, "&6История ставок", hist));
        }

        inv.setItem(49, item(Material.ARROW, "&7◀ Назад", List.of()));
        p.openInventory(inv);
    }

    public void openCreateType(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            msg(p, "&cВозьмите предмет в основную руку, затем повторите");
            return;
        }
        pendingItem.put(p.getUniqueId(), held.clone());
        CreateTypeHolder h = new CreateTypeHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Тип лота &8▌"));
        h.inv = inv;
        inv.setItem(11, item(Material.GOLD_BLOCK, "&aBuyout",
                List.of("&7Фиксированная цена", "&7Кто первый заплатит — тот купит")));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6Аукцион",
                List.of("&7Ставки до истечения", "&7Побеждает максимальная ставка")));
        inv.setItem(15, item(Material.DIAMOND, "&bАукцион + Buyout",
                List.of("&7И ставки, и мгновенная покупка")));
        inv.setItem(22, item(Material.BARRIER, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    private void openCreateConfirm(Player p) {
        CreateWizard w = wizards.get(p.getUniqueId());
        if (w == null) return;
        CreateConfirmHolder h = new CreateConfirmHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Подтверждение &8▌"));
        h.inv = inv;
        double refPrice = (w.type == AuctionLot.LotType.AUCTION) ? w.startPrice : w.buyoutPrice;
        double listingFee = Math.max(0.1D, Math.round(refPrice * 0.01D * 100.0D) / 100.0D);
        List<String> lore = new ArrayList<>();
        lore.add("&7Тип: &f" + w.type.name());
        if (w.type != AuctionLot.LotType.BUYOUT) lore.add("&7Стартовая: &f" + fmt(w.startPrice) + " GLD");
        if (w.type != AuctionLot.LotType.AUCTION) lore.add("&7Buyout: &f" + fmt(w.buyoutPrice) + " GLD");
        lore.add("&7Длительность: &f" + w.durationHours + "ч");
        lore.add("&7Комиссия листинга: &c-" + fmt(listingFee) + " GLD");
        inv.setItem(13, item(Material.BOOK, "&6Сводка", lore));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a✔ Выставить", List.of()));
        inv.setItem(15, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof MyListingsHolder)
                && !(raw instanceof MyBidsHolder) && !(raw instanceof CollectHolder)
                && !(raw instanceof DetailHolder) && !(raw instanceof CreateTypeHolder)
                && !(raw instanceof CreateConfirmHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 45 && mh.page > 0) { openMarket(p, mh.page - 1); return; }
            if (slot == 53) { openMarket(p, mh.page + 1); return; }
            if (slot == 2) { openMyListings(p, 0); return; }
            if (slot == 4) { openMyBids(p); return; }
            if (slot == 6) { openCollect(p); return; }
            if (slot == 8) { openCreateType(p); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
            int gi = -1;
            for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
            if (gi < 0) return;
            List<AuctionLot> active = auctions.listActive();
            int idx = mh.page * 28 + gi;
            if (idx < active.size()) openDetails(p, active.get(idx).id());
            return;
        }

        if (raw instanceof MyListingsHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 45) { openMarket(p, 0); return; }
            if (slot == 53) { openMyListings(p, mh.page + 1); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
            int gi = -1;
            for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
            if (gi < 0) return;
            List<AuctionLot> mine = auctions.myListings(p.getUniqueId());
            int idx = mh.page * 28 + gi;
            if (idx < mine.size()) openDetails(p, mine.get(idx).id());
            return;
        }

        if (raw instanceof MyBidsHolder) {
            if (slot == 49) { openMarket(p, 0); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            int gi = -1;
            for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
            if (gi < 0) return;
            List<AuctionLot> bids = auctions.myBids(p.getUniqueId());
            if (gi < bids.size()) openDetails(p, bids.get(gi).id());
            return;
        }

        if (raw instanceof CollectHolder) {
            if (slot == 49) { openMarket(p, 0); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            int gi = -1;
            for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
            if (gi < 0) return;
            List<AuctionLot> col = auctions.myCollectable(p.getUniqueId());
            if (gi < col.size()) {
                boolean ok = auctions.collect(p.getUniqueId(), col.get(gi).id());
                msg(p, ok ? "&aПредмет забран" : "&cНе удалось");
                openCollect(p);
            }
            return;
        }

        if (raw instanceof DetailHolder dh) {
            if (slot == 49) { openMarket(p, 0); return; }
            AuctionLot lot = auctions.get(dh.lotId);
            if (lot == null) { p.closeInventory(); return; }
            boolean own = lot.seller().equals(p.getUniqueId());
            if (slot == 21 && !own && lot.type() != AuctionLot.LotType.AUCTION && lot.status() == AuctionLot.Status.ACTIVE) {
                String err = auctions.buyout(p.getUniqueId(), lot.id());
                msg(p, err == null ? "&aПредмет куплен" : "&c" + err);
                openMarket(p, 0);
                return;
            }
            if (slot == 23 && !own && lot.type() != AuctionLot.LotType.BUYOUT && lot.status() == AuctionLot.Status.ACTIVE) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "bid:" + lot.id());
                msg(p, "&7Введите сумму ставки (мин &f" + fmt(lot.minNextBid()) + "&7):");
                return;
            }
            if (slot == 25 && own && lot.status() == AuctionLot.Status.ACTIVE) {
                String err = auctions.cancel(p.getUniqueId(), lot.id());
                msg(p, err == null ? "&aЛот отменён" : "&c" + err);
                openMyListings(p, 0);
                return;
            }
            return;
        }

        if (raw instanceof CreateTypeHolder) {
            CreateWizard w = wizards.computeIfAbsent(p.getUniqueId(), k -> new CreateWizard());
            w.item = pendingItem.get(p.getUniqueId());
            if (slot == 22) { p.closeInventory(); wizards.remove(p.getUniqueId()); pendingItem.remove(p.getUniqueId()); return; }
            if (slot == 11) { w.type = AuctionLot.LotType.BUYOUT; p.closeInventory(); chatField.put(p.getUniqueId(), "buyout-price"); msg(p, "&7Введите цену buyout (GLD):"); }
            if (slot == 13) { w.type = AuctionLot.LotType.AUCTION; p.closeInventory(); chatField.put(p.getUniqueId(), "auction-start"); msg(p, "&7Введите стартовую цену (GLD):"); }
            if (slot == 15) { w.type = AuctionLot.LotType.AUCTION_BUYOUT; p.closeInventory(); chatField.put(p.getUniqueId(), "hybrid-start"); msg(p, "&7Введите стартовую цену (GLD):"); }
            return;
        }

        if (raw instanceof CreateConfirmHolder) {
            CreateWizard w = wizards.get(p.getUniqueId());
            if (slot == 15) { p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
            if (slot == 11 && w != null) {
                AuctionLot lot = auctions.create(p.getUniqueId(), w.item, w.type, w.startPrice, w.buyoutPrice, w.durationHours);
                msg(p, lot != null ? "&aЛот &f" + lot.id().substring(0, 8) + " &aвыставлен на аукцион"
                        : "&cНе удалось (комиссия или лимит лотов)");
                p.closeInventory();
                wizards.remove(p.getUniqueId());
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String[] parts = field.split(":", 2);
        String action = parts[0];
        String arg = parts.length > 1 ? parts[1] : "";
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (action) {
                case "bid" -> {
                    double amt = parse(text);
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); openDetails(p, arg); return; }
                    String err = auctions.bid(p.getUniqueId(), arg, amt);
                    msg(p, err == null ? "&aСтавка принята" : "&c" + err);
                    openDetails(p, arg);
                }
                case "buyout-price" -> {
                    CreateWizard w = wizards.get(p.getUniqueId());
                    if (w == null) return;
                    double v = parse(text);
                    if (!(v > 0)) { msg(p, "&cЦена > 0"); return; }
                    w.buyoutPrice = v;
                    chatField.put(p.getUniqueId(), "duration");
                    msg(p, "&7Введите длительность в часах (1-72):");
                }
                case "auction-start" -> {
                    CreateWizard w = wizards.get(p.getUniqueId());
                    if (w == null) return;
                    double v = parse(text);
                    if (!(v > 0)) { msg(p, "&cЦена > 0"); return; }
                    w.startPrice = v;
                    chatField.put(p.getUniqueId(), "duration");
                    msg(p, "&7Введите длительность в часах (1-72):");
                }
                case "hybrid-start" -> {
                    CreateWizard w = wizards.get(p.getUniqueId());
                    if (w == null) return;
                    double v = parse(text);
                    if (!(v > 0)) { msg(p, "&cЦена > 0"); return; }
                    w.startPrice = v;
                    chatField.put(p.getUniqueId(), "hybrid-buyout");
                    msg(p, "&7Введите цену buyout (GLD, больше стартовой):");
                }
                case "hybrid-buyout" -> {
                    CreateWizard w = wizards.get(p.getUniqueId());
                    if (w == null) return;
                    double v = parse(text);
                    if (!(v > w.startPrice)) { msg(p, "&cBuyout должен быть больше стартовой"); return; }
                    w.buyoutPrice = v;
                    chatField.put(p.getUniqueId(), "duration");
                    msg(p, "&7Введите длительность в часах (1-72):");
                }
                case "duration" -> {
                    CreateWizard w = wizards.get(p.getUniqueId());
                    if (w == null) return;
                    int v;
                    try { v = Integer.parseInt(text); } catch (NumberFormatException ex) { msg(p, "&cЦелое число"); return; }
                    if (v < 1 || v > 72) { msg(p, "&c1-72 часа"); return; }
                    w.durationHours = v;
                    chatField.remove(p.getUniqueId());
                    openCreateConfirm(p);
                }
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {}

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }
}
