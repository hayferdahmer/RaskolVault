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
 * GUI Долей и Ужитка (1.2.4).
 * Страницы:
 *  - Портфель (мои Доли) — список своих долей, продажа
 *  - Рынок (эмитированные Доли по нациям, король-эмитент виден)
 */
public final class ShareGui implements Listener {

    public static final class PortfolioHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MarketHolder implements InventoryHolder {
        final String nation;
        MarketHolder(String nation) { this.nation = nation; }
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, String> chatField = new ConcurrentHashMap<>(); // "issue-grams"|"uzhitok-share"
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
            meta.setLore(l);
            s.setItemMeta(meta);
        }
        return s;
    }
    private static ItemStack pane() { return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    /** Портфель — мои Доли по всем нациям. */
    public void openPortfolio(Player p) {
        PortfolioHolder h = new PortfolioHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мой портфель Долей &8▌"));
        h.inv = inv;
        List<Share> mine = shares.listByHolder(p.getUniqueId());
        for (int i = 0; i < 45 && i < mine.size(); i++) {
            Share s = mine.get(i);
            double price = shares.pricePerGram(s.nation());
            inv.setItem(i, item(Material.PAPER, "&6Доля &7" + s.id().substring(0, 8), List.of(
                    "&7Нация: &f" + s.nation(),
                    "&7Вес: &f" + fmt(s.grams()) + " золотников",
                    "&7Текущая цена: &f" + fmt(s.grams() * price) + " GLD",
                    "", "&eКлик — продать в казну")));
        }
        for (int i = mine.size(); i < 45; i++) inv.setItem(i, pane());
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    /** Рынок Долей одной нации — кто держит, сколько. */
    public void openMarket(Player p, String nation) {
        MarketHolder h = new MarketHolder(nation);
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Доли · " + nation + " &8▌"));
        h.inv = inv;
        double total = shares.totalGrams(nation);
        double reserve = plugin.getReserveBank().reserveOf(nation);
        double coverage = total > 0 ? reserve / total : 0.0D;
        double price = shares.pricePerGram(nation);

        inv.setItem(4, item(Material.GOLD_BLOCK, "&6Сводка Долей", List.of(
                "&7Всего эмитировано: &f" + fmt(total) + " золотников",
                "&7Резерв: &f" + fmt(reserve) + " GLD",
                "&7Покрытие: &f" + String.format(Locale.ROOT, "%.1f%%", coverage * 100),
                "&7Цена за золотник: &f" + fmt(price) + " GLD")));

        Map<UUID, Double> holders = shares.holdersOf(nation);
        int slot = 10;
        for (Map.Entry<UUID, Double> e : holders.entrySet()) {
            if (slot > 44) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            inv.setItem(slot, item(Material.GOLD_NUGGET, "&6" + (name == null ? e.getKey().toString().substring(0, 8) : name),
                    List.of("&7Держит: &f" + fmt(e.getValue()) + " золотников",
                            "&7Доля от общего: &f" + String.format(Locale.ROOT, "%.2f%%", e.getValue() / total * 100))));
            slot++;
        }

        boolean king = isKing(p, nation);
        if (king) {
            inv.setItem(46, item(Material.EMERALD, "&aЭмитировать Долю", List.of("&7Чат-ввод граммов")));
            inv.setItem(48, item(Material.SUNFLOWER, "&aРаздать Ужиток", List.of("&7Чат-ввод % от казны")));
        }
        inv.setItem(50, item(Material.BOOK, "&6Мой портфель", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    private boolean isKing(Player p, String nation) {
        return plugin.getTownyHook().isAvailable()
                && plugin.getTownyHook().isKing(p.getUniqueId(), nation);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof PortfolioHolder) && !(raw instanceof MarketHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof PortfolioHolder) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot < 45) {
                List<Share> mine = shares.listByHolder(p.getUniqueId());
                if (slot < mine.size()) {
                    Share s = mine.get(slot);
                    boolean ok = shares.redeem(s.id());
                    msg(p, ok ? "&aДоля продана в казну" : "&cКазна не может выплатить");
                    openPortfolio(p);
                }
            }
            return;
        }

        if (raw instanceof MarketHolder mh) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 50) { openPortfolio(p); return; }
            if (slot == 46) {
                if (!isKing(p, mh.nation)) { msg(p, "&cТолько король"); return; }
                chatField.put(p.getUniqueId(), "issue-grams:" + mh.nation);
                p.closeInventory();
                msg(p, "&7Введите массу новой Доли в золотниках (макс &f"
                        + fmt(shares.maxIssueGrams(mh.nation)) + "&7):");
                return;
            }
            if (slot == 48) {
                if (!isKing(p, mh.nation)) { msg(p, "&cТолько король"); return; }
                chatField.put(p.getUniqueId(), "uzhitok-share:" + mh.nation);
                p.closeInventory();
                msg(p, "&7Введите % от казны для раздачи Ужитка (0..100):");
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
        String action = parts[0];
        String nation = parts.length > 1 ? parts[1] : "";
        double v;
        try { v = Double.parseDouble(e.getMessage().replace(",", ".").trim()); }
        catch (NumberFormatException ex) { msg(p, "&cНекорректное число"); return; }

        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (action) {
                case "issue-grams" -> {
                    if (!(v > 0)) { msg(p, "&cМасса > 0"); return; }
                    if (v > shares.maxIssueGrams(nation) + 1e-9D) {
                        msg(p, "&cМаксимум &f" + fmt(shares.maxIssueGrams(nation)) + "&c золотников");
                        return;
                    }
                    Share s = shares.issue(nation, p.getUniqueId(), v);
                    msg(p, s != null ? "&aДоля &f" + s.id().substring(0, 8) + " &aэмитирована" : "&cНе удалось");
                    openMarket(p, nation);
                }
                case "uzhitok-share" -> {
                    if (v <= 0 || v > 100) { msg(p, "&c% в пределах 0..100"); return; }
                    double total = shares.distributeUzhitok(nation, v);
                    msg(p, "&aУжиток роздан: &f" + fmt(total) + " GLD");
                    openMarket(p, nation);
                }
            }
        });
    }
}
