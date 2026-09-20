// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.arbitrage.ArbitrageSimulator;
import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.confirm.ConfirmManager;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.exchange.ExchangeService;
import dev.raskol.vault.exchange.RatesService;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.listener.NationAutoCurrencyListener;
import dev.raskol.vault.nation.NationTreasury;
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
    private RatesService rates;
    private ExchangeService exchange;
    private ConfirmManager confirms;
    private RaskolCoreHook coreHook;
    private TownyHook townyHook;
    private NationTreasury treasury;
    private ArbitrageSimulator arbitrage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("currencies.yml", false);
        saveResource("messages.yml", false);
        saveResource("rates.yml", false);

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

        rates = new RatesService(this, getConfig().getDouble("exchange.default-fee", 0.02));
        rates.load(new File(getDataFolder(), getConfig().getString("exchange.rates-file", "rates.yml")));

        exchange = new ExchangeService(this, wallets, currencies, rates);
        confirms = new ConfirmManager(getConfig().getLong("exchange.confirm-timeout-seconds", 30));

        townyHook = new TownyHook(this);
        if (townyPresent && getConfig().getBoolean("hooks.towny.enabled", true)) {
            townyHook.init();
        }

        treasury = new NationTreasury(wallets);
        arbitrage = new ArbitrageSimulator(this, currencies, rates);

        if (townyHook.isAvailable()
                && getConfig().getBoolean("hooks.towny.auto-create-national", true)) {
            NationAutoCurrencyListener listener =
                    new NationAutoCurrencyListener(this, currencies, ledger);
            listener.register();
        }

        if (corePresent && getConfig().getBoolean("hooks.raskolcore.register-as-provider", true)) {
            coreHook = new RaskolCoreHook(this, wallets, currencies);
            coreHook.init();
        }

        RaskolVaultCommand executor = new RaskolVaultCommand(this);
        PluginCommand command = getCommand("rv");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Команда rv не описана в plugin.yml — команды отключены");
        }

        arbitrage.logReport();

        getLogger().info(() -> "RaskolVault v" + getPluginMeta().getVersion() + " включён"
                + " · Paper/MC " + getServer().getVersion()
                + " · Core " + (corePresent ? "on" : "off")
                + " · CoreProvider " + (coreHook != null && coreHook.isRegistered() ? "§aregistered§r" : "§coff§r")
                + " · Essentials " + (essentialsPresent ? "on" : "off")
                + " · Towny " + (townyPresent ? "on" : "off") + "/" + (townyHook.isAvailable() ? "§ahooked§r" : "§coff§r")
                + " · LP " + (luckPermsPresent ? "on" : "off")
                + " · PAPI " + (placeholderPresent ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        if (coreHook != null) {
            coreHook.shutdown();
        }
        if (confirms != null) {
            confirms.clear();
        }
        if (getConfig().getBoolean("storage.yaml-backup.enabled", true) && wallets != null) {
            saveBalancesBackup();
        }
        if (ledger != null) {
            ledger.close();
        }
        getLogger().info("RaskolVault выключен");
    }

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

    public SQLiteLedger getLedger() { return ledger; }
    public MessagesConfig getMessages() { return messages; }
    public CurrencyRegistry getCurrencies() { return currencies; }
    public EssentialsHook getEssentialsHook() { return essentialsHook; }
    public WalletService getWallets() { return wallets; }
    public RatesService getRates() { return rates; }
    public ExchangeService getExchange() { return exchange; }
    public ConfirmManager getConfirms() { return confirms; }
    public RaskolCoreHook getCoreHook() { return coreHook; }
    public TownyHook getTownyHook() { return townyHook; }
    public NationTreasury getTreasury() { return treasury; }
    public ArbitrageSimulator getArbitrage() { return arbitrage; }

    public boolean isCorePresent() { return corePresent; }
    public boolean isEssentialsPresent() { return essentialsPresent; }
    public boolean isTownyPresent() { return townyPresent; }
    public boolean isLuckPermsPresent() { return luckPermsPresent; }
    public boolean isPlaceholderPresent() { return placeholderPresent; }
}
