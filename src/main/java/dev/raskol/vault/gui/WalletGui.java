// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GUI «Кошелёк» (1.1.2): кошелёк БЕЗ кабинета и кодекса (только валюты + конверт/курсы/история).
 * Кабинет правителя: рычаги + внизу книги по механике валюты (открывают кодекс).
 */
public final class WalletGui {

    public static final java.util.Map<java.util.UUID, String[]> CHAT_CAPTURE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String TITLE_MAIN = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Кошелёк &8▌");
    private static final String TITLE_FROM = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Конверт: из &8▌");
    private static final String TITLE_TO = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Конверт: во &8▌");
    private static final String TITLE_AMOUNT = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Конверт: сумма &8▌");
    private static final String TITLE_CONFIRM = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Подтверждение &8▌");
    private static final String TITLE_RATES = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Курсы &8▌");
    private static final String TITLE_HISTORY = ChatColor.translateAlternateColorCodes('&', "&8▌&6 История &8▌");
    private static final String TITLE_CABINET = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Кабинет правителя &8▌");
    private static final String TITLE_CODEX = ChatColor.translateAlternateColorCodes('&', "&8▌&6 Кодекс правителя &8▌");

    private static final ItemStack PANE = item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of());

    /** Страницы кодекса: механика валюты простыми словами. */
    public static final List<String[]> CODEX_PAGES = List.of(
            new String[]{
                    "&6I. Резерв и покрытие",
                    "&7Резерв — золото нации, внесённое депозитом.",
                    "&7Покрытие = резерв / (эмиссия × паритет).",
                    "&7≥100% = валюта полностью обеспечена.",
                    "&7<100% = валюта обеспечена частично → дешевеет.",
                    "&717500% = переобеспечение: цена = паритет, минт-лимит большой."
            },
            new String[]{
                    "&6II. Паритет и цена",
                    "&7Паритет = объявленный курс нации к золоту.",
                    "&7Это ПОТОЛОК цены, не пол.",
                    "&7Цена = min(паритет, резерв/эмиссия).",
                    "&7Границы паритета 0.50…2.00 — чтобы король",
                    "&7не мог устроить гипер-ревальвацию одним кликом."
            },
            new String[]{
                    "&6III. Покрытие и девальвация",
                    "&7Покрытие ≥100% → цена = паритет (стабильно).",
                    "&7Покрытие <100% → цена = резерв/эмиссия (дешевеет).",
                    "&7Вернул резерв ≥100% → цена вернулась к паритету",
                    "&7автоматически (ре-вальвация, broadcast нации).",
                    "&7Кризис = покрытие < пола (0.5) → broadcast."
            },
            new String[]{
                    "&6IV. Налог и конвертация",
                    "&7Конверт A→B идёт через резерв нации A.",
                    "&7Резерв A отдаёт золото, резерв B его принимает.",
                    "&7Налог конвертации В валюту B идёт в казну B.",
                    "&7Границы налога 0…5% (рычаг короля B).",
                    "&7Если резерв A исчерпан — конверт закрыт."
            },
            new String[]{
                    "&6V. Кризис и команды",
                    "&7Кризис = резерв исчерпан → конверты закрыты.",
                    "&7Лечится депозитом золота в резерв.",
                    "&7Команды: /rv bank info|deposit|withdraw|parity|tax",
                    "&7Админ: /rv admin mint|burn|reserve set|add",
                    "&7Минт облагается сеньоражем (burn в никуда)."
            }
    );

    private WalletGui() {
    }

    // ---------- страницы ----------

    public static void openMain(RaskolVault plugin, Player player) {
        WalletGuiHolder holder = WalletGuiHolder.of(player.getUniqueId(), WalletGuiHolder.Page.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_MAIN);
        border(inv);
        int slot = 10;
        for (Currency c : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(player.getUniqueId(), c.id());
            inv.setItem(slot, item(iconOf(c.id()),
                    "&6" + c.displayName() + " &7(" + c.id() + ")",
                    List.of("&7Баланс: &f" + Formatter.amount(bal, c.decimals()) + " " + c.id(),
                            "&7Цена в золоте: &f" + String.format(Locale.ROOT, "%.4f", plugin.getReserveBank().priceOf(c)),
                            "&7Нажми для конвертации")));
            slot += 2;
        }
        inv.setItem(29, item(Material.EMERALD, "&aКонверт", List.of("&7Обменять одну валюту на другую")));
        inv.setItem(31, item(Material.MAP, "&6Курсы", List.of("&7Матрица курсов и комиссий")));
        inv.setItem(33, item(Material.CLOCK, "&bИстория", List.of("&7Твои последние транзакции")));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        player.openInventory(inv);
    }

    public static void openConvertFrom(RaskolVault plugin, Player player) {
        WalletGuiHolder holder = WalletGuiHolder.of(player.getUniqueId(), WalletGuiHolder.Page.CONVERT_FROM);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_FROM);
        border27(inv);
        int slot = 10;
        for (Currency c : plugin.getCurrencies().all()) {
            double bal = plugin.getWallets().getBalance(player.getUniqueId(), c.id());
            inv.setItem(slot, item(iconOf(c.id()), "&6" + c.displayName(),
                    List.of("&7Баланс: &f" + Formatter.amount(bal, c.decimals()) + " " + c.id())));
            slot += 2;
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        player.openInventory(inv);
    }

    public static void openConvertTo(RaskolVault plugin, Player player, String fromId) {
        WalletGuiHolder holder = WalletGuiHolder.ofConvert(player.getUniqueId(), WalletGuiHolder.Page.CONVERT_TO, fromId, null, 0.0D);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_TO);
        border27(inv);
        int slot = 10;
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.id().equals(fromId)) {
                continue;
            }
            inv.setItem(slot, item(iconOf(c.id()), "&6" + c.displayName(),
                    List.of("&7Курс: &f" + String.format(Locale.ROOT, "%.4f",
                            plugin.getConvertEngine().rate(fromId, c.id())))));
            slot += 2;
        }
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        player.openInventory(inv);
    }

    public static void openConvertAmount(RaskolVault plugin, Player player, String fromId, String toId) {
        WalletGuiHolder holder = WalletGuiHolder.ofConvert(player.getUniqueId(), WalletGuiHolder.Page.CONVERT_AMOUNT, fromId, toId, 0.0D);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_AMOUNT);
        border27(inv);
        double bal = plugin.getWallets().getBalance(player.getUniqueId(), fromId);
        inv.setItem(10, item(Material.GOLD_NUGGET, "&61 " + fromId, List.of("&7Нажми для выбора")));
        inv.setItem(11, item(Material.GOLD_NUGGET, "&610 " + fromId, List.of("&7Нажми для выбора")));
        inv.setItem(12, item(Material.GOLD_NUGGET, "&664 " + fromId, List.of("&7Нажми для выбора")));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6100 " + fromId, List.of("&7Нажми для выбора")));
        inv.setItem(14, item(Material.GOLD_BLOCK, "&6Весь баланс",
                List.of("&7" + Formatter.amount(bal, 2) + " " + fromId)));
        inv.setItem(16, item(Material.PAPER, "&bВвести свою сумму", List.of("&7Напиши число в чат")));
        inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
        player.openInventory(inv);
    }

    public static void openConvertConfirm(RaskolVault plugin, Player player, String fromId, String toId, double amount) {
        WalletGuiHolder holder = WalletGuiHolder.ofConvert(player.getUniqueId(), WalletGuiHolder.Page.CONVERT_CONFIRM, fromId, toId, amount);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_CONFIRM);
        border27(inv);
        var quote = plugin.getConvertEngine().quote(player.getUniqueId(), fromId, toId, amount);
        if (quote.isEmpty()) {
            inv.setItem(13, item(Material.BARRIER, "&cКонвертация невозможна",
                    List.of("&7Недостаточно средств или нет курса")));
            inv.setItem(22, item(Material.ARROW, "&7Назад", List.of()));
            player.openInventory(inv);
            return;
        }
        var q = quote.get();
        Currency from = plugin.getCurrencies().get(fromId).orElse(null);
        Currency to = plugin.getCurrencies().get(toId).orElse(null);
        inv.setItem(13, item(Material.GOLD_NUGGET, "&6Сводка конверта", List.of(
                "&7Отдаёшь: &c" + fmtSym(q.amount(), from, fromId),
                "&7Получаешь: &a" + fmtSym(q.net(), to, toId),
                "&7Курс: &f" + String.format(Locale.ROOT, "%.4f", q.rate()),
                "&7Комиссия: &f" + fmtSym(q.feeBase() + q.feeTax(), from, fromId))));
        inv.setItem(11, item(Material.RED_CONCRETE, "&cОтмена", List.of()));
        inv.setItem(15, item(Material.LIME_CONCRETE, "&aПодтвердить", List.of("&7Курс фиксируется в момент клика")));
        player.openInventory(inv);
    }

    public static void openRates(RaskolVault plugin, Player player) {
        WalletGuiHolder holder = WalletGuiHolder.of(player.getUniqueId(), WalletGuiHolder.Page.RATES);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_RATES);
        border(inv);
        int[] slots = {10, 12, 14, 19, 21, 23, 28, 30, 32};
        int i = 0;
        for (Currency a : plugin.getCurrencies().all()) {
            for (Currency b : plugin.getCurrencies().all()) {
                if (a.id().equals(b.id()) || i >= slots.length) {
                    continue;
                }
                double r = plugin.getConvertEngine().rate(a.id(), b.id());
                inv.setItem(slots[i], item(Material.PAPER, "&6" + a.id() + " → " + b.id(),
                        List.of("&7Курс: &f" + String.format(Locale.ROOT, "%.4f", r))));
                i++;
            }
        }
        inv.setItem(49, item(Material.ARROW, "&7Назад", List.of()));
        player.openInventory(inv);
    }

    public static void openHistory(RaskolVault plugin, Player player, int page) {
        WalletGuiHolder holder = WalletGuiHolder.ofPage(player.getUniqueId(), WalletGuiHolder.Page.HISTORY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_HISTORY);
        List<dev.raskol.vault.api.transaction.Transaction> all;
        try {
            all = plugin.getLedger().queryTransactions(player.getUniqueId(), 200);
        } catch (Exception e) {
            all = List.of();
        }
        int perPage = 45;
        int from = page * perPage;
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            if (idx >= all.size()) {
                break;
            }
            var tx = all.get(idx);
            boolean incoming = tx.to() != null && tx.to().equals(player.getUniqueId());
            String sign = incoming ? "&a+" : "&c-";
            inv.setItem(i, item(Material.PAPER,
                    sign + Formatter.amount(tx.amount(), 2) + " " + tx.currencyId(),
                    List.of("&7" + fmt.format(new java.util.Date(tx.timestampMillis())),
                            "&7" + tx.type(),
                            "&7" + tx.reason())));
        }
        if (page > 0) {
            inv.setItem(45, item(Material.ARROW, "&7Назад", List.of("&7стр. " + page)));
        }
        if ((page + 1) * perPage < all.size()) {
            inv.setItem(53, item(Material.ARROW, "&7Вперёд", List.of("&7стр. " + (page + 2))));
        }
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        player.openInventory(inv);
    }

    public static void openCabinet(RaskolVault plugin, Player player) {
        if (!isKing(plugin, player)) {
            return;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null) {
            return;
        }
        WalletGuiHolder holder = WalletGuiHolder.of(player.getUniqueId(), WalletGuiHolder.Page.CABINET);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_CABINET);
        border(inv);
        var bank = plugin.getReserveBank();
        Currency national = nationalOf(plugin, nation);
        double reserve = bank.reserveOf(nation);
        double coverage = national == null ? 1.0D : bank.coverageOf(nation, national.id());
        double price = national == null ? 1.0D : bank.priceOf(national);
        double parity = bank.parityOf(nation);
        double tax = bank.taxOf(nation);

        inv.setItem(10, item(Material.GOLD_BLOCK, "&6Резерв",
                List.of("&f" + Formatter.amount(reserve, 2) + " GLD",
                        "&7Золото нации, внесённое депозитом")));
        inv.setItem(13, item(coverage >= 1.0D ? Material.LIME_CONCRETE : (coverage >= bank.coverageFloor() ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE),
                "&6Покрытие",
                List.of("&f" + String.format(Locale.ROOT, "%.1f%%", coverage * 100.0D),
                        coverage >= 1.0D ? "&aВалюта торгуется по паритету" : "&eЦену держит резерв")));
        inv.setItem(16, item(Material.GOLD_INGOT, "&6Цена валюты",
                List.of("&f" + String.format(Locale.ROOT, "%.4f", price) + " GLD")));

        inv.setItem(19, item(Material.RED_STAINED_GLASS_PANE, "&cПаритет −0.10",
                List.of("&7Сейчас: &f" + String.format(Locale.ROOT, "%.2f", parity))));
        inv.setItem(21, item(Material.LIME_STAINED_GLASS_PANE, "&aПаритет +0.10",
                List.of("&7Сейчас: &f" + String.format(Locale.ROOT, "%.2f", parity))));
        inv.setItem(23, item(Material.RED_STAINED_GLASS_PANE, "&cНалог −0.5%",
                List.of("&7Сейчас: &f" + String.format(Locale.ROOT, "%.1f%%", tax * 100.0D))));
        inv.setItem(25, item(Material.LIME_STAINED_GLASS_PANE, "&aНалог +0.5%",
                List.of("&7Сейчас: &f" + String.format(Locale.ROOT, "%.1f%%", tax * 100.0D))));

        inv.setItem(29, item(Material.GOLD_NUGGET, "&6Депозит 100 GLD",
                List.of("&7Личное золото → резерв", "&7Курс крепнет")));
        inv.setItem(30, item(Material.GOLD_BLOCK, "&6Депозит 1000 GLD",
                List.of("&7Личное золото → резерв", "&7Курс крепнет")));
        inv.setItem(31, item(Material.GOLD_NUGGET, "&cВывод 100 GLD",
                List.of("&7Резерв → личное золото", "&7Лимит 25% резерва в сутки")));
        inv.setItem(32, item(Material.GOLD_BLOCK, "&cВывод 1000 GLD",
                List.of("&7Резерв → личное золото", "&7Лимит 25% резерва в сутки")));
        inv.setItem(33, item(Material.SUNFLOWER, "&aМинт 100",
                List.of("&7Эмиссия в казну (минус сеньораж)",
                        "&7Лимит: &f" + Formatter.amount(national == null ? 0 : bank.maxMint(nation, national.id()), 2))));
        inv.setItem(34, item(Material.WITHER_ROSE, "&cБёрн 100",
                List.of("&7Сжечь эмиссию из казны", "&7Курс крепнет")));

        // Книги по механике валюты (внизу кабинета)
        inv.setItem(45, item(Material.BOOK, "&6Резерв и покрытие", List.of("&7Открыть кодекс, стр. 1")));
        inv.setItem(46, item(Material.BOOK, "&6Паритет и цена", List.of("&7Открыть кодекс, стр. 2")));
        inv.setItem(47, item(Material.BOOK, "&6Налог и конвертация", List.of("&7Открыть кодекс, стр. 4")));
        inv.setItem(48, item(Material.BOOK, "&6Кризис и команды", List.of("&7Открыть кодекс, стр. 5")));
        inv.setItem(53, item(Material.ARROW, "&7Назад", List.of()));
        player.openInventory(inv);
    }

    public static void openCodex(RaskolVault plugin, Player player, int page) {
        int clamped = Math.max(0, Math.min(page, CODEX_PAGES.size() - 1));
        WalletGuiHolder holder = WalletGuiHolder.ofPage(player.getUniqueId(), WalletGuiHolder.Page.CODEX, clamped);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_CODEX);
        border27(inv);
        String[] lines = CODEX_PAGES.get(clamped);
        List<String> lore = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            lore.add(lines[i]);
        }
        lore.add("");
        lore.add("&7стр. " + (clamped + 1) + "/" + CODEX_PAGES.size());
        inv.setItem(13, item(Material.WRITTEN_BOOK, lines[0], lore));
        if (clamped > 0) {
            inv.setItem(18, item(Material.ARROW, "&7Назад", List.of()));
        }
        if (clamped < CODEX_PAGES.size() - 1) {
            inv.setItem(26, item(Material.ARROW, "&7Вперёд", List.of()));
        }
        inv.setItem(22, item(Material.BARRIER, "&cЗакрыть", List.of()));
        player.openInventory(inv);
    }

    // ---------- helpers ----------

    public static boolean isKing(RaskolVault plugin, Player player) {
        if (!plugin.getTownyHook().isAvailable()) {
            return false;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        return nation != null && plugin.getTownyHook().isKing(player.getUniqueId(), nation);
    }

    private static Currency nationalOf(RaskolVault plugin, String nation) {
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) {
                return cur;
            }
        }
        return null;
    }

    private static String fmtSym(double value, Currency cur, String fallback) {
        if (cur == null) {
            return Formatter.amount(value, 2) + " " + fallback;
        }
        return Formatter.withSymbol(value, cur.decimals(), cur.symbol());
    }

    public static Material iconOf(String currencyId) {
        switch (currencyId.toUpperCase(Locale.ROOT)) {
            case "GLD":
                return Material.GOLD_INGOT;
            case "RAS":
                return Material.SUNFLOWER;
            case "VLR":
                return Material.GOLDEN_HELMET;
            default:
                return Material.GOLD_NUGGET;
        }
    }

    private static void border(Inventory inv) {
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, PANE);
            }
        }
    }

    private static void border27(Inventory inv) {
        for (int i = 0; i < 27; i++) {
            if (i < 9 || i >= 18 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, PANE);
            }
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> colored = new ArrayList<>();
            for (String line : lore) {
                colored.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(colored);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
