// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Единый обработчик кликов GUI «Кошелёк» + захват чата для ввода суммы (1.1.0-c).
 * Все клики внутри GUI отменяются (предметы не двигаются).
 */
public final class GuiListener implements Listener {

    private final RaskolVault plugin;

    public GuiListener(RaskolVault plugin) {
        this.plugin = plugin;
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
                if (slot == 49) {
                    WalletGui.openMain(plugin, player);
                }
            }
            case HISTORY -> onHistory(player, holder, slot);
            case CABINET -> onCabinet(player, slot);
            case CODEX -> onCodex(player, holder, slot);
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
            event.getPlayer().sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount",
                    java.util.Map.of("value", event.getMessage())));
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () ->
                WalletGui.openConvertConfirm(plugin, player, ctx[0], ctx[1], amount));
    }

    private void onMain(Player player, int slot) {
        switch (slot) {
            case 29 -> WalletGui.openConvertFrom(plugin, player);
            case 31 -> WalletGui.openRates(plugin, player);
            case 33 -> WalletGui.openHistory(plugin, player, 0);
            case 35 -> {
                if (WalletGui.isKing(plugin, player)) {
                    WalletGui.openCabinet(plugin, player);
                }
            }
            case 40 -> WalletGui.openCodex(plugin, player, 0);
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void onFrom(Player player, int slot) {
        if (slot == 22) {
            WalletGui.openMain(plugin, player);
            return;
        }
        List<Currency> list = tradeable();
        int index = indexOf(slot);
        if (index < 0 || index >= list.size()) {
            return;
        }
        WalletGui.openConvertTo(plugin, player, list.get(index).id());
    }

    private void onTo(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 22) {
            WalletGui.openConvertFrom(plugin, player);
            return;
        }
        List<Currency> list = tradeableExcept(holder.fromId());
        int index = indexOf(slot);
        if (index < 0 || index >= list.size()) {
            return;
        }
        WalletGui.openConvertAmount(plugin, player, holder.fromId(), list.get(index).id());
    }

    private void onAmount(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 22) {
            WalletGui.openConvertTo(plugin, player, holder.fromId());
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
            WalletGui.openConvertConfirm(plugin, player, holder.fromId(), holder.toId(), amount);
            return;
        }
        if (slot == 16) {
            WalletGui.CHAT_CAPTURE.put(player.getUniqueId(), new String[]{holder.fromId(), holder.toId()});
            player.closeInventory();
            player.sendMessage(plugin.getMessages().prefix() + "&7Напиши сумму в чат:");
        }
    }

    private void onConfirm(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 11) {
            WalletGui.openConvertAmount(plugin, player, holder.fromId(), holder.toId());
            return;
        }
        if (slot != 15) {
            return;
        }
        if (!plugin.getRateLimiter().tryConsume(player.getUniqueId())) {
            player.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.rate-limited", null));
            return;
        }
        var executed = plugin.getConvertEngine().execute(
                player.getUniqueId(), holder.fromId(), holder.toId(), holder.amount());
        if (executed.isEmpty()) {
            player.sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.convert.generic",
                    java.util.Map.of("reason", "курс или средства изменились",
                            "from", holder.fromId(), "to", holder.toId())));
            player.closeInventory();
            return;
        }
        var q = executed.get();
        Currency to = plugin.getCurrencies().get(q.toId()).orElse(null);
        player.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("convert.done", java.util.Map.of(
                "amount", FormatterSafe(q.net(), to))));
        WalletGui.openMain(plugin, player);
    }

    private void onHistory(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 45 && holder.pageIndex() > 0) {
            WalletGui.openHistory(plugin, player, holder.pageIndex() - 1);
        } else if (slot == 53) {
            WalletGui.openHistory(plugin, player, holder.pageIndex() + 1);
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
        var bank = plugin.getReserveBank();
        Currency national = null;
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL
                    && nation.equalsIgnoreCase(c.nationId())) {
                national = c;
            }
        }
        switch (slot) {
            case 19 -> bank.setParity(nation, Math.round((bank.parityOf(nation) - 0.10D) * 100.0D) / 100.0D);
            case 21 -> bank.setParity(nation, Math.round((bank.parityOf(nation) + 0.10D) * 100.0D) / 100.0D);
            case 23 -> bank.setTax(nation, Math.round((bank.taxOf(nation) - 0.005D) * 1000.0D) / 1000.0D);
            case 25 -> bank.setTax(nation, Math.round((bank.taxOf(nation) + 0.005D) * 1000.0D) / 1000.0D);
            case 29 -> bank.depositToReserve(player.getUniqueId(), nation, 100.0D, "gui");
            case 30 -> bank.depositToReserve(player.getUniqueId(), nation, 1000.0D, "gui");
            case 31 -> bank.withdrawFromReserve(player.getUniqueId(), nation, 100.0D, "gui");
            case 32 -> bank.withdrawFromReserve(player.getUniqueId(), nation, 1000.0D, "gui");
            case 33 -> {
                if (national != null && bank.canMint(nation, national.id(), 100.0D)) {
                    plugin.getTreasury().deposit(nation, national.id(), 100.0D, "gui-mint");
                }
            }
            case 34 -> {
                if (national != null) {
                    plugin.getTreasury().withdraw(nation, national.id(), 100.0D, "gui-burn");
                }
            }
            case 49 -> {
                WalletGui.openMain(plugin, player);
                return;
            }
            default -> {
                return;
            }
        }
        WalletGui.openCabinet(plugin, player);
    }

    private void onCodex(Player player, WalletGuiHolder holder, int slot) {
        if (slot == 18 && holder.pageIndex() > 0) {
            WalletGui.openCodex(plugin, player, holder.pageIndex() - 1);
        } else if (slot == 26) {
            WalletGui.openCodex(plugin, player, holder.pageIndex() + 1);
        } else if (slot == 22) {
            player.closeInventory();
        }
    }

    private List<Currency> tradeable() {
        List<Currency> list = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.tradeable()) {
                list.add(c);
            }
        }
        return list;
    }

    private List<Currency> tradeableExcept(String exceptId) {
        List<Currency> list = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.tradeable() && !c.id().equals(exceptId)) {
                list.add(c);
            }
        }
        return list;
    }

    /** Слоты 10,12,14,... → индекс 0,1,2,... */
    private int indexOf(int slot) {
        if (slot < 10 || (slot - 10) % 2 != 0) {
            return -1;
        }
        return (slot - 10) / 2;
    }

    private String FormatterSafe(double amount, Currency currency) {
        if (currency == null) {
            return dev.raskol.vault.util.Formatter.amount(amount, 2);
        }
        return dev.raskol.vault.util.Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
    }
}
