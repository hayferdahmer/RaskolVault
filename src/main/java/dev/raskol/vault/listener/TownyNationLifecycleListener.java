// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.listener;

import com.palmergames.bukkit.towny.event.nation.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.nation.RenameNationEvent;
import com.palmergames.bukkit.towny.object.Nation;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Слушатель переименования и удаления наций Towny (1.0.6).
 *
 * - RenameNationEvent: обновляет nation_id в currencies (и в памяти, и в БД) на новое имя.
 * - DeleteNationEvent: НЕ удаляет валюты (деструктивно) — только warning с числом orphan-валют.
 *
 * Монитор-приоритет: реагируем после того, как Towny уже подтвердил операцию.
 * ignoreCancelled=true: при откате операции Towny ничего не трогаем.
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
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("RaskolVault: Towny-lifecycle слушатель активен (rename/delete nation)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(RenameNationEvent event) {
        Nation nation = event.getNation();
        String oldName = event.getOldName();
        String newName = nation.getName();
        if (oldName == null || oldName.equals(newName)) {
            return;
        }
        try {
            int updatedDb = ledger.renameNationId(oldName, newName);
            int updatedMem = currencies.updateNationId(oldName, newName);
            plugin.getLogger().info("RaskolVault: нация '" + oldName + "' → '" + newName
                    + "' (БД-строк: " + updatedDb + ", валют в реестре: " + updatedMem + ")");
        } catch (Exception e) {
            plugin.getLogger().severe("RaskolVault: не удалось синхронизировать переименование нации '"
                    + oldName + "' → '" + newName + "': " + e.getMessage()
                    + " — расследуй через /rv admin currency list");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDelete(DeleteNationEvent event) {
        Nation nation = event.getNation();
        String name = nation.getName();
        int affected = currencies.countByNation(name);
        if (affected <= 0) {
            return;
        }
        String plural = affected == 1 ? "ая валюта" : "ых валют";
        plugin.getLogger().warning("RaskolVault: нация '" + name + "' удалена в Towny, но "
                + affected + " привязанн" + plural + " остались в реестре как orphan."
                + " Реши вручную: /rv admin currency rename <OLD_ID> <NEW_ID> или через currencies.yml + reload");
    }
}
