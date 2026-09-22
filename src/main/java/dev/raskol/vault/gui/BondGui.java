// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.bond.Bond;
import dev.raskol.vault.bond.BondService;
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
 * GUI облигаций (1.2.3): рынок (покупка), мои облигации (погашение), эмиссия (король).
 */
public final class BondGui implements Listener {

    public static final class MarketHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    public static final class MyHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    // wizard эмиссии: step 0=face,1=coupon,2=term
    private static final class IssueWizard { int step; double face; double coupon; }
    private final Map<UUID, IssueWizard> wizards = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatField = new ConcurrentHashMap<>(); // "face"|"coupon"|"term"

    private final RaskolVault plugin;
    private final BondService bonds;

    public BondGui(RaskolVault plugin, BondService bonds) {
        this.plugin = plugin;
        this.bonds = bonds;
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

    public void openMarket(Player p) {
        MarketHolder h = new MarketHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Королевская рента · рынок &8▌"));
        h.inv = inv;
        List<Bond> open = bonds.listOpen();
        for (int i = 0; i < 45 && i < open.size(); i++) {
            Bond b = open.get(i);
            inv.setItem(i, item(Material.PAPER, "&6Рента &7" + b.id().substring(0, 8), List.of(
                    "&7Нация: &f" + b.nation(),
                    "&7Номинал: &f" + fmt(b.face()) + " GLD",
                    "&7Купон: &f" + String.format(Locale.ROOT, "%.1f%%", b.couponRate() * 100) + " годовых",
                    "&7Дней до погашения: &f" + b.remainingDays(System.currentTimeMillis()),
                    "", "&eКлик — купить за номинал")));
        }
        for (int i = open.size(); i < 45; i++) inv.setItem(i, pane());
        inv.setItem(45, item(Material.BOOK, "&6Мои облигации", List.of()));
        if (isKing(p)) inv.setItem(50, item(Material.GOLD_BLOCK, "&aВыпустить ренту", List.of("&7Только король")));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    public void openMy(Player p) {
        MyHolder h = new MyHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&8▌&6 Мои облигации &8▌"));
        h.inv = inv;
        List<Bond> mine = bonds.listByHolder(p.getUniqueId());
        for (int i = 0; i < 45 && i < mine.size(); i++) {
            Bond b = mine.get(i);
            boolean matured = b.isMatured(System.currentTimeMillis());
            inv.setItem(i, item(matured ? Material.GOLD_INGOT : Material.PAPER,
                    "&6Рента &7" + b.id().substring(0, 8), List.of(
                    "&7Нация: &f" + b.nation(),
                    "&7Номинал: &f" + fmt(b.face()) + " GLD",
                    "&7Купон накоплен: &f" + fmt(b.accruedCoupon(System.currentTimeMillis())),
                    matured ? "&aСозрела — клик для погашения" : "&7Дней до погашения: &f" + b.remainingDays(System.currentTimeMillis()))));
        }
        for (int i = mine.size(); i < 45; i++) inv.setItem(i, pane());
        inv.setItem(45, item(Material.CHEST, "&6Рынок", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cЗакрыть", List.of()));
        p.openInventory(inv);
    }

    private boolean isKing(Player p) {
        if (!plugin.getTownyHook().isAvailable()) return false;
        String n = plugin.getTownyHook().nationOf(p.getUniqueId());
        return n != null && plugin.getTownyHook().isKing(p.getUniqueId(), n);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder raw = e.getInventory().getHolder();
        if (!(raw instanceof MarketHolder) && !(raw instanceof MyHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (raw instanceof MarketHolder) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 45) { openMy(p); return; }
            if (slot == 50) {
                if (!isKing(p)) { msg(p, "&cТолько король может выпускать ренту"); return; }
                wizards.put(p.getUniqueId(), new IssueWizard());
                chatField.put(p.getUniqueId(), "face");
                p.closeInventory();
                msg(p, "&7Введите номинал ренты (GLD):");
                return;
            }
            if (slot < 45) {
                List<Bond> open = bonds.listOpen();
                if (slot < open.size()) {
                    Bond b = open.get(slot);
                    boolean ok = bonds.buy(b.id(), p.getUniqueId());
                    msg(p, ok ? "&aКуплена рента &f" + b.id().substring(0, 8) : "&cНе удалось купить (недостаточно средств)");
                    openMarket(p);
                }
            }
            return;
        }

        if (raw instanceof MyHolder) {
            if (slot == 49) { p.closeInventory(); return; }
            if (slot == 45) { openMarket(p); return; }
            if (slot < 45) {
                List<Bond> mine = bonds.listByHolder(p.getUniqueId());
                if (slot < mine.size()) {
                    Bond b = mine.get(slot);
                    if (!b.isMatured(System.currentTimeMillis())) { msg(p, "&cЕщё не созрела"); return; }
                    boolean ok = bonds.redeem(b.id(), p.getUniqueId());
                    msg(p, ok ? "&aПогашено: номинал + купон зачислены" : "&cКазна не может выплатить");
                    openMy(p);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String field = chatField.remove(e.getPlayer().getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        double v;
        try { v = Double.parseDouble(e.getMessage().replace(",", ".").trim()); }
        catch (NumberFormatException ex) { msg(p, "&cНекорректное число"); return; }
        IssueWizard w = wizards.get(p.getUniqueId());
        if (w == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (field) {
                case "face" -> {
                    if (v <= 0) { msg(p, "&cНоминал должен быть > 0"); wizards.remove(p.getUniqueId()); return; }
                    w.face = v; w.step = 1;
                    chatField.put(p.getUniqueId(), "coupon");
                    msg(p, "&7Введите купонную ставку в % годовых (0..20):");
                }
                case "coupon" -> {
                    if (v < 0 || v > 20) { msg(p, "&cСтавка 0..20%"); wizards.remove(p.getUniqueId()); return; }
                    w.coupon = v / 100.0D; w.step = 2;
                    chatField.put(p.getUniqueId(), "term");
                    msg(p, "&7Введите срок в днях:");
                }
                case "term" -> {
                    if (v <= 0) { msg(p, "&cСрок должен быть > 0"); wizards.remove(p.getUniqueId()); return; }
                    String nation = plugin.getTownyHook().nationOf(p.getUniqueId());
                    if (nation == null) { msg(p, "&cУ вас нет нации"); wizards.remove(p.getUniqueId()); return; }
                    Bond b = bonds.issue(nation, w.face, w.coupon, (long) v);
                    wizards.remove(p.getUniqueId());
                    msg(p, b != null ? "&aРента выпущена: &f" + b.id().substring(0, 8) : "&cНе удалось выпустить");
                    openMarket(p);
                }
            }
        });
    }
}
