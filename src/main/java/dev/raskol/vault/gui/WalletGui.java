// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кошелёк как банковское приложение (1.2.1-fix).
 * Секции: ВАЛЮТЫ (глобальная сверху, национальные ниже) / ОБМЕН / КУРСЫ /
 * ПЕРЕВОД (игроки ≤6 блоков) / ИСТОРИЯ / СПРАВКА.
 * Все кнопки в строгой сетке, у каждой lore-описание, «Назад» на каждой странице.
 * Кабинет и команда /rv rates из кошелька убраны.
 */
public final class WalletGui {

    public static final Map<UUID, String[]> CHAT_CAPTURE = new ConcurrentHashMap<>();

    public static final class Holder implements InventoryHolder {
        public final String page;
        public final String fromId;   // для конверта: исходная валюта
        public final String toId;     // для конверта: целевая валюта; для перевода: ник цели
        public final double amount;
        public final int pageIndex;
        Holder(String page, String fromId, String toId, double amount, int pageIndex) {
            this.page = page; this.fromId = fromId; this.toId = toId; this.amount = amount; this.pageIndex = pageIndex;
        }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private WalletGui() {}

    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(c(name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(c(x));
            meta.setLore(l);
            // Скрываем ванильные атрибуты/подсказки (убирает «+2 Броня / Когда надето»)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_DYE);
            s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }

    @SuppressWarnings("deprecation")
    private static ItemStack playerHead(String name, List<String> lore) {
        ItemStack s = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(c("&6" + name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(c(x));
            meta.setLore(l);
            meta.setOwner(name);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            s.setItemMeta(meta);
        }
        return s;
    }

    private static void frame(Inventory inv) {
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
    }
    private static void back(Inventory inv) { inv.setItem(49, item(Material.ARROW, "&7Назад", List.of("&7Вернуться в кошелёк"))); }

    // ---------- ГЛАВНАЯ ----------
    public static void openMain(RaskolVault plugin, Player p) {
        Holder h = new Holder("main", null, null, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кошелёк &8▌"));
        h.inv = inv;
        frame(inv);

        double glbBal = plugin.getWallets().getBalance(p.getUniqueId(), plugin.getCurrencies().globalId());
        inv.setItem(4, item(Material.GOLD_BLOCK, "&6RaskolVault · Банк", List.of(
                "&7Владелец: &f" + p.getName(),
                "&7Глобальный баланс: &f" + fmt(glbBal) + " GLD",
                "&7Разделы: валюты / обмен / курсы / перевод / история"))));

        // ВАЛЮТЫ: глобальная сверху (центр), национальные ниже
        Currency global = plugin.getCurrencies().get(plugin.getCurrencies().globalId()).orElse(null);
        if (global != null) {
            inv.setItem(13, item(iconOf(global.id()), "&6" + global.displayName() + " &7(" + global.id() + ")", List.of(
                    "&7Баланс: &f" + fmt(plugin.getWallets().getBalance(p.getUniqueId(), global.id())) + " " + global.id(),
                    "&7Цена: &f1.0000 GLD &7(глобальная)",
                    "&eКлик — конвертировать из " + global.id())));
        }
        int natSlot = 20;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() != CurrencyType.NATIONAL) continue;
            if (natSlot > 24) break;
            inv.setItem(natSlot, item(iconOf(cur.id()), "&6" + cur.displayName() + " &7(" + cur.id() + ")", List.of(
                    "&7Баланс: &f" + fmt(plugin.getWallets().getBalance(p.getUniqueId(), cur.id())) + " " + cur.id(),
                    "&7Цена: &f" + String.format(Locale.ROOT, "%.4f", plugin.getReserveBank().priceOf(cur)) + " GLD",
                    "&eКлик — конвертировать из " + cur.id())));
            natSlot += 2;
        }

        // МЕНЮ (строгая сетка)
        inv.setItem(27, item(Material.EMERALD, "&aОбмен валют", List.of("&7Поменять одну валюту на другую", "&eКлик — открыть")));
        inv.setItem(29, item(Material.MAP, "&6Курс валют", List.of("&7Таблица курсов и комиссий", "&eКлик — открыть")));
        inv.setItem(31, item(Material.GOLD_INGOT, "&eПеревод", List.of("&7Передать деньги игроку рядом (≤6 блоков)", "&eКлик — открыть")));
        inv.setItem(33, item(Material.CLOCK, "&bИстория", List.of("&7Последние операции по кошельку", "&eКлик — открыть")));
        inv.setItem(35, item(Material.WRITABLE_BOOK, "&6Справка", List.of("&7Как пользоваться кошельком", "&eКлик — открыть")));

        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    // ---------- ОБМЕН ----------
    public static void openConvertFrom(RaskolVault plugin, Player p) {
        Holder h = new Holder("cfrom", null, null, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Обмен: из &8▌"));
        h.inv = inv;
        frame(inv);
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (!cur.tradeable()) continue;
            if (slot > 34) break;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(), List.of(
                    "&7Баланс: &f" + fmt(plugin.getWallets().getBalance(p.getUniqueId(), cur.id())) + " " + cur.id(),
                    "&eКлик — выбрать исходную")));
            slot += 2;
        }
        back(inv);
        p.openInventory(inv);
    }

    public static void openConvertTo(RaskolVault plugin, Player p, String fromId) {
        Holder h = new Holder("cto", fromId, null, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Обмен: во &8▌"));
        h.inv = inv;
        frame(inv);
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (!cur.tradeable() || cur.id().equals(fromId)) continue;
            if (slot > 34) break;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(), List.of(
                    "&7Курс: &f" + String.format(Locale.ROOT, "%.4f", plugin.getConvertEngine().rate(fromId, cur.id())),
                    "&eКлик — выбрать целевую")));
            slot += 2;
        }
        back(inv);
        p.openInventory(inv);
    }

    public static void openConvertAmount(RaskolVault plugin, Player p, String fromId, String toId) {
        Holder h = new Holder("camt", fromId, toId, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Обмен: сумма &8▌"));
        h.inv = inv;
        frame(inv);
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), fromId);
        inv.setItem(10, item(Material.GOLD_NUGGET, "&61", List.of("&eКлик — 1")));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&610", List.of("&eКлик — 10")));
        inv.setItem(14, item(Material.GOLD_NUGGET, "&664", List.of("&eКлик — 64")));
        inv.setItem(16, item(Material.GOLD_INGOT, "&6100", List.of("&eКлик — 100")));
        inv.setItem(18, item(Material.GOLD_BLOCK, "&6Всё (&f" + fmt(bal) + "&6)", List.of("&eКлик — весь баланс")));
        inv.setItem(22, item(Material.PAPER, "&bСвоя сумма", List.of("&eКлик → ввод в чат")));
        back(inv);
        p.openInventory(inv);
    }

    public static void openConvertConfirm(RaskolVault plugin, Player p, String fromId, String toId, double amount) {
        Holder h = new Holder("cconf", fromId, toId, amount, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Обмен: подтверждение &8▌"));
        h.inv = inv;
        frame(inv);
        var q = plugin.getConvertEngine().quote(p.getUniqueId(), fromId, toId, amount);
        inv.setItem(13, item(Material.GOLD_BLOCK, "&6Сводка обмена", q.map(x -> List.of(
                "&7Отдаёшь: &c" + fmt(x.amount()) + " " + x.fromId(),
                "&7Получаешь: &a" + fmt(x.net()) + " " + x.toId(),
                "&7Курс: &f" + String.format(Locale.ROOT, "%.4f", x.rate()),
                "&7Комиссия: &f" + fmt(x.feeBase() + x.feeTax()),
                "", "&eКлик ✔ — исполнить")).orElse(List.of("&cНедоступно"))));
        inv.setItem(11, item(Material.RED_CONCRETE, "&cОтмена", List.of("&7Вернуться к сумме")));
        inv.setItem(15, item(Material.LIME_CONCRETE, "&aПодтвердить", List.of("&eКлик — исполнить обмен")));
        back(inv);
        p.openInventory(inv);
    }

    // ---------- КУРСЫ ----------
    public static void openRates(RaskolVault plugin, Player p) {
        Holder h = new Holder("rates", null, null, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Курс валют &8▌"));
        h.inv = inv;
        frame(inv);
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int i = 0;
        for (Currency a : plugin.getCurrencies().all()) {
            for (Currency b : plugin.getCurrencies().all()) {
                if (a.id().equals(b.id()) || i >= grid.length) continue;
                double r = plugin.getConvertEngine().rate(a.id(), b.id());
                inv.setItem(grid[i], item(Material.PAPER, "&6" + a.id() + " → " + b.id(), List.of(
                        "&7Курс: &f" + String.format(Locale.ROOT, "%.4f", r),
                        "&7Комиссия: &f" + String.format(Locale.ROOT, "%.1f%%",
                                plugin.getRates().feeFor(a.id(), b.id()) * 100))));
                i++;
            }
        }
        for (int k = i; k < grid.length; k++) inv.setItem(grid[k], pane());
        back(inv);
        p.openInventory(inv);
    }

    // ---------- ПЕРЕВОД ----------
    public static void openTransfer(RaskolVault plugin, Player p) {
        Holder h = new Holder("transfer", null, null, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Перевод: кому &8▌"));
        h.inv = inv;
        frame(inv);
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int i = 0;
        for (Player other : p.getWorld().getPlayers()) {
            if (other.getUniqueId().equals(p.getUniqueId())) continue;
            if (p.getLocation().distance(other.getLocation()) > 6.0D) continue;
            if (i >= grid.length) break;
            inv.setItem(grid[i], playerHead(other.getName(), List.of(
                    "&7Дистанция: &f" + String.format(Locale.ROOT, "%.1f", p.getLocation().distance(other.getLocation())) + " блоков",
                    "&eКлик — выбрать получателя")));
            i++;
        }
        for (int k = i; k < grid.length; k++) inv.setItem(grid[k], pane());
        inv.setItem(40, item(Material.PAPER, "&bВвести ник", List.of("&7Если игроков рядом много,", "&7введите ник вручную", "&eКлик → ввод в чат")));
        back(inv);
        p.openInventory(inv);
    }

    public static void openTransferAmount(RaskolVault plugin, Player p, String targetName) {
        Holder h = new Holder("transferamt", null, targetName, 0, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Перевод: сумма &8▌"));
        h.inv = inv;
        frame(inv);
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), plugin.getCurrencies().globalId());
        inv.setItem(10, item(Material.GOLD_NUGGET, "&61", List.of("&eКлик — 1")));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&610", List.of("&eКлик — 10")));
        inv.setItem(14, item(Material.GOLD_NUGGET, "&664", List.of("&eКлик — 64")));
        inv.setItem(16, item(Material.GOLD_INGOT, "&6100", List.of("&eКлик — 100")));
        inv.setItem(18, item(Material.GOLD_BLOCK, "&6Всё (&f" + fmt(bal) + "&6)", List.of("&eКлик — весь баланс")));
        inv.setItem(22, item(Material.PAPER, "&bСвоя сумма", List.of("&eКлик → ввод в чат")));
        back(inv);
        p.openInventory(inv);
    }

    public static void openTransferConfirm(RaskolVault plugin, Player p, String targetName, double amount) {
        Holder h = new Holder("transferconf", null, targetName, amount, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Перевод: подтверждение &8▌"));
        h.inv = inv;
        frame(inv);
        inv.setItem(13, item(Material.GOLD_INGOT, "&6Сводка перевода", List.of(
                "&7Получатель: &f" + targetName,
                "&7Сумма: &f" + fmt(amount) + " GLD",
                "&7Условие: получатель в ≤6 блоков",
                "", "&eКлик ✔ — передать")));
        inv.setItem(11, item(Material.RED_CONCRETE, "&cОтмена", List.of("&7Вернуться к сумме")));
        inv.setItem(15, item(Material.LIME_CONCRETE, "&aПодтвердить", List.of("&eКлик — передать")));
        back(inv);
        p.openInventory(inv);
    }

    // ---------- ИСТОРИЯ ----------
    public static void openHistory(RaskolVault plugin, Player p, int page) {
        Holder h = new Holder("history", null, null, 0, page);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 История &8▌"));
        h.inv = inv;
        var txs = plugin.getLedger().queryTransactions(p.getUniqueId(), 200);
        int perPage = 45;
        int start = page * perPage;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (int i = 0; i < perPage && (start + i) < txs.size(); i++) {
            var tx = txs.get(start + i);
            boolean in = tx.to() != null && tx.to().equals(p.getUniqueId());
            inv.setItem(i, item(Material.PAPER, (in ? "&a+" : "&c-") + fmt(tx.amount()) + " " + tx.currencyId(), List.of(
                    "&7" + sdf.format(new java.util.Date(tx.timestampMillis())),
                    "&7" + tx.type(),
                    "&7" + tx.reason())));
        }
        for (int i = Math.min(perPage, txs.size() - start); i < 45; i++) inv.setItem(i, pane());
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&7Назад (стр.)", List.of()));
        if ((page + 1) * perPage < txs.size()) inv.setItem(53, item(Material.ARROW, "&7Вперёд (стр.)", List.of()));
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of("&7Вернуться в кошелёк")));
        p.openInventory(inv);
    }

    // ---------- СПРАВКА ----------
    private static final String[][] GUIDE = {
            {"&6Кошелёк", "&7Показывает все ваши валюты.", "&7Глобальная GLD сверху, национальные ниже.", "&7Клик по валюте — быстрый обмен из неё."},
            {"&6Перевод", "&7Передаёт GLD игроку рядом (≤6 блоков).", "&7Выберите голову игрока или введите ник.", "&7Реалистично: из рук в руки."},
            {"&6Обмен", "&7Меняет одну валюту на другую по курсу.", "&7Комиссия сжигается, налог идёт в казну.", "&7Курс = ценаA / ценаB."},
            {"&6Курсы", "&7Таблица всех пар и комиссий.", "&7Комиссия = база + налог нации."},
            {"&6История и безопасность", "&7Все операции логируются.", "&7Переводы только рядом, обмен с комиссией.", "&7Кошелёк защищён от дюпов."}
    };

    public static void openGuide(RaskolVault plugin, Player p, int page) {
        int idx = Math.max(0, Math.min(page, GUIDE.length - 1));
        Holder h = new Holder("guide", null, null, 0, idx);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Справка &8▌"));
        h.inv = inv;
        frame(inv);
        List<String> lore = new ArrayList<>();
        for (int i = 1; i < GUIDE[idx].length; i++) lore.add(GUIDE[idx][i]);
        lore.add("");
        lore.add("&7стр. " + (idx + 1) + "/" + GUIDE.length);
        inv.setItem(13, item(Material.WRITABLE_BOOK, GUIDE[idx][0], lore));
        if (idx > 0) inv.setItem(45, item(Material.ARROW, "&7Назад (стр.)", List.of()));
        if (idx < GUIDE.length - 1) inv.setItem(53, item(Material.ARROW, "&7Вперёд (стр.)", List.of()));
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of("&7Вернуться в кошелёк")));
        p.openInventory(inv);
    }

    private static Material iconOf(String id) {
        switch (id.toUpperCase(Locale.ROOT)) {
            case "GLD": return Material.GOLD_INGOT;
            case "RAS": return Material.SUNFLOWER;
            case "VLR": return Material.GOLDEN_HELMET;
            default: return Material.GOLD_NUGGET;
        }
    }

    private enum CurrencyType { NATIONAL }
}
