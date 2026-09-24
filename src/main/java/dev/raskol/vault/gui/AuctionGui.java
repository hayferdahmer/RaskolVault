// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionCategory;
import dev.raskol.vault.auction.AuctionFilter;
import dev.raskol.vault.auction.AuctionLot;
import dev.raskol.vault.auction.AuctionService;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.util.GuiItems;
import dev.raskol.vault.util.Numbers;
import dev.raskol.vault.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аукцион GUI (1.2.6 fix): ВОССТАНОВЛЕНЫ категории-ряд, GUI фильтров, осмотр предмета.
 * Сохранены фиксы 1.2.6-a: оба конструктора + изъятие предмета при создании.
 */
public final class AuctionGui implements Listener {

    private final RaskolVault plugin;
    private final AuctionService auctions;
    private final Map<UUID, CreateWizard> wizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();

    private static final class CreateWizard {
        ItemStack item;
        AuctionLot.LotType type = AuctionLot.LotType.BUYOUT;
        double startPrice = 0;
        double buyoutPrice = 0;
        int durationHours = 24;
        String currencyId = "GLD";
    }

    public static final class MarketHolder implements InventoryHolder {
        final int page; final AuctionFilter filter;
        MarketHolder(int page, AuctionFilter filter) { this.page = page; this.filter = filter; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class FilterHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class InspectHolder implements InventoryHolder {
        final String lotId;
        InspectHolder(String lotId) { this.lotId = lotId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyListingsHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateTypeHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateCurrencyHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateConfirmHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class DetailHolder implements InventoryHolder {
        final String lotId;
        DetailHolder(String lotId) { this.lotId = lotId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public AuctionGui(RaskolVault plugin) { this(plugin, plugin.getAuctionService()); }
    public AuctionGui(RaskolVault plugin, AuctionService auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
    }

    private void msg(Player p, String raw) { p.sendMessage(GuiItems.c(plugin.getMessages().prefix() + raw)); }
    private static ItemStack it(Material m, String n, List<String> lore) { return GuiItems.item(m, n, lore); }
    private static ItemStack pane() { return GuiItems.pane(); }

    private static final int[] GRID = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34, 37,38,39,40,41,42,43};

    // ---------- РЫНОК ----------
    public void openMarket(Player p, int page) { openMarket(p, page, AuctionFilter.empty()); }

    public void openMarket(Player p, int page, AuctionFilter filter) {
        MarketHolder h = new MarketHolder(page, filter);
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Аукцион · рынок &8▌"));
        h.inv = inv;
        // Row0: категории-быстрый фильтр + кнопка фильтров
        AuctionCategory[] cats = AuctionCategory.values();
        for (int i = 0; i < cats.length && i < 8; i++) {
            boolean on = filter.category() == cats[i];
            inv.setItem(i, it(cats[i].icon(), (on ? "&a" : "&6") + cats[i].displayName(),
                    List.of("&7" + cats[i].description(), on ? "&aАктивна — клик снимет" : "&eКлик — фильтр")));
        }
        inv.setItem(8, it(Material.COMPARATOR, "&6Фильтры", List.of("&7Тип/цена/поиск", "&eКлик — настроить")));
        // Лоты
        List<AuctionLot> active = auctions.listActive(filter);
        int perPage = GRID.length;
        int start = page * perPage;
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length && (start + i) < active.size(); i++) {
            AuctionLot lot = active.get(start + i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                String base = meta.hasDisplayName() ? meta.getDisplayName() : lot.item().getType().name();
                meta.setDisplayName(GuiItems.c("&6" + base));
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(GuiItems.c("&7Продавец: &f" + lot.sellerName()));
                lore.add(GuiItems.c("&7Валюта: &f" + lot.currencyId()));
                if (lot.type() != AuctionLot.LotType.AUCTION)
                    lore.add(GuiItems.c("&7Buyout: &f" + Numbers.fmt(lot.buyoutPrice())));
                if (lot.type() != AuctionLot.LotType.BUYOUT)
                    lore.add(GuiItems.c("&7Ставка: &f" + Numbers.fmt(lot.currentBid() > 0 ? lot.currentBid() : lot.startPrice())));
                lore.add(GuiItems.c("&7Осталось: &e" + TimeFormat.timeLeft(lot.expiresAt())));
                lore.add(GuiItems.c("&eЛКМ — детали · ПКМ — осмотр"));
                meta.setLore(lore);
                show.setItemMeta(meta);
            }
            inv.setItem(GRID[i], show);
        }
        for (int i = Math.max(0, active.size() - start); i < GRID.length; i++) inv.setItem(GRID[i], pane());
        // Row5
        if (page > 0) inv.setItem(45, it(Material.ARROW, "&7◀ Пред.", List.of()));
        inv.setItem(46, it(Material.GOLD_BLOCK, "&aМои лоты", List.of("&eКлик")));
        inv.setItem(48, it(Material.EMERALD, "&aВыставить предмет", List.of("&eКлик → мастер")));
        inv.setItem(49, it(Material.BARRIER, "&cЗакрыть", List.of()));
        if ((page + 1) * perPage < active.size()) inv.setItem(53, it(Material.ARROW, "&7След. ▶", List.of()));
        inv.setItem(52, it(Material.MAP, "&6Лотов: &f" + active.size(), List.of()));
        p.openInventory(inv);
    }

    // ---------- ФИЛЬТРЫ ----------
    private void openFilters(Player p, AuctionFilter f) {
        FilterHolder h = new FilterHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Фильтры аукциона &8▌"));
        h.inv = inv;
        inv.setItem(10, it(Material.BOOK, "&6Категория", List.of(
                "&7Текущая: &f" + (f.category() == null ? "любая" : f.category().displayName()), "&eКлик — сменить")));
        inv.setItem(11, it(Material.COMPASS, "&6Тип лота", List.of(
                "&7Текущий: &f" + (f.type() == null ? "любой" : f.type().name()), "&eКлик — переключить")));
        inv.setItem(12, it(Material.GOLD_NUGGET, "&6Мин. цена", List.of(
                "&7: &f" + (f.minPrice() == null ? "—" : Numbers.fmt(f.minPrice())), "&eКлик → ввод")));
        inv.setItem(13, it(Material.GOLD_BLOCK, "&6Макс. цена", List.of(
                "&7: &f" + (f.maxPrice() == null ? "—" : Numbers.fmt(f.maxPrice())), "&eКлик → ввод")));
        inv.setItem(14, it(Material.NAME_TAG, "&bПоиск", List.of(
                "&7: &f" + (f.searchQuery() == null ? "—" : f.searchQuery()), "&eКлик → ввод")));
        inv.setItem(15, it(Material.RED_CONCRETE, "&cСбросить", List.of("&eКлик")));
        inv.setItem(22, it(Material.ARROW, "&7Применить/назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- ОСМОТР ----------
    private void openInspect(Player p, String lotId) {
        AuctionLot lot = auctions.get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); return; }
        InspectHolder h = new InspectHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Осмотр предмета &8▌"));
        h.inv = inv;
        inv.setItem(13, lot.item().clone());
        List<String> props = new ArrayList<>();
        props.add("&7Тип: &f" + lot.item().getType().name());
        props.add("&7Кол-во: &f" + lot.item().getAmount());
        ItemMeta m = lot.item().getItemMeta();
        if (m != null && m.hasEnchants()) {
            props.add("&7Зачарования:");
            m.getEnchants().forEach((e, lvl) -> props.add("&7- &f" + e.getKey().getKey() + " " + TimeFormat.roman(lvl)));
        }
        inv.setItem(11, it(Material.BOOK, "&6Свойства", props));
        inv.setItem(15, it(Material.PAPER, "&6О лоте", List.of(
                "&7Продавец: &f" + lot.sellerName(),
                "&7Категория: &f" + AuctionCategory.of(lot.item()).displayName(),
                "&7Статус: &f" + lot.status())));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- МОИ ЛОТЫ ----------
    private void openMyListings(Player p) {
        MyListingsHolder h = new MyListingsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Мои лоты &8▌"));
        h.inv = inv;
        GuiItems.frame54(inv);
        List<AuctionLot> mine = auctions.myListings(p.getUniqueId());
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length && i < mine.size(); i++) {
            AuctionLot lot = mine.get(i);
            inv.setItem(GRID[i], it(Material.PAPER, "&6Лот &7" + lot.id().substring(0, 8), List.of(
                    "&7Статус: &f" + lot.status(),
                    "&7Цена: &f" + Numbers.fmt(lot.type() == AuctionLot.LotType.AUCTION ? lot.startPrice() : lot.buyoutPrice()),
                    "&7Осталось: &e" + TimeFormat.timeLeft(lot.expiresAt()),
                    "&eКлик — детали")));
        }
        for (int i = mine.size(); i < GRID.length; i++) inv.setItem(GRID[i], pane());
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- ДЕТАЛИ ----------
    private void openDetails(Player p, String lotId) {
        AuctionLot lot = auctions.get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); return; }
        DetailHolder h = new DetailHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Лот &7" + lotId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        inv.setItem(13, lot.item().clone());
        inv.setItem(11, it(Material.BOOK, "&6Информация", List.of(
                "&7Продавец: &f" + lot.sellerName(),
                "&7Валюта: &f" + lot.currencyId(),
                "&7Статус: &f" + lot.status(),
                "&7Осталось: &e" + TimeFormat.timeLeft(lot.expiresAt()))));
        boolean own = lot.seller().equals(p.getUniqueId());
        if (own) {
            if (lot.status() == AuctionLot.Status.ACTIVE)
                inv.setItem(15, it(Material.RED_CONCRETE, "&cОтменить лот", List.of()));
            else if (lot.status() == AuctionLot.Status.EXPIRED || lot.status() == AuctionLot.Status.CANCELLED)
                inv.setItem(15, it(Material.HOPPER, "&bЗабрать предмет", List.of()));
        } else if (lot.status() == AuctionLot.Status.ACTIVE) {
            if (lot.type() != AuctionLot.LotType.AUCTION)
                inv.setItem(15, it(Material.EMERALD, "&aКупить за &f" + Numbers.fmt(lot.buyoutPrice()), List.of()));
            else
                inv.setItem(15, it(Material.GOLD_NUGGET, "&6Ставка", List.of("&eКлик → ввод суммы")));
        }
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- МАСТЕР СОЗДАНИЯ ----------
    private void openCreateType(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) { msg(p, "&cВозьмите предмет в основную руку"); return; }
        CreateWizard w = wizards.computeIfAbsent(p.getUniqueId(), k -> new CreateWizard());
        w.item = held.clone();
        CreateTypeHolder h = new CreateTypeHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Тип лота &8▌"));
        h.inv = inv;
        inv.setItem(11, it(Material.GOLD_BLOCK, "&aТорговая грамота", List.of("&7Фикс. цена (BUYOUT)")));
        inv.setItem(13, it(Material.GOLD_NUGGET, "&6Торги", List.of("&7Ставки (AUCTION)")));
        inv.setItem(15, it(Material.DIAMOND, "&bТорги с выкупом", List.of("&7Ставки + buyout")));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }
    private void openCreateCurrency(Player p) {
        CreateCurrencyHolder h = new CreateCurrencyHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Валюта лота &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (slot > 16) break;
            inv.setItem(slot, it(GuiItems.currencyIcon(cur.id()), "&6" + cur.displayName(), List.of("&7ID: &f" + cur.id())));
            slot += 2;
        }
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }
    private void openCreateConfirm(Player p) {
        CreateWizard w = wizards.get(p.getUniqueId());
        if (w == null) { openCreateType(p); return; }
        CreateConfirmHolder h = new CreateConfirmHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Подтверждение &8▌"));
        h.inv = inv;
        inv.setItem(13, it(Material.BOOK, "&6Сводка лота", List.of(
                "&7Предмет: &f" + w.item.getType().name() + " x" + w.item.getAmount(),
                "&7Тип: &f" + w.type.name(),
                "&7Валюта: &f" + w.currencyId,
                "&cВНИМАНИЕ: предмет будет изъят")));
        inv.setItem(11, it(Material.LIME_CONCRETE, "&a✔ Выставить", List.of()));
        inv.setItem(15, it(Material.RED_CONCRETE, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    // ---------- КЛИКИ ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof FilterHolder) && !(raw instanceof InspectHolder)
                && !(raw instanceof MyListingsHolder) && !(raw instanceof CreateTypeHolder)
                && !(raw instanceof CreateCurrencyHolder) && !(raw instanceof CreateConfirmHolder)
                && !(raw instanceof DetailHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 8) { openFilters(p, mh.filter); return; }
            if (slot == 46) { openMyListings(p); return; }
            if (slot == 48) { openCreateType(p); return; }
            if (slot == 45 && mh.page > 0) { openMarket(p, mh.page - 1, mh.filter); return; }
            if (slot == 53) { openMarket(p, mh.page + 1, mh.filter); return; }
            if (slot >= 0 && slot < 8) {
                AuctionCategory[] cats = AuctionCategory.values();
                AuctionCategory clicked = cats[slot];
                AuctionFilter next = mh.filter.category() == clicked ? mh.filter.withCategory(null) : mh.filter.withCategory(clicked);
                openMarket(p, 0, next);
                return;
            }
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] != slot) continue;
                List<AuctionLot> active = auctions.listActive(mh.filter);
                int idx = mh.page * GRID.length + i;
                if (idx < active.size()) {
                    if (e.isRightClick()) openInspect(p, active.get(idx).id());
                    else openDetails(p, active.get(idx).id());
                }
                return;
            }
            return;
        }
        if (raw instanceof FilterHolder) {
            AuctionFilter f = AuctionFilter.empty();
            if (slot == 22) { openMarket(p, 0); return; }
            if (slot == 15) { openMarket(p, 0); return; }
            if (slot == 10) { chatField.put(p.getUniqueId(), "f-cat"); p.closeInventory(); msg(p, "&7Введите номер/название категории или &cотмена&7:"); return; }
            if (slot == 11) { chatField.put(p.getUniqueId(), "f-type"); p.closeInventory(); msg(p, "&7Тип: BUYOUT / AUCTION / AUCTION_BUYOUT или &cотмена&7:"); return; }
            if (slot == 12) { chatField.put(p.getUniqueId(), "f-min"); p.closeInventory(); msg(p, "&7Мин. цена или &cотмена&7:"); return; }
            if (slot == 13) { chatField.put(p.getUniqueId(), "f-max"); p.closeInventory(); msg(p, "&7Макс. цена или &cотмена&7:"); return; }
            if (slot == 14) { chatField.put(p.getUniqueId(), "f-search"); p.closeInventory(); msg(p, "&7Поиск или &cотмена&7:"); return; }
            return;
        }
        if (raw instanceof InspectHolder ih) {
            if (slot == 22) { openDetails(p, ih.lotId); return; }
            return;
        }
        if (raw instanceof MyListingsHolder) {
            if (slot == 49) { openMarket(p, 0); return; }
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] != slot) continue;
                List<AuctionLot> mine = auctions.myListings(p.getUniqueId());
                if (i < mine.size()) openDetails(p, mine.get(i).id());
                return;
            }
            return;
        }
        if (raw instanceof CreateTypeHolder) {
            if (slot == 22) { p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
            CreateWizard w = wizards.get(p.getUniqueId());
            if (w == null) return;
            if (slot == 11) w.type = AuctionLot.LotType.BUYOUT;
            else if (slot == 13) w.type = AuctionLot.LotType.AUCTION;
            else if (slot == 15) w.type = AuctionLot.LotType.AUCTION_BUYOUT;
            else return;
            openCreateCurrency(p);
            return;
        }
        if (raw instanceof CreateCurrencyHolder) {
            if (slot == 22) { openCreateType(p); return; }
            CreateWizard w = wizards.get(p.getUniqueId());
            if (w == null) return;
            int idx = (slot - 10) / 2;
            List<Currency> all = plugin.getCurrencies().all();
            if (idx >= 0 && idx < all.size()) { w.currencyId = all.get(idx).id(); openCreateConfirm(p); }
            return;
        }
        if (raw instanceof CreateConfirmHolder) {
            CreateWizard w = wizards.get(p.getUniqueId());
            if (slot == 15) { p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
            if (slot == 11 && w != null) {
                ItemStack live = p.getInventory().getItemInMainHand();
                if (live == null || live.getType().isAir()) { msg(p, "&cПредмет не в руке — отменено"); p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
                ItemStack lotItem = live.clone();
                p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                AuctionLot lot = auctions.create(p.getUniqueId(), lotItem, w.type, w.startPrice, w.buyoutPrice, w.durationHours, w.currencyId);
                if (lot == null) { p.getInventory().setItemInMainHand(lotItem); msg(p, "&cНе удалось создать — предмет возвращён"); }
                else msg(p, "&aЛот &f" + lot.id().substring(0, 8) + " &aвыставлен");
                p.closeInventory(); wizards.remove(p.getUniqueId());
            }
            return;
        }
        if (raw instanceof DetailHolder dh) {
            if (slot == 22) { openMarket(p, 0); return; }
            AuctionLot lot = auctions.get(dh.lotId);
            if (lot == null) return;
            boolean own = lot.seller().equals(p.getUniqueId());
            if (slot == 15) {
                if (own) {
                    if (lot.status() == AuctionLot.Status.ACTIVE) {
                        String err = auctions.cancel(p.getUniqueId(), dh.lotId);
                        msg(p, err == null ? "&aЛот отменён, предмет в «Забрать»" : "&c" + err);
                    } else {
                        boolean ok = auctions.collect(p.getUniqueId(), dh.lotId);
                        msg(p, ok ? "&aПредмет забран" : "&cНе удалось");
                    }
                } else if (lot.status() == AuctionLot.Status.ACTIVE) {
                    if (lot.type() != AuctionLot.LotType.AUCTION) {
                        String err = auctions.buyout(p.getUniqueId(), dh.lotId);
                        msg(p, err == null ? "&aПредмет куплен" : "&c" + err);
                    } else {
                        p.closeInventory();
                        chatField.put(p.getUniqueId(), "bid:" + dh.lotId);
                        msg(p, "&7Введите сумму ставки (мин &f" + Numbers.fmt(lot.minNextBid()) + "&7):");
                        return;
                    }
                }
                openMarket(p, 0);
            }
            return;
        }
    }

    // ---------- ЧАТ (фильтры + ставки) ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean cancel = text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel");
            if (field.startsWith("bid:")) {
                if (cancel) { openMarket(p, 0); return; }
                double v = Numbers.parseDouble(text, -1);
                if (!(v > 0)) { msg(p, "&cСумма > 0"); return; }
                String err = auctions.bid(p.getUniqueId(), field.substring(4), v);
                msg(p, err == null ? "&aСтавка принята" : "&c" + err);
                openMarket(p, 0);
                return;
            }
            // Фильтры применяются к пустому фильтру и открывают рынок
            AuctionFilter f = AuctionFilter.empty();
            switch (field) {
                case "f-cat" -> {
                    if (cancel) { openMarket(p, 0); return; }
                    for (AuctionCategory c : AuctionCategory.values())
                        if (c.displayName().equalsIgnoreCase(text) || c.name().equalsIgnoreCase(text)) f = f.withCategory(c);
                    openMarket(p, 0, f);
                }
                case "f-type" -> {
                    if (cancel) { openMarket(p, 0); return; }
                    try { f = f.withType(AuctionLot.LotType.valueOf(text.toUpperCase())); } catch (IllegalArgumentException ex) { msg(p, "&cНеверный тип"); return; }
                    openMarket(p, 0, f);
                }
                case "f-min" -> {
                    if (cancel) { openMarket(p, 0); return; }
                    f = f.withMinPrice(Numbers.parseDouble(text, 0));
                    openMarket(p, 0, f);
                }
                case "f-max" -> {
                    if (cancel) { openMarket(p, 0); return; }
                    f = f.withMaxPrice(Numbers.parseDouble(text, 0));
                    openMarket(p, 0, f);
                }
                case "f-search" -> {
                    if (cancel) { openMarket(p, 0); return; }
                    f = f.withSearch(text);
                    openMarket(p, 0, f);
                }
            }
        });
    }
}
