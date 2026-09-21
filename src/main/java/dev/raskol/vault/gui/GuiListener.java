// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Обработчик GUI (FIX 1.1.1.1): трансляция &-кодов; переходы GUI на следующий тик.
 */
public final class GuiListener implements Listener {

    private final RaskolVault plugin;

    public GuiListener(RaskolVault plugin) {
        this.plugin = plugin;
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void send(Player p, String raw) {
        p.sendMessage(c(raw));
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WalletGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }
        switch (holder.page()) {
            case MAIN -> onMain(player, slot);
            case CONVERT_FROM -> onFrom(player, slot);
            case CONVERT_TO -> onTo(player, holder, slot);
            case CONVERT_AMOUNT -> onAmount(player, holder, slot);
            case CONVERT_CONFIRM -> onConfirm(player, holder, slot);
            case RATES -> {
                if (slot == 49) later(() -> WalletGui.openMain(plugin, player));
            }
            case HISTORY -> onHistory(player, holder, slot);
            case CABINET -> onCabinet(player, slot);
            case ADVISOR -> {
                if (slot == 49) later(() -> WalletGui.openCabinet(plugin, player));
            }
            case CODEX -> onCodex(player, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WalletGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String[] ctx = WalletGui.CHAT_CAPTURE.remove(event.getPlayer().getUniqueId());
        if (ctx == null) {
            return;
        }
        event.setCancelled(true);
        double amount;
        try {
            amount = Double.parseDouble(event.getMessage().replace(",", ".").trim());
        } catch (NumberFormatException e) {
            send(event.getPlayer(), plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", event.getMessage())));
            return;
        }
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            send(event.getPlayer(), plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount", Map.of("value", event.getMessage())));
            return;
        }
        Player player = event.getPlayer();
        later(() -> WalletGui.openConvertConfirm(plugin, player, ctx[0], ctx[1], amount));
    }

    private void onMain(Player player, int slot) {
        switch (slot) {
            case 10, 12, 14 -> {
                int index = (slot - 10) / 2;
                List<Currency> list = new ArrayList<>(plugin.getCurrencies().all());
                if (index >= 0 && index < list.size()) {
                    String id = list.get(index).id();
                    later(() -> WalletGui.openConvertTo(plugin, player, id));
                }
            }
            case 29 -> later(() -> WalletGui.openConvertFrom(plugin, player));
            case 31 -> later(() -> WalletGui.openRates(plugin, player));
            case 33 -> later(() -> WalletGui.openHistory(plugin, player, 0));
            case 35 -> {
                if (WalletGui.isKing(plugin, player)) {
                    later(() -> WalletGui.openCabinet(plugin, player));
                }
            }
            case 40 -> later(() -> WalletGui.openCodex(plugin, player, 0));
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void onFrom(Player player, int slot) {
        if (slot == 22) {
            later(() -> WalletGui.openMain(plugin, player));
            return;
        }
        List<Currency> list = tradeable();
        int index = indexOf(slot);
        if (index < 0 || index >= list.size()) {
            return;
        }
        String id = list.get(index).id();
        later(() -> WalletGui.openConvertTo(plugin, player, id));
    }

    private void onTo(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 22) {
            later(() -> WalletGui.openConvertFrom(plugin, player));
            return;
        }
        List<Currency> list = tradeableExcept(holder.fromId());
        int index = indexOf(slot);
        if (index < 0 || index >= list.size()) {
            return;
        }
        String id = list.get(index).id();
        later(() -> WalletGui.openConvertAmount(plugin, player, holder.fromId(), id));
    }

    private void onAmount(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 22) {
            later(() -> WalletGui.openConvertTo(plugin, player, holder.fromId()));
            return;
        }
        double balance = plugin.getWallets().getBalance(player.getUniqueId(), holder.fromId());
        Double amount = switch (slot) {
            case 10 -> 1.0D;
            case 11 -> 10.0D;
            case 12 -> 64.0D;
            case 13 -> 100.0D;
            case 14 -> balance;
            default -> null;
        };
        if (amount != null) {
            double amt = amount;
            later(() -> WalletGui.openConvertConfirm(plugin, player, holder.fromId(), holder.toId(), amt));
            return;
        }
        if (slot == 16) {
            WalletGui.CHAT_CAPTURE.put(player.getUniqueId(), new String[]{holder.fromId(), holder.toId()});
            player.closeInventory();
            send(player, plugin.getMessages().prefix() + "&7Напиши сумму в чат:");
        }
    }

    private void onConfirm(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 11) {
            later(() -> WalletGui.openConvertAmount(plugin, player, holder.fromId(), holder.toId()));
            return;
        }
        if (slot != 15) {
            return;
        }
        if (!plugin.getRateLimiter().tryConsume(player.getUniqueId())) {
            send(player, plugin.getMessages().prefix() + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        var executed = plugin.getConvertEngine().execute(
                player.getUniqueId(), holder.fromId(), holder.toId(), holder.amount());
        if (executed.isEmpty()) {
            String reason = plugin.getConvertEngine().blockReason(
                    player.getUniqueId(), holder.fromId(), holder.toId(), holder.amount());
            send(player, plugin.getMessages().prefix() + plugin.getMessages().get("error.convert.generic",
                    Map.of("reason", reason.isEmpty() ? "неизвестно" : reason,
                            "from", holder.fromId(), "to", holder.toId())));
            player.closeInventory();
            return;
        }
        var q = executed.get();
        Currency to = plugin.getCurrencies().get(q.toId()).orElse(null);
        send(player, plugin.getMessages().prefix() + plugin.getMessages().get("convert.done",
                Map.of("amount", fmtSym(q.net(), to, q.toId()))));
        later(() -> WalletGui.openMain(plugin, player));
    }

    private void onHistory(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 45 && holder.pageIndex() > 0) {
            int p = holder.pageIndex() - 1;
            later(() -> WalletGui.openHistory(plugin, player, p));
        } else if (slot == 53) {
            int p = holder.pageIndex() + 1;
            later(() -> WalletGui.openHistory(plugin, player, p));
        } else if (slot == 49) {
            player.closeInventory();
        }
    }

    private void onCabinet(Player player, int slot) {
        if (!WalletGui.isKing(plugin, player)) {
            player.closeInventory();
            return;
        }
        String nation = plugin.getTownyHook().nationOf(player.getUniqueId());
        if (nation == null) {
            player.closeInventory();
            return;
        }
        ReserveBank bank = plugin.getReserveBank();
        Currency national = null;
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(cur.nationId())) {
                national = cur;
            }
        }
        boolean refresh = true;
        switch (slot) {
            case 19 -> feedback(player, bank.setParity(nation, round2(bank.parityOf(nation) - 0.10D)),
                    "Паритет: " + String.format(java.util.Locale.ROOT, "%.2f", bank.parityOf(nation)), "граница 0.50");
            case 21 -> feedback(player, bank.setParity(nation, round2(bank.parityOf(nation) + 0.10D)),
                    "Паритет: " + String.format(java.util.Locale.ROOT, "%.2f", bank.parityOf(nation)), "граница 2.00");
            case 23 -> feedback(player, bank.setTax(nation, round4(bank.taxOf(nation) - 0.005D)),
                    "Налог: " + String.format(java.util.Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D), "граница 0%");
            case 25 -> feedback(player, bank.setTax(nation, round4(bank.taxOf(nation) + 0.005D)),
                    "Налог: " + String.format(java.util.Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D), "граница 5%");
            case 29 -> depositFeedback(player, bank, nation, 100.0D);
            case 30 -> depositFeedback(player, bank, nation, 1000.0D);
            case 31 -> withdrawFeedback(player, bank, nation, 100.0D);
            case 32 -> withdrawFeedback(player, bank, nation, 1000.0D);
            case 33 -> {
                if (national == null) {
                    feedback(player, false, "", "нет национальной валюты");
                } else if (!bank.canMint(nation, national.id(), 100.0D)) {
                    feedback(player, false, "", "лимит покрытия: "
                            + Formatter.amount(bank.maxMint(nation, national.id()), 2) + " — пополняй резерв");
                } else {
                    feedback(player, plugin.getTreasury().deposit(nation, national.id(), 100.0D, "gui-mint"),
                            "Минт 100 " + national.id() + " в казну", "казна недоступна");
                }
            }
            case 34 -> {
                if (national == null) {
                    feedback(player, false, "", "нет национальной валюты");
                } else {
                    feedback(player, plugin.getTreasury().withdraw(nation, national.id(), 100.0D, "gui-burn"),
                            "Бёрн 100 " + national.id() + " из казны", "в казне меньше 100");
                }
            }
            case 40 -> {
                later(() -> WalletGui.openAdvisor(plugin, player));
                refresh = false;
            }
            case 49 -> {
                later(() -> WalletGui.openMain(plugin, player));
                refresh = false;
            }
            default -> refresh = false;
        }
        if (refresh) {
            later(() -> WalletGui.openCabinet(plugin, player));
        }
    }

    private void depositFeedback(Player player, ReserveBank bank, String nation, double amount) {
        String glb = plugin.getCurrencies().globalId();
        if (!plugin.getWallets().has(player.getUniqueId(), glb, amount)) {
            feedback(player, false, "", "недостаточно личного золота ("
                    + Formatter.amount(plugin.getWallets().getBalance(player.getUniqueId(), glb), 2) + " GLD)");
            return;
        }
        feedback(player, bank.depositToReserve(player.getUniqueId(), nation, amount, "gui"),
                "Внесено " + Formatter.amount(amount, 2) + " GLD в резерв (резерв: "
                        + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                "резерв не принимает");
    }

    private void withdrawFeedback(Player player, ReserveBank bank, String nation, double amount) {
        double reserve = bank.reserveOf(nation);
        if (reserve + 1.0E-9D < amount) {
            feedback(player, false, "", "резерв нации исчерпан (" + Formatter.amount(reserve, 2) + " GLD)");
            return;
        }
        if (amount > bank.dailyWithdrawLimit(nation) + 1.0E-9D) {
            feedback(player, false, "", "суточный лимит: " + Formatter.amount(bank.dailyWithdrawLimit(nation), 2) + " GLD");
            return;
        }
        feedback(player, bank.withdrawFromReserve(player.getUniqueId(), nation, amount, "gui"),
                "Выведено " + Formatter.amount(amount, 2) + " GLD (резерв: "
                        + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                "операция отклонена");
    }

    private void onCodex(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 18 && holder.pageIndex() > 0) {
            int p = holder.pageIndex() - 1;
            later(() -> WalletGui.openCodex(plugin, player, p));
        } else if (slot == 26) {
            int p = holder.pageIndex() + 1;
            later(() -> WalletGui.openCodex(plugin, player, p));
        } else if (slot == 22) {
            player.closeInventory();
        }
    }

    private void feedback(Player player, boolean ok, String success, String failReason) {
        send(player, plugin.getMessages().prefix() + (ok ? "&a✔ " + success : "&c✖ Отказ: " + failReason));
    }

    private List<Currency> tradeable() {
        List<Currency> list = new ArrayList<>();
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.tradeable()) {
                list.add(cur);
            }
        }
        return list;
    }

    private List<Currency> tradeableExcept(String exceptId) {
        List<Currency> list = new ArrayList<>();
        for (Currency cur : plugin.getCurrencies().all()) {
            if (cur.tradeable() && !cur.id().equals(exceptId)) {
                list.add(cur);
            }
        }
        return list;
    }

    private int indexOf(int slot) {
        if (slot < 10 || (slot - 10) % 2 != 0) {
            return -1;
        }
        return (slot - 10) / 2;
    }

    private String fmtSym(double value, Currency cur, String fallback) {
        if (cur == null) {
            return Formatter.amount(value, 2) + " " + fallback;
        }
        return Formatter.withSymbol(value, cur.decimals(), cur.symbol());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }
}
