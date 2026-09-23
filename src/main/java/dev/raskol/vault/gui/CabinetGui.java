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
 * Кабинет государя (1.2.4.1-fix): строгая сетка, lore-описания у каждой кнопки,
 * «Назад» на каждой странице, страницы: Резерв / Монетарная / Налоги / Торговля /
 * Советник / Гайд. Доли открывают ShareGui.
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
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> l = new ArrayList<>();
            for (String x : lore) l.add(ChatColor.translateAlternateColorCodes('&', x));
            meta.setLore(l); s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", v * 100); }

    // ---------- ГЛАВНАЯ ----------
    public void openHome(Player king, String nation) {
        Holder h = new Holder(nation, "home");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кабинет · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());

        ReserveBank bank = plugin.getReserveBank();
        Currency nat = nationalCurrency(nation);
        double reserve = bank.reserveOf(nation);
        double coverage = nat == null ? 0 : bank.coverageOf(nation, nat.id());
        inv.setItem(4, item(Material.GOLDEN_APPLE, "&6Государство " + nation, List.of(
                "&7Резерв: &f" + fmt(reserve) + " GLD",
                nat == null ? "&cНет национальной валюты" : "&7Эмиссия: &f" + fmt(bank.supplyOf(nat.id())),
                "&7Покрытие: &f" + pct(coverage),
                "&7Паритет: &f" + fmt(bank.parityOf(nation)))));

        inv.setItem(10, item(Material.GOLD_BLOCK, "&6Резерв", List.of(
                "&7Золотой запас нации.",
                "&7Депозит/вывод, ячейки, покрытие.",
                "&eКлик — открыть")));
        inv.setItem(12, item(Material.COMPASS, "&6Монетарная политика", List.of(
                "&7Паритет (курс) и эмиссия (минт).",
                "&eКлик — открыть")));
        inv.setItem(14, item(Material.GOLD_NUGGET, "&6Доли и Ужиток", List.of(
                "&7Рынок Долей, эмиссия, выкуп, Ужиток.",
                "&eКлик — открыть")));
        inv.setItem(16, item(Material.CHEST, "&6Торговая политика", List.of(
                "&7Разрешить/запретить валюты в обороте.",
                "&eКлик — открыть")));
        inv.setItem(20, item(Material.BOOK, "&6Советник", List.of(
                "&7Прогноз: к чему приведут действия",
                "&7при текущей экономике.",
                "&eКлик — открыть")));
        inv.setItem(22, item(Material.TRIPWIRE_HOOK, "&6Налоги", List.of(
                "&7Конверсионный / торговый / рыночный.",
                "&eКлик — открыть")));
        inv.setItem(24, item(Material.WRITABLE_BOOK, "&6Гайд правителя", List.of(
                "&7Инструкции по каждой секции.",
                "&eКлик — открыть")));

        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        king.openInventory(inv);
    }

    // ---------- РЕЗЕРВ ----------
    private void openReserve(Player king, String nation) {
        Holder h = new Holder(nation, "reserve");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Резерв · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        ReserveBank bank = plugin.getReserveBank();
        double reserve = bank.reserveOf(nation);
        Currency nat = nationalCurrency(nation);
        double coverage = nat == null ? 0 : bank.coverageOf(nation, nat.id());
        inv.setItem(4, item(Material.GOLD_BLOCK, "&6Золотой резерв", List.of(
                "&7В казне: &f" + fmt(reserve) + " GLD",
                "&7Покрытие эмиссии: &f" + pct(coverage),
                "&7Лимит вывода в сутки: &f" + fmt(bank.dailyWithdrawLimit(nation)) + " GLD")));

        int cells = (int) Math.min(14, reserve / CELL_GLD);
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25};
        for (int i = 0; i < grid.length; i++) {
            inv.setItem(grid[i], i < cells
                    ? item(Material.GOLD_BLOCK, "&6Ячейка резерва", List.of("&71000 GLD золота"))
                    : item(Material.BLACK_STAINED_GLASS_PANE, "&8пусто", List.of("&7Ячейка пуста")));
        }
        inv.setItem(27, item(Material.GOLD_INGOT, "&aДепозит в резерв", List.of(
                "&7Внести своё личное золото в казну.",
                "&7Повышает покрытие и укрепляет валюту.",
                "&eКлик → ввод суммы в чат")));
        inv.setItem(29, item(Material.HOPPER, "&cВывод из резерва", List.of(
                "&7Взять золото из казны себе.",
                "&7Лимит 25% резерва в сутки.",
                "&eКлик → ввод суммы в чат")));
        inv.setItem(33, item(Material.PAPER, "&6Справка по резерву", List.of(
                "&7Резерв обеспечивает национальную валюту.",
                "&7Покрытие = резерв / (эмиссия × паритет).",
                "&7<100% — валюта дешевеет; <50% — КРИЗИС.")));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- МОНЕТАРНАЯ ----------
    private void openMonetary(Player king, String nation) {
        Holder h = new Holder(nation, "monetary");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Монетарная · " + nation + " &8▌"));
        h.inv = inv;
        ReserveBank bank = plugin.getReserveBank();
        double parity = bank.parityOf(nation);
        inv.setItem(10, item(Material.REDSTONE, "&cПаритет −0.05", List.of(
                "&7Текущий: &f" + fmt(parity),
                "&7Ослабляет валюту (дешевле экспорт).",
                "&eКлик — уменьшить")));
        inv.setItem(12, item(Material.NETHER_STAR, "&6Паритет &f" + fmt(parity), List.of(
                "&7Официальный курс нацвалюты к GLD.",
                "&7Диапазон 0.50..2.00")));
        inv.setItem(14, item(Material.GLOWSTONE_DUST, "&aПаритет +0.05", List.of(
                "&7Текущий: &f" + fmt(parity),
                "&7Укрепляет валюту (дороже импорт).",
                "&eКлик — увеличить")));
        inv.setItem(16, item(Material.EMERALD, "&aМинт (эмиссия)", List.of(
                "&7Напечатать нацвалюту в казну.",
                "&7Лимит по покрытию: &f" + fmt(nationalCurrency(nation) == null ? 0 : bank.maxMint(nation, nationalCurrency(nation).id())),
                "&eКлик → ввод суммы в чат")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- НАЛОГИ ----------
    private void openTax(Player king, String nation) {
        Holder h = new Holder(nation, "tax");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Налоги · " + nation + " &8▌"));
        h.inv = inv;
        TaxService tax = plugin.getTaxService();
        inv.setItem(10, item(Material.GOLD_NUGGET, "&6Конверсионный &f" + pct(tax.getConvertRate(nation)), List.of(
                "&7Налог при обмене В нацвалюту.",
                "&7Идёт в казну. 0..5%.",
                "&eКлик → ввод % в чат")));
        inv.setItem(12, item(Material.ENDER_CHEST, "&6Торговый &f" + pct(tax.getExchangeRate(nation)), List.of(
                "&7Налог со сделок на бирже.",
                "&7Идёт в казну. 0..5%.",
                "&eКлик → ввод % в чат")));
        inv.setItem(14, item(Material.CHEST, "&6Рыночный &f" + pct(tax.getMarketRate(nation)), List.of(
                "&7Налог с ChestShop/ESGUI в черте нации.",
                "&7Идёт в казну. 0..5%.",
                "&eКлик → ввод % в чат")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- ТОРГОВАЯ ПОЛИТИКА ----------
    private void openTrade(Player king, String nation) {
        Holder h = new Holder(nation, "trade");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Торговая политика · " + nation + " &8▌"));
        h.inv = inv;
        var pol = plugin.getTradePolicy().policyOf(nation);
        inv.setItem(10, item(Material.LIME_CONCRETE, "&aРежим: &f" + pol.mode(), List.of(
                "&7NONE — все валюты разрешены.",
                "&7WHITELIST — только из списка.",
                "&7BLACKLIST — все, кроме списка.",
                "&eКлик — переключить")));
        inv.setItem(12, item(Material.PAPER, "&6Текущий список", pol.list().isEmpty()
                ? List.of("&7Список пуст")
                : pol.list().stream().map(x -> "&7- &f" + x).toList()));
        inv.setItem(14, item(Material.WRITABLE_BOOK, "&6Изменить список", List.of(
                "&7Ввести валюты через запятую.",
                "&7Напр: &fRAS,VLR",
                "&eКлик → ввод в чат")));
        inv.setItem(16, item(Material.BOOK, "&6Справка", List.of(
                "&7Запрещённые валюты нельзя",
                "&7конвертировать и передавать",
                "&7в черте вашей нации.")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- СОВЕТНИК ----------
    private void openAdvisor(Player king, String nation) {
        Holder h = new Holder(nation, "advisor");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Советник · " + nation + " &8▌"));
        h.inv = inv;
        inv.setItem(13, item(Material.BOOK, "&6Экономический советник", adviceLines(nation)));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    private List<String> adviceLines(String nation) {
        ReserveBank bank = plugin.getReserveBank();
        Currency nat = nationalCurrency(nation);
        double reserve = bank.reserveOf(nation);
        double supply = nat == null ? 0 : bank.supplyOf(nat.id());
        double parity = bank.parityOf(nation);
        double coverage = nat == null ? 0 : bank.coverageOf(nation, nat.id());
        List<String> l = new ArrayList<>();
        l.add("&7Резерв: &f" + fmt(reserve) + " GLD");
        l.add("&7Эмиссия: &f" + fmt(supply));
        l.add("&7Покрытие: &f" + pct(coverage));
        l.add("");
        if (coverage >= 1.0) l.add("&aВалюта полностью обеспечена, идёт по паритету.");
        else if (coverage >= bank.coverageFloor()) l.add("&eПокрытие <100%: валюта дешевеет до резерв/эмиссия.");
        else l.add("&cКРИЗИС: покрытие ниже пола, валюта обесценена.");
        l.add("");
        double den = Math.max(1.0D, supply) * parity;
        l.add("&7Депозит +1000 GLD → покрытие &f" + pct((reserve + 1000) / den));
        l.add("&7Вывод −1000 GLD → покрытие &f" + pct(Math.max(0, reserve - 1000) / den));
        l.add("&7Паритет +0.05 → цена &f" + fmt(Math.min(parity + 0.05, supply > 0 ? reserve / supply : parity + 0.05)));
        l.add("&7Паритет −0.05 → цена &f" + fmt(Math.min(Math.max(0.5, parity - 0.05), supply > 0 ? reserve / supply : parity - 0.05)));
        return l;
    }

    // ---------- ГАЙД ----------
    private void openGuide(Player king, String nation) {
        Holder h = new Holder(nation, "guide");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Гайд правителя &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        inv.setItem(10, item(Material.GOLD_BLOCK, "&6Резерв", List.of(
                "&7Золото нации. Депозит укрепляет",
                "&7валюту, вывод ослабляет.",
                "&7Держи покрытие ≥100%.")));
        inv.setItem(12, item(Material.COMPASS, "&6Монетарная", List.of(
                "&7Паритет = курс к GLD.",
                "&7Минт печатает валюту, но",
                "&7требует покрытия резервом.")));
        inv.setItem(14, item(Material.GOLD_NUGGET, "&6Доли", List.of(
                "&7Доля = пай резерва. Игроки",
                "&7покупают, получают Ужиток.",
                "&7Эмиссия Долей без личного списания.")));
        inv.setItem(16, item(Material.CHEST, "&6Торговля", List.of(
                "&7WHITELIST/BLACKLIST валют.",
                "&7Запрет защищает внутренний рынок.")));
        inv.setItem(20, item(Material.TRIPWIRE_HOOK, "&6Налоги", List.of(
                "&7Три канала налогов в казну.",
                "&7Баланс: выше налог = меньше оборот.")));
        inv.setItem(22, item(Material.BOOK, "&6Советник", List.of(
                "&7Показывает последствия решений",
                "&7при текущей экономике.")));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- КЛИКИ ----------
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
                if (slot == 10) { openReserve(p, nation); return; }
                if (slot == 12) { openMonetary(p, nation); return; }
                if (slot == 14) { plugin.getShareGui().openMarket(p, nation); return; }
                if (slot == 16) { openTrade(p, nation); return; }
                if (slot == 20) { openAdvisor(p, nation); return; }
                if (slot == 22) { openTax(p, nation); return; }
                if (slot == 24) { openGuide(p, nation); return; }
            }
            case "reserve" -> {
                if (slot == 49) { openHome(p, nation); return; }
                if (slot == 27) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-dep", nation}); msg(p, "&7Сумма депозита:"); return; }
                if (slot == 29) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-with", nation}); msg(p, "&7Сумма вывода:"); return; }
            }
            case "monetary" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 10) { adjParity(p, nation, -0.05); return; }
                if (slot == 14) { adjParity(p, nation, +0.05); return; }
                if (slot == 16) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"mint", nation}); msg(p, "&7Сумма минта:"); return; }
            }
            case "tax" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 10) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-convert", nation}); msg(p, "&7Ставка % (0..5):"); return; }
                if (slot == 12) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-exchange", nation}); msg(p, "&7Ставка % (0..5):"); return; }
                if (slot == 14) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"tax-market", nation}); msg(p, "&7Ставка % (0..5):"); return; }
            }
            case "trade" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 10) { cycleMode(p, nation); openTrade(p, nation); return; }
                if (slot == 14) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"trade-list", nation}); msg(p, "&7Список валют через запятую:"); return; }
            }
            case "advisor", "guide" -> {
                if (slot == 22 || slot == 49) { openHome(p, nation); return; }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String[] ctx = CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String nation = ctx[1];
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (ctx[0]) {
                case "mint" -> {
                    double amt = parse(text);
                    Currency nat = nationalCurrency(nation);
                    if (!(amt > 0) || nat == null) { msg(p, "&cНекорректно"); openMonetary(p, nation); return; }
                    boolean ok = plugin.getReserveBank().mint(nation, nat.id(), amt, "cabinet");
                    msg(p, ok ? "&aЭмитировано &f" + fmt(amt) + " " + nat.id() : "&cОтказ (покрытие)");
                    openMonetary(p, nation);
                }
                case "tax-convert", "tax-exchange", "tax-market" -> {
                    double v = parse(text);
                    if (v < 0 || v > 5) { msg(p, "&c0..5%"); openTax(p, nation); return; }
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
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); openReserve(p, nation); return; }
                    boolean ok = ctx[0].equals("reserve-dep")
                            ? plugin.getReserveBank().depositToReserve(p.getUniqueId(), nation, amt, "cabinet")
                            : plugin.getReserveBank().withdrawFromReserve(p.getUniqueId(), nation, amt, "cabinet");
                    msg(p, ok ? "&aОперация выполнена" : "&cОтказ");
                    openReserve(p, nation);
                }
            }
        });
    }

    private void adjParity(Player p, String nation, double delta) {
        double next = plugin.getReserveBank().parityOf(nation) + delta;
        boolean ok = plugin.getReserveBank().setParity(nation, next, "cabinet");
        msg(p, ok ? "&aПаритет: &f" + fmt(next) : "&cДиапазон 0.50..2.00");
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

    private Currency nationalCurrency(String nation) {
        for (Currency cur : plugin.getCurrencies().all())
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) return cur;
        return null;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }
}
