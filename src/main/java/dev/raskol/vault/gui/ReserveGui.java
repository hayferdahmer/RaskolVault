// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.reserve.ReserveBank;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI «Ячейки резерва» (1.2.1): визуальное золото нации.
 * 1 ячейка (GOLD_BLOCK) = 1000 GLD резерва. Слоты 0..26 — ячейки.
 * Слот 27 = Депозит, 28 = Вывод, 29 = Назад (в кабинет), 31 = справка.
 * Ввод суммы — через чат-захват (отдельная карта, не конфликтует с WalletGui).
 */
public final class ReserveGui implements Listener {

    public static final double CELL_GLD = 1000.0D;
    public static final Map<UUID, String[]> CHAT_CAPTURE = new ConcurrentHashMap<>(); // {nation, mode}

    public static final class Holder implements InventoryHolder {
        private final String nation;
        private Inventory inventory;

        Holder(String nation) {
            this.nation = nation;
        }

        public String nation() { return nation; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private final RaskolVault plugin;

    public ReserveGui(RaskolVault plugin) {
        this.plugin = plugin;
    }

    public void open(Player king, String nation) {
        ReserveBank bank = plugin.getReserveBank();
        double reserve = bank.reserveOf(nation);
        Holder holder = new Holder(nation);
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.translateAlternateColorCodes('&', "&8▌&6 Казна " + nation + " &8▌"));
        holder.inventory = inv;

        // Ячейки золота: 1 блок = 1000 GLD
        int cells = (int) Math.min(27L, (long) (reserve / CELL_GLD));
        for (int i = 0; i < cells; i++) {
            inv.setItem(i, item(Material.GOLD_BLOCK, "&6Ячейка резерва",
                    List.of("&71000 GLD", "&7Всего в казне: &f" + fmt(reserve) + " GLD")));
        }
        // Пустые ячейки — стекло
        for (int i = cells; i < 27; i++) {
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, "&8пусто", List.of()));
        }

        inv.setItem(27, item(Material.GOLD_BLOCK, "&aДепозит",
                List.of("&7Внести своё золото в резерв", "&7Клик → ввод суммы в чат")));
        inv.setItem(28, item(Material.HOPPER, "&cВывод",
                List.of("&7Вывести золото из резерва себе", "&7Лимит: 25% резерва в сутки", "&7Клик → ввод суммы в чат")));
        inv.setItem(29, item(Material.ARROW, "&7Назад в кабинет", List.of()));
        inv.setItem(31, item(Material.BOOK, "&6Справка",
                List.of("&7Резерв обеспечивает национальную валюту.",
                        "&7Покрытие = резерв / (эмиссия × паритет).",
                        "&7Покрытие < 100% → валюта дешевеет.",
                        "&7Покрытие < 50% → КРИЗИС.")));
        for (int i = 36; i < 54; i++) {
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of()));
        }
        king.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        String nation = holder.nation();
        if (slot == 27) {
            player.closeInventory();
            CHAT_CAPTURE.put(player.getUniqueId(), new String[]{nation, "deposit"});
            player.sendMessage(prefix() + "&7Введите сумму депозита в чат (GLD):");
        } else if (slot == 28) {
            player.closeInventory();
            CHAT_CAPTURE.put(player.getUniqueId(), new String[]{nation, "withdraw"});
            player.sendMessage(prefix() + "&7Введите сумму вывода в чат (GLD):");
        } else if (slot == 29) {
            player.closeInventory();
            WalletGui.openCabinet(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String[] ctx = CHAT_CAPTURE.remove(event.getPlayer().getUniqueId());
        if (ctx == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String nation = ctx[0];
        String mode = ctx[1];
        double amount;
        try {
            amount = Double.parseDouble(event.getMessage().replace(",", ".").trim());
        } catch (NumberFormatException e) {
            player.sendMessage(prefix() + "&cНекорректная сумма");
            return;
        }
        if (!(amount > 0.0D) || !Double.isFinite(amount)) {
            player.sendMessage(prefix() + "&cНекорректная сумма");
            return;
        }
        ReserveBank bank = plugin.getReserveBank();
        boolean ok;
        if ("deposit".equals(mode)) {
            ok = bank.depositToReserve(player.getUniqueId(), nation, amount, "gui-reserve");
            player.sendMessage(prefix() + (ok
                    ? "&aВнесено &f" + fmt(amount) + " GLD &aв резерв " + nation
                    : "&cНедостаточно личного золота"));
        } else {
            ok = bank.withdrawFromReserve(player.getUniqueId(), nation, amount, "gui-reserve");
            player.sendMessage(prefix() + (ok
                    ? "&aВыведено &f" + fmt(amount) + " GLD &aиз резерва " + nation
                    : "&cОтказ: лимит 25% резерва в сутки или недостаточно резерва"));
        }
        Bukkit.getScheduler().runTask(plugin, () -> open(player, nation));
    }

    private String prefix() {
        return plugin.getMessages().prefix();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
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
