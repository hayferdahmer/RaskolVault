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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI кошелька (1.2.3-b): + кнопка «Перевод» (слот 32), перевод из рук в руки ≤6 блоков.
 * /rv pay удалён — переводы только отсюда.
 */
public final class WalletGui {

    public static final Map<UUID, String[]> CHAT_CAPTURE = new ConcurrentHashMap<>(); // {mode, ...}

    public static final class Holder implements InventoryHolder {
        final String page;
        final String fromId;
        final String toId;
        final double amount;
        Holder(String page, String fromId, String toId, double amount) {
            this.page = page; this.fromId = fromId; this.toId = toId; this.amount = amount;
        }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private WalletGui() {}

    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(c(name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(c(x));
            meta.setLore(l);
            s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    public static void openMain(RaskolVault plugin, Player p) {
        Holder h = new Holder("main", null, null, 0);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кошелёк &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(p.getUniqueId(), cur.id());
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(),
                    List.of("&7Баланс: &f" + fmt(bal) + " " + cur.id(),
                            "&7Цена: &f" + String.format(Locale.ROOT, "%.4f", plugin.getReserveBank().priceOf(cur)) + " GLD",
                            "&eКлик — конвертировать из " + cur.id())));
            slot += 2;
        }
        inv.setItem(29, item(Material.EMERALD, "&aКонверт", List.of("&7Обмен валют")));
        inv.setItem(31, item(Material.MAP, "&6Курсы", List.of()));
        inv.setItem(32, item(Material.GOLD_INGOT, "&eПеревод", List.of("&7Передать из рук в руки (≤6 блоков)", "&7Клик → ник в чат")));
        inv.setItem(33, item(Material.CLOCK, "&bИстория", List.of()));
        if (isKing(plugin, p)) inv.setItem(35, item(Material.GOLDEN_CHESTPLATE, "&6Кабинет", List.of()));
        inv.setItem(40, item(Material.WRITABLE_BOOK, "&6Кодекс", List.of()));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    public static void openConvertFrom(RaskolVault plugin, Player p) {
        Holder h = new Holder("cfrom", null, null, 0);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Конверт: из &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(),
                    List.of("&7Баланс: &f" + fmt(plugin.getWallets().getBalance(p.getUniqueId(), cur.id())))));
            slot += 2;
        }
        inv.setItem(22, item(Material.BARRIER, "&cОтмена", List.of()));
        p.openInventory(inv);
    }

    public static void openConvertTo(RaskolVault plugin, Player p, String fromId) {
        Holder h = new Holder("cto", fromId, null, 0);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Конверт: во &8▌"));
        h.inv = inv;
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.id().equals(fromId)) continue;
            inv.setItem(slot, item(iconOf(cur.id()), "&6" + cur.displayName(),
                    List.of("&7Курс: &f" + String.format(Locale.ROOT, "%.4f", plugin.getConvertEngine().rate(fromId, cur.id())))));
            slot += 2;
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    public static void openConvertAmount(RaskolVault plugin, Player p, String fromId, String toId) {
        Holder h = new Holder("camt", fromId, toId, 0);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Конверт: сумма &8▌"));
        h.inv = inv;
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), fromId);
        inv.setItem(10, item(Material.GOLD_NUGGET, "&61", List.of()));
        inv.setItem(11, item(Material.GOLD_NUGGET, "&610", List.of()));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&664", List.of()));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6100", List.of()));
        inv.setItem(14, item(Material.GOLD_BLOCK, "&6Всё (&f" + fmt(bal) + "&6)", List.of()));
        inv.setItem(16, item(Material.PAPER, "&bСвоя сумма", List.of("&7Ввод в чат")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    public static void openConvertConfirm(RaskolVault plugin, Player p, String fromId, String toId, double amount) {
        Holder h = new Holder("cconf", fromId, toId, amount);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Подтверждение &8▌"));
        h.inv = inv;
        var q = plugin.getConvertEngine().quote(p.getUniqueId(), fromId, toId, amount);
        inv.setItem(13, item(Material.GOLD_BLOCK, "&6Сводка", q.map(x -> List.of(
                "&7Отдаёшь: &f" + fmt(x.amount()) + " " + x.fromId(),
                "&7Получаешь: &f" + fmt(x.net()) + " " + x.toId(),
                "&7Курс: &f" + String.format(Locale.ROOT, "%.4f", x.rate()),
                "&7Комиссия: &f" + fmt(x.feeBase() + x.feeTax()),
                "", "&eКлик ✔ — исполнить")).orElse(List.of("&cНедоступно"))));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a✔ Исполнить", List.of()));
        inv.setItem(15, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    public static void openCodex(RaskolVault plugin, Player p, int page) {
        Holder h = new Holder("codex", null, null, page);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Кодекс правителя &8▌"));
        h.inv = inv;
        String[][] pages = {
            {"&6I. Резерв и покрытие", "&7Покрытие = резерв/(эмиссия×паритет)", "&7<100% — валюта дешевеет", "&7<50% — КРИЗИС"},
            {"&6II. Паритет", "&7Диапазон 0.5..2.0", "&7Цена = min(паритет, резерв/эмиссия)"},
            {"&6III. Налоги", "&7Конверсионный/торговый/рыночный", "&70..5%, в казну нации"},
            {"&6IV. Торговая политика", "&7whitelist/blacklist валют", "&7Кабинет → Торговля"},
            {"&6V. Облигации", "&7Королевская рента / Заёмная грамота", "&7/rv bond"}
        };
        int idx = Math.max(0, Math.min(page, pages.length - 1));
        inv.setItem(13, item(Material.WRITABLE_BOOK, pages[idx][0], java.util.Arrays.asList(java.util.Arrays.copyOfRange(pages[idx], 1, pages[idx].length))));
        if (idx > 0) inv.setItem(18, item(Material.ARROW, "&7Назад", List.of()));
        if (idx < pages.length - 1) inv.setItem(26, item(Material.ARROW, "&7Вперёд", List.of()));
        inv.setItem(22, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    private static boolean isKing(RaskolVault plugin, Player p) {
        if (!plugin.getTownyHook().isAvailable()) return false;
        String n = plugin.getTownyHook().nationOf(p.getUniqueId());
        return n != null && plugin.getTownyHook().isKing(p.getUniqueId(), n);
    }

    private static Material iconOf(String id) {
        switch (id.toUpperCase(Locale.ROOT)) {
            case "GLD": return Material.GOLD_INGOT;
            case "RAS": return Material.SUNFLOWER;
            case "VLR": return Material.GOLDEN_HELMET;
            default: return Material.GOLD_NUGGET;
        }
    }
}
