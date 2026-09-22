// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.tax.TaxService;
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
 * Кабинет государя (1.2.4): + секция «Доли» (эмиссия/ужиток).
 */
public final class CabinetGui implements Listener {

    public static final double CELL_GLD = 1000.0D;
    public static final Map<UUID, String[]> CHAT_CAPTURE = new ConcurrentHashMap<>();

    public static final class Holder implements InventoryHolder {
        final String nation; final String page;
        Holder(String nation, String page) { this.nation = nation; this.page = page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final RaskolVault plugin;
    public CabinetGui(RaskolVault plugin) { this.plugin = plugin; }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(Player p, String raw) { p.sendMessage(c(plugin.getMessages().prefix() + raw)); }
    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) { meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> l = new ArrayList<>(); for (String x : lore) l.add(ChatColor.translateAlternateColorCodes('&', x));
            meta.setLore(l); s.setItemMeta(meta); }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", v * 100); }

    public void openHome(Player king, String nation) {
        Holder h = new Holder(nation, "home");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кабинет " + nation + " &8▌"));
        h.inv = inv;
        ReserveBank bank = plugin.getReserveBank();
        Currency nat = nationalCurrency(nation);
        double reserve = bank.reserveOf(nation);
        double totalShares = plugin.getShareService().totalGrams(nation);
        inv.setItem(4, item(Material.GOLDEN_APPLE, "&6Обзор", List.of(
                "&7Резерв: &f" + fmt(reserve) + " GLD",
                nat == null ? "&cНет национальной валюты" : "&7Эмиссия: &f" + fmt(bank.supplyOf(nat.id())),
                "&7Доли эмитированы: &f" + fmt(totalShares) + " золотников")));
        inv.setItem(20, item(Material.GOLD_BLOCK, "&6Резерв", List.of()));
        inv.setItem(21, item(Material.COMPASS, "&6Монетарная", List.of()));
        inv.setItem(22, item(Material.TRIPWIRE_HOOK, "&6Налоги", List.of()));
        inv.setItem(23, item(Material.PAPER, "&6Доли и Ужиток", List.of("&7Эмиссия, держатели, раздача Ужитка")));
        inv.setItem(24, item(Material.CHEST, "&6Торговая политика", List.of()));
        inv.setItem(30, item(Material.WRITABLE_BOOK, "&6Отчёты", List.of()));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        king.openInventory(inv);
    }

    private void openMonetary(Player king, String nation) {
        Holder h = new Holder(nation, "monetary");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Монетарная &8▌"));
        h.inv = inv;
        ReserveBank bank = plugin.getReserveBank();
        Currency nat = nationalCurrency(nation);
        if (nat == null) {
            inv.setItem(13, item(Material.GOLD_BLOCK, "&aВыпустить валюту",
                    List.of("&7Создать национальную валюту", "&7ID = первые 3 буквы нации")));
        } else {
            double parity = bank.parityOf(nation);
            inv.setItem(10, item(Material.REDSTONE, "&cПаритет −0.05", List.of("&7Текущий: &f" + fmt(parity))));
            inv.setItem(12, item(Material.NETHER_STAR, "&6Паритет &f" + fmt(parity), List.of()));
            inv.setItem(14, item(Material.GLOWSTONE_DUST, "&aПаритет +0.05", List.of("&7Текущий: &f" + fmt(parity))));
            inv.setItem(16, item(Material.EMERALD, "&aМинт", List.of("&7Чат-ввод суммы")));
        }
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    private void openTax(Player king, String nation) {
        Holder h = new Holder(nation, "tax");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Налоги &8▌"));
        h.inv = inv;
        TaxService tax = plugin.getTaxService();
        inv.setItem(10, item(Material.GOLD_NUGGET, "&6Конверсионный &f" + pct(tax.getConvertRate(nation)), List.of("&eКлик — задать %")));
        inv.setItem(12, item(Material.ENDER_CHEST, "&6Торговый &f" + pct(tax.getExchangeRate(nation)), List.of("&eКлик — задать %")));
        inv.setItem(14, item(Material.CHEST, "&6Рыночный &f" + pct(tax.getMarketRate(nation)), List.of("&eКлик — задать %")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    private void openTrade(Player king, String nation) {
        Holder h = new Holder(nation, "trade");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Торговая политика &8▌"));
        h.inv = inv;
        var pol = plugin.getTradePolicy().policyOf(nation);
        inv.setItem(11, item(Material.LIME_CONCRETE, "&aРежим: &f" + pol.mode(), List.of("&eКлик — переключить")));
        inv.setItem(15, item(Material.PAPER, "&6Список", List.of("&7Чат-ввод через запятую")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    private void openReserve(Player king, String nation) {
        Holder h = new Holder(nation, "reserve");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Резерв &8▌"));
        h.inv = inv;
        double reserve = plugin.getReserveBank().reserveOf(nation);
        int cells = (int) Math.min(27L, (long) (reserve / CELL_GLD));
        for (int i = 0; i < cells; i++) inv.setItem(i, item(Material.GOLD_BLOCK, "&6Ячейка", List.of("&71000 GLD")));
        for (int i = cells; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(27, item(Material.GOLD_BLOCK, "&aДепозит", List.of("&eКлик → сумма в чат")));
        inv.setItem(28, item(Material.HOPPER, "&cВывод", List.of("&eКлик → сумма в чат")));
        for (int i = 36; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        String nation = h.nation;
        switch (h.page) {
            case "home" -> {
                if (slot == 49) { p.closeInventory(); return; }
                if (slot == 20) { openReserve(p, nation); return; }
                if (slot == 21) { openMonetary(p, nation); return; }
                if (slot == 22) { openTax(p, nation); return; }
                if (slot == 23) { plugin.getShareGui().openMarket(p, nation); return; }
                if (slot == 24) { openTrade(p, nation); return; }
                if (slot == 30) { openTax(p, nation); return; }
            }
            case "monetary" -> {
                if (slot == 22) { openHome(p, nation); return; }
                Currency nat = nationalCurrency(nation);
                if (nat == null) { if (slot == 13) { issueCurrency(p, nation); } return; }
                if (slot == 10) { adjParity(p, nation, -0.05); return; }
                if (slot == 14) { adjParity(p, nation, +0.05); return; }
                if (slot == 16) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"mint", nation}); msg(p, "&7Сумма минта:"); }
            }
            case "tax" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 10) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-convert", nation}); msg(p, "&7Ставка % (0..5):"); }
                if (slot == 12) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-exchange", nation}); msg(p, "&7Ставка % (0..5):"); }
                if (slot == 14) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-market", nation}); msg(p, "&7Ставка % (0..5):"); }
            }
            case "trade" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 11) { cycleMode(p, nation); openTrade(p, nation); }
                if (slot == 15) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"trade-list", nation}); msg(p, "&7Список валют через запятую:"); }
            }
            case "reserve" -> {
                if (slot == 49) { openHome(p, nation); return; }
                if (slot == 27) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-dep", nation}); msg(p, "&7Сумма депозита:"); }
                if (slot == 28) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-with", nation}); msg(p, "&7Сумма вывода:"); }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String[] ctx = CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String nation = ctx[1];
            switch (ctx[0]) {
                case "mint" -> {
                    double amt = parse(text);
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); return; }
                    Currency nat = nationalCurrency(nation);
                    if (nat == null) { msg(p, "&cНет валюты"); return; }
                    boolean ok = plugin.getReserveBank().mint(nation, nat.id(), amt, "cabinet");
                    msg(p, ok ? "&aЭмитировано &f" + fmt(amt) + " " + nat.id() : "&cОтказ (покрытие)");
                    openMonetary(p, nation);
                }
                case "tax-convert", "tax-exchange", "tax-market" -> {
                    double v = parse(text);
                    if (v < 0 || v > 5) { msg(p, "&c0..5%"); return; }
                    double rate = v / 100.0D;
                    if (ctx[0].endsWith("convert")) plugin.getTaxService().setConvertRate(nation, rate);
                    else if (ctx[0].endsWith("exchange")) plugin.getTaxService().setExchangeRate(nation, rate);
                    else plugin.getTaxService().setMarketRate(nation, rate);
                    msg(p, "&aНалог установлен: &f" + pct(rate));
                    openTax(p, nation);
                }
                case "trade-list" -> {
                    List<String> list = new ArrayList<>();
                    for (String s : text.split(",")) if (!s.isBlank()) list.add(s.trim().toUpperCase(Locale.ROOT));
                    plugin.getTradePolicy().setList(nation, list);
                    msg(p, "&aСписок обновлён: &f" + String.join(",", list));
                    openTrade(p, nation);
                }
                case "reserve-dep", "reserve-with" -> {
                    double amt = parse(text);
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); return; }
                    boolean ok = ctx[0].equals("reserve-dep")
                            ? plugin.getReserveBank().depositToReserve(p.getUniqueId(), nation, amt, "cabinet")
                            : plugin.getReserveBank().withdrawFromReserve(p.getUniqueId(), nation, amt, "cabinet");
                    msg(p, ok ? "&aОперация выполнена" : "&cОтказ");
                    openReserve(p, nation);
                }
            }
        });
    }

    private void issueCurrency(Player p, String nation) {
        String base = nation.replaceAll("[^A-Za-zА-Яа-я]", "");
        String id = (base.length() >= 3 ? base.substring(0, 3) : base).toUpperCase(Locale.ROOT);
        String candidate = id;
        int suffix = 0;
        while (plugin.getCurrencies().get(candidate).isPresent()) { suffix++; candidate = id + suffix; }
        Currency cur = new dev.raskol.vault.api.currency.Currency(candidate,
                "Динар " + nation, candidate, CurrencyType.NATIONAL, nation, 2, true);
        plugin.getCurrencies().addCurrency(cur);
        plugin.getLedger().upsertCurrency(cur);
        msg(p, "&aВыпущена валюта &f" + candidate + " &aдля нации " + nation);
        openMonetary(p, nation);
    }

    private void cycleMode(Player p, String nation) {
        var cur = plugin.getTradePolicy().policyOf(nation);
        var next = switch (cur.mode()) {
            case NONE -> dev.raskol.vault.trade.TradePolicyService.Mode.WHITELIST;
            case WHITELIST -> dev.raskol.vault.trade.TradePolicyService.Mode.BLACKLIST;
            case BLACKLIST -> dev.raskol.vault.trade.TradePolicyService.Mode.NONE;
        };
        plugin.getTradePolicy().setMode(nation, next);
        msg(p, "&aРежим: &f" + next);
    }

    private void adjParity(Player p, String nation, double delta) {
        double cur = plugin.getReserveBank().parityOf(nation);
        double next = cur + delta;
        boolean ok = plugin.getReserveBank().setParity(nation, next, "cabinet");
        msg(p, ok ? "&aПаритет: &f" + fmt(next) : "&cДиапазон 0.5..2.0");
        openMonetary(p, nation);
    }

    private Currency nationalCurrency(String nation) {
        for (Currency cur : plugin.getCurrencies().all())
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) return cur;
        return null;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }
}
