// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Слушатель переименования и удаления наций Towny (1.0.6: через рефлексию).
 *
 * Подписка на события по имени класса (без compile-зависимости от Towny API).
 * - RenameNationEvent: атомарно обновляет nation_id в БД и в памяти.
 * - DeleteNationEvent: warning + число orphan-валют (без деструктивного удаления).
 */
public final class TownyNationLifecycleListener implements Listener {

    private final Plugin plugin;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    public TownyNationLifecycleListener(Plugin plugin, CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    public void register() {
        registerEvent("com.palmergames.bukkit.towny.event.nation.RenameNationEvent", this::onRename);
        registerEvent("com.palmergames.bukkit.towny.event.nation.DeleteNationEvent", this::onDelete);
        plugin.getLogger().info("RaskolVault: Towny-lifecycle слушатель активен (рефлексия)");
    }

    private void registerEvent(String eventClassName, EventExecutor executor) {
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(eventClassName);
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin, true);
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("RaskolVault: событие " + eventClassName + " не найдено (Towny другой версии?)");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: не удалось подписаться на " + eventClassName + ": " + e.getMessage());
        }
    }

    private void onRename(Listener listener, Event event) {
        try {
            Method getNation = event.getClass().getMethod("getNation");
            Method getOldName = event.getClass().getMethod("getOldName");
            Object nation = getNation.invoke(event);
            String oldName = (String) getOldName.invoke(event);
            Method getName = nation.getClass().getMethod("getName");
            String newName = (String) getName.invoke(nation);
            if (oldName == null || oldName.equals(newName)) {
                return;
            }
            int updatedDb = ledger.renameNationId(oldName, newName);
            int updatedMem = currencies.updateNationId(oldName, newName);
            plugin.getLogger().info("RaskolVault: нация '" + oldName + "' → '" + newName
                    + "' (БД-строк: " + updatedDb + ", валют в реестре: " + updatedMem + ")");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: ошибка в onRename: " + e.getMessage());
        }
    }

    private void onDelete(Listener listener, Event event) {
        try {
            Method getNation = event.getClass().getMethod("getNation");
            Object nation = getNation.invoke(event);
            Method getName = nation.getClass().getMethod("getName");
            String name = (String) getName.invoke(nation);
            int affected = currencies.countByNation(name);
            if (affected <= 0) {
                return;
            }
            String plural = affected == 1 ? "ая валюта" : "ых валют";
            plugin.getLogger().warning("RaskolVault: нация '" + name + "' удалена в Towny, но "
                    + affected + " привязанн" + plural + " остались в реестре как orphan."
                    + " Реши вручную: /rv admin currency rename <OLD_ID> <NEW_ID>");
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: ошибка в onDelete: " + e.getMessage());
        }
    }
}
