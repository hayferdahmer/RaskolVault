// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Обработчик кошелька (1.2.1-fix): новая сетка, перевод с выбором цели ≤6 блоков,
 * курсы/история/справка внутри кошелька, «Назад» везде.
 */
public final class GuiListener implements Listener {

    private static final double MAX_DISTANCE = 6.0D;
    private final RaskolVault plugin;

    public GuiListener(RaskolVault plugin) { this.plugin = plugin; }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(Player p, String raw) { p.sendMessage(c(plugin.getMessages().prefix() + raw)); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof WalletGui.Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        switch (h.page) {
            case "main" -> onMain(p, slot);
            case "cfrom" -> onFrom(p, slot);
            case "cto" -> onTo(p, h, slot);
            case "camt" -> onAmt(p, h, slot);
            case "cconf" -> onConf(p, h, slot);
            case "rates" -> { if (slot == 49) WalletGui.openMain(plugin, p); }
            case "transfer" -> onTransfer(p, h, slot);
            case "transferamt" -> onTransferAmt(p, h, slot);
            case "transferconf" -> onTransferConf(p, h, slot);
            case "history" -> onHistory(p, h, slot);
            case "guide" -> onGuide(p, h, slot);
        }
    }

    private void onMain(Player p, int slot) {
        switch (slot) {
            case 49 -> p.closeInventory();
            case 13 -> WalletGui.openConvertTo(plugin, p, plugin.getCurrencies().globalId());
            case 20, 22, 24 -> {
                int idx = (slot - 20) / 2;
                List<Currency> nats = nationals();
                if (idx < nats.size()) WalletGui.openConvertTo(plugin, p, nats.get(idx).id());
            }
            case 27 -> WalletGui.openConvertFrom(plugin, p);
            case 29 -> WalletGui.openRates(plugin, p);
            case 31 -> WalletGui.openTransfer(plugin, p);
            case 33 -> WalletGui.openHistory(plugin, p, 0);
            case 35 -> WalletGui.openGuide(plugin, p, 0);
        }
    }

    private void onFrom(Player p, int slot) {
        if (slot == 49) { WalletGui.openMain(plugin, p); return; }
        int[] grid = {10,12,14,16,18,20,22,24,26,28,30,32,34};
        List<Currency> trade = tradeable();
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == slot && i < trade.size()) { WalletGui.openConvertTo(plugin, p, trade.get(i).id()); return; }
        }
    }

    private void onTo(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49) { WalletGui.openConvertFrom(plugin, p); return; }
        int[] grid = {10,12,14,16,18,20,22,24,26,28,30,32,34};
        List<Currency> trade = tradeableExcept(h.fromId);
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == slot && i < trade.size()) { WalletGui.openConvertAmount(plugin, p, h.fromId, trade.get(i).id()); return; }
        }
    }

    private void onAmt(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49) { WalletGui.openConvertTo(plugin, p, h.fromId); return; }
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), h.fromId);
        Double amt = switch (slot) {
            case 10 -> 1.0; case 12 -> 10.0; case 14 -> 64.0; case 16 -> 100.0; case 18 -> bal;
            default -> null;
        };
        if (amt != null) { WalletGui.openConvertConfirm(plugin, p, h.fromId, h.toId, amt); return; }
        if (slot == 22) {
            p.closeInventory();
            WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"convert-amt", h.fromId, h.toId});
            msg(p, "&7Введите сумму:");
        }
    }

    private void onConf(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49 || slot == 11) { WalletGui.openConvertAmount(plugin, p, h.fromId, h.toId); return; }
        if (slot == 15) {
            var res = plugin.getConvertEngine().execute(p.getUniqueId(), h.fromId, h.toId, h.amount);
            msg(p, res.map(x -> "&aПолучено &f" + String.format(java.util.Locale.ROOT, "%.2f", x.net()) + " " + x.toId())
                    .orElse("&cНе удалось исполнить"));
            WalletGui.openMain(plugin, p);
        }
    }

    private void onTransfer(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49) { WalletGui.openMain(plugin, p); return; }
        if (slot == 40) {
            p.closeInventory();
            WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"transfer-name"});
            msg(p, "&7Введите ник получателя:");
            return;
        }
        int[] grid = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        List<Player> near = nearby(p);
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == slot && i < near.size()) { WalletGui.openTransferAmount(plugin, p, near.get(i).getName()); return; }
        }
    }

    private void onTransferAmt(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49) { WalletGui.openTransfer(plugin, p); return; }
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), plugin.getCurrencies().globalId());
        Double amt = switch (slot) {
            case 10 -> 1.0; case 12 -> 10.0; case 14 -> 64.0; case 16 -> 100.0; case 18 -> bal;
            default -> null;
        };
        if (amt != null) { WalletGui.openTransferConfirm(plugin, p, h.toId, amt); return; }
        if (slot == 22) {
            p.closeInventory();
            WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"transfer-amt", h.toId});
            msg(p, "&7Введите сумму:");
        }
    }

    private void onTransferConf(Player p, WalletGui.Holder h, int slot) {
        if (slot == 49 || slot == 11) { WalletGui.openTransferAmount(plugin, p, h.toId); return; }
        if (slot == 15) {
            Player target = Bukkit.getPlayerExact(h.toId);
            if (target == null) { msg(p, "&cИгрок не в сети"); WalletGui.openTransfer(plugin, p); return; }
            if (!p.getWorld().equals(target.getWorld()) || p.getLocation().distance(target.getLocation()) > MAX_DISTANCE) {
                msg(p, "&cСлишком далеко. Подойдите ближе (≤6 блоков).");
                WalletGui.openTransfer(plugin, p); return;
            }
            boolean ok = plugin.getWallets().transfer(p.getUniqueId(), target.getUniqueId(),
                    plugin.getCurrencies().globalId(), h.amount, "wallet-transfer");
            msg(p, ok ? "&aПередано &f" + String.format(java.util.Locale.ROOT, "%.2f", h.amount) + " GLD &aигроку &f" + target.getName()
                    : "&cНедостаточно средств");
            WalletGui.openMain(plugin, p);
        }
    }

    private void onHistory(Player p, WalletGui.Holder h, int slot) {
        if (slot == 45 && h.pageIndex > 0) { WalletGui.openHistory(plugin, p, h.pageIndex - 1); return; }
        if (slot == 53) { WalletGui.openHistory(plugin, p, h.pageIndex + 1); return; }
        if (slot == 49) WalletGui.openMain(plugin, p);
    }

    private void onGuide(Player p, WalletGui.Holder h, int slot) {
        if (slot == 45 && h.pageIndex > 0) { WalletGui.openGuide(plugin, p, h.pageIndex - 1); return; }
        if (slot == 53) { WalletGui.openGuide(plugin, p, h.pageIndex + 1); return; }
        if (slot == 49) WalletGui.openMain(plugin, p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        String[] ctx = WalletGui.CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (ctx[0]) {
                case "convert-amt" -> {
                    double amt = parse(text);
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); return; }
                    WalletGui.openConvertConfirm(plugin, p, ctx[1], ctx[2], amt);
                }
                case "transfer-name" -> {
                    Player t = Bukkit.getPlayerExact(text);
                    if (t == null) { msg(p, "&cИгрок не найден"); WalletGui.openTransfer(plugin, p); return; }
                    if (t.getUniqueId().equals(p.getUniqueId())) { msg(p, "&cНельзя себе"); WalletGui.openTransfer(plugin, p); return; }
                    if (!p.getWorld().equals(t.getWorld()) || p.getLocation().distance(t.getLocation()) > MAX_DISTANCE) {
                        msg(p, "&cСлишком далеко (≤6 блоков)."); WalletGui.openTransfer(plugin, p); return;
                    }
                    WalletGui.openTransferAmount(plugin, p, t.getName());
                }
                case "transfer-amt" -> {
                    double amt = parse(text);
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); return; }
                    WalletGui.openTransferConfirm(plugin, p, ctx[1], amt);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { WalletGui.CHAT_CAPTURE.remove(e.getPlayer().getUniqueId()); }

    private List<Player> nearby(Player p) {
        List<Player> out = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId())) continue;
            if (p.getLocation().distance(o.getLocation()) <= MAX_DISTANCE) out.add(o);
        }
        return out;
    }
    private List<Currency> tradeable() {
        List<Currency> out = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) if (c.tradeable()) out.add(c);
        return out;
    }
    private List<Currency> tradeableExcept(String except) {
        List<Currency> out = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) if (c.tradeable() && !c.id().equals(except)) out.add(c);
        return out;
    }
    private List<Currency> nationals() {
        List<Currency> out = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all())
            if (c.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL) out.add(c);
        return out;
    }
    private double parse(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (NumberFormatException e) { return -1; }
    }
}
