// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.confirm.ConfirmManager;
import dev.raskol.vault.exchange.ExchangeService;
import dev.raskol.vault.exchange.RatesService;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.listener.NationAutoCurrencyListener;
import dev.raskol.vault.listener.TownyNationLifecycleListener;
import dev.raskol.vault.nation.NationTreasury;
import dev.raskol.vault.offline.OfflinePlayerRegistry;
import dev.raskol.vault.storage.BackupService;
import dev.raskol.vault.storage.LedgerWriter;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.test.LoadSimulator;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * RaskolVault 1.1.0 — многовалютный экономический слой поверх EssentialsX.
 * 1.1.0-a: бренд (буквенные коды), /rv admin balance, /rv rates, merge валют из БД.
 */
public final class RaskolVault extends JavaPlugin {

    private boolean corePresent;
    private boolean essentialsPresent;
    private boolean townyPresent;
    private boolean luckPermsPresent;
    private boolean placeholderPresent;

    private SQLiteLedger ledger;
    private LedgerWriter writer;
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
    private OfflinePlayerRegistry offlinePlayerRegistry;
    private BackupService backups;
    private LoadSimulator loadSimulator;
    private BukkitTask checkpointTask;
    private BukkitTask inflationTask;
    private ConvertSubcommand convertSubcommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfAbsent("currencies.yml");
        saveResourceIfAbsent("messages.yml");
        saveResourceIfAbsent("rates.yml");

        messages = new MessagesConfig(this);
        messages.load(new File(getDataFolder(), getConfig().getString("messages.file", "messages.yml")));

        detectHooks();

        File dbFile = new File(getDataFolder(), getConfig().getString("storage.sqlite.file", "data/ledger.sqlite"));
        try {
            ledger = new SQLiteLedger(this, dbFile,
                    getConfig().getInt("storage.sqlite.pool-size", 5),
                    getConfig().getString("storage.sqlite.synchronous", "NORMAL"),
                    getConfig().getLong("storage.sqlite.borrow-timeout-ms", 5000));
            ledger.init();
            getLogger().info(() -> "RaskolVault: SQLite-леджер открыт (" + dbFile.getPath() + ") · " + ledger.describeStats());
        } catch (SQLException e) {
            getLogger().severe("RaskolVault: не могу открыть SQLite-леджер, плагин отключён: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        currencies = new CurrencyRegistry(this);
        currencies.load(new File(getDataFolder(), "currencies.yml"),
                getConfig().getString("global-currency.id", "GLD"),
                getConfig().getString("global-currency.display-name", "Золото"),
                getConfig().getString("global-currency.symbol", "GLD"),
                getConfig().getInt("global-currency.decimals", 2));
        currencies.mergeFromLedger(ledger);   // 1.1.0-a: рантайм-валюты переживают рестарт
        currencies.syncToLedger(ledger);

        essentialsHook = new EssentialsHook(this);
        essentialsHook.init();

        writer = new LedgerWriter(this, getConfig().getInt("storage.sqlite.writer-queue-cap", 10000));
        ledger.attachWriterStats(() -> " · writer queue " + writer.queueSize()
                + " · applied " + writer.applied() + " · failed " + writer.failed());

        wallets = new WalletService(this, ledger, writer, currencies, essentialsHook,
                getConfig().getLong("storage.sqlite.borrow-timeout-ms", 5000));
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

        offlinePlayerRegistry = new OfflinePlayerRegistry(this);
        offlinePlayerRegistry.init();
        getServer().getPluginManager().registerEvents(offlinePlayerRegistry, this);

        if (townyHook.isAvailable()) {
            new TownyNationLifecycleListener(this, currencies, ledger).register();
            if (getConfig().getBoolean("hooks.towny.auto-create-national", true)) {
                new NationAutoCurrencyListener(this, currencies, ledger).register();
            }
        }

        if (corePresent && getConfig().getBoolean("hooks.raskolcore.register-as-provider", true)) {
            coreHook = new RaskolCoreHook(this, wallets, currencies);
            coreHook.init();
        }

        if (placeholderPresent && getConfig().getBoolean("hooks.placeholderapi.enabled", true)) {
            try {
                new dev.raskol.vault.hook.PlaceholderApiHook(this).register();
                getLogger().info("RaskolVault: PAPI-экспаншн зарегистрирован (%raskolvault_*)");
            } catch (Throwable t) {
                getLogger().warning("RaskolVault: PAPI-регистрация не удалась: " + t.getMessage());
            }
        }

        if (getConfig().getBoolean("storage.daily-backup.enabled", true)) {
            backups = new BackupService(this, dbFile,
                    getConfig().getString("storage.daily-backup.time", "04:00"),
                    getConfig().getInt("storage.daily-backup.keep-days", 7));
            backups.start();
        }

        long checkpointMinutes = getConfig().getLong("storage.sqlite.checkpoint-interval-minutes", 5);
        if (checkpointMinutes > 0) {
            long periodTicks = checkpointMinutes * 60L * 20L;
            checkpointTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                long pages = ledger.checkpoint();
                if (getConfig().getBoolean("general.debug", false)) {
                    getLogger().info("RaskolVault: WAL checkpoint, страниц свёрнуто: " + pages);
                }
            }, periodTicks, periodTicks);
        }

        loadSimulator = new LoadSimulator(this, wallets, currencies.globalId());

        convertSubcommand = new ConvertSubcommand(this);
        RaskolVaultCommand executor = new RaskolVaultCommand(this, convertSubcommand);
        PluginCommand command = getCommand("rv");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Команда rv не описана в plugin.yml — команды отключены");
        }

        getLogger().info(() -> "RaskolVault v" + getPluginMeta().getVersion() + " включён"
                + " · Paper/MC " + getServer().getVersion()
                + " · Core " + (corePresent ? "on" : "off")
                + " · CoreProvider " + (coreHook != null && coreHook.isRegistered() ? "registered" : "off")
                + " · Essentials " + (essentialsPresent ? "on" : "off")
                + " · Towny " + (townyPresent ? "on" : "off") + "/" + (townyHook.isAvailable() ? "hooked" : "off")
                + " · LP " + (luckPermsPresent ? "on" : "off")
                + " · PAPI " + (placeholderPresent ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        if (inflationTask != null) {
            inflationTask.cancel();
            inflationTask = null;
        }
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        if (coreHook != null) {
            coreHook.shutdown();
        }
        if (confirms != null) {
            confirms.clear();
        }
        if (backups != null) {
            backups.stop();
        }
        if (getConfig().getBoolean("storage.yaml-backup.enabled", true) && wallets != null) {
            saveBalancesBackup();
        }
        if (writer != null) {
            writer.close(10000L);
        }
        if (ledger != null) {
            ledger.close();
        }
        getLogger().info("RaskolVault выключен");
    }

    private void saveResourceIfAbsent(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
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
    public LedgerWriter getWriter() { return writer; }
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
    public OfflinePlayerRegistry getOfflinePlayerRegistry() { return offlinePlayerRegistry; }
    public BackupService getBackups() { return backups; }
    public LoadSimulator getLoadSimulator() { return loadSimulator; }
    public ConvertSubcommand getConvertSubcommand() { return convertSubcommand; }

    public boolean isCorePresent() { return corePresent; }
    public boolean isEssentialsPresent() { return essentialsPresent; }
    public boolean isTownyPresent() { return townyPresent; }
    public boolean isLuckPermsPresent() { return luckPermsPresent; }
    public boolean isPlaceholderPresent() { return placeholderPresent; }
}
