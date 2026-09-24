// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.bank.BankAccount;
import dev.raskol.vault.bank.BankLoan;
import dev.raskol.vault.bank.BankService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Банк GUI (fix): «Мои вклады» показывает ТОЛЬКО активные вклады (закрытые исчезают).
 */
public final class BankGui implements Listener {

    private final RaskolVault plugin;
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>();

    public static final class BankHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class DepositsHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class DepositTermHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class LoansHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class LoanDetailHolder implements InventoryHolder { final String loanId; LoanDetailHolder(String id){this.loanId=id;} private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class LoanTypeHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }
    public static final class LoanCollateralHolder implements InventoryHolder { private Inventory inv; @Override public Inventory getInventory() { return inv; } }

    public BankGui(RaskolVault plugin) { this.plugin = plugin; }
    private BankService bank() { return plugin.getBankService(); }
    private void msg(Player p, String raw) { p.sendMessage(GuiItems.c(plugin.getMessages().prefix() + raw)); }
    private static ItemStack it(Material m, String n, List<String> lore) { return GuiItems.item(m, n, lore); }
    private static final int[] GRID = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
    private String nationOf(Player p) { return plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null; }
    private String glb() { return plugin.getCurrencies().globalId(); }

    public void openBank(Player p) {
        BankHolder h = new BankHolder();
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Банк &8▌"));
        h.inv = inv;
        GuiItems.frame54(inv);
        String nation = nationOf(p);
        inv.setItem(10, it(Material.GOLD_INGOT, "&6Кошелёк", List.of("&7Балансы, переводы, обмен", "&eКлик")));
        inv.setItem(12, it(Material.CHEST, "&aМои вклады", List.of("&eКлик")));
        inv.setItem(14, it(Material.GOLD_BLOCK, "&aОткрыть вклад", List.of("&7Процент по сроку", "&eКлик")));
        inv.setItem(16, it(Material.PAPER, "&6Мои кредиты", List.of("&eКлик")));
        inv.setItem(20, it(Material.EMERALD, "&6Взять кредит", List.of("&7Необеспеченный или под залог", "&eКлик")));
        inv.setItem(22, it(Material.BOOK, "&6Условия", List.of("&7Ставки и лимиты нации",
                nation == null ? "&cВы вне нации" : "&7Нация: &f" + nation)));
        if (nation != null && plugin.getTownyHook().isKing(p.getUniqueId(), nation))
            inv.setItem(24, it(Material.BEACON, "&6Банк нации", List.of("&7Пул, резерв, лимиты", "&eКлик (король)")));
        inv.setItem(49, it(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    private void openDeposits(Player p) {
        DepositsHolder h = new DepositsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Мои вклады &8▌"));
        h.inv = inv;
        GuiItems.frame54(inv);
        // FIX: только АКТИВНЫЕ вклады (закрытые исчезают из списка)
        List<BankAccount> deps = bank().myDeposits(p.getUniqueId()).stream().filter(BankAccount::isActive).toList();
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length && i < deps.size(); i++) {
            BankAccount a = deps.get(i);
            a.accrue(now, bank().params(a.nation()).demandRate());
            inv.setItem(GRID[i], it(Material.GOLD_BLOCK, "&6Вклад &7" + a.id().substring(0, 8), List.of(
                    "&7Тело: &f" + Numbers.fmt(a.principal()) + " " + a.currencyId(),
                    "&7Начислено: &a" + Numbers.fmt(a.accrued()),
                    "&7Срок: &f" + (a.isDemand() ? "до востребования" : a.termDays() + " дн"),
                    "&cКлик = закрыть вклад")));
        }
        for (int i = deps.size(); i < GRID.length; i++) inv.setItem(GRID[i], GuiItems.pane());
        if (deps.isEmpty()) inv.setItem(22, it(Material.BARRIER, "&7Активных вкладов нет", List.of()));
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openDepositTerm(Player p) {
        DepositTermHolder h = new DepositTermHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Вклад: срок &8▌"));
        h.inv = inv;
        BankService.BankParams bp = bank().params(nationOf(p));
        inv.setItem(10, it(Material.IRON_INGOT, "&7До востребования", List.of("&7Ставка: &f" + Numbers.pct(bp.demandRate()), "&eКлик")));
        inv.setItem(11, it(Material.GOLD_INGOT, "&67 дней", List.of("&7Ставка: &f" + Numbers.pct(bp.r7()), "&eКлик")));
        inv.setItem(12, it(Material.GOLD_BLOCK, "&630 дней", List.of("&7Ставка: &f" + Numbers.pct(bp.r30()), "&eКлик")));
        inv.setItem(13, it(Material.DIAMOND, "&b90 дней", List.of("&7Ставка: &f" + Numbers.pct(bp.r90()), "&eКлик")));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openLoans(Player p) {
        LoansHolder h = new LoansHolder();
        Inventory inv = Bukkit.createInventory(h, 54, GuiItems.c("&8▌&6 Мои кредиты &8▌"));
        h.inv = inv;
        GuiItems.frame54(inv);
        List<BankLoan> loans = bank().myLoans(p.getUniqueId());
        long now = System.currentTimeMillis();
        for (int i = 0; i < GRID.length && i < loans.size(); i++) {
            BankLoan l = loans.get(i);
            inv.setItem(GRID[i], it(l.isOverdue(now) ? Material.REDSTONE_BLOCK : Material.PAPER,
                    "&6Кредит &7" + l.id().substring(0, 8), List.of(
                    "&7Остаток: &c" + Numbers.fmt(l.outstanding(now)) + " " + l.currencyId(),
                    "&7Залог: &f" + l.collateralType(),
                    l.isOverdue(now) ? "&cПРОСРОЧЕН" : "&7До: &f" + TimeFormat.daysLeft(l.dueAt(), now),
                    "&eКлик — детали")));
        }
        for (int i = loans.size(); i < GRID.length; i++) inv.setItem(GRID[i], GuiItems.pane());
        inv.setItem(49, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openLoanDetail(Player p, String loanId) {
        BankLoan l = bank().getLoan(loanId);
        if (l == null) { msg(p, "&cКредит не найден"); return; }
        LoanDetailHolder h = new LoanDetailHolder(loanId);
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Кредит &7" + loanId.substring(0, 8) + " &8▌"));
        h.inv = inv;
        long now = System.currentTimeMillis();
        inv.setItem(13, it(Material.PAPER, "&6Сводка", List.of(
                "&7Тело: &f" + Numbers.fmt(l.principal()),
                "&7Остаток: &c" + Numbers.fmt(l.outstanding(now)),
                "&7Ставка: &f" + Numbers.pct(l.rateAnnual()),
                "&7Залог: &f" + l.collateralType())));
        inv.setItem(11, it(Material.GOLD_NUGGET, "&aПогасить часть", List.of("&eКлик → ввод суммы")));
        inv.setItem(15, it(Material.EMERALD_BLOCK, "&aПогасить полностью", List.of("&7Сумма: &f" + Numbers.fmt(l.outstanding(now)))));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openLoanType(Player p) {
        LoanTypeHolder h = new LoanTypeHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Кредит: тип &8▌"));
        h.inv = inv;
        String nation = nationOf(p);
        double uMax = nation == null ? 0 : bank().unsecuredMax(p.getUniqueId(), nation);
        inv.setItem(11, it(Material.PAPER, "&6Необеспеченный", List.of(
                "&7Лимит: &f" + Numbers.fmt(uMax),
                "&7Ставка выше, залог не нужен", "&eКлик")));
        inv.setItem(13, it(Material.ANVIL, "&6Под залог", List.of(
                "&7Залог ≥ 120% суммы", "&7Ставка ниже", "&eКлик")));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    private void openLoanCollateral(Player p) {
        LoanCollateralHolder h = new LoanCollateralHolder();
        Inventory inv = Bukkit.createInventory(h, 27, GuiItems.c("&8▌&6 Кредит: залог &8▌"));
        h.inv = inv;
        inv.setItem(11, it(Material.GOLD_BLOCK, "&6Залог валютой", List.of("&eКлик → ввод суммы")));
        inv.setItem(13, it(Material.DIAMOND_SWORD, "&6Залог предметом в руке", List.of("&eКлик → ввод суммы")));
        inv.setItem(22, it(Material.ARROW, "&7Назад", List.of()));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof BankHolder) && !(raw instanceof DepositsHolder) && !(raw instanceof DepositTermHolder)
                && !(raw instanceof LoansHolder) && !(raw instanceof LoanDetailHolder)
                && !(raw instanceof LoanTypeHolder) && !(raw instanceof LoanCollateralHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        UUID uuid = p.getUniqueId();
        String nation = nationOf(p);

        if (raw instanceof BankHolder) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 10) { WalletGui.openMain(plugin, p); return; }
            if (slot == 12) { openDeposits(p); return; }
            if (slot == 14) { openDepositTerm(p); return; }
            if (slot == 16) { openLoans(p); return; }
            if (slot == 20) { openLoanType(p); return; }
            if (slot == 22) { msg(p, "&7Ставки и лимиты нации смотри в Кабинете (король) или у короля"); return; }
            if (slot == 24 && nation != null) { msg(p, "&7Пул: &f" + Numbers.fmt(bank().pool(nation, glb()))
                    + " &7· Лимит выдач: &f" + Numbers.fmt(bank().maxLoans(nation))); return; }
            return;
        }
        if (raw instanceof DepositsHolder) {
            if (slot == 49) { openBank(p); return; }
            List<BankAccount> deps = bank().myDeposits(uuid).stream().filter(BankAccount::isActive).toList();
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] != slot) continue;
                if (i < deps.size()) {
                    String err = bank().closeDeposit(uuid, deps.get(i).id());
                    msg(p, err == null ? "&aВклад закрыт, выплата зачислена" : "&c" + err);
                    openDeposits(p);
                }
                return;
            }
            return;
        }
        if (raw instanceof DepositTermHolder) {
            if (slot == 22) { openBank(p); return; }
            BankAccount.Term term = switch (slot) { case 10 -> BankAccount.Term.DEMAND; case 11 -> BankAccount.Term.TERM_7;
                    case 12 -> BankAccount.Term.TERM_30; case 13 -> BankAccount.Term.TERM_90; default -> null; };
            if (term != null) {
                p.closeInventory();
                chatField.put(uuid, "dep-amount:" + term.name());
                msg(p, "&7Введите сумму вклада (" + glb() + "):");
            }
            return;
        }
        if (raw instanceof LoansHolder) {
            if (slot == 49) { openBank(p); return; }
            List<BankLoan> loans = bank().myLoans(uuid);
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] != slot) continue;
                if (i < loans.size()) openLoanDetail(p, loans.get(i).id());
                return;
            }
            return;
        }
        if (raw instanceof LoanDetailHolder lh) {
            if (slot == 22) { openLoans(p); return; }
            BankLoan l = bank().getLoan(lh.loanId);
            if (l == null) return;
            if (slot == 11) { p.closeInventory(); chatField.put(uuid, "repay-amount:" + lh.loanId); msg(p, "&7Введите сумму погашения:"); return; }
            if (slot == 15) {
                String err = bank().repayLoan(uuid, lh.loanId, l.outstanding(System.currentTimeMillis()));
                msg(p, err == null ? "&aКредит погашен" : "&c" + err);
                openLoans(p);
            }
            return;
        }
        if (raw instanceof LoanTypeHolder) {
            if (slot == 22) { openBank(p); return; }
            if (slot == 11) { p.closeInventory(); chatField.put(uuid, "loan-unsecured"); msg(p, "&7Сумма необеспеченного кредита (лимит " + Numbers.fmt(bank().unsecuredMax(uuid, nation)) + "):"); return; }
            if (slot == 13) { openLoanCollateral(p); return; }
            return;
        }
        if (raw instanceof LoanCollateralHolder) {
            if (slot == 22) { openLoanType(p); return; }
            if (slot == 11) { p.closeInventory(); chatField.put(uuid, "loan-sec-currency"); msg(p, "&7Сумма кредита под залог валютой:"); return; }
            if (slot == 13) { p.closeInventory(); chatField.put(uuid, "loan-sec-item"); msg(p, "&7Сумма кредита под залог предмета в руке:"); return; }
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String nation = nationOf(p);
            if (nation == null) { msg(p, "&cВы вне нации — банк недоступен"); return; }
            double v = Numbers.parseDouble(text, -1);
            if (field.startsWith("dep-amount:")) {
                if (!(v > 0)) { msg(p, "&cСумма > 0"); openBank(p); return; }
                BankAccount.Term term = BankAccount.Term.valueOf(field.substring("dep-amount:".length()));
                String err = bank().openDeposit(uuid, p.getName(), nation, glb(), v, term);
                msg(p, err == null ? "&aВклад открыт" : "&c" + err);
                openBank(p);
                return;
            }
            if (field.equals("loan-unsecured")) {
                if (!(v > 0)) { msg(p, "&cСумма > 0"); openBank(p); return; }
                String err = bank().applyLoanUnsecured(uuid, p.getName(), nation, glb(), v, 30);
                msg(p, err == null ? "&aКредит выдан" : "&c" + err);
                openBank(p);
                return;
            }
            if (field.equals("loan-sec-currency")) {
                if (!(v > 0)) { msg(p, "&cСумма > 0"); openBank(p); return; }
                String err = bank().applyLoan(uuid, p.getName(), nation, glb(), v, 30,
                        BankLoan.CollateralType.CURRENCY, glb(), null);
                msg(p, err == null ? "&aКредит выдан" : "&c" + err);
                openBank(p);
                return;
            }
            if (field.equals("loan-sec-item")) {
                if (!(v > 0)) { msg(p, "&cСумма > 0"); openBank(p); return; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) { msg(p, "&cПустая рука"); openBank(p); return; }
                ItemStack taken = hand.clone();
                p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                String err = bank().applyLoan(uuid, p.getName(), nation, glb(), v, 30,
                        BankLoan.CollateralType.ITEM, null, taken);
                if (err != null) p.getInventory().setItemInMainHand(taken);
                msg(p, err == null ? "&aКредит выдан, предмет в залоге" : "&c" + err + " (предмет возвращён)");
                openBank(p);
                return;
            }
            if (field.startsWith("repay-amount:")) {
                if (!(v > 0)) { msg(p, "&cСумма > 0"); openLoans(p); return; }
                String err = bank().repayLoan(uuid, field.substring("repay-amount:".length()), v);
                msg(p, err == null ? "&aПогашение принято" : "&c" + err);
                openLoans(p);
            }
        });
    }
}
