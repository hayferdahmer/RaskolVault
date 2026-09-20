// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * RaskolVault — многовалютный экономический слой поверх EssentialsX
 * для сервера «РАСКОЛ | ДВЕ КОРОНЫ».
 *
 * Этап 2: реестр валют, кошельки (global = Essentials, остальные = леджер),
 * команда /rv balance, messages.yml, yaml-бекап балансов на выключении.
 */
public final class RaskolVault extends JavaPlugin {

    private boolean corePresent;
    private boolean essentialsPresent;
    private boolean townyPresent;
    private boolean luckPermsPresent;
    private boolean placeholderPresent;

    private SQLiteLedger ledger;
    private MessagesConfig messages;
    private CurrencyRegistry currencies;
    private EssentialsHook essentialsHook;
    private WalletService wallets;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("currencies.yml", false);
        saveResource("messages.yml", false);

        messages = new MessagesConfig(this);
        messages.load(new File(getDataFolder(), getConfig().getString("messages.file", "messages.yml")));

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

        currencies = new CurrencyRegistry(this);
        currencies.load(new File(getDataFolder(), "currencies.yml"),
                getConfig().getString("global-currency.id", "gold"),
                getConfig().getString("global-currency.display-name", "Золото"),
                getConfig().getString("global-currency.symbol", "⚜"),
                getConfig().getInt("global-currency.decimals", 2));
        currencies.syncToLedger(ledger);

        essentialsHook = new EssentialsHook(this);
        essentialsHook.init();

        wallets = new WalletService(this, ledger, currencies, essentialsHook);
        wallets.init();

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
            getLogger().warning("Essentials не найден: глобальная валюта ⚜ недоступна для операций, "
                    + "национальные валюты работают автономно");
        }
        if (!corePresent) {
            getLogger().warning("RaskolCore не найден: регистрация провайдера в EconomyRegistry "
                    + "отключена, раскол-плагины продолжат ходить в Vault/Essentials");
        }
    }

    @Override
    public void onDisable() {
        if (getConfig().getBoolean("storage.yaml-backup.enabled", true) && wallets != null) {
            saveBalancesBackup();
        }
        if (ledger != null) {
            ledger.close();
        }
        getLogger().info("RaskolVault выключен");
    }

    /** Атомарный yaml-бекап неблобальных балансов: temp → .bak → rename. */
    private void saveBalancesBackup() {
        File file = new File(getDataFolder(), getConfig().getString("storage.yaml-backup.file", "data/balances.yml"));
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("balances");
        for (Map.Entry<UUID, Map<String, Double>> entry : wallets.cacheSnapshot().entrySet()) {
            ConfigurationSection row = root.createSection(entry.getKey().toString());
            for (Map.Entry<String, Double> cell : entry.getValue().entrySet()) {
                row.set(cell.getKey(), cell.getValue());
            }
        }
        SafeStorage.saveAtomic(yaml, file, this);
    }

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

    public MessagesConfig getMessages() {
        return messages;
    }

    public CurrencyRegistry getCurrencies() {
        return currencies;
    }

    public EssentialsHook getEssentialsHook() {
        return essentialsHook;
    }

    public WalletService getWallets() {
        return wallets;
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
