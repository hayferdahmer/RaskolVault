// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.util.Formatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Обработчик GUI «Кошелёк» (1.1.0.2):
 * - InventoryDragEvent отменяется (предметы больше нельзя вытащить перетаскиванием);
 * - действия кабинета дают явную обратную связь (успех/отказ с причиной);
 * - добавлена страница советника.
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
            case ADVISOR -> {
                if (slot == 49) {
                    WalletGui.openCabinet(plugin, player);
                }
            }
            case CODEX -> onCodex(player, holder, slot);
        }
    }

    /** ФИКС 1.1.0.2: перетаскивание предметов из GUI запрещено. */
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
            event.getPlayer().sendMessage(plugin.getMessages().prefix()
                    + plugin.getMessages().get("error.invalid-amount",
                    Map.of("value", event.getMessage())));
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () ->
                WalletGui.openConvertConfirm(plugin, player, ctx[0], ctx[1], amount));
    }

    private void onMain(Player player, int slot) {
        switch (slot) {
            case 10, 12, 14 -> {
                int index = (slot - 10) / 2;
                List<Currency> list = new ArrayList<>(plugin.getCurrencies().all());
                if (index >= 0 && index < list.size()) {
                    WalletGui.openConvertTo(plugin, player, list.get(index).id());
                }
            }
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
                    Map.of("reason", "курс или средства изменились",
                            "from", holder.fromId(), "to", holder.toId())));
            player.closeInventory();
            return;
        }
        var q = executed.get();
        Currency to = plugin.getCurrencies().get(q.toId()).orElse(null);
        player.sendMessage(plugin.getMessages().prefix()
                + plugin.getMessages().get("convert.done", Map.of(
                "amount", Formatter.withSymbol(q.net(),
                        to == null ? 2 : to.decimals(),
                        to == null ? q.toId() : to.symbol()))));
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
        ReserveBank bank = plugin.getReserveBank();
        Currency national = null;
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(c.nationId())) {
                national = c;
            }
        }
        boolean refresh = true;
        switch (slot) {
            case 19 -> feedback(player, bank.setParity(nation, round2(bank.parityOf(nation) - 0.10D)),
                    "Паритет: " + String.format(Locale.ROOT, "%.2f", bank.parityOf(nation)), "граница 0.50");
            case 21 -> feedback(player, bank.setParity(nation, round2(bank.parityOf(nation) + 0.10D)),
                    "Паритет: " + String.format(Locale.ROOT, "%.2f", bank.parityOf(nation)), "граница 2.00");
            case 23 -> feedback(player, bank.setTax(nation, round4(bank.taxOf(nation) - 0.005D)),
                    "Налог: " + String.format(Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D), "граница 0%");
            case 25 -> feedback(player, bank.setTax(nation, round4(bank.taxOf(nation) + 0.005D)),
                    "Налог: " + String.format(Locale.ROOT, "%.1f%%", bank.taxOf(nation) * 100.0D), "граница 5%");
            case 29 -> feedback(player, bank.depositToReserve(player.getUniqueId(), nation, 100.0D, "gui"),
                    "Внесено 100 GLD в резерв (резерв: " + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                    "недостаточно личного золота");
            case 30 -> feedback(player, bank.depositToReserve(player.getUniqueId(), nation, 1000.0D, "gui"),
                    "Внесено 1000 GLD в резерв (резерв: " + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                    "недостаточно личного золота");
            case 31 -> feedback(player, bank.withdrawFromReserve(player.getUniqueId(), nation, 100.0D, "gui"),
                    "Выведено 100 GLD из резерва (резерв: " + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                    "лимит 25% резерва в сутки или резерв пуст");
            case 32 -> feedback(player, bank.withdrawFromReserve(player.getUniqueId(), nation, 1000.0D, "gui"),
                    "Выведено 1000 GLD из резерва (резерв: " + Formatter.amount(bank.reserveOf(nation), 2) + ")",
                    "лимит 25% резерва в сутки или резерв пуст");
            case 33 -> {
                if (national == null) {
                    feedback(player, false, "Минт невозможен: нет национальной валюты", "создай валюту нации");
                } else if (!bank.canMint(nation, national.id(), 100.0D)) {
                    feedback(player, false, "Минт отклонён: лимит покрытия",
                            "лимит: " + Formatter.amount(bank.maxMint(nation, national.id()), 2) + " — пополняй резерв");
                } else {
                    feedback(player, plugin.getTreasury().deposit(nation, national.id(), 100.0D, "gui-mint"),
                            "Минт 100 " + national.id() + " в казну", "казна недоступна");
                }
            }
            case 34 -> {
                if (national == null) {
                    feedback(player, false, "Бёрн невозможен: нет национальной валюты", "создай валюту нации");
                } else {
                    feedback(player, plugin.getTreasury().withdraw(nation, national.id(), 100.0D, "gui-burn"),
                            "Бёрн 100 " + national.id() + " из казны", "в казне меньше 100");
                }
            }
            case 40 -> {
                WalletGui.openAdvisor(plugin, player);
                refresh = false;
            }
            case 49 -> {
                WalletGui.openMain(plugin, player);
                refresh = false;
            }
            default -> refresh = false;
        }
        if (refresh) {
            WalletGui.openCabinet(plugin, player);
        }
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

    private void feedback(Player player, boolean ok, String success, String failReason) {
        player.sendMessage(plugin.getMessages().prefix()
                + (ok ? "&a✔ " + success : "&c✖ Отказ: " + failReason));
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

    private int indexOf(int slot) {
        if (slot < 10 || (slot - 10) % 2 != 0) {
            return -1;
        }
        return (slot - 10) / 2;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }
}
