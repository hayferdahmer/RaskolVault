// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;

/**
 * RaskolVault — многовалютный экономический слой поверх EssentialsX
 * для сервера «РАСКОЛ | ДВЕ КОРОНЫ».
 *
 * Этап 1: модели (api.*), SQLite-леджер (storage.SQLiteLedger), атомарный сейв
 * (storage.SafeStorage), команда /rv (help/version/debug).
 * Хуки (Essentials/RaskolCore/Towny/LP/PAPI) — softdepend + рефлексию, этап 3.
 */
public final class RaskolVault extends JavaPlugin {

    private boolean corePresent;
    private boolean essentialsPresent;
    private boolean townyPresent;
    private boolean luckPermsPresent;
    private boolean placeholderPresent;

    private SQLiteLedger ledger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("currencies.yml", false);
        detectHooks();

        File dbFile = new File(getDataFolder(), getConfig().getString("storage.sqlite.file", "data/ledger.sqlite"));
        try {
            ledger = new SQLiteLedger(this, dbFile);
            ledger.init();
            getLogger().info(() -> "RaskolVault: SQLite-леджер открыт (" + dbFile.getPath() + ") · " + ledger.describeStats());
        } catch (SQLException e) {
            getLogger().severe("RaskolVault: не могу открыть SQLite-леджер, плагин отключён: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RaskolVaultCommand executor = new RaskolVaultCommand(this);
        PluginCommand command = getCommand("rv");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Команда rv не описана в plugin.yml — команды отключены");
        }

        getLogger().info(() -> "RaskolVault v" + getPluginMeta().getVersion() + " включён"
                + " · Paper/MC " + getServer().getVersion()
                + " · Java " + System.getProperty("java.version")
                + " · Core " + (corePresent ? "on" : "off")
                + " · Essentials " + (essentialsPresent ? "on" : "off")
                + " · Towny " + (townyPresent ? "on" : "off")
                + " · LP " + (luckPermsPresent ? "on" : "off")
                + " · PAPI " + (placeholderPresent ? "on" : "off"));
        if (!essentialsPresent) {
            getLogger().warning("Essentials не найден: глобальная валюта ⚜ не будет "
                    + "синхронизирована, национальные валюты работают автономно");
        }
        if (!corePresent) {
            getLogger().warning("RaskolCore не найден: регистрация провайдера в EconomyRegistry "
                    + "отключена, раскол-плагины продолжат ходить в Vault/Essentials");
        }
    }

    @Override
    public void onDisable() {
        if (ledger != null) {
            ledger.close();
        }
        getLogger().info("RaskolVault выключен");
    }

    /** Детект соседних плагинов: только факт наличия и enabled, без классов. */
    private void detectHooks() {
        corePresent = isPluginEnabled("RaskolCore");
        essentialsPresent = isPluginEnabled("Essentials");
        townyPresent = isPluginEnabled("Towny");
        luckPermsPresent = isPluginEnabled("LuckPerms");
        placeholderPresent = isPluginEnabled("PlaceholderAPI");
    }

    private boolean isPluginEnabled(String name) {
        Plugin plugin = getServer().getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    public SQLiteLedger getLedger() {
        return ledger;
    }

    public boolean isCorePresent() {
        return corePresent;
    }

    public boolean isEssentialsPresent() {
        return essentialsPresent;
    }

    public boolean isTownyPresent() {
        return townyPresent;
    }

    public boolean isLuckPermsPresent() {
        return luckPermsPresent;
    }

    public boolean isPlaceholderPresent() {
        return placeholderPresent;
    }
}
