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
import java.util.Locale;
import java.util.UUID;

/**
 * Обработчик WalletGui (1.2.3-b, FIX компиляции):
 *  - Holder-поля public final → компилятор видит String
 *  - импорт Locale
 *  - без хелпера list(), явная фильтрация Currency
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
        String page = h.page;
        if (page == null) return;
        switch (page) {
            case "main" -> onMain(p, slot);
            case "cfrom" -> onFrom(p, slot);
            case "cto" -> onTo(p, h.fromId, slot);
            case "camt" -> onAmt(p, h.fromId, h.toId, slot);
            case "cconf" -> onConf(p, h.fromId, h.toId, h.amount, slot);
            case "codex" -> onCodex(p, (int) h.amount, slot);
            default -> {}
        }
    }

    private void onMain(Player p, int slot) {
        if (slot == 49) { p.closeInventory(); return; }
        if (slot == 29) { WalletGui.openConvertFrom(plugin, p); return; }
        if (slot == 31) { msg(p, "&7Смотри курсы: &f/rv rates"); return; }
        if (slot == 33) { msg(p, "&7История: &f/rv admin audit " + p.getName()); return; }
        if (slot == 35) {
            String n = plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
            if (n != null && plugin.getTownyHook().isKing(p.getUniqueId(), n)) plugin.getCabinetGui().openHome(p, n);
            else msg(p, "&cТолько король");
            return;
        }
        if (slot == 40) { WalletGui.openCodex(plugin, p, 0); return; }
        if (slot == 32) {
            p.closeInventory();
            WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"transfer-name"});
            msg(p, "&7Введите ник получателя:");
            return;
        }
        // клик по валюте для конвертации "из"
        if (slot == 10 || slot == 12 || slot == 14 || slot == 16 || slot == 18) {
            int idx = (slot - 10) / 2;
            List<Currency> all = plugin.getCurrencies().all();
            if (idx >= 0 && idx < all.size()) WalletGui.openConvertTo(plugin, p, all.get(idx).id());
        }
    }

    private void onFrom(Player p, int slot) {
        if (slot == 22) { p.closeInventory(); return; }
        int idx = (slot - 10) / 2;
        List<Currency> all = plugin.getCurrencies().all();
        if (idx >= 0 && idx < all.size()) WalletGui.openConvertTo(plugin, p, all.get(idx).id());
    }

    private void onTo(Player p, String fromId, int slot) {
        if (slot == 22) { WalletGui.openConvertFrom(plugin, p); return; }
        int idx = (slot - 10) / 2;
        List<Currency> all = plugin.getCurrencies().all();
        // FIX: явная фильтрация Currency без хелпера list()
        List<Currency> filtered = new ArrayList<>();
        for (Currency x : all) {
            if (fromId != null && !x.id().equals(fromId)) filtered.add(x);
        }
        if (idx >= 0 && idx < filtered.size() && fromId != null) {
            WalletGui.openConvertAmount(plugin, p, fromId, filtered.get(idx).id());
        }
    }

    private void onAmt(Player p, String fromId, String toId, int slot) {
        if (slot == 22) { WalletGui.openConvertTo(plugin, p, fromId); return; }
        double bal = plugin.getWallets().getBalance(p.getUniqueId(), fromId);
        Double amt = switch (slot) {
            case 10 -> 1.0; case 11 -> 10.0; case 12 -> 64.0; case 13 -> 100.0; case 14 -> bal;
            default -> null;
        };
        if (amt != null) { WalletGui.openConvertConfirm(plugin, p, fromId, toId, amt); return; }
        if (slot == 16) {
            p.closeInventory();
            WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"convert-amt", fromId, toId});
            msg(p, "&7Введите сумму:");
        }
    }

    private void onConf(Player p, String fromId, String toId, double amount, int slot) {
        if (slot == 22) { WalletGui.openConvertAmount(plugin, p, fromId, toId); return; }
        if (slot == 15) { p.closeInventory(); return; }
        if (slot == 11) {
            String nation = plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
            if (!plugin.getTradePolicy().isAllowed(nation, toId)) {
                msg(p, "&cВалюта " + toId + " запрещена к обороту в вашей нации");
                p.closeInventory(); return;
            }
            var res = plugin.getConvertEngine().execute(p.getUniqueId(), fromId, toId, amount);
            msg(p, res.map(x -> "&aПолучено &f" + String.format(Locale.ROOT, "%.2f", x.net()) + " " + x.toId())
                    .orElse("&cНе удалось исполнить"));
            p.closeInventory();
        }
    }

    private void onCodex(Player p, int currentPage, int slot) {
        if (slot == 18 && currentPage > 0) { WalletGui.openCodex(plugin, p, currentPage - 1); return; }
        if (slot == 26) { WalletGui.openCodex(plugin, p, currentPage + 1); return; }
        if (slot == 22) p.closeInventory();
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
                case "transfer-name" -> {
                    Player target = Bukkit.getPlayerExact(text);
                    if (target == null) { msg(p, "&cИгрок не найден"); return; }
                    if (target.getUniqueId().equals(p.getUniqueId())) { msg(p, "&cНельзя себе"); return; }
                    WalletGui.CHAT_CAPTURE.put(p.getUniqueId(), new String[]{"transfer-amt", target.getName()});
                    msg(p, "&7Введите сумму перевода:");
                }
                case "transfer-amt" -> {
                    double amt;
                    try { amt = Double.parseDouble(text.replace(",", ".")); }
                    catch (NumberFormatException ex) { msg(p, "&cНекорректная сумма"); return; }
                    if (!(amt > 0)) { msg(p, "&cСумма > 0"); return; }
                    Player target = Bukkit.getPlayerExact(ctx[1]);
                    if (target == null) { msg(p, "&cИгрок не найден"); return; }
                    if (!p.getWorld().equals(target.getWorld())
                            || p.getLocation().distance(target.getLocation()) > MAX_DISTANCE) {
                        msg(p, "&cСлишком далеко. Подойдите ближе (≤6 блоков).");
                        return;
                    }
                    String nation = plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(p.getUniqueId()) : null;
                    String glb = plugin.getCurrencies().globalId();
                    if (!plugin.getTradePolicy().isAllowed(nation, glb)) {
                        msg(p, "&cВалюта запрещена к обороту в вашей нации");
                        return;
                    }
                    boolean ok = plugin.getWallets().transfer(p.getUniqueId(), target.getUniqueId(), glb, amt, "wallet-transfer");
                    msg(p, ok ? "&aПередано &f" + String.format(Locale.ROOT, "%.2f", amt) + " GLD &aигроку &f" + target.getName()
                            : "&cНедостаточно средств");
                }
                case "convert-amt" -> {
                    double amt;
                    try { amt = Double.parseDouble(text.replace(",", ".")); }
                    catch (NumberFormatException ex) { msg(p, "&cНекорректная сумма"); return; }
                    WalletGui.openConvertConfirm(plugin, p, ctx[1], ctx[2], amt);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        WalletGui.CHAT_CAPTURE.remove(e.getPlayer().getUniqueId());
    }
}
