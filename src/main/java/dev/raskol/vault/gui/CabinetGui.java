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
 * Кабинет государя (1.2.2-b): полный GUI для короля нации.
 * Страницы:
 *  - Главная (обзор + навигация)
 *  - Резерв (ячейки + депозит/вывод)
 *  - Монетарная (паритет ±, минт/бёрн)
 *  - Налоговая (3 налога 0..5%)
 *  - Отчёты (сводка за сутки)
 */
public final class CabinetGui implements Listener {

    public static final double CELL_GLD = 1000.0D;
    public static final Map<UUID, String[]> CHAT_CAPTURE = new ConcurrentHashMap<>(); // {nation, mode}

    // mode: deposit / withdraw / parity / mint / burn / tax-convert / tax-exchange / tax-market
    public static final class Holder implements InventoryHolder {
        final String nation;
        final String page;
        Holder(String nation, String page) { this.nation = nation; this.page = page; }
        public String nation() { return nation; }
        public String page() { return page; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final RaskolVault plugin;

    public CabinetGui(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(Player p, String raw) { p.sendMessage(c(plugin.getMessages().prefix() + raw)); }

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
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private static String fmtPct(double v) { return String.format(Locale.ROOT, "%.1f%%", v * 100.0); }

    // ---------- Главная страница ----------
    public void openHome(Player king, String nation) {
        Holder h = new Holder(nation, "home");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Кабинет " + nation + " &8▌"));
        h.inv = inv;

        ReserveBank bank = plugin.getReserveBank();
        double reserve = bank.reserveOf(nation);
        Currency national = nationalCurrency(nation);
        double supply = national == null ? 0.0D : bank.supplyOf(national.id());
        double parity = bank.parityOf(nation);
        double price = national == null ? 0.0D : bank.priceOf(national);
        double coverage = national == null ? 0.0D : bank.coverageOf(nation, national.id());

        // заголовок — сводка
        inv.setItem(4, item(Material.GOLDEN_APPLE, "&6Обзор государства", List.of(
                "&7Нация: &f" + nation,
                "&7Король: &f" + nameOf(king),
                "&7Резерв: &f" + fmt(reserve) + " GLD",
                "&7Эмиссия: &f" + fmt(supply) + " " + (national == null ? "?" : national.id()),
                "&7Паритет: &f" + fmt(parity),
                "&7Цена: &f" + fmt(price) + " GLD",
                "&7Покрытие: &f" + fmtPct(coverage))));

        // навигация
        inv.setItem(20, item(Material.GOLD_BLOCK, "&6🏦 Резерв", List.of("&7Ячейки, депозит/вывод")));
        inv.setItem(22, item(Material.COMPASS, "&6📈 Монетарная политика", List.of("&7Паритет, минт/бёрн")));
        inv.setItem(24, item(Material.TRIPWIRE_HOOK, "&6⚖ Налоговая система", List.of("&7Ставки 0..5% по 3 каналам")));
        inv.setItem(30, item(Material.WRITABLE_BOOK, "&6📊 Отчёты", List.of("&7Сборы налогов за сутки")));
        inv.setItem(32, item(Material.PAPER, "&6📖 Кодекс правителя", List.of("&7Формулы и лимиты")));

        for (int i = 36; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));

        king.openInventory(inv);
    }

    // ---------- Резерв (ячейки) ----------
    public void openReserve(Player king, String nation) {
        Holder h = new Holder(nation, "reserve");
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Резерв · " + nation + " &8▌"));
        h.inv = inv;
        double reserve = plugin.getReserveBank().reserveOf(nation);
        int cells = (int) Math.min(27L, (long) (reserve / CELL_GLD));
        for (int i = 0; i < cells; i++) {
            inv.setItem(i, item(Material.GOLD_BLOCK, "&6Ячейка резерва",
                    List.of("&71000 GLD", "&7Всего: &f" + fmt(reserve) + " GLD")));
        }
        for (int i = cells; i < 27; i++) inv.setItem(i, pane());

        inv.setItem(27, item(Material.GOLD_BLOCK, "&aДепозит", List.of("&7Внести своё золото", "&7Клик → ввод в чат")));
        inv.setItem(28, item(Material.HOPPER, "&cВывод", List.of("&7Вывести из резерва", "&7Лимит 25% в сутки", "&7Клик → ввод в чат")));
        for (int i = 36; i < 54; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.ARROW, "&7← Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- Монетарная ----------
    public void openMonetary(Player king, String nation) {
        Holder h = new Holder(nation, "monetary");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Монетарная · " + nation + " &8▌"));
        h.inv = inv;

        double parity = plugin.getReserveBank().parityOf(nation);
        Currency national = nationalCurrency(nation);
        double supply = national == null ? 0.0D : plugin.getReserveBank().supplyOf(national.id());
        double maxMint = national == null ? 0.0D : plugin.getReserveBank().maxMint(nation, national.id());

        inv.setItem(10, item(Material.REDSTONE, "&cПаритет −0.05", List.of("&7Текущий: &f" + fmt(parity))));
        inv.setItem(12, item(Material.NETHER_STAR, "&6Паритет &f" + fmt(parity), List.of("&7Король может менять", "&7в пределах 0.5..2.0")));
        inv.setItem(14, item(Material.GLOWSTONE_DUST, "&aПаритет +0.05", List.of("&7Текущий: &f" + fmt(parity))));

        inv.setItem(16, item(Material.EMERALD, "&aМинт (эмиссия)",
                List.of("&7Сумма: &f(чат-ввод)", "&7Лимит: &f" + fmt(maxMint) + " " + (national == null ? "?" : national.id()))));

        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7← Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- Налоговая ----------
    public void openTax(Player king, String nation) {
        Holder h = new Holder(nation, "tax");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Налоги · " + nation + " &8▌"));
        h.inv = inv;

        TaxService tax = plugin.getTaxService();
        double conv = tax.getConvertRate(nation);
        double exch = tax.getExchangeRate(nation);
        double mkt = tax.getMarketRate(nation);
        TaxService.DailyReport r = tax.todayReport(nation);

        inv.setItem(10, item(Material.GOLD_NUGGET, "&6Конверсионный &f" + fmtPct(conv),
                List.of("&7При обмене в национальную валюту", "&7Собрано сегодня: &f" + fmt(r.convert()))));
        inv.setItem(12, item(Material.ENDER_CHEST, "&6Торговый &f" + fmtPct(exch),
                List.of("&7С сделок на бирже", "&7Собрано сегодня: &f" + fmt(r.exchange()))));
        inv.setItem(14, item(Material.CHEST, "&6Рыночный &f" + fmtPct(mkt),
                List.of("&7С ChestShop/ESGUI в черте нации", "&7Собрано сегодня: &f" + fmt(r.market()))));
        inv.setItem(16, item(Material.GOLD_INGOT, "&6Всего за сутки: &f" + fmt(r.total()),
                List.of("&7Поступления в казну")));

        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7← Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- Отчёты (те же данные, другая подача) ----------
    public void openReports(Player king, String nation) {
        openTax(king, nation); // переиспользуем налоговую страницу как отчёт
    }

    // ---------- Кодекс (текст) ----------
    public void openCodex(Player king, String nation) {
        Holder h = new Holder(nation, "codex");
        Inventory inv = Bukkit.createInventory(h, 27, c("&8▌&6 Кодекс правителя &8▌"));
        h.inv = inv;
        inv.setItem(13, item(Material.WRITABLE_BOOK, "&6Формулы и лимиты", List.of(
                "&7• Покрытие = резерв / (эмиссия × паритет)",
                "&7• Цена = min(паритет, резерв / эмиссия)",
                "&7• Паритет: 0.5..2.0",
                "&7• Покрытие < 100% — валюта дешевеет",
                "&7• Покрытие < 50% — КРИЗИС",
                "&7• Вывод резерва: ≤ 25% в сутки",
                "&7• Налоги: 0..5% по каждому каналу")));
        for (int i = 18; i < 27; i++) inv.setItem(i, pane());
        inv.setItem(22, item(Material.ARROW, "&7← Назад", List.of()));
        king.openInventory(inv);
    }

    // ---------- Клики ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        String nation = h.nation();

        switch (h.page()) {
            case "home" -> {
                if (slot == 20) openReserve(p, nation);
                else if (slot == 22) openMonetary(p, nation);
                else if (slot == 24) openTax(p, nation);
                else if (slot == 30) openReports(p, nation);
                else if (slot == 32) openCodex(p, nation);
                else if (slot == 49) p.closeInventory();
            }
            case "reserve" -> {
                if (slot == 27) { capture(p, nation, "deposit"); return; }
                if (slot == 28) { capture(p, nation, "withdraw"); return; }
                if (slot == 49) { openHome(p, nation); return; }
            }
            case "monetary" -> {
                if (slot == 10) { adjustParity(p, nation, -0.05); return; }
                if (slot == 14) { adjustParity(p, nation, +0.05); return; }
                if (slot == 16) { capture(p, nation, "mint"); return; }
                if (slot == 22) { openHome(p, nation); return; }
            }
            case "tax" -> {
                if (slot == 10) { capture(p, nation, "tax-convert"); return; }
                if (slot == 12) { capture(p, nation, "tax-exchange"); return; }
                if (slot == 14) { capture(p, nation, "tax-market"); return; }
                if (slot == 22) { openHome(p, nation); return; }
            }
            case "codex" -> {
                if (slot == 22) openHome(p, nation);
            }
        }
    }

    // ---------- Чат-захват ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String[] ctx = CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String nation = ctx[0];
        String mode = ctx[1];
        double amount;
        try { amount = Double.parseDouble(e.getMessage().replace(",", ".").trim()); }
        catch (NumberFormatException ex) { msg(p, "&cНекорректная сумма"); return; }
        if (!Double.isFinite(amount)) { msg(p, "&cНекорректная сумма"); return; }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ReserveBank bank = plugin.getReserveBank();
            TaxService tax = plugin.getTaxService();
            switch (mode) {
                case "deposit" -> {
                    if (amount <= 0) { msg(p, "&cСумма должна быть > 0"); return; }
                    boolean ok = bank.depositToReserve(p.getUniqueId(), nation, amount, "cabinet");
                    msg(p, ok ? "&aВнесено &f" + fmt(amount) + " GLD" : "&cНедостаточно золота");
                }
                case "withdraw" -> {
                    if (amount <= 0) { msg(p, "&cСумма должна быть > 0"); return; }
                    boolean ok = bank.withdrawFromReserve(p.getUniqueId(), nation, amount, "cabinet");
                    msg(p, ok ? "&aВыведено &f" + fmt(amount) + " GLD" : "&cОтказ (лимит 25% или мало резерва)");
                }
                case "mint" -> {
                    if (amount <= 0) { msg(p, "&cСумма должна быть > 0"); return; }
                    Currency nat = nationalCurrency(nation);
                    if (nat == null) { msg(p, "&cУ нации нет национальной валюты"); return; }
                    boolean ok = bank.mint(nation, nat.id(), amount, "cabinet");
                    msg(p, ok ? "&aЭмитировано &f" + fmt(amount) + " " + nat.id() : "&cОтказ (покрытие)");
                }
                case "tax-convert", "tax-exchange", "tax-market" -> {
                    double newRate;
                    if (amount < 0) newRate = 0.0D;
                    else if (amount > 5.0D) newRate = 0.05D; // процент в долях
                    else newRate = amount / 100.0D;
                    double set;
                    String label;
                    if (mode.endsWith("convert")) { set = tax.setConvertRate(nation, newRate); label = "Конверсионный"; }
                    else if (mode.endsWith("exchange")) { set = tax.setExchangeRate(nation, newRate); label = "Торговый"; }
                    else { set = tax.setMarketRate(nation, newRate); label = "Рыночный"; }
                    msg(p, "&a" + label + " налог: &f" + fmtPct(set));
                }
            }
            reopenAfterChat(p, nation, mode);
        });
    }

    private void capture(Player p, String nation, String mode) {
        p.closeInventory();
        CHAT_CAPTURE.put(p.getUniqueId(), new String[]{nation, mode});
        String prompt = switch (mode) {
            case "deposit" -> "&7Введите сумму депозита в чат (GLD):";
            case "withdraw" -> "&7Введите сумму вывода в чат (GLD):";
            case "mint" -> "&7Введите сумму эмиссии в чат (единицы национальной валюты):";
            case "tax-convert", "tax-exchange", "tax-market" -> "&7Введите ставку налога в % (0..5), например 2.5:";
            default -> "&7Введите число:";
        };
        p.sendMessage(c(plugin.getMessages().prefix() + prompt));
    }

    private void reopenAfterChat(Player p, String nation, String mode) {
        switch (mode) {
            case "deposit", "withdraw" -> openReserve(p, nation);
            case "mint" -> openMonetary(p, nation);
            case "tax-convert", "tax-exchange", "tax-market" -> openTax(p, nation);
        }
    }

    private void adjustParity(Player p, String nation, double delta) {
        ReserveBank bank = plugin.getReserveBank();
        double cur = bank.parityOf(nation);
        double next = cur + delta;
        if (next < 0.5D || next > 2.0D) { msg(p, "&cПаритет ограничен диапазоном 0.5..2.0"); return; }
        boolean ok = bank.setParity(nation, next, "cabinet");
        msg(p, ok ? "&aПаритет: &f" + fmt(next) : "&cОтказ");
        openMonetary(p, nation);
    }

    private Currency nationalCurrency(String nation) {
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) return cur;
        }
        return null;
    }

    private String nameOf(Player p) { return p.getName(); }
}
