// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.RaskolVault;
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
 * GUI Банка (1.2.5-b): вклады, кредиты под залог, управление банком нации (король).
 * Строгий «банковский» стиль: рамки, подписи, lore-подсказки у каждой кнопки.
 */
public final class BankGui implements Listener {

    private final RaskolVault plugin;
    private final Map<UUID, LoanWizard> loanWizards = new ConcurrentHashMap<>();
    private final Map<UUID, BankAccount.Term> depositTerms = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();

    private static final class LoanWizard {
        int termDays = 30;
        double amount = 0;
    }

    public static final class BankHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class DepTermHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyDepositsHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class DepDetailHolder implements InventoryHolder {
        final String accountId;
        DepDetailHolder(String accountId) { this.accountId = accountId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class LoanTermHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class LoanCollateralHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyLoansHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class LoanDetailHolder implements InventoryHolder {
        final String loanId;
        LoanDetailHolder(String loanId) { this.loanId = loanId; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class NationBankHolder implements InventoryHolder {
        final String nation;
        NationBankHolder(String nation) { this.nation = nation; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public BankGui(RaskolVault plugin) { this.plugin = plugin; }

    private BankService bank() { return plugin.getBankService(); }
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
    private static void frame(Inventory inv) {
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
    }
    private String nationOf(Player p) {
        return plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
    }
    private boolean isKing(Player p, String nation) {
        return nation != null && plugin.getTownyHook().isKing(p.getUniqueId(), nation);
    }

    // ---------- ГЛАВНАЯ ----------
    public void openBank(Player p) {
        BankHolder h = new BankHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Банк &8▌"));
        h.inv = inv;
        frame(inv);
        String nation = nationOf(p);
        inv.setItem(4, item(Material.GOLD_BLOCK, "&6Банк нации", List.of(
                nation == null ? "&cВы вне нации" : "&7Нация: &f" + nation,
                "&7Кредитный скор: &f" + bank().creditScore(p.getUniqueId()))));
        inv.setItem(10, item(Material.CHEST, "&aМои вклады", List.of("&eКлик — список вкладов")));
        inv.setItem(12, item(Material.PAPER, "&6Мои кредиты", List.of("&eКлик — список кредитов")));
        inv.setItem(14, item(Material.GOLD_INGOT, "&aОткрыть вклад", List.of("&7Депозит с процентом", "&eКлик — выбрать срок")));
        inv.setItem(16, item(Material.EMERALD, "&6Взять кредит", List.of("&7Под залог валюты/предмета", "&eКлик — выбрать срок")));
        if (isKing(p, nation)) {
            inv.setItem(22, item(Material.BEACON, "&6Управление банком", List.of("&7Ставки, пул, кредиты, ликвидация", "&eКлик — открыть")));
        }
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    // ---------- ОТКРЫТЬ ВКЛАД: срок ----------
    private void openDepositTerm(Player p) {
        String nation = nationOf(p);
        if (nation == null) { msg(p, "&cВы вне нации — вклад недоступен"); return; }
        DepTermHolder h = new DepTermHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Вклад: срок &8▌"));
        h.inv = inv;
        BankService.BankParams bp = bank().params(nation);
        inv.setItem(10, item(Material.IRON_INGOT, "&7До востребования", List.of(
                "&7Ставка: &f" + pct(bp.demandRate()) + " годовых", "&7Снятие в любой момент", "&eКлик")));
        inv.setItem(12, item(Material.GOLD_INGOT, "&67 дней", List.of("&7Ставка: &f" + pct(bp.r7()), "&eКлик")));
        inv.setItem(14, item(Material.GOLD_BLOCK, "&630 дней", List.of("&7Ставка: &f" + pct(bp.r30()), "&eКлик")));
        inv.setItem(16, item(Material.DIAMOND, "&b90 дней", List.of("&7Ставка: &f" + pct(bp.r90()), "&eКлик")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- МОИ ВКЛАДЫ ----------
    private void openMyDeposits(Player p) {
        MyDepositsHolder h = new MyDepositsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои вклады &8▌"));
        h.inv = inv;
        frame(inv);
        List<BankAccount> deps = bank().myDeposits(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < deps.size(); i++) {
            BankAccount a = deps.get(i);
            inv.setItem(grid[i], item(Material.GOLD_INGOT, "&6Вклад &7" + a.id().substring(0, 8), List.of(
                    "&7Тело: &f" + fmt(a.principal()) + " " + a.currencyId(),
                    "&7Срок: &f" + (a.isDemand() ? "до востребования" : a.termDays() + " дн"),
                    "&7Начислено: &a" + fmt(a.accrued()),
                    "&eКлик — подробности")));
        }
        for (int i = deps.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openDepositDetail(Player p, String accountId) {
        BankAccount a = bank().getAccount(accountId);
        if (a == null) { msg(p, "&cВклад не найден"); return; }
        DepDetailHolder h = new DepDetailHolder(accountId);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Вклад &7" + accountId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        long now = System.currentTimeMillis();
        a.accrue(now, bank().params(a.nation()).demandRate());
        inv.setItem(13, item(Material.GOLD_BLOCK, "&6Сводка вклада", List.of(
                "&7Тело: &f" + fmt(a.principal()) + " " + a.currencyId(),
                "&7Ставка: &f" + pct(a.rateAnnual()),
                "&7Начислено: &a" + fmt(a.accrued()),
                a.isDemand() ? "&7Тип: до востребования" : "&7Созревает через: &f" + daysLeft(a.maturesAt(), now),
                "&7Статус: &f" + a.status())));
        inv.setItem(11, item(Material.RED_CONCRETE, "&cЗакрыть вклад", List.of(
                a.isDemand() || a.isMatured(now) ? "&7Выплата: тело + проценты" : "&cДосрочно: потеря % + штраф")));
        inv.setItem(15, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- ВЗЯТЬ КРЕДИТ: срок ----------
    private void openLoanTerm(Player p) {
        String nation = nationOf(p);
        if (nation == null) { msg(p, "&cВы вне нации — кредит недоступен"); return; }
        LoanTermHolder h = new LoanTermHolder();
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Кредит: срок &8▌"));
        h.inv = inv;
        BankService.BankParams bp = bank().params(nation);
        double rate = bp.loanRate() + creditBonus(p);
        inv.setItem(11, item(Material.GOLD_INGOT, "&67 дней", List.of("&7Ставка: &f" + pct(rate), "&eКлик")));
        inv.setItem(13, item(Material.GOLD_BLOCK, "&630 дней", List.of("&7Ставка: &f" + pct(rate), "&eКлик")));
        inv.setItem(15, item(Material.DIAMOND, "&b90 дней", List.of("&7Ставка: &f" + pct(rate), "&eКлик")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private double creditBonus(Player p) {
        int score = bank().creditScore(p.getUniqueId());
        return -Math.min(0.02D, score * 0.002D) + Math.max(0.0D, -score * 0.005D);
    }

    // ---------- КРЕДИТ: залог ----------
    private void openLoanCollateral(Player p) {
        LoanWizard w = loanWizards.get(p.getUniqueId());
        if (w == null) { openLoanTerm(p); return; }
        String nation = nationOf(p);
        LoanCollateralHolder h = new LoanCollateralHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кредит: залог &8▌"));
        h.inv = inv;
        frame(inv);
        BankService.BankParams bp = bank().params(nation);
        double required = w.amount * bp.collateralRatio();
        inv.setItem(4, item(Material.ANVIL, "&6Требуемый залог", List.of(
                "&7Сумма кредита: &f" + fmt(w.amount),
                "&7Коэффициент: &f" + fmt(bp.collateralRatio()),
                "&7Нужно залога: &c" + fmt(required))));
        int slot = 10;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (slot > 16) break;
            double bal = plugin.getWallets().getBalance(p.getUniqueId(), cur.id());
            boolean enough = bal >= required - 1e-9;
            inv.setItem(slot, item(iconOf(cur.id()), (enough ? "&a" : "&c") + cur.displayName(), List.of(
                    "&7Баланс: &f" + fmt(bal) + " " + cur.id(),
                    enough ? "&eКлик — заложить валюту" : "&cНедостаточно")));
            slot += 2;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && !hand.getType().isAir()) {
            double appr = bank().appraisal(hand);
            inv.setItem(22, item(hand.getType(), "&6Предмет в руке", List.of(
                    "&7Оценка: &f" + fmt(appr),
                    appr >= required ? "&eКлик — заложить предмет" : "&cОценка мала")));
        }
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- МОИ КРЕДИТЫ ----------
    private void openMyLoans(Player p) {
        MyLoansHolder h = new MyLoansHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои кредиты &8▌"));
        h.inv = inv;
        frame(inv);
        List<BankLoan> loans = bank().myLoans(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        long now = System.currentTimeMillis();
        for (int i = 0; i < grid.length && i < loans.size(); i++) {
            BankLoan l = loans.get(i);
            inv.setItem(grid[i], item(l.isOverdue(now) ? Material.REDSTONE_BLOCK : Material.PAPER,
                    "&6Кредит &7" + l.id().substring(0, 8), List.of(
                    "&7Тело: &f" + fmt(l.principal()) + " " + l.currencyId(),
                    "&7Остаток: &c" + fmt(l.outstanding(now)),
                    l.isOverdue(now) ? "&cПРОСРОЧЕН" : "&7До: &f" + daysLeft(l.dueAt(), now),
                    "&eКлик — подробности")));
        }
        for (int i = loans.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openLoanDetail(Player p, String loanId) {
        BankLoan l = bank().getLoan(loanId);
        if (l == null) { msg(p, "&cКредит не найден"); return; }
        LoanDetailHolder h = new LoanDetailHolder(loanId);
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Кредит &7" + loanId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        long now = System.currentTimeMillis();
        inv.setItem(13, item(Material.PAPER, "&6Сводка кредита", List.of(
                "&7Тело: &f" + fmt(l.principal()) + " " + l.currencyId(),
                "&7Ставка: &f" + pct(l.rateAnnual()),
                "&7Остаток: &c" + fmt(l.outstanding(now)),
                "&7Залог: &f" + l.collateralType() + " (" + fmt(l.collateralValue()) + ")",
                l.isOverdue(now) ? "&cПРОСРОЧЕН — грозит ликвидация" : "&7До: &f" + daysLeft(l.dueAt(), now))));
        inv.setItem(11, item(Material.GOLD_INGOT, "&aПогасить часть", List.of("&eКлик → ввод суммы в чат")));
        inv.setItem(15, item(Material.EMERALD_BLOCK, "&aПогасить полностью", List.of("&7Сумма: &f" + fmt(l.outstanding(now)))));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- УПРАВЛЕНИЕ БАНКОМ (король) ----------
    public void openNationBank(Player p, String nation) {
        NationBankHolder h = new NationBankHolder(nation);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Банк · " + nation + " &8▌"));
        h.inv = inv;
        frame(inv);
        BankService.BankParams bp = bank().params(nation);
        String glb = plugin.getCurrencies().globalId();
        inv.setItem(4, item(Material.BEACON, "&6Показатели банка", List.of(
                "&7Пул ликвидности: &f" + fmt(bank().pool(nation, glb)) + " " + glb,
                "&7Процентный резерв: &f" + fmt(bank().interestReserve(nation, glb)),
                "&7Вклады: &f" + fmt(bank().totalDeposits(nation)),
                "&7Выдано кредитов: &f" + fmt(bank().totalOutstandingLoans(nation)),
                "&7Лимит выдачи: &f" + fmt(bank().maxLoans(nation)))));
        inv.setItem(10, item(Material.IRON_INGOT, "&7Ставка demand &f" + pct(bp.demandRate()), List.of("&eКлик → ввод в чат")));
        inv.setItem(11, item(Material.GOLD_INGOT, "&6Ставка 7д &f" + pct(bp.r7()), List.of("&eКлик → ввод в чат")));
        inv.setItem(12, item(Material.GOLD_BLOCK, "&6Ставка 30д &f" + pct(bp.r30()), List.of("&eКлик → ввод в чат")));
        inv.setItem(13, item(Material.DIAMOND, "&bСтавка 90д &f" + pct(bp.r90()), List.of("&eКлик → ввод в чат")));
        inv.setItem(14, item(Material.EMERALD, "&6Ставка кредита &f" + pct(bp.loanRate()), List.of("&eКлик → ввод в чат")));
        inv.setItem(15, item(Material.ANVIL, "&6Залог-коэф. &f" + fmt(bp.collateralRatio()), List.of("&eКлик → ввод в чат")));
        inv.setItem(16, item(Material.BEACON, "&6Резерв-мультипл. &f" + fmt(bp.reserveMultiplier()), List.of("&eКлик → ввод в чат")));
        inv.setItem(22, item(Material.BOOK, "&6Активные кредиты", List.of("&7Просмотр и ликвидация", "&eКлик")));
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openNationLoans(Player p, String nation) {
        NationBankHolder h = new NationBankHolder(nation);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кредиты · " + nation + " &8▌"));
        h.inv = inv;
        frame(inv);
        List<BankLoan> loans = bank().activeLoans(nation);
        int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        long now = System.currentTimeMillis();
        for (int i = 0; i < grid.length && i < loans.size(); i++) {
            BankLoan l = loans.get(i);
            inv.setItem(grid[i], item(l.isOverdue(now) ? Material.REDSTONE_BLOCK : Material.PAPER,
                    "&6" + l.borrowerName(), List.of(
                    "&7Остаток: &c" + fmt(l.outstanding(now)) + " " + l.currencyId(),
                    l.isOverdue(now) ? "&cПРОСРОЧЕН" : "&7До: &f" + daysLeft(l.dueAt(), now),
                    "&eКлик — ликвидировать")));
        }
        for (int i = loans.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    // ---------- КЛИКИ ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof BankHolder) && !(raw instanceof DepTermHolder)
                && !(raw instanceof MyDepositsHolder) && !(raw instanceof DepDetailHolder)
                && !(raw instanceof LoanTermHolder) && !(raw instanceof LoanCollateralHolder)
                && !(raw instanceof MyLoansHolder) && !(raw instanceof LoanDetailHolder)
                && !(raw instanceof NationBankHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        String nation = nationOf(p);

        if (raw instanceof BankHolder) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 10) { openMyDeposits(p); return; }
            if (slot == 12) { openMyLoans(p); return; }
            if (slot == 14) { openDepositTerm(p); return; }
            if (slot == 16) { openLoanTerm(p); return; }
            if (slot == 22 && isKing(p, nation)) { openNationBank(p, nation); return; }
            return;
        }
        if (raw instanceof DepTermHolder) {
            if (slot == 22) { openBank(p); return; }
            BankAccount.Term term = switch (slot) {
                case 10 -> BankAccount.Term.DEMAND;
                case 12 -> BankAccount.Term.TERM_7;
                case 14 -> BankAccount.Term.TERM_30;
                case 16 -> BankAccount.Term.TERM_90;
                default -> null;
            };
            if (term != null) {
                depositTerms.put(p.getUniqueId(), term);
                p.closeInventory();
                chatField.put(p.getUniqueId(), "dep-amount");
                msg(p, "&7Введите сумму вклада:");
            }
            return;
        }
        if (raw instanceof MyDepositsHolder) {
            if (slot == 49) { openBank(p); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            List<BankAccount> deps = bank().myDeposits(p.getUniqueId());
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == slot && i < deps.size()) { openDepositDetail(p, deps.get(i).id()); return; }
            }
            return;
        }
        if (raw instanceof DepDetailHolder dh) {
            if (slot == 15) { openMyDeposits(p); return; }
            if (slot == 11) {
                String err = bank().closeDeposit(p.getUniqueId(), dh.accountId);
                msg(p, err == null ? "&aВклад закрыт, выплата зачислена" : "&c" + err);
                openMyDeposits(p);
            }
            return;
        }
        if (raw instanceof LoanTermHolder) {
            if (slot == 22) { openBank(p); return; }
            Integer days = switch (slot) { case 11 -> 7; case 13 -> 30; case 15 -> 90; default -> null; };
            if (days != null) {
                LoanWizard w = loanWizards.computeIfAbsent(p.getUniqueId(), k -> new LoanWizard());
                w.termDays = days;
                p.closeInventory();
                chatField.put(p.getUniqueId(), "loan-amount");
                msg(p, "&7Введите сумму кредита:");
            }
            return;
        }
        if (raw instanceof LoanCollateralHolder) {
            if (slot == 49) { openLoanTerm(p); return; }
            LoanWizard w = loanWizards.get(p.getUniqueId());
            if (w == null) return;
            BankService.BankParams bp = bank().params(nation);
            double required = w.amount * bp.collateralRatio();
            if (slot == 22) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) { msg(p, "&cПустая рука"); return; }
                ItemStack taken = hand.clone();
                p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                String err = bank().applyLoan(p.getUniqueId(), p.getName(), nation,
                        plugin.getCurrencies().globalId(), w.amount, w.termDays,
                        BankLoan.CollateralType.ITEM, null, taken);
                msg(p, err == null ? "&aКредит выдан" : "&c" + err);
                loanWizards.remove(p.getUniqueId());
                openBank(p);
                return;
            }
            int idx = (slot - 10) / 2;
            List<Currency> all = plugin.getCurrencies().all();
            if (idx >= 0 && idx < all.size()) {
                Currency cur = all.get(idx);
                String err = bank().applyLoan(p.getUniqueId(), p.getName(), nation,
                        plugin.getCurrencies().globalId(), w.amount, w.termDays,
                        BankLoan.CollateralType.CURRENCY, cur.id(), null);
                msg(p, err == null ? "&aКредит выдан" : "&c" + err);
                loanWizards.remove(p.getUniqueId());
                openBank(p);
            }
            return;
        }
        if (raw instanceof MyLoansHolder) {
            if (slot == 49) { openBank(p); return; }
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            List<BankLoan> loans = bank().myLoans(p.getUniqueId());
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == slot && i < loans.size()) { openLoanDetail(p, loans.get(i).id()); return; }
            }
            return;
        }
        if (raw instanceof LoanDetailHolder lh) {
            if (slot == 22) { openMyLoans(p); return; }
            BankLoan l = bank().getLoan(lh.loanId);
            if (l == null) return;
            if (slot == 11) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "repay-amount:" + lh.loanId);
                msg(p, "&7Введите сумму погашения (остаток &f" + fmt(l.outstanding(System.currentTimeMillis())) + "&7):");
                return;
            }
            if (slot == 15) {
                String err = bank().repayLoan(p.getUniqueId(), lh.loanId, l.outstanding(System.currentTimeMillis()));
                msg(p, err == null ? "&aКредит погашен, залог возвращён" : "&c" + err);
                openMyLoans(p);
            }
            return;
        }
        if (raw instanceof NationBankHolder nh) {
            if (slot == 49) { openNationBank(p, nh.nation); return; }
            if (slot == 22) { openNationLoans(p, nh.nation); return; }
            String key = switch (slot) {
                case 10 -> "demand-rate";
                case 11 -> "rate-7";
                case 12 -> "rate-30";
                case 13 -> "rate-90";
                case 14 -> "loan-rate";
                case 15 -> "collateral-ratio";
                case 16 -> "reserve-multiplier";
                default -> null;
            };
            if (key != null) {
                p.closeInventory();
                chatField.put(p.getUniqueId(), "setparam:" + nh.nation + ":" + key);
                msg(p, "&7Введите значение &f" + key + "&7:");
            }
            return;
        }
        // клик по лоту-кредиту в nationLoans (тот же NationBankHolder, но страница кредитов) — ликвидация
        if (raw instanceof NationBankHolder nh2 && e.getView().getTitle().contains("Кредиты")) {
            int[] grid = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
            List<BankLoan> loans = bank().activeLoans(nh2.nation);
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] == slot && i < loans.size()) {
                    String err = bank().liquidate(loans.get(i).id());
                    msg(p, err == null ? "&aЗалог ликвидирован" : "&c" + err);
                    openNationLoans(p, nh2.nation);
                    return;
                }
            }
        }
    }

    // ---------- ЧАТ ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String nation = nationOf(p);
            if (field.equals("dep-amount")) {
                double v = parse(text);
                BankAccount.Term term = depositTerms.remove(p.getUniqueId());
                if (!(v > 0) || term == null) { msg(p, "&cНекорректно"); openBank(p); return; }
                String err = bank().openDeposit(p.getUniqueId(), p.getName(), nation,
                        plugin.getCurrencies().globalId(), v, term);
                msg(p, err == null ? "&aВклад открыт" : "&c" + err);
                openBank(p);
                return;
            }
            if (field.equals("loan-amount")) {
                double v = parse(text);
                LoanWizard w = loanWizards.get(p.getUniqueId());
                if (!(v > 0) || w == null) { msg(p, "&cНекорректно"); loanWizards.remove(p.getUniqueId()); openBank(p); return; }
                w.amount = v;
                openLoanCollateral(p);
                return;
            }
            if (field.startsWith("repay-amount:")) {
                String loanId = field.substring("repay-amount:".length());
                double v = parse(text);
                if (!(v > 0)) { msg(p, "&cНекорректно"); openMyLoans(p); return; }
                String err = bank().repayLoan(p.getUniqueId(), loanId, v);
                msg(p, err == null ? "&aПогашение принято" : "&c" + err);
                openMyLoans(p);
                return;
            }
            if (field.startsWith("setparam:")) {
                String[] parts = field.substring("setparam:".length()).split(":", 2);
                String n = parts[0];
                String key = parts.length > 1 ? parts[1] : "";
                double v = parse(text);
                bank().setParam(n, key, v);
                msg(p, "&aПараметр &f" + key + " &a= &f" + fmt(v));
                openNationBank(p, n);
            }
        });
    }

    private static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", v * 100); }
    private static String daysLeft(long due, long now) {
        long d = (due - now) / 86_400_000L;
        return Math.max(0, d) + " дн";
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
