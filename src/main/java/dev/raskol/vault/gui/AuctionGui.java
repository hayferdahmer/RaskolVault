// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionCategory;
import dev.raskol.vault.auction.AuctionFilter;
import dev.raskol.vault.auction.AuctionLot;
import dev.raskol.vault.auction.AuctionService;
import dev.raskol.vault.api.currency.Currency;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
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
 * GUI аукциона (1.2.6-a fix): конструктор принимает ИЛИ (RaskolVault), ИЛИ
 * (RaskolVault, AuctionService) — любой вызов в RaskolVault компилируется.
 * Баг 1 (дюп): предмет изымается из инвентаря при подтверждении создания лота.
 */
public final class AuctionGui implements Listener {

    private final RaskolVault plugin;
    private final AuctionService auctions;
    private final Map<UUID, CreateWizard> loanWizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();

    private static final class CreateWizard {
        ItemStack item;
        AuctionLot.LotType type = AuctionLot.LotType.BUYOUT;
        double startPrice = 0;
        double buyoutPrice = 0;
        int durationHours = 24;
        String currencyId = "GLD";
    }

    // FIX: оба конструктора, чтобы любой вызов в RaskolVault компилировался
    public AuctionGui(RaskolVault plugin) {
        this(plugin, plugin.getAuctionService());
    }

    public AuctionGui(RaskolVault plugin, AuctionService auctions) {
        this.plugin = plugin;
        this.auctions = auctions;
    }

    private AuctionService auctions() { return auctions; }

    public static final class MarketHolder implements InventoryHolder {
        final int page; final AuctionFilter filter;
        MarketHolder(int page, AuctionFilter filter) { this.page = page; this.filter = filter; }
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

    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
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
    private static String timeLeft(long due, long now) {
        long d = (due - now) / 86_400_000L;
        return Math.max(0, d) + " дн";
    }
    private static void frame(Inventory inv) {
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
    }

    public void openMarket(Player p, int page) { openMarket(p, page, AuctionFilter.empty()); }

    public void openMarket(Player p, int page, AuctionFilter filter) {
        MarketHolder h = new MarketHolder(page, filter);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Аукцион · рынок &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> active = auctions().listActive(filter);
        int perPage = 28;
        int start = page * perPage;
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        long now = System.currentTimeMillis();
        for (int i = 0; i < grid.length && (start + i) < active.size(); i++) {
            AuctionLot lot = active.get(start + i);
            ItemStack show = lot.item().clone();
            ItemMeta meta = show.getItemMeta();
            if (meta != null) {
                String baseName = meta.hasDisplayName() ? meta.getDisplayName() : lot.item().getType().name();
                meta.setDisplayName(c("&6" + baseName));
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add(c("&7Продавец: &f" + lot.sellerName()));
                lore.add(c("&7Валюта: &f" + lot.currencyId()));
                if (lot.type() != AuctionLot.LotType.AUCTION)
                    lore.add(c("&7Buyout: &f" + fmt(lot.buyoutPrice()) + " " + lot.currencyId()));
                if (lot.type() != AuctionLot.LotType.BUYOUT)
                    lore.add(c("&7Ставка: &f" + fmt(lot.currentBid() > 0 ? lot.currentBid() : lot.startPrice()) + " " + lot.currencyId()));
                lore.add(c("&7Осталось: &e" + timeLeft(lot.expiresAt(), now)));
                lore.add(c("&eКлик — подробности"));
                meta.setLore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                show.setItemMeta(meta);
            }
            inv.setItem(grid[i], show);
        }
        for (int i = Math.max(0, active.size() - start); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(2, item(Material.GOLD_BLOCK, "&aМои лоты", List.of("&eКлик")));
        inv.setItem(6, item(Material.EMERALD, "&aВыставить предмет", List.of("&eКлик → мастер")));
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&7◀ Пред.", List.of()));
        if ((page + 1) * perPage < active.size()) inv.setItem(53, item(Material.ARROW, "&7След. ▶", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    private void openMyListings(Player p) {
        MyListingsHolder h = new MyListingsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои лоты &8▌"));
        h.inv = inv;
        frame(inv);
        List<AuctionLot> mine = auctions().myListings(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        long now = System.currentTimeMillis();
        for (int i = 0; i < grid.length && i < mine.size(); i++) {
            AuctionLot lot = mine.get(i);
            inv.setItem(grid[i], item(Material.PAPER, "&6Лот &7" + lot.id().substring(0, 8), List.of(
                    "&7Статус: &f" + lot.status(),
                    "&7Цена: &f" + fmt(lot.type() == AuctionLot.LotType.AUCTION ? lot.startPrice() : lot.buyoutPrice()) + " " + lot.currencyId(),
                    "&7Осталось: &e" + timeLeft(lot.expiresAt(), now),
                    "&eКлик — подробности")));
        }
        for (int i = mine.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreateType(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) { msg(p, "&cВозьмите предмет в основную руку"); return; }
        CreateWizard w = loanWizards.computeIfAbsent(p.getUniqueId(), k -> new CreateWizard());
        w.item = held.clone();
        CreateTypeHolder h = new CreateTypeHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Тип лота &8▌"));
        h.inv = inv;
        inv.setItem(11, item(Material.GOLD_BLOCK, "&aТорговая грамота", List.of("&7Фикс. цена (BUYOUT)")));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6Торги", List.of("&7Ставки (AUCTION)")));
        inv.setItem(15, item(Material.DIAMOND, "&bТорги с выкупом", List.of("&7Ставки + buyout")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreateCurrency(Player p) {
        CreateCurrencyHolder h = new CreateCurrencyHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Валюта лота &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (slot > 16) break;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(), List.of("&7ID: &f" + cur.id(), "&eКлик")));
            slot += 2;
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreateConfirm(Player p) {
        CreateWizard w = loanWizards.get(p.getUniqueId());
        if (w == null) { openCreateType(p); return; }
        CreateConfirmHolder h = new CreateConfirmHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Подтверждение &8▌"));
        h.inv = inv;
        inv.setItem(13, item(Material.BOOK, "&6Сводка лота", List.of(
                "&7Предмет: &f" + w.item.getType().name() + " x" + w.item.getAmount(),
                "&7Тип: &f" + w.type.name(),
                "&7Валюта: &f" + w.currencyId,
                w.type != AuctionLot.LotType.BUYOUT ? "&7Старт: &f" + fmt(w.startPrice) : "",
                w.type != AuctionLot.LotType.AUCTION ? "&7Buyout: &f" + fmt(w.buyoutPrice) : "",
                "&7Срок: &f" + w.durationHours + "ч",
                "&cВНИМАНИЕ: предмет будет изъят из инвентаря")));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a✔ Выставить", List.of()));
        inv.setItem(15, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    private void openDetails(Player p, String lotId) {
        AuctionLot lot = auctions().get(lotId);
        if (lot == null) { msg(p, "&cЛот не найден"); return; }
        DetailHolder h = new DetailHolder(lotId);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Лот &7" + lotId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        long now = System.currentTimeMillis();
        inv.setItem(13, lot.item().clone());
        inv.setItem(11, item(Material.BOOK, "&6Информация", List.of(
                "&7Продавец: &f" + lot.sellerName(),
                "&7Валюта: &f" + lot.currencyId(),
                "&7Статус: &f" + lot.status(),
                "&7Осталось: &e" + timeLeft(lot.expiresAt(), now))));
        boolean own = lot.seller().equals(p.getUniqueId());
        if (!own && lot.status() == AuctionLot.Status.ACTIVE) {
            if (lot.type() != AuctionLot.LotType.AUCTION)
                inv.setItem(15, item(Material.EMERALD, "&aКупить за &f" + fmt(lot.buyoutPrice()), List.of()));
            else
                inv.setItem(15, item(Material.GOLD_NUGGET, "&6Ставка", List.of("&eКлик → ввод суммы")));
        } else if (own && lot.status() == AuctionLot.Status.ACTIVE) {
            inv.setItem(15, item(Material.RED_CONCRETE, "&cОтменить лот", List.of()));
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof MyListingsHolder)
                && !(raw instanceof CreateTypeHolder) && !(raw instanceof CreateCurrencyHolder)
                && !(raw instanceof CreateConfirmHolder) && !(raw instanceof DetailHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 2) { openMyListings(p); return; }
            if (slot == 6) { openCreateType(p); return; }
            if (slot == 45 && mh.page > 0) { openMarket(p, mh.page - 1, mh.filter); return; }
            if (slot == 53) { openMarket(p, mh.page + 1, mh.filter); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
            List<AuctionLot> active = auctions().listActive(mh.filter);
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == slot && (mh.page * 28 + i) < active.size()) { openDetails(p, active.get(mh.page * 28 + i).id()); return; }
            }
            return;
        }
        if (raw instanceof MyListingsHolder) {
            if (slot == 49) { openMarket(p, 0); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            List<AuctionLot> mine = auctions().myListings(p.getUniqueId());
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == slot && i < mine.size()) { openDetails(p, mine.get(i).id()); return; }
            }
            return;
        }
        if (raw instanceof CreateTypeHolder) {
            if (slot == 22) { p.closeInventory(); loanWizards.remove(p.getUniqueId()); return; }
            CreateWizard w = loanWizards.get(p.getUniqueId());
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
            CreateWizard w = loanWizards.get(p.getUniqueId());
            if (w == null) return;
            int idx = (slot - 10) / 2;
            List<Currency> all = plugin.getCurrencies().all();
            if (idx >= 0 && idx < all.size()) { w.currencyId = all.get(idx).id(); openCreateConfirm(p); }
            return;
        }
        if (raw instanceof CreateConfirmHolder) {
            CreateWizard w = loanWizards.get(p.getUniqueId());
            if (slot == 15) { p.closeInventory(); loanWizards.remove(p.getUniqueId()); return; }
            if (slot == 11 && w != null) {
                ItemStack live = p.getInventory().getItemInMainHand();
                if (live == null || live.getType().isAir()) {
                    msg(p, "&cПредмет не в руке — создание отменено");
                    p.closeInventory(); loanWizards.remove(p.getUniqueId());
                    return;
                }
                ItemStack lotItem = live.clone();
                p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                AuctionLot lot = auctions().create(p.getUniqueId(), lotItem, w.type,
                        w.startPrice, w.buyoutPrice, w.durationHours, w.currencyId);
                if (lot == null) {
                    p.getInventory().setItemInMainHand(lotItem);
                    msg(p, "&cНе удалось создать лот — предмет возвращён");
                } else {
                    msg(p, "&aЛот &f" + lot.id().substring(0, 8) + " &aвыставлен");
                }
                p.closeInventory();
                loanWizards.remove(p.getUniqueId());
            }
            return;
        }
        if (raw instanceof DetailHolder dh) {
            if (slot == 22) { openMarket(p, 0); return; }
            AuctionLot lot = auctions().get(dh.lotId);
            if (lot == null) return;
            boolean own = lot.seller().equals(p.getUniqueId());
            if (slot == 15) {
                if (own) {
                    String err = auctions().cancel(p.getUniqueId(), dh.lotId);
                    msg(p, err == null ? "&aЛот отменён, предмет в «Забрать»" : "&c" + err);
                } else if (lot.type() != AuctionLot.LotType.AUCTION) {
                    String err = auctions().buyout(p.getUniqueId(), dh.lotId);
                    msg(p, err == null ? "&aПредмет куплен" : "&c" + err);
                } else {
                    p.closeInventory();
                    chatField.put(p.getUniqueId(), "bid:" + dh.lotId);
                    msg(p, "&7Введите сумму ставки:");
                    return;
                }
                openMarket(p, 0);
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
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (field.startsWith("bid:")) {
                String lotId = field.substring(4);
                double v = parse(text);
                if (!(v > 0)) { msg(p, "&cСумма > 0"); return; }
                String err = auctions().bid(p.getUniqueId(), lotId, v);
                msg(p, err == null ? "&aСтавка принята" : "&c" + err);
            }
        });
    }

    private static Material iconOf(String id) {
        return switch (id.toUpperCase(Locale.ROOT)) {
            case "GLD" -> Material.GOLD_INGOT;
            case "RAS" -> Material.SUNFLOWER;
            case "VLR" -> Material.GOLDEN_HELMET;
            default -> Material.GOLD_NUGGET;
        };
    }
    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }
}
