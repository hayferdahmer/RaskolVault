// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.exchange.ExchangeOrderService;
import dev.raskol.vault.storage.SQLiteLedger.ExchangeOrderRow;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI межгосударственной биржи (1.2.2-a).
 * Страницы: Стакан · Мастер ордера (4 шага) · Мои ордера · Карточка ордера (взять/отменить).
 * Свой Listener + чат-захват для суммы/цены. Короли создают/берут; все видят стакан.
 */
public final class ExchangeGui implements Listener {

    private final RaskolVault plugin;
    private final ExchangeOrderService orders;

    // Состояние мастера создания ордера (per-player)
    private static final class Wizard {
        String giveCur, getCur;
        double amount, price;
    }
    private final Map<UUID, Wizard> wizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>(); // "amount" | "price"

    // ---------- Holders ----------
    public static final class BookHolder implements InventoryHolder {
        final int page;
        BookHolder(int page) { this.page = page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class CreateHolder implements InventoryHolder {
        final int step; // 0 give,1 get,2 amount,3 price,4 confirm
        CreateHolder(int step) { this.step = step; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyHolder implements InventoryHolder {
        final int page;
        MyHolder(int page) { this.page = page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class DetailHolder implements InventoryHolder {
        final String orderId;
        DetailHolder(String orderId) { this.orderId = orderId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public ExchangeGui(RaskolVault plugin) {
        this.plugin = plugin;
        this.orders = new ExchangeOrderService(plugin, plugin.getLedger(), plugin.getWallets(),
                plugin.getCurrencies(), plugin.getEscrow());
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(Player p, String raw) { p.sendMessage(c(raw)); }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> l = new ArrayList<>();
            for (String line : lore) l.add(ChatColor.translateAlternateColorCodes('&', line));
            meta.setLore(l);
            s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() {
        return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of());
    }

    private boolean isKing(Player p) {
        if (!plugin.getTownyHook().isAvailable()) return false;
        String n = plugin.getTownyHook().nationOf(p.getUniqueId());
        return n != null && plugin.getTownyHook().isKing(p.getUniqueId(), n);
    }
    private String nationOf(Player p) {
        return plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
    }

    // ---------- Стакан ----------
    public void openBook(Player p, int page) {
        BookHolder h = new BookHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Биржа · Стакан &8▌"));
        h.inv = inv;
        List<ExchangeOrderRow> open = orders.openOrders(200);
        int perPage = 21;
        int start = page * perPage;
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length; i++) {
            int idx = start + i;
            if (idx >= open.size()) break;
            ExchangeOrderRow o = open.get(idx);
            inv.setItem(grid[i], item(Material.PAPER, "&eОрдер &7" + o.id().substring(0, 8),
                    List.of("&7Нация: &f" + o.nation(),
                            "&7Продаёт: &f" + fmt(o.sellAmount()) + " " + o.sellCurrency(),
                            "&7Хочет: &f" + fmt(o.buyAmount()) + " " + o.buyCurrency(),
                            "&7Цена: &f" + fmt(o.price()) + " " + o.buyCurrency() + "/" + o.sellCurrency(),
                            "&7Осталось: &f" + fmt(o.remaining()),
                            "", "&eКлик — карточка ордера")));
        }
        for (int i = 36; i < 54; i++) inv.setItem(i, pane());
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&7Назад", List.of()));
        if ((page + 1) * perPage < open.size()) inv.setItem(53, item(Material.ARROW, "&7Вперёд", List.of()));
        inv.setItem(48, item(Material.GOLD_BLOCK, "&aСоздать ордер", List.of("&7Только короли")));
        inv.setItem(50, item(Material.BOOK, "&6Мои ордера", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    // ---------- Мастер создания ----------
    private Wizard wizard(Player p) {
        return wizards.computeIfAbsent(p.getUniqueId(), k -> new Wizard());
    }

    public void openCreateGive(Player p) {
        Wizard w = wizard(p);
        w.giveCur = null; w.getCur = null; w.amount = 0; w.price = 0;
        CreateHolder h = new CreateHolder(0);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер · что отдаёшь &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (!cur.tradeable()) continue;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(),
                    List.of("&7Баланс: &f" + fmt(plugin.getWallets().getBalance(p.getUniqueId(), cur.id())),
                            "&eКлик — выбрать")));
            slot += 2;
        }
        inv.setItem(22, item(Material.BARRIER, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    private void openCreateGet(Player p) {
        Wizard w = wizard(p);
        CreateHolder h = new CreateHolder(1);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер · что получаешь &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (!cur.tradeable() || cur.id().equals(w.giveCur)) continue;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(),
                    List.of("&eКлик — выбрать")));
            slot += 2;
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreateAmount(Player p) {
        Wizard w = wizard(p);
        CreateHolder h = new CreateHolder(2);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер · сколько отдаёшь &8▌"));
        h.inv = inv;
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), w.giveCur);
        inv.setItem(10, item(Material.GOLD_NUGGET, "&61", List.of()));
        inv.setItem(11, item(Material.GOLD_NUGGET, "&610", List.of()));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&664", List.of()));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6100", List.of()));
        inv.setItem(14, item(Material.GOLD_BLOCK, "&6Всё (&f" + fmt(bal) + "&6)", List.of()));
        inv.setItem(16, item(Material.PAPER, "&bСвоя сумма", List.of("&7Ввод в чат")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreatePrice(Player p) {
        Wizard w = wizard(p);
        CreateHolder h = new CreateHolder(3);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер · цена за единицу &8▌"));
        h.inv = inv;
        double rate = plugin.getConvertEngine().rate(w.giveCur, w.getCur);
        inv.setItem(10, item(Material.GOLD_NUGGET, "&6-10% (&f" + fmt(rate * 0.9) + "&6)", List.of()));
        inv.setItem(12, item(Material.GOLD_INGOT, "&6Рынок (&f" + fmt(rate) + "&6)", List.of()));
        inv.setItem(14, item(Material.GOLD_BLOCK, "&6+10% (&f" + fmt(rate * 1.1) + "&6)", List.of()));
        inv.setItem(16, item(Material.PAPER, "&bСвоя цена", List.of("&7Ввод в чат")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openCreateConfirm(Player p) {
        Wizard w = wizard(p);
        CreateHolder h = new CreateHolder(4);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер · подтверждение &8▌"));
        h.inv = inv;
        double buyTotal = w.amount * w.price;
        inv.setItem(13, item(Material.GOLD_BLOCK, "&6Сводка ордера", List.of(
                "&7Отдаёшь: &f" + fmt(w.amount) + " " + w.giveCur,
                "&7Получаешь: &f" + fmt(buyTotal) + " " + w.getCur,
                "&7Цена: &f" + fmt(w.price) + " " + w.getCur + "/" + w.giveCur,
                "", "&eКлик ✔ — выставить ордер")));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a✔ Выставить", List.of()));
        inv.setItem(15, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- Мои ордера ----------
    private void openMy(Player p, int page) {
        MyHolder h = new MyHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои ордера &8▌"));
        h.inv = inv;
        List<ExchangeOrderRow> mine = orders.myOrders(p.getUniqueId());
        int perPage = 21;
        int start = page * perPage;
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length; i++) {
            int idx = start + i;
            if (idx >= mine.size()) break;
            ExchangeOrderRow o = mine.get(idx);
            inv.setItem(grid[i], item(statusMat(o.status()), "&eОрдер &7" + o.id().substring(0, 8),
                    List.of("&7Статус: &f" + o.status(),
                            "&7" + fmt(o.sellAmount()) + " " + o.sellCurrency() + " → " + fmt(o.buyAmount()) + " " + o.buyCurrency(),
                            "&7Цена: &f" + fmt(o.price()),
                            "&eКлик — карточка")));
        }
        for (int i = 36; i < 54; i++) inv.setItem(i, pane());
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&7Назад", List.of()));
        if ((page + 1) * perPage < mine.size()) inv.setItem(53, item(Material.ARROW, "&7Вперёд", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    // ---------- Карточка ордера ----------
    private void openDetail(Player p, String orderId) {
        ExchangeOrderRow o = orders.get(orderId);
        if (o == null) { msg(p, "&cОрдер не найден"); return; }
        DetailHolder h = new DetailHolder(orderId);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Ордер &7" + orderId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        inv.setItem(13, item(Material.PAPER, "&6Ордер &7" + orderId.substring(0, 8), List.of(
                "&7Нация: &f" + o.nation(),
                "&7Продаёт: &f" + fmt(o.sellAmount()) + " " + o.sellCurrency(),
                "&7Хочет: &f" + fmt(o.buyAmount()) + " " + o.buyCurrency(),
                "&7Цена: &f" + fmt(o.price()),
                "&7Осталось: &f" + fmt(o.remaining()),
                "&7Статус: &f" + o.status())));
        boolean own = o.owner().equals(p.getUniqueId());
        if (own) {
            if ("OPEN".equals(o.status())) inv.setItem(15, item(Material.RED_CONCRETE, "&cОтменить", List.of()));
        } else {
            if ("OPEN".equals(o.status()) && isKing(p)) inv.setItem(15, item(Material.LIME_CONCRETE, "&aВзять ордер", List.of()));
        }
        inv.setItem(11, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- Клики ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof BookHolder || h instanceof CreateHolder || h instanceof MyHolder || h instanceof DetailHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (h instanceof BookHolder bh) {
            if (slot == 45 && bh.page > 0) { openBook(p, bh.page - 1); return; }
            if (slot == 53) { openBook(p, bh.page + 1); return; }
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 48) {
                if (!isKing(p)) { msg(p, "&cТолько короли наций могут выставлять ордера"); return; }
                openCreateGive(p); return;
            }
            if (slot == 50) { openMy(p, 0); return; }
            String id = orderAt(bh.page, slot);
            if (id != null) openDetail(p, id);
            return;
        }

        if (h instanceof CreateHolder ch) {
            Wizard w = wizard(p);
            if (slot == 22) {
                if (ch.step == 0) { p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
                if (ch.step == 1) { openCreateGive(p); return; }
                if (ch.step == 2) { openCreateGet(p); return; }
                if (ch.step == 3) { openCreateAmount(p); return; }
                if (ch.step == 4) { openCreatePrice(p); return; }
                return;
            }
            switch (ch.step) {
                case 0 -> {
                    String cur = currencyAt(slot);
                    if (cur != null) { w.giveCur = cur; openCreateGet(p); }
                }
                case 1 -> {
                    String cur = currencyAt(slot);
                    if (cur != null && !cur.equals(w.giveCur)) { w.getCur = cur; openCreateAmount(p); }
                }
                case 2 -> {
                    double bal = plugin.getWallets().getBalance(p.getUniqueId(), w.giveCur);
                    Double amt = switch (slot) {
                        case 10 -> 1.0; case 11 -> 10.0; case 12 -> 64.0; case 13 -> 100.0;
                        case 14 -> bal;
                        default -> null;
                    };
                    if (amt != null) { w.amount = amt; openCreatePrice(p); return; }
                    if (slot == 16) { chatField.put(p.getUniqueId(), "amount"); p.closeInventory(); msg(p, "&7Введите сумму в чат:"); }
                }
                case 3 -> {
                    double rate = plugin.getConvertEngine().rate(w.giveCur, w.getCur);
                    Double pr = switch (slot) {
                        case 10 -> rate * 0.9; case 12 -> rate; case 14 -> rate * 1.1;
                        default -> null;
                    };
                    if (pr != null) { w.price = pr; openCreateConfirm(p); return; }
                    if (slot == 16) { chatField.put(p.getUniqueId(), "price"); p.closeInventory(); msg(p, "&7Введите цену в чат:"); }
                }
                case 4 -> {
                    if (slot == 15) { p.closeInventory(); wizards.remove(p.getUniqueId()); return; }
                    if (slot == 11) {
                        String nation = nationOf(p);
                        if (nation == null || !isKing(p)) { msg(p, "&cТолько короли"); return; }
                        var res = orders.createOrder(p.getUniqueId(), nation, w.giveCur, w.getCur, w.amount, w.amount * w.price);
                        msg(p, res.success() ? "&aОрдер создан: &f" + res.orderId().substring(0, 8) : "&cОтказ: " + res.error());
                        p.closeInventory(); wizards.remove(p.getUniqueId());
                    }
                }
            }
            return;
        }

        if (h instanceof MyHolder mh) {
            if (slot == 45 && mh.page > 0) { openMy(p, mh.page - 1); return; }
            if (slot == 53) { openMy(p, mh.page + 1); return; }
            if (slot == 49) { p.closeInventory(); return; }
            String id = myAt(p, mh.page, slot);
            if (id != null) openDetail(p, id);
            return;
        }

        if (h instanceof DetailHolder dh) {
            if (slot == 11) { openBook(p, 0); return; }
            if (slot == 15) {
                ExchangeOrderRow o = orders.get(dh.orderId);
                if (o == null) { p.closeInventory(); return; }
                if (o.owner().equals(p.getUniqueId())) {
                    var r = orders.cancelOrder(dh.orderId, p.getUniqueId());
                    msg(p, r.success() ? "&aОрдер отменён, заморозка возвращена" : "&cОтказ: " + r.error());
                } else if (isKing(p)) {
                    var r = orders.takeOrder(dh.orderId, p.getUniqueId());
                    msg(p, r.success() ? "&aОрдер исполнен" : "&cОтказ: " + r.error());
                }
                p.closeInventory();
            }
        }
    }

    // ---------- Чат-захват (сумма/цена мастера) ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        double v;
        try { v = Double.parseDouble(e.getMessage().replace(",", ".").trim()); }
        catch (NumberFormatException ex) { msg(p, "&cНекорректное число"); return; }
        if (!(v > 0) || !Double.isFinite(v)) { msg(p, "&cНекорректное число"); return; }
        Wizard w = wizard(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if ("amount".equals(field)) { w.amount = v; openCreatePrice(p); }
            else { w.price = v; openCreateConfirm(p); }
        });
    }

    // ---------- helpers ----------
    private String orderAt(int page, int slot) {
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int gi = -1;
        for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
        if (gi < 0) return null;
        List<ExchangeOrderRow> open = orders.openOrders(200);
        int idx = page * 21 + gi;
        return idx < open.size() ? open.get(idx).id() : null;
    }

    private String myAt(Player p, int page, int slot) {
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int gi = -1;
        for (int i = 0; i < grid.length; i++) if (grid[i] == slot) { gi = i; break; }
        if (gi < 0) return null;
        List<ExchangeOrderRow> mine = orders.myOrders(p.getUniqueId());
        int idx = page * 21 + gi;
        return idx < mine.size() ? mine.get(idx).id() : null;
    }

    private String currencyAt(int slot) {
        int idx = (slot - 10) / 2;
        List<Currency> trade = new ArrayList<>();
        for (Currency cur : plugin.getCurrencies().all()) if (cur.tradeable()) trade.add(cur);
        return idx >= 0 && idx < trade.size() ? trade.get(idx).id() : null;
    }

    private Material iconOf(String id) {
        switch (id.toUpperCase(Locale.ROOT)) {
            case "GLD": return Material.GOLD_INGOT;
            case "RAS": return Material.SUNFLOWER;
            case "VLR": return Material.GOLDEN_HELMET;
            default: return Material.GOLD_NUGGET;
        }
    }
    private Material statusMat(String status) {
        switch (status) {
            case "OPEN": return Material.LIME_CONCRETE;
            case "MATCHED": return Material.BLUE_CONCRETE;
            default: return Material.RED_CONCRETE;
        }
    }
    private String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
