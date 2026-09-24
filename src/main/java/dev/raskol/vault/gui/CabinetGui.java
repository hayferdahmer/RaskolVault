// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.tax.TaxService;
import dev.raskol.vault.util.GuiItems;
import dev.raskol.vault.util.Numbers;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кабинет государя (1.2.6 fix): + секция «Банк» — король настраивает ВСЕ банковские
 * параметры нации (ставки вкладов, ставка кредита, collateral-ratio, reserve-multiplier,
 * early-penalty, параметры необеспеченных кредитов).
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

    private void msg(Player p, String raw) { p.sendMessage(GuiItems.c(plugin.getMessages().prefix() + raw)); }
    private static ItemStack it(Material m, String n, List<String> lore) { return GuiItems.item(m, n, lore); }
    private static ItemStack pane() { return GuiItems.pane(); }

    // ---------- ГЛАВНАЯ ----------
    public void openHome(Player king, String nation) {
        Holder h = new Holder(nation, "home");
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Кабинет · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        ReserveBank bank = plugin.getReserveBank();
        Currency nat = nationalCurrency(nation);
        double reserve = bank.reserveOf(nation);
        double coverage = nat == null ? 0 : bank.coverageOf(nation, nat.id());
        inv.setItem(4, it(Material.GOLDEN_APPLE, "&6Государство " + nation, List.of(
                "&7Резерв: &f" + Numbers.fmt(reserve) + " GLD",
                nat == null ? "&cНет нацвалюты" : "&7Эмиссия: &f" + Numbers.fmt(bank.supplyOf(nat.id())),
                "&7Покрытие: &f" + Numbers.pct(coverage),
                "&7Паритет: &f" + Numbers.fmt(bank.parityOf(nation)))));
        inv.setItem(10, it(Material.GOLD_BLOCK, "&6Резерв", List.of("&7Ячейки, депозит/вывод, покрытие", "&eКлик")));
        inv.setItem(12, it(Material.COMPASS, "&6Монетарная", List.of("&7Паритет, минт", "&eКлик")));
        inv.setItem(14, it(Material.GOLD_NUGGET, "&6Доли и Ужиток", List.of("&7Рынок долей, эмиссия, ужиток", "&eКлик")));
        inv.setItem(16, it(Material.CHEST, "&6Торговая политика", List.of("&7whitelist/blacklist валют", "&eКлик")));
        inv.setItem(20, it(Material.BOOK, "&6Советник", List.of("&7Прогноз последствий решений", "&eКлик")));
        inv.setItem(22, it(Material.TRIPWIRE_HOOK, "&6Налоги", List.of("&7convert/exchange/market/auction", "&eКлик")));
        inv.setItem(24, it(Material.WRITABLE_BOOK, "&6Гайд правителя", List.of("&7Инструкции по секциям", "&eКлик")));
        inv.setItem(26, it(Material.BEACON, "&6Банк", List.of("&7Ставки вкладов/кредитов, лимиты", "&eКлик")));
        inv.setItem(49, it(Material.BARRIER, "&cЗакрыть", List.of()));
        king.openInventory(inv);
    }

    // ---------- БАНК (настройки государя) ----------
    private void openBankSettings(Player king, String nation) {
        Holder h = new Holder(nation, "banksettings");
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Банк · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        var bp = plugin.getBankService().params(nation);
        inv.setItem(4, it(Material.BEACON, "&6Показатели банка", List.of(
                "&7Пул ликвидности: &f" + Numbers.fmt(plugin.getBankService().pool(nation, plugin.getCurrencies().globalId())),
                "&7Вклады: &f" + Numbers.fmt(plugin.getBankService().totalDeposits(nation)),
                "&7Выдано: &f" + Numbers.fmt(plugin.getBankService().totalOutstandingLoans(nation)),
                "&7Лимит: &f" + Numbers.fmt(plugin.getBankService().maxLoans(nation)))));
        inv.setItem(10, it(Material.IRON_INGOT, "&6Ставка demand &f" + Numbers.pct(bp.demandRate()), List.of("&eКлик → ввод %")));
        inv.setItem(11, it(Material.GOLD_INGOT, "&6Ставка 7д &f" + Numbers.pct(bp.r7()), List.of("&eКлик → ввод %")));
        inv.setItem(12, it(Material.GOLD_BLOCK, "&6Ставка 30д &f" + Numbers.pct(bp.r30()), List.of("&eКлик → ввод %")));
        inv.setItem(13, it(Material.DIAMOND, "&6Ставка 90д &f" + Numbers.pct(bp.r90()), List.of("&eКлик → ввод %")));
        inv.setItem(14, it(Material.EMERALD, "&6Ставка кредита &f" + Numbers.pct(bp.loanRate()), List.of("&eКлик → ввод %")));
        inv.setItem(15, it(Material.ANVIL, "&6Залог-коэф. &f" + Numbers.fmt(bp.collateralRatio()), List.of("&7Напр. 1.2 = 120%", "&eКлик → ввод")));
        inv.setItem(16, it(Material.BEACON, "&6Резерв-мультипл. &f" + Numbers.fmt(bp.reserveMultiplier()), List.of("&eКлик → ввод")));
        inv.setItem(19, it(Material.RED_CONCRETE, "&6Штраф досрочный &f" + Numbers.pct(bp.earlyPenaltyRate()), List.of("&eКлик → ввод %")));
        inv.setItem(20, it(Material.PAPER, "&6Необесп. база &f" + Numbers.fmt(bp.unsecuredBase()), List.of("&eКлик → ввод")));
        inv.setItem(21, it(Material.PAPER, "&6Необесп. за скор &f" + Numbers.fmt(bp.unsecuredPerScore()), List.of("&eКлик → ввод")));
        inv.setItem(22, it(Material.PAPER, "&6Необесп. премия &f" + Numbers.pct(bp.unsecuredPremium()), List.of("&eКлик → ввод %")));
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- РЕЗЕРВ ----------
    private void openReserve(Player king, String nation) {
        Holder h = new Holder(nation, "reserve");
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Резерв · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        ReserveBank bank = plugin.getReserveBank();
        double reserve = bank.reserveOf(nation);
        Currency nat = nationalCurrency(nation);
        inv.setItem(4, it(Material.GOLD_BLOCK, "&6Золотой резерв", List.of(
                "&7В казне: &f" + Numbers.fmt(reserve) + " GLD",
                "&7Покрытие: &f" + Numbers.pct(nat == null ? 0 : bank.coverageOf(nation, nat.id())),
                "&7Лимит вывода/сутки: &f" + Numbers.fmt(bank.dailyWithdrawLimit(nation)))));
        int cells = (int) Math.min(14, reserve / CELL_GLD);
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25};
        for (int i = 0; i < grid.length; i++)
            inv.setItem(grid[i], i < cells ? it(Material.GOLD_BLOCK, "&6Ячейка", List.of("&71000 GLD")) : pane());
        inv.setItem(27, it(Material.GOLD_INGOT, "&aДепозит", List.of("&eКлик → сумма в чат")));
        inv.setItem(29, it(Material.HOPPER, "&cВывод", List.of("&eКлик → сумма в чат")));
        inv.setItem(33, it(Material.PAPER, "&6Справка", List.of("&7Покрытие = резерв/(эмиссия×паритет)", "&7<100% дешевеет, <50% КРИЗИС")));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- МОНЕТАРНАЯ ----------
    private void openMonetary(Player king, String nation) {
        Holder h = new Holder(nation, "monetary");
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Монетарная · " + nation + " &8▌"));
        h.inv = inv;
        ReserveBank bank = plugin.getReserveBank();
        double parity = bank.parityOf(nation);
        Currency nat = nationalCurrency(nation);
        if (nat == null) {
            inv.setItem(13, it(Material.GOLD_BLOCK, "&aВыпустить валюту", List.of("&7Создать нацвалюту", "&eКлик")));
        } else {
            inv.setItem(10, it(Material.REDSTONE, "&cПаритет −0.05", List.of("&7Текущий: &f" + Numbers.fmt(parity))));
            inv.setItem(12, it(Material.NETHER_STAR, "&6Паритет &f" + Numbers.fmt(parity), List.of("&7Диапазон 0.5..2.0")));
            inv.setItem(14, it(Material.GLOWSTONE_DUST, "&aПаритет +0.05", List.of("&7Текущий: &f" + Numbers.fmt(parity))));
            inv.setItem(16, it(Material.EMERALD, "&aМинт", List.of("&eКлик → сумма в чат")));
        }
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- НАЛОГИ ----------
    private void openTax(Player king, String nation) {
        Holder h = new Holder(nation, "tax");
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Налоги · " + nation + " &8▌"));
        h.inv = inv;
        TaxService tax = plugin.getTaxService();
        inv.setItem(10, it(Material.GOLD_NUGGET, "&6Конверсионный &f" + Numbers.pct(tax.getConvertRate(nation)), List.of("&eКлик → %")));
        inv.setItem(12, it(Material.ENDER_CHEST, "&6Торговый &f" + Numbers.pct(tax.getExchangeRate(nation)), List.of("&eКлик → %")));
        inv.setItem(14, it(Material.CHEST, "&6Рыночный &f" + Numbers.pct(tax.getMarketRate(nation)), List.of("&eКлик → %")));
        inv.setItem(16, it(Material.BEACON, "&6Аукционный &f" + Numbers.pct(tax.getAuctionRate(nation)), List.of("&eКлик → %")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- ТОРГОВЛЯ ----------
    private void openTrade(Player king, String nation) {
        Holder h = new Holder(nation, "trade");
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Торговая политика &8▌"));
        h.inv = inv;
        var pol = plugin.getTradePolicy().policyOf(nation);
        inv.setItem(10, it(Material.LIME_CONCRETE, "&aРежим: &f" + pol.mode(), List.of("&eКлик — переключить")));
        inv.setItem(12, it(Material.PAPER, "&6Список", pol.list().isEmpty() ? List.of("&7пуст") : pol.list().stream().map(x -> "&7- &f" + x).toList()));
        inv.setItem(14, it(Material.WRITABLE_BOOK, "&6Изменить список", List.of("&eКлик → ввод в чат")));
        inv.setItem(16, it(Material.BOOK, "&6Справка", List.of("&7Запрещённые валюты нельзя", "&7конвертировать/передавать в нации")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- СОВЕТНИК ----------
    private void openAdvisor(Player king, String nation) {
        Holder h = new Holder(nation, "advisor");
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Советник · " + nation + " &8▌"));
        h.inv = inv;
        inv.setItem(13, it(Material.BOOK, "&6Экономический советник", adviceLines(nation)));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
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
        l.add("&7Резерв: &f" + Numbers.fmt(reserve) + " GLD");
        l.add("&7Эмиссия: &f" + Numbers.fmt(supply));
        l.add("&7Покрытие: &f" + Numbers.pct(coverage));
        l.add("");
        if (coverage >= 1.0) l.add("&aВалюта полностью обеспечена.");
        else if (coverage >= bank.coverageFloor()) l.add("&eПокрытие <100%: валюта дешевеет.");
        else l.add("&cКРИЗИС: покрытие ниже пола.");
        l.add("");
        double den = Math.max(1.0D, supply) * parity;
        l.add("&7Депозит +1000 → покрытие &f" + Numbers.pct((reserve + 1000) / den));
        l.add("&7Вывод −1000 → покрытие &f" + Numbers.pct(Math.max(0, reserve - 1000) / den));
        return l;
    }

    // ---------- ГАЙД ----------
    private void openGuide(Player king, String nation) {
        Holder h = new Holder(nation, "guide");
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Гайд правителя &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        inv.setItem(10, it(Material.GOLD_BLOCK, "&6Резерв", List.of("&7Депозит укрепляет, вывод ослабляет.", "&7Держи покрытие ≥100%.")));
        inv.setItem(12, it(Material.COMPASS, "&6Монетарная", List.of("&7Паритет = курс. Минт требует покрытия.")));
        inv.setItem(14, it(Material.GOLD_NUGGET, "&6Доли", List.of("&7Доля = пай резерва. Ужиток = дивиденд.")));
        inv.setItem(16, it(Material.CHEST, "&6Торговля", List.of("&7WHITELIST/BLACKLIST валют.")));
        inv.setItem(20, it(Material.TRIPWIRE_HOOK, "&6Налоги", List.of("&74 канала в казну. Баланс ставок.")));
        inv.setItem(22, it(Material.BEACON, "&6Банк", List.of("&7Ставки вкладов/кредитов.", "&7Спред = прибыль нации.")));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
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
                if (slot == 26) { openBankSettings(p, nation); return; }
            }
            case "banksettings" -> {
                if (slot == 49) { openHome(p, nation); return; }
                String key = switch (slot) {
                    case 10 -> "demand-rate"; case 11 -> "rate-7"; case 12 -> "rate-30"; case 13 -> "rate-90";
                    case 14 -> "loan-rate"; case 15 -> "collateral-ratio"; case 16 -> "reserve-multiplier";
                    case 19 -> "early-penalty"; case 20 -> "unsecured-base"; case 21 -> "unsecured-per-score";
                    case 22 -> "unsecured-premium"; default -> null;
                };
                if (key != null) {
                    p.closeInventory();
                    CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"bankparam:" + key, nation});
                    msg(p, "&7Введите значение &f" + key + "&7 (проценты — числом, напр. 2.5 = 2.5%):");
                }
                return;
            }
            case "reserve" -> {
                if (slot == 49) { openHome(p, nation); return; }
                if (slot == 27) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-dep", nation}); msg(p, "&7Сумма депозита:"); return; }
                if (slot == 29) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"reserve-with", nation}); msg(p, "&7Сумма вывода:"); return; }
            }
            case "monetary" -> {
                if (slot == 22) { openHome(p, nation); return; }
                Currency nat = nationalCurrency(nation);
                if (nat == null) { if (slot == 13) issueCurrency(p, nation); return; }
                if (slot == 10) { adjParity(p, nation, -0.05); return; }
                if (slot == 14) { adjParity(p, nation, +0.05); return; }
                if (slot == 16) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"mint", nation}); msg(p, "&7Сумма минта:"); return; }
            }
            case "tax" -> {
                if (slot == 22) { openHome(p, nation); return; }
                String tk = switch (slot) { case 10 -> "tax-convert"; case 12 -> "tax-exchange"; case 14 -> "tax-market"; case 16 -> "tax-auction"; default -> null; };
                if (tk != null) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{tk, nation}); msg(p, "&7Ставка % (0..5):"); }
                return;
            }
            case "trade" -> {
                if (slot == 22) { openHome(p, nation); return; }
                if (slot == 10) { cycleMode(p, nation); openTrade(p, nation); return; }
                if (slot == 14) { p.closeInventory(); CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"trade-list", nation}); msg(p, "&7Список валют через запятую:"); return; }
            }
            case "advisor", "guide" -> { if (slot == 22 || slot == 49) { openHome(p, nation); return; } }
        }
    }

    // ---------- ЧАТ ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String[] ctx = CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String nation = ctx[1];
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (ctx[0].startsWith("bankparam:")) {
                String key = ctx[0].substring("bankparam:".length());
                double v = parse(text);
                // проценты вводятся как 2.5 → 0.025; коэффициенты как есть
                boolean isPercent = key.contains("rate") || key.equals("early-penalty") || key.equals("unsecured-premium");
                double value = isPercent ? v / 100.0D : v;
                plugin.getBankService().setParam(nation, key, value);
                msg(p, "&aПараметр &f" + key + " &a= &f" + (isPercent ? Numbers.pct(value) : Numbers.fmt(value)));
                openBankSettings(p, nation);
                return;
            }
            switch (ctx[0]) {
                case "mint" -> {
                    double amt = parse(text);
                    Currency nat = nationalCurrency(nation);
                    if (!(amt > 0) || nat == null) { msg(p, "&cНекорректно"); openMonetary(p, nation); return; }
                    boolean ok = plugin.getReserveBank().mint(nation, nat.id(), amt, "cabinet");
                    msg(p, ok ? "&aЭмитировано &f" + Numbers.fmt(amt) + " " + nat.id() : "&cОтказ (покрытие)");
                    openMonetary(p, nation);
                }
                case "tax-convert", "tax-exchange", "tax-market", "tax-auction" -> {
                    double v = parse(text);
                    if (v < 0 || v > 5) { msg(p, "&c0..5%"); openTax(p, nation); return; }
                    double rate = v / 100.0D;
                    TaxService t = plugin.getTaxService();
                    if (ctx[0].endsWith("convert")) t.setConvertRate(nation, rate);
                    else if (ctx[0].endsWith("exchange")) t.setExchangeRate(nation, rate);
                    else if (ctx[0].endsWith("market")) t.setMarketRate(nation, rate);
                    else t.setAuctionRate(nation, rate);
                    msg(p, "&aНалог установлен: &f" + Numbers.pct(rate));
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
        msg(p, ok ? "&aПаритет: &f" + Numbers.fmt(next) : "&cДиапазон 0.50..2.00");
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

    private void issueCurrency(Player p, String nation) {
        String base = nation.replaceAll("[^A-Za-zА-Яа-я]", "");
        String id = (base.length() >= 3 ? base.substring(0, 3) : base).toUpperCase(Locale.ROOT);
        String candidate = id;
        int suffix = 0;
        while (plugin.getCurrencies().get(candidate).isPresent()) { suffix++; candidate = id + suffix; }
        Currency cur = new Currency(candidate, "Динар " + nation, candidate, CurrencyType.NATIONAL, nation, 2, true);
        plugin.getCurrencies().addCurrency(cur);
        plugin.getLedger().upsertCurrency(cur);
        msg(p, "&aВыпущена валюта &f" + candidate + " &aдля нации " + nation);
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
