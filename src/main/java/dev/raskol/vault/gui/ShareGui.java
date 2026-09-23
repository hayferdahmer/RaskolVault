// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.share.Share;
import dev.raskol.vault.share.ShareService;
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
 * GUI Долей (1.2.4.1-fix): Рынок (непроданные сертификаты + покупка + эмиссия короля)
 * и Портфель (мои доли + погашение). Везде есть «Назад» в кабинет.
 */
public final class ShareGui implements Listener {

    public static final class MarketHolder implements InventoryHolder {
        final String nation;
        MarketHolder(String nation) { this.nation = nation; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class PortfolioHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, String> chatField = new ConcurrentHashMap<>(); // "issue-grams:<nation>"
    private final RaskolVault plugin;
    private final ShareService shares;

    public ShareGui(RaskolVault plugin, ShareService shares) {
        this.plugin = plugin;
        this.shares = shares;
    }

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

    public void openMarket(Player p, String nation) {
        MarketHolder h = new MarketHolder(nation);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Доли · " + nation + " &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());

        double total = shares.totalGrams(nation);
        double reserve = plugin.getReserveBank().reserveOf(nation);
        double price = shares.pricePerGram(nation);
        inv.setItem(4, item(Material.GOLD_BLOCK, "&6Рынок Долей", List.of(
                "&7Эмитировано всего: &f" + fmt(total) + " &7золотников",
                "&7Резерв нации: &f" + fmt(reserve) + " GLD",
                "&7Цена золотника: &f" + fmt(price) + " GLD",
                "&7Покрытие долей: &f" + (total > 0 ? fmt(reserve / total) : "—"))));

        List<Share> unsold = shares.listUnsold(nation);
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < unsold.size(); i++) {
            Share s = unsold.get(i);
            inv.setItem(grid[i], item(Material.GOLD_NUGGET, "&6Доля &7" + s.id().substring(0, 8), List.of(
                    "&7Вес: &f" + fmt(s.grams()) + " &7золотников",
                    "&7Цена покупки: &f" + fmt(s.grams() * price) + " GLD",
                    "", "&eКлик — купить (оплата в казну)")));
        }
        for (int i = unsold.size(); i < grid.length; i++) inv.setItem(grid[i], pane());

        boolean king = isKing(p, nation);
        if (king) {
            inv.setItem(46, item(Material.EMERALD, "&aЭмитировать на рынок", List.of(
                    "&7Выпустить новые сертификаты Доли,",
                    "&7обеспеченные резервом (без личного списания).",
                    "&7Максимум: &f" + fmt(shares.maxIssueGrams(nation)) + " &7золотников",
                    "&eКлик → ввод массы в чат")));
        }
        inv.setItem(50, item(Material.BOOK, "&6Мой портфель", List.of("&7Мои купленные Доли и погашение")));
        inv.setItem(49, item(Material.ARROW, "&7Назад в кабинет", List.of()));
        p.openInventory(inv);
    }

    public void openPortfolio(Player p) {
        PortfolioHolder h = new PortfolioHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мой портфель Долей &8▌"));
        h.inv = inv;
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        List<Share> mine = shares.listByHolder(p.getUniqueId());
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        for (int i = 0; i < grid.length && i < mine.size(); i++) {
            Share s = mine.get(i);
            double price = shares.pricePerGram(s.nation());
            inv.setItem(grid[i], item(Material.GOLD_INGOT, "&6Доля &7" + s.id().substring(0, 8), List.of(
                    "&7Нация: &f" + s.nation(),
                    "&7Вес: &f" + fmt(s.grams()) + " &7золотников",
                    "&7Стоимость: &f" + fmt(s.grams() * price) + " GLD",
                    "", "&eКлик — погасить (продать в казну)")));
        }
        for (int i = mine.size(); i < grid.length; i++) inv.setItem(grid[i], pane());
        inv.setItem(49, item(Material.ARROW, "&7Назад в кабинет", List.of()));
        p.openInventory(inv);
    }

    private boolean isKing(Player p, String nation) {
        return plugin.getTownyHook().isAvailable() && plugin.getTownyHook().isKing(p.getUniqueId(), nation);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof PortfolioHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { plugin.getCabinetGui().openHome(p, mh.nation); return; }
            if (slot == 50) { openPortfolio(p); return; }
            if (slot == 46) {
                if (!isKing(p, mh.nation)) { msg(p, "&cТолько король"); return; }
                chatField.put(p.getUniqueId(), "issue-grams:" + mh.nation);
                p.closeInventory();
                msg(p, "&7Введите массу эмиссии в золотниках (макс &f"
                        + fmt(shares.maxIssueGrams(mh.nation)) + "&7):");
                return;
            }
            int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] != slot) continue;
                List<Share> unsold = shares.listUnsold(mh.nation);
                if (i < unsold.size()) {
                    boolean ok = shares.buyFromMarket(unsold.get(i).id(), p.getUniqueId());
                    msg(p, ok ? "&aДоля куплена" : "&cНе удалось купить (недостаточно средств)");
                    openMarket(p, mh.nation);
                }
                return;
            }
            return;
        }

        if (raw instanceof PortfolioHolder) {
            if (slot == 49) {
                String n = plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
                if (n != null) plugin.getCabinetGui().openHome(p, n); else p.closeInventory();
                return;
            }
            int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] != slot) continue;
                List<Share> mine = shares.listByHolder(p.getUniqueId());
                if (i < mine.size()) {
                    boolean ok = shares.redeem(mine.get(i).id());
                    msg(p, ok ? "&aДоля погашена, выплата зачислена" : "&cКазна не может выплатить");
                    openPortfolio(p);
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String[] parts = field.split(":", 2);
        String nation = parts.length > 1 ? parts[1] : "";
        double v;
        try { v = Double.parseDouble(e.getMessage().replace(",", ".").trim()); }
        catch (NumberFormatException ex) { msg(p, "&cНекорректное число"); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(v > 0)) { msg(p, "&cМасса > 0"); openMarket(p, nation); return; }
            if (v > shares.maxIssueGrams(nation) + 1e-9D) {
                msg(p, "&cМаксимум &f" + fmt(shares.maxIssueGrams(nation)) + "&c золотников (резерв)");
                openMarket(p, nation); return;
            }
            Share s = shares.issueToMarket(nation, v);
            msg(p, s != null ? "&aЭмитировано &f" + fmt(v) + " &aзолотников на рынок" : "&cНе удалось");
            openMarket(p, nation);
        });
    }
}
