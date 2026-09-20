// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.arbitrage.ArbitrageSimulator;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.confirm.ConfirmManager;
import dev.raskol.vault.exchange.ExchangeService;
import dev.raskol.vault.exchange.RatesService;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.hook.PlaceholderApiHook;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.listener.NationAutoCurrencyListener;
import dev.raskol.vault.nation.NationTreasury;
import dev.raskol.vault.observability.InflationCheckpoint;
import dev.raskol.vault.observability.SparkHook;
import dev.raskol.vault.observability.TxPerMinuteCounter;
import dev.raskol.vault.security.TokenBucket;
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
 * RaskolVault — многовалютный экономический слой поверх EssentialsX.
 *
 * 1.0.5: TokenBucket rate-limit, оптимистичные коммиты + heal,
 * почасовой инфляционный чекпоинт, GLOBAL-мутации под локом.
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
    private ArbitrageSimulator arbitrage;
    private PlaceholderApiHook papiHook;
    private BackupService backups;
    private LoadSimulator loadSimulator;
    private BukkitTask checkpointTask;
    private BukkitTask inflationTask;
    private ConvertSubcommand convertSubcommand;
    private TxPerMinuteCounter txCounter;
    private SparkHook spark;
    private TokenBucket rateLimiter;
    private InflationCheckpoint inflationCheckpoint;

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

        txCounter = new TxPerMinuteCounter();
        ledger.attachTxCounter(txCounter);
        spark = new SparkHook(this);

        // 1.0.5: rate-limit (capacity <= 0 = выключен)
        rateLimiter = new TokenBucket(
                getConfig().getBoolean("security.rate-limit.enabled", true)
                        ? getConfig().getDouble("security.rate-limit.capacity", 8.0D)
                        : 0.0D,
                getConfig().getDouble("security.rate-limit.refill-per-second", 2.0D));

        writer = new LedgerWriter(this, getConfig().getInt("storage.sqlite.writer-queue-cap", 10000));
        ledger.attachWriterStats(() -> " · writer queue " + writer.queueSize()
                + " · applied " + writer.applied() + " · failed " + writer.failed());

        currencies = new CurrencyRegistry(this);
        currencies.load(new File(getDataFolder(), "currencies.yml"),
                getConfig().getString("global-currency.id", "GLD"),
                getConfig().getString("global-currency.display-name", "Золото"),
                getConfig().getString("global-currency.symbol", "⚜"),
                getConfig().getInt("global-currency.decimals", 2));
        currencies.syncToLedger(ledger);

        ledger.migrateLegacyCurrencyIds(Map.of(
                "gold", "GLD",
                "denarius", "RAS",
                "crown", "VLR"
        ));

        essentialsHook = new EssentialsHook(this);
        essentialsHook.init();

        wallets = new WalletService(this, ledger, writer, currencies, essentialsHook,
                getConfig().getLong("storage.sqlite.borrow-timeout-ms", 5000), spark);
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

        if (placeholderPresent && getConfig().getBoolean("hooks.placeholderapi.enabled", true)) {
            try {
                papiHook = new PlaceholderApiHook(this);
                papiHook.register();
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
            getLogger().info("RaskolVault: WAL-checkpoint каждые " + checkpointMinutes + " мин");
        }

        // 1.0.5: инфляционный чекпоинт (первый прогон через 100 тиков, далее каждый час)
        if (getConfig().getBoolean("security.inflation-check.enabled", true)) {
            inflationCheckpoint = new InflationCheckpoint(this, ledger, currencies, rateLimiter);
            long intervalTicks = getConfig().getLong("security.inflation-check.interval-minutes", 60L) * 60L * 20L;
            inflationTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    inflationCheckpoint, 100L, Math.max(1200L, intervalTicks));
            getLogger().info("RaskolVault: инфляционный чекпоинт каждые "
                    + getConfig().getLong("security.inflation-check.interval-minutes", 60L) + " мин");
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

        arbitrage.logReport();

        getLogger().info(() -> "RaskolVault v" + getPluginMeta().getVersion() + " включён"
                + " · Paper/MC " + getServer().getVersion()
                + " · Core " + (corePresent ? "on" : "off")
                + " · CoreProvider " + (coreHook != null && coreHook.isRegistered() ? "registered" : "off")
                + " · Essentials " + (essentialsPresent ? "on" : "off")
                + " · Towny " + (townyPresent ? "on" : "off") + "/" + (townyHook.isAvailable() ? "hooked" : "off")
                + " · LP " + (luckPermsPresent ? "on" : "off")
                + " · PAPI " + (placeholderPresent ? "on" : "off")
                + " · Spark " + (spark.isAvailable() ? "on" : "off")
                + " · RateLimit " + (rateLimiter.isEnabled() ? "on" : "off"));
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
        if (papiHook != null) {
            papiHook.unregister();
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
    public ArbitrageSimulator getArbitrage() { return arbitrage; }
    public BackupService getBackups() { return backups; }
    public LoadSimulator getLoadSimulator() { return loadSimulator; }
    public ConvertSubcommand getConvertSubcommand() { return convertSubcommand; }
    public TxPerMinuteCounter getTxCounter() { return txCounter; }
    public SparkHook getSpark() { return spark; }
    public TokenBucket getRateLimiter() { return rateLimiter; }
    public InflationCheckpoint getInflationCheckpoint() { return inflationCheckpoint; }

    public boolean isCorePresent() { return corePresent; }
    public boolean isEssentialsPresent() { return essentialsPresent; }
    public boolean isTownyPresent() { return townyPresent; }
    public boolean isLuckPermsPresent() { return luckPermsPresent; }
    public boolean isPlaceholderPresent() { return placeholderPresent; }
}
