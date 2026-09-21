// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault;

import dev.raskol.vault.api.RaskolVaultAPI;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.command.sub.ConvertSubcommand;
import dev.raskol.vault.config.ConfigValidator;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.confirm.ConfirmManager;
import dev.raskol.vault.escrow.EscrowService;
import dev.raskol.vault.exchange.ConvertEngine;
import dev.raskol.vault.exchange.ExchangeService;
import dev.raskol.vault.exchange.RatesService;
import dev.raskol.vault.gui.ExchangeGui;
import dev.raskol.vault.gui.GuiListener;
import dev.raskol.vault.gui.ReserveGui;
import dev.raskol.vault.gui.WalletGui;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.hook.LuckPermsHook;
import dev.raskol.vault.hook.PlaceholderApiHook;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.listener.NationAutoCurrencyListener;
import dev.raskol.vault.listener.TownyNationLifecycleListener;
import dev.raskol.vault.nation.NationTreasury;
import dev.raskol.vault.observability.InflationCheckpoint;
import dev.raskol.vault.observability.SparkHook;
import dev.raskol.vault.observability.TxCounter;
import dev.raskol.vault.offline.OfflinePlayerRegistry;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.safety.RateLimiter;
import dev.raskol.vault.storage.BackupService;
import dev.raskol.vault.storage.LedgerWriter;
import dev.raskol.vault.storage.RestoreService;
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
 * RaskolVault 1.2.1-SNAPSHOT (база для 1.2.2): + ExchangeGui (GUI биржи).
 */
public final class RaskolVault extends JavaPlugin {

    private boolean corePresent, essentialsPresent, townyPresent, luckPermsPresent, placeholderPresent;

    private SQLiteLedger ledger;
    private LedgerWriter writer;
    private MessagesConfig messages;
    private CurrencyRegistry currencies;
    private EssentialsHook essentialsHook;
    private WalletService wallets;
    private RatesService rates;
    private ExchangeService exchange;
    private ConvertEngine convertEngine;
    private ConfirmManager confirms;
    private RaskolCoreHook coreHook;
    private TownyHook townyHook;
    private LuckPermsHook luckPermsHook;
    private NationTreasury treasury;
    private OfflinePlayerRegistry offlinePlayerRegistry;
    private BackupService backups;
    private LoadSimulator loadSimulator;
    private RestoreService restoreService;
    private EscrowService escrowService;
    private BukkitTask checkpointTask, inflationTask, reconcileTask, writerAlarmTask;
    private ConvertSubcommand convertSubcommand;
    private SparkHook sparkHook;
    private ReserveBank reserveBank;
    private RateLimiter rateLimiter, payLimiter;
    private TxCounter txCounter;
    private InflationCheckpoint inflationCheckpoint;
    private RaskolVaultAPI api;
    private ReserveGui reserveGui;
    private ExchangeGui exchangeGui;
    private long startTimeMillis;
    private volatile long lastReconcileMillis;

    @Override
    public void onEnable() {
        this.startTimeMillis = System.currentTimeMillis();
        saveDefaultConfig();
        saveResourceIfAbsent("currencies.yml");
        saveResourceIfAbsent("messages.yml");
        saveResourceIfAbsent("rates.yml");

        ConfigValidator validator = new ConfigValidator(this);
        validator.report(validator.validate(getConfig()));

        messages = new MessagesConfig(this);
        messages.load(new File(getDataFolder(), getConfig().getString("messages.file", "messages.yml")));

        detectHooks();

        File dbFile = new File(getDataFolder(), getConfig().getString("storage.sqlite.file", "data/ledger.sqlite"));
        restoreService = new RestoreService(this);
        restoreService.maybeRestore(dbFile);

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
        currencies.mergeFromLedger(ledger);
        currencies.syncToLedger(ledger);

        essentialsHook = new EssentialsHook(this);
        essentialsHook.init();

        writer = new LedgerWriter(this, getConfig().getInt("storage.sqlite.writer-queue-cap", 10000));
        ledger.attachWriterStats(() -> " · writer queue " + writer.queueSize()
                + " · applied " + writer.applied() + " · failed " + writer.failed());

        sparkHook = new SparkHook(this);

        wallets = new WalletService(this, ledger, writer, currencies, essentialsHook,
                getConfig().getLong("storage.sqlite.borrow-timeout-ms", 5000), sparkHook);
        wallets.init();

        rates = new RatesService(this, getConfig().getDouble("exchange.default-fee", 0.02));
        rates.load(new File(getDataFolder(), getConfig().getString("exchange.rates-file", "rates.yml")));

        reserveBank = new ReserveBank(this, wallets, currencies, ledger);
        convertEngine = new ConvertEngine(this, wallets, currencies, reserveBank);
        exchange = new ExchangeService(this, wallets, currencies, rates);
        confirms = new ConfirmManager(getConfig().getLong("exchange.confirm-timeout-seconds", 30));
        escrowService = new EscrowService(this, wallets, ledger);

        luckPermsHook = new LuckPermsHook(this);
        if (luckPermsPresent && getConfig().getBoolean("hooks.luckperms.enabled", true)) luckPermsHook.init();

        townyHook = new TownyHook(this);
        if (townyPresent && getConfig().getBoolean("hooks.towny.enabled", true)) townyHook.init();

        treasury = new NationTreasury(wallets);

        rateLimiter = new RateLimiter(getConfig().getDouble("safety.rate-limit.capacity", 5.0),
                getConfig().getDouble("safety.rate-limit.refill-per-second", 0.5));
        payLimiter = new RateLimiter(getConfig().getDouble("safety.pay-rate-limit.capacity", 5.0),
                getConfig().getDouble("safety.pay-rate-limit.refill-per-second", 0.5));
        txCounter = new TxCounter(ledger);
        inflationCheckpoint = new InflationCheckpoint(this, reserveBank, currencies, townyHook);

        api = new RaskolVaultAPI(this);

        offlinePlayerRegistry = new OfflinePlayerRegistry(this);
        offlinePlayerRegistry.init();
        getServer().getPluginManager().registerEvents(offlinePlayerRegistry, this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        // GUI: резерв (кабинет-заглушка) + биржа
        reserveGui = new ReserveGui(this);
        getServer().getPluginManager().registerEvents(reserveGui, this);
        exchangeGui = new ExchangeGui(this);
        getServer().getPluginManager().registerEvents(exchangeGui, this);

        if (townyHook.isAvailable()) {
            new TownyNationLifecycleListener(this, currencies, ledger).register();
            if (getConfig().getBoolean("hooks.towny.auto-create-national", true))
                new NationAutoCurrencyListener(this, currencies, ledger).register();
        }

        if (corePresent && getConfig().getBoolean("hooks.raskolcore.register-as-provider", true)) {
            coreHook = new RaskolCoreHook(this, wallets, currencies);
            coreHook.init();
        }

        if (placeholderPresent && getConfig().getBoolean("hooks.placeholderapi.enabled", true)) {
            try {
                new PlaceholderApiHook(this).register();
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
            long period = checkpointMinutes * 60L * 20L;
            checkpointTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                long pages = ledger.checkpoint();
                if (getConfig().getBoolean("general.debug", false))
                    getLogger().info("RaskolVault: WAL checkpoint, страниц свёрнуто: " + pages);
            }, period, period);
        }

        long inflationMinutes = getConfig().getLong("reserve.check-interval-minutes", 60);
        if (inflationMinutes > 0) {
            long period = inflationMinutes * 60L * 20L;
            inflationTask = getServer().getScheduler().runTaskTimerAsynchronously(this, inflationCheckpoint, period, period);
        }

        long reconcileMinutes = getConfig().getLong("storage.reconcile-interval-minutes", 30);
        if (reconcileMinutes > 0) {
            long period = reconcileMinutes * 60L * 20L;
            reconcileTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                int healed = wallets.reconcile();
                int evicted = confirms.evictExpired();
                WalletGui.CHAT_CAPTURE.clear();
                ReserveGui.CHAT_CAPTURE.clear();
                lastReconcileMillis = System.currentTimeMillis();
                if (healed > 0) getLogger().warning("RaskolVault: сверка кэш↔леджер: вылечено расхождений: " + healed);
            }, period, period);
        }

        long[] alarm = new long[]{0};
        writerAlarmTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            int queue = writer.queueSize();
            long failed = writer.failed();
            if (queue > 500) getLogger().warning("RaskolVault: ⚠ writer queue " + queue + " > 500");
            if (failed > alarm[0]) {
                getLogger().warning("RaskolVault: ⚠ writer failed +" + (failed - alarm[0]) + " (итого " + failed + ")");
                alarm[0] = failed;
            }
        }, 600L, 600L);

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
                + " · Towny " + (townyHook.isAvailable() ? "hooked" : "off")
                + " · Биржа GUI активна");
    }

    @Override
    public void onDisable() {
        if (writerAlarmTask != null) writerAlarmTask.cancel();
        if (reconcileTask != null) reconcileTask.cancel();
        if (inflationTask != null) inflationTask.cancel();
        if (checkpointTask != null) checkpointTask.cancel();
        if (coreHook != null) coreHook.shutdown();
        if (confirms != null) confirms.clear();
        if (backups != null) backups.stop();
        if (getConfig().getBoolean("storage.yaml-backup.enabled", true) && wallets != null) saveBalancesBackup();
        if (writer != null) writer.close(10000L);
        if (ledger != null) ledger.close();
        getLogger().info("RaskolVault выключен");
    }

    private void saveResourceIfAbsent(String name) {
        if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void saveBalancesBackup() {
        File file = new File(getDataFolder(), getConfig().getString("storage.yaml-backup.file", "data/balances.yml"));
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("balances");
        for (Map.Entry<UUID, Map<String, Double>> e : wallets.cacheSnapshot().entrySet()) {
            ConfigurationSection row = root.createSection(e.getKey().toString());
            for (Map.Entry<String, Double> cell : e.getValue().entrySet()) row.set(cell.getKey(), cell.getValue());
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
        Plugin p = getServer().getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    public SQLiteLedger getLedger() { return ledger; }
    public LedgerWriter getWriter() { return writer; }
    public MessagesConfig getMessages() { return messages; }
    public CurrencyRegistry getCurrencies() { return currencies; }
    public EssentialsHook getEssentialsHook() { return essentialsHook; }
    public WalletService getWallets() { return wallets; }
    public RatesService getRates() { return rates; }
    public ExchangeService getExchange() { return exchange; }
    public ConvertEngine getConvertEngine() { return convertEngine; }
    public ConfirmManager getConfirms() { return confirms; }
    public RaskolCoreHook getCoreHook() { return coreHook; }
    public TownyHook getTownyHook() { return townyHook; }
    public LuckPermsHook getLuckPermsHook() { return luckPermsHook; }
    public NationTreasury getTreasury() { return treasury; }
    public OfflinePlayerRegistry getOfflinePlayerRegistry() { return offlinePlayerRegistry; }
    public BackupService getBackups() { return backups; }
    public LoadSimulator getLoadSimulator() { return loadSimulator; }
    public RestoreService getRestoreService() { return restoreService; }
    public EscrowService getEscrow() { return escrowService; }
    public ConvertSubcommand getConvertSubcommand() { return convertSubcommand; }
    public SparkHook getSparkHook() { return sparkHook; }
    public ReserveBank getReserveBank() { return reserveBank; }
    public RateLimiter getRateLimiter() { return rateLimiter; }
    public RateLimiter getPayRateLimiter() { return payLimiter; }
    public TxCounter getTxCounter() { return txCounter; }
    public InflationCheckpoint getInflationCheckpoint() { return inflationCheckpoint; }
    public RaskolVaultAPI getAPI() { return api; }
    public ReserveGui getReserveGui() { return reserveGui; }
    public ExchangeGui getExchangeGui() { return exchangeGui; }
    public long getStartTimeMillis() { return startTimeMillis; }
    public long getLastReconcileMillis() { return lastReconcileMillis; }

    public boolean isCorePresent() { return corePresent; }
    public boolean isEssentialsPresent() { return essentialsPresent; }
    public boolean isTownyPresent() { return townyPresent; }
    public boolean isLuckPermsPresent() { return luckPermsPresent; }
    public boolean isPlaceholderPresent() { return placeholderPresent; }
}
