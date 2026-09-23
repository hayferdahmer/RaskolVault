// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionCategory;
import dev.raskol.vault.auction.AuctionFilter;
import dev.raskol.vault.auction.AuctionLot;
import dev.raskol.vault.auction.AuctionService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
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

/**
 * GUI аукциона (1.2.5-a.1): категории, фильтры, инспекция предмета, поиск.
 */
public final class AuctionGui implements Listener {

    private final Map<UUID, CreateWizard> wizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> pendingItem = new ConcurrentHashMap<>();
    private final Map<UUID, AuctionFilter> activeFilters = new ConcurrentHashMap<>();

    private static final class CreateWizard {
        ItemStack item;
        AuctionLot.LotType type = AuctionLot.LotType.BUYOUT;
        double startPrice = 0;
        double buyoutPrice = 0;
        int durationHours = 24;
    }

    public static final class MarketHolder implements InventoryHolder {
        final int page; final AuctionFilter filter;
        MarketHolder(int page, AuctionFilter filter) { this.page = page; this.filter = filter; }
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
    public static final class InspectHolder implements InventoryHolder {
        final String lotId;
        InspectHolder(String lotId) { this.lotId = lotId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class FilterHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CategoryHolder implements InventoryHolder {
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
            meta.setDisplayName(c(name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(c(x));
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                    ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
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

    private AuctionFilter filterOf(Player p) {
        return activeFilters.getOrDefault(p.getUniqueId(), AuctionFilter.empty());
    }

    // ---------- ГЛАВНАЯ (с фильтрами) ----------
    public void openMarket(Player p, int page) { openMarket(p, page, filterOf(p)); }

    public void openMarket(Player p, int page, AuctionFilter filter) {
        activeFilters.put(p.getUniqueId(), filter);
        MarketHolder h = new MarketHolder(page, filter);
        String title = filter.isEmpty() ? "&8▌&6 Аукцион · рынок &8▌"
                : "&8▌&6 Аукцион · фильтр активен &8▌";
        Inventory inv = Bukkit.createInventory(h, 54, c(title));
        h.inv = inv;
        frame(inv);

        List<AuctionLot> active = auctions.listActive(filter);
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
                String baseName = meta.hasDisplayName() ? meta.getDisplayName() : formatMat(lot.item().getType().name());
                meta.setDisplayName(c("&6" + baseName));
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(c("&7Продавец: &f" + lot.sellerName()));
                lore.add(c("&7Тип: &f" + formatLotType(lot.type())));
                lore.add(c("&7Категория: &f" + AuctionCategory.of(lot.item()).displayName()));
                if (lot.type() != AuctionLot.LotType.AUCTION)
                    lore.add(c("&7Buyout: &f" + fmt(lot.buyoutPrice()) + " GLD"));
                if (lot.type() != AuctionLot.LotType.BUYOUT)
                    lore.add(c("&7Текущая ставка: &f" + (lot.currentBid() > 0 ? fmt(lot.currentBid()) : fmt(lot.startPrice())) + " GLD"));
                lore.add(c("&7Осталось: &e" + timeLeft(lot.expiresAt())));
                lore.add("");
                lore.add(c("&eЛКМ — подробности"));
                lore.add(c("&eПКМ — осмотр предмета"));
                meta.setLore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                show.setItemMeta(meta);
            }
            inv.setItem(grid[i], show);
        }
        for (int i = Math.max(0, active.size() - start); i < grid.length; i++) inv.setItem(grid[i], pane());

        // Верхняя панель: категории быстрого доступа
        AuctionCategory[] cats = AuctionCategory.values();
        for (int i = 0; i < cats.length; i++) {
            AuctionCategory cat = cats[i];
            boolean active2 = filter.category() == cat;
            inv.setItem(1 + i, item(cat.icon(),
                    (active2 ? "&a" : "&6") + cat.displayName(),
                    List.of("&7" + cat.description(),
                            active2 ? "&aФильтр активен — клик снимет" : "&eКлик — фильтр по категории")));
        }

        // Нижняя панель: управление
        inv.setItem(2, item(Material.GOLD_BLOCK, "&aМои лоты", List.of("&7Выставленные мной товары", "&eКлик — открыть")));
        inv.setItem(4, item(Material.ENDER_CHEST, "&6Мои ставки", List.of("&7Лоты где я лидер", "&eКлик — открыть")));
        inv.setItem(6, item(Material.HOPPER, "&bЗабрать", List.of("&7Истёкшие/отменённые предметы", "&eКлик — открыть")));
        inv.setItem(8, item(Material.EMERALD, "&aВыставить предмет", List.of("&7Создать новый лот", "&eКлик → мастер")));

        // Фильтры (слева)
        inv.setItem(45, item(Material.COMPARATOR, "&6Фильтры", filterSummary(filter)));
        inv.setItem(46, item(Material.NAME_TAG, "&bПоиск", List.of(
                "&7Найти по имени предмета или нику продавца",
                filter.searchQuery() != null ? "&7Активный запрос: &f" + filter.searchQuery() : "&7Запрос не задан",
                "&eКлик → ввод в чат")));

        if (page > 0) inv.setItem(47, item(Material.ARROW, "&7◀ Пред.", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        if ((page + 1) * perPage < active.size()) inv.setItem(51, item(Material.ARROW, "&7След. ▶", List.of()));

        inv.setItem(52, item(Material.MAP, "&6Всего лотов: &f" + active.size(),
                List.of("&7С фильтром: &f" + active.size(),
                        "&7Без фильтра: &f" + auctions.listActive().size())));

        p.openInventory(inv);
    }

    private List<String> filterSummary(AuctionFilter f) {
        List<String> lines = new ArrayList<>();
        lines.add("&7Настройте критерии поиска");
        if (f.category() != null) lines.add("&7Категория: &f" + f.category().displayName());
        if (f.type() != null) lines.add("&7Тип: &f" + formatLotType(f.type()));
        if (f.nation() != null) lines.add("&7Нация: &f" + f.nation());
        if (f.minPrice() != null) lines.add("&7Мин. цена: &f" + fmt(f.minPrice()) + " GLD");
        if (f.maxPrice() != null) lines.add("&7Макс. цена: &f" + fmt(f.maxPrice()) + " GLD");
        if (f.searchQuery() != null) lines.add("&7Поиск: &f" + f.searchQuery());
        if (f.isEmpty()) lines.add("&7Фильтры не заданы");
        lines.add("");
        lines.add("&eКлик — открыть настройку");
        return lines;
    }

    // ---------- ФИЛЬТРЫ ----------
    public void openFilters(Player p) {
        FilterHolder h = new FilterHolder();
        Inventory inv = Bukkit.createInventory(h, 36, c("&8▌&6 Настройка фильтров &8▌"));
        h.inv = inv;
        AuctionFilter f = filterOf(p);

        inv.setItem(10, item(Material.COMPASS, "&6Тип лота",
                List.of("&7Текущий: &f" + (f.type() == null ? "любой" : formatLotType(f.type())),
                        "&7BUYOUT / AUCTION / AUCTION_BUYOUT",
                        "&eКлик — переключить")));
        inv.setItem(11, item(Material.BOOK, "&6Категория",
                List.of("&7Текущая: &f" + (f.category() == null ? "любая" : f.category().displayName()),
                        "&eКлик — выбрать категорию")));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&6Мин. цена",
                List.of("&7Текущая: &f" + (f.minPrice() == null ? "—" : fmt(f.minPrice()) + " GLD"),
                        "&eКлик → ввод в чат")));
        inv.setItem(13, item(Material.GOLD_BLOCK, "&6Макс. цена",
                List.of("&7Текущая: &f" + (f.maxPrice() == null ? "—" : fmt(f.maxPrice()) + " GLD"),
                        "&eКлик → ввод в чат")));
        inv.setItem(14, item(Material.NAME_TAG, "&bПоиск",
                List.of("&7По имени предмета или нику продавца",
                        "&7Текущий: &f" + (f.searchQuery() == null ? "—" : f.searchQuery()),
                        "&eКлик → ввод в чат")));

        if (plugin.getTownyHook().isAvailable()) {
            inv.setItem(15, item(Material.BANNER_PATTERN, "&6Нация продавца",
                    List.of("&7Текущая: &f" + (f.nation() == null ? "любая" : f.nation()),
                            "&eКлик → ввод названия нации в чат")));
        }

        inv.setItem(22, item(Material.LIME_CONCRETE, "&aПрименить", List.of("&eКлик — вернуться на рынок")));
        if (!f.isEmpty()) {
            inv.setItem(23, item(Material.RED_CONCRETE, "&cСбросить все", List.of("&eКлик — очистить фильтры")));
        }
        inv.setItem(31, item(Material.ARROW, "&7Назад", List.of("&7Без применения")));
        p.openInventory(inv);
    }

    public void openCategories(Player p) {
        CategoryHolder h = new CategoryHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Выбор категории &8▌"));
        h.inv = inv;
        AuctionFilter f = filterOf(p);
        AuctionCategory[] cats = AuctionCategory.values();
        for (int i = 0; i < cats.length; i++) {
            AuctionCategory cat = cats[i];
            boolean active = f.category() == cat;
            inv.setItem(10 + i, item(cat.icon(),
                    (active ? "&a" : "&6") + cat.displayName(),
                    List.of("&7" + cat.description(),
                            active ? "&aАктивна — клик снимет" : "&eКлик — выбрать")));
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- МОИ ЛОТЫ ----------
    public void openMyListings(Player p, int page) {
        MyListingsHolder h = new MyListingsHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои лоты &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> mine = auctions.myListings(p.getUniqueId());
        int perPage = 28;
        int start = page * perPage;
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        for (int i = 0; i < grid.length && (start + i) < mine.size(); i++) {
            AuctionLot lot = mine.get(start + i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                String baseName = meta.hasDisplayName() ? meta.getDisplayName() : formatMat(lot.item().getType().name());
                meta.setDisplayName(c("&6" + baseName));
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

    // ---------- МОИ СТАВКИ ----------
    public void openMyBids(Player p) {
        MyBidsHolder h = new MyBidsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои ставки &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> bids = auctions.myBids(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < bids.size(); i++) {
            AuctionLot lot = bids.get(i);
            inv.setItem(grid[i], item(Material.PAPER, "&6" + AuctionFilter.itemDisplayName(lot), List.of(
                    "&7Моя ставка: &f" + fmt(lot.currentBid()) + " GLD",
                    "&7Осталось: &e" + timeLeft(lot.expiresAt()),
                    "&eКлик — подробности")));
        }
        for (int i = bids.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7◀ Рынок", List.of()));
        p.openInventory(inv);
    }

    // ---------- ЗАБРАТЬ ----------
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

    // ---------- ДЕТАЛИ ЛОТА ----------
    public void openDetails(Player p, String lotId) {
        AuctionLot lot = auctions.get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); openMarket(p, 0); return; }
        DetailHolder h = new DetailHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Лот &7" + lotId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        frame(inv);

        inv.setItem(4, lot.item().clone());
        inv.setItem(13, item(Material.MAGNIFIER_GLASS_PANE_FALLBACK(), "&6Осмотреть предмет",
                List.of("&7Открыть детальный осмотр", "&7со всеми свойствами предмета",
                        "&eКлик — осмотр")));

        List<String> info = new ArrayList<>();
        info.add("&7Продавец: &f" + lot.sellerName());
        info.add("&7Тип: &f" + formatLotType(lot.type()));
        info.add("&7Категория: &f" + AuctionCategory.of(lot.item()).displayName());
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
        inv.setItem(22, item(Material.BOOK, "&6Информация", info));

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

    // ---------- ИНСПЕКЦИЯ ПРЕДМЕТА ----------
    public void openInspect(Player p, String lotId) {
        AuctionLot lot = auctions.get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); return; }
        InspectHolder h = new InspectHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Осмотр: " + AuctionFilter.itemDisplayName(lot) + " &8▌"));
        h.inv = inv;
        frame(inv);

        // Предмет в центре
        inv.setItem(13, lot.item().clone());

        // Базовая информация
        List<String> baseInfo = new ArrayList<>();
        baseInfo.add("&7Тип: &f" + formatMat(lot.item().getType().name()));
        baseInfo.add("&7Количество: &f" + lot.item().getAmount());
        if (lot.item().getType().getMaxDurability() > 0) {
            ItemMeta meta = lot.item().getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.hasDamage()) {
                int max = lot.item().getType().getMaxDurability();
                int remaining = max - dmg.getDamage();
                baseInfo.add("&7Прочность: &f" + remaining + "/" + max);
            }
        }
        inv.setItem(20, item(Material.BOOK, "&6Свойства", baseInfo));

        // Энчанты
        List<String> enchants = new ArrayList<>();
        enchants.add("&7Зачарования:");
        Map<Enchantment, Integer> enchs = lot.item().getEnchantments();
        if (enchs.isEmpty()) {
            enchants.add("&8Нет");
        } else {
            for (var e : enchs.entrySet()) {
                enchants.add("&7- &f" + formatEnchantName(e.getKey()) + " " + romanLevel(e.getValue()));
            }
        }
        inv.setItem(21, item(Material.ENCHANTED_BOOK, "&6Зачарования", enchants));

        // Лор предмета
        List<String> itemLore = new ArrayList<>();
        itemLore.add("&7Описание предмета:");
        ItemMeta itemMeta = lot.item().getItemMeta();
        if (itemMeta != null && itemMeta.hasLore() && itemMeta.getLore() != null) {
            for (String line : itemMeta.getLore()) {
                itemLore.add("&7" + line);
            }
        } else {
            itemLore.add("&8Описание отсутствует");
        }
        inv.setItem(22, item(Material.WRITABLE_BOOK, "&6Описание", itemLore));

        // Информация о лоте
        List<String> lotInfo = new ArrayList<>();
        lotInfo.add("&7Продавец: &f" + lot.sellerName());
        lotInfo.add("&7Тип лота: &f" + formatLotType(lot.type()));
        lotInfo.add("&7Категория: &f" + AuctionCategory.of(lot.item()).displayName());
        if (lot.type() != AuctionLot.LotType.AUCTION) lotInfo.add("&7Buyout: &f" + fmt(lot.buyoutPrice()) + " GLD");
        if (lot.type() != AuctionLot.LotType.BUYOUT && lot.currentBid() > 0)
            lotInfo.add("&7Ставка: &f" + fmt(lot.currentBid()) + " GLD");
        inv.setItem(23, item(Material.PAPER, "&6Информация о лоте", lotInfo));

        // Кнопка "Назад" и "Купить/Ставка"
        inv.setItem(40, item(Material.ARROW, "&7◀ К деталям лота", List.of()));

        boolean own = lot.seller().equals(p.getUniqueId());
        boolean active = lot.status() == AuctionLot.Status.ACTIVE;
        if (active && !own) {
            if (lot.type() != AuctionLot.LotType.AUCTION) {
                inv.setItem(42, item(Material.EMERALD, "&aКупить за &f" + fmt(lot.buyoutPrice()) + " GLD",
                        List.of("&eКлик — подтвердить покупку")));
            } else if (lot.type() == AuctionLot.LotType.AUCTION) {
                inv.setItem(42, item(Material.GOLD_NUGGET, "&6Сделать ставку",
                        List.of("&7Мин: &f" + fmt(lot.minNextBid()) + " GLD",
                                "&eКлик → ввод суммы в чат")));
            }
        }

        p.openInventory(inv);
    }

    // ---------- СОЗДАНИЕ ----------
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
        inv.setItem(11, item(Material.GOLD_BLOCK, "&aТорговая грамота",
                List.of("&7Фиксированная цена", "&7Кто первый заплатит — тот купит",
                        "&7(BUYOUT)")));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6Торги",
                List.of("&7Ставки до истечения", "&7Побеждает максимальная ставка",
                        "&7(AUCTION)")));
        inv.setItem(15, item(Material.DIAMOND, "&bТорги с выкупом",
                List.of("&7И ставки, и мгновенная покупка",
                        "&7(AUCTION_BUYOUT)")));
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
        lore.add("&7Тип: &f" + formatLotType(w.type));
        if (w.type != AuctionLot.LotType.BUYOUT) lore.add("&7Стартовая: &f" + fmt(w.startPrice) + " GLD");
        if (w.type != AuctionLot.LotType.AUCTION) lore.add("&7Buyout: &f" + fmt(w.buyoutPrice) + " GLD");
        lore.add("&7Длительность: &f" + w.durationHours + "ч");
        lore.add("&7Комиссия листинга: &c-" + fmt(listingFee) + " GLD");
        inv.setItem(13, item(Material.BOOK, "&6Сводка", lore));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a✔ Выставить", List.of()));
        inv.setItem(15, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    // ---------- КЛИКИ ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof MyListingsHolder)
                && !(raw instanceof MyBidsHolder) && !(raw instanceof CollectHolder)
                && !(raw instanceof DetailHolder) && !(raw instanceof InspectHolder)
                && !(raw instanceof FilterHolder) && !(raw instanceof CategoryHolder)
                && !(raw instanceof CreateTypeHolder) && !(raw instanceof CreateConfirmHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 47 && mh.page > 0) { openMarket(p, mh.page - 1, mh.filter); return; }
            if (slot == 51) { openMarket(p, mh.page + 1, mh.filter); return; }
            if (slot == 2) { openMyListings(p, 0); return; }
            if (slot == 4) { openMyBids(p); return; }
            if (slot == 6) { openCollect(p); return; }
            if (slot == 8) { openCreateType(p); return; }
            if (slot == 45) { openFilters(p); return; }
            if (slot == 46) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "search:");
                msg(p, "&7Введите запрос для поиска (имя предмета или ник продавца):");
                return;
            }
            // Категории в верхней панели
            AuctionCategory[] cats = AuctionCategory.values();
            if (slot >= 1 && slot <= cats.length) {
                AuctionCategory clicked = cats[slot - 1];
                AuctionFilter cur = filterOf(p);
                AuctionFilter next = cur.category() == clicked ? cur.withCategory(null) : cur.withCategory(clicked);
                openMarket(p, 0, next);
                return;
            }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
            int gi = -1;
            for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
            if (gi < 0) return;
            List<AuctionLot> active = auctions.listActive(mh.filter);
            int idx = mh.page * 28 + gi;
            if (idx < active.size()) {
                if (e.isRightClick()) openInspect(p, active.get(idx).id());
                else openDetails(p, active.get(idx).id());
            }
            return;
        }

        if (raw instanceof FilterHolder) {
            if (slot == 31) { openMarket(p, 0); return; }
            if (slot == 22) { openMarket(p, 0); return; }
            AuctionFilter cur = filterOf(p);
            if (slot == 23) { activeFilters.put(p.getUniqueId(), AuctionFilter.empty()); openMarket(p, 0); return; }
            if (slot == 10) {
                AuctionLot.LotType[] types = {null, AuctionLot.LotType.BUYOUT, AuctionLot.LotType.AUCTION, AuctionLot.LotType.AUCTION_BUYOUT};
                int ci = 0;
                for (int i = 0; i < types.length; i++) if (types[i] == cur.type()) { ci = i; break; }
                int next = (ci + 1) % types.length;
                activeFilters.put(p.getUniqueId(), cur.withType(types[next]));
                openFilters(p);
                return;
            }
            if (slot == 11) { openCategories(p); return; }
            if (slot == 12) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "filter-min:");
                msg(p, "&7Введите минимальную цену (GLD) или &cотмена&7:");
                return;
            }
            if (slot == 13) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "filter-max:");
                msg(p, "&7Введите максимальную цену (GLD) или &cотмена&7:");
                return;
            }
            if (slot == 14) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "search:");
                msg(p, "&7Введите запрос для поиска (имя предмета или ник):");
                return;
            }
            if (slot == 15) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "filter-nation:");
                msg(p, "&7Введите название нации или &cотмена&7:");
                return;
            }
            return;
        }

        if (raw instanceof CategoryHolder) {
            if (slot == 22) { openFilters(p); return; }
            AuctionCategory[] cats = AuctionCategory.values();
            if (slot >= 10 && slot < 10 + cats.length) {
                AuctionCategory clicked = cats[slot - 10];
                AuctionFilter cur = filterOf(p);
                AuctionFilter next = cur.category() == clicked ? cur.withCategory(null) : cur.withCategory(clicked);
                activeFilters.put(p.getUniqueId(), next);
                openCategories(p);
            }
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
            if (slot == 13) { openInspect(p, lot.id()); return; }
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

        if (raw instanceof InspectHolder ih) {
            if (slot == 40) { openDetails(p, ih.lotId); return; }
            AuctionLot lot = auctions.get(ih.lotId);
            if (lot == null) { p.closeInventory(); return; }
            boolean own = lot.seller().equals(p.getUniqueId());
            if (slot == 42 && !own && lot.status() == AuctionLot.Status.ACTIVE) {
                if (lot.type() != AuctionLot.LotType.AUCTION) {
                    String err = auctions.buyout(p.getUniqueId(), lot.id());
                    msg(p, err == null ? "&aПредмет куплен" : "&c" + err);
                    openMarket(p, 0);
                } else {
                    p.closeInventory();
                    chatField.put(p.getUniqueId(), "bid:" + lot.id());
                    msg(p, "&7Введите сумму ставки (мин &f" + fmt(lot.minNextBid()) + "&7):");
                }
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
                case "search" -> {
                    AuctionFilter cur = filterOf(p);
                    String q = text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") ? null : text;
                    activeFilters.put(p.getUniqueId(), cur.withSearch(q));
                    openMarket(p, 0);
                }
                case "filter-min" -> {
                    AuctionFilter cur = filterOf(p);
                    if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
                        openFilters(p); return;
                    }
                    double v = parse(text);
                    if (!(v > 0)) { msg(p, "&cЦена > 0"); openFilters(p); return; }
                    activeFilters.put(p.getUniqueId(), cur.withMinPrice(v));
                    openFilters(p);
                }
                case "filter-max" -> {
                    AuctionFilter cur = filterOf(p);
                    if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
                        openFilters(p); return;
                    }
                    double v = parse(text);
                    if (!(v > 0)) { msg(p, "&cЦена > 0"); openFilters(p); return; }
                    activeFilters.put(p.getUniqueId(), cur.withMaxPrice(v));
                    openFilters(p);
                }
                case "filter-nation" -> {
                    AuctionFilter cur = filterOf(p);
                    String n = text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") ? null : text;
                    activeFilters.put(p.getUniqueId(), cur.withNation(n));
                    openFilters(p);
                }
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {}

    // ---------- Хелперы ----------
    private static String formatLotType(AuctionLot.LotType t) {
        return switch (t) {
            case BUYOUT -> "Торговая грамота";
            case AUCTION -> "Торги";
            case AUCTION_BUYOUT -> "Торги с выкупом";
        };
    }

    private static String formatMat(String name) {
        String[] parts = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String formatEnchantName(Enchantment e) {
        String key = e.getKey().getKey();
        return switch (key) {
            case "sharpness" -> "Острота";
            case "smite" -> "Небесная кара";
            case "bane_of_arthropods" -> "Бич членистоногих";
            case "knockback" -> "Отдача";
            case "fire_aspect" -> "Заговор огня";
            case "looting" -> "Добыча";
            case "sweeping_edge" -> "Разящий клинок";
            case "efficiency" -> "Эффективность";
            case "silk_touch" -> "Шёлковое касание";
            case "unbreaking" -> "Прочность";
            case "fortune" -> "Удача";
            case "power" -> "Сила";
            case "punch" -> "Отбрасывание";
            case "flame" -> "Воспламенение";
            case "infinity" -> "Бесконечность";
            case "protection" -> "Защита";
            case "fire_protection" -> "Огнеупорность";
            case "feather_falling" -> "Невесомость";
            case "blast_protection" -> "Взрывоустойчивость";
            case "projectile_protection" -> "Защита от снарядов";
            case "respiration" -> "Подводное дыхание";
            case "aqua_affinity" -> "Родство с водой";
            case "thorns" -> "Шипы";
            case "depth_strider" -> "Подводная ходьба";
            case "frost_walker" -> "Ледоход";
            case "mending" -> "Починка";
            case "looting" -> "Добыча";
            case "luck_of_the_sea" -> "Морская удача";
            case "lure" -> "Приманка";
            case "loyalty" -> "Верность";
            case "impaling" -> "Пронзатель";
            case "riptide" -> "Тягун";
            case "channeling" -> "Громовержец";
            case "multishot" -> "Тройной выстрел";
            case "quick_charge" -> "Быстрая перезарядка";
            case "piercing" -> "Пронзающая стрела";
            case "soul_speed" -> "Скорость души";
            case "swift_sneak" -> "Проворство";
            default -> key;
        };
    }

    private static String romanLevel(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(level);
        };
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }

    /** Фолбэк-материал для «лупы» — если MAGNIFIER_GLASS_PANE недоступен, используем SPYGLASS. */
    private static Material MAGNIFIER_GLASS_PANE_FALLBACK() {
        try { return Material.SPYGLASS; } catch (Throwable t) { return Material.GLASS_PANE; }
    }
}
