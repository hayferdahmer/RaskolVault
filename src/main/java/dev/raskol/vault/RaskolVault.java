package dev.raskol.vault;

import dev.raskol.vault.command.RaskolVaultCommand;
import dev.raskol.vault.config.MessagesConfig;
import dev.raskol.vault.config.VaultConfig;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.exchange.ExchangeRatesRegistry;
import dev.raskol.vault.exchange.ExchangeService;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.hook.LuckPermsHook;
import dev.raskol.vault.hook.PlaceholderExpansion;
import dev.raskol.vault.hook.RaskolCoreHook;
import dev.raskol.vault.hook.TownyHook;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

/**
 * RaskolVault — multi-currency economy layer over EssentialsX.
 *
 * Stage 0 skeleton: loads configs, initializes SQLite ledger, hooks optional
 * integrations (Essentials, RaskolCore, Towny, LuckPerms, PlaceholderAPI).
 *
 * Copyright (c) 2026 hayferdahmer — RASKOL Proprietary License v1.0
 */
public final class RaskolVault extends JavaPlugin {

    private static RaskolVault instance;

    private VaultConfig vaultConfig;
    private MessagesConfig messagesConfig;
    private SQLiteLedger ledger;
    private CurrencyRegistry currencyRegistry;
    private WalletService walletService;
    private ExchangeRatesRegistry ratesRegistry;
    private ExchangeService exchangeService;

    private EssentialsHook essentialsHook;
    private RaskolCoreHook raskolCoreHook;
    private TownyHook townyHook;
    private LuckPermsHook luckPermsHook;
    private PlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        instance = this;

        // ---- Configs ----
        saveDefaultConfig();
        this.vaultConfig = new VaultConfig(this);
        this.messagesConfig = new MessagesConfig(this);

        // ---- SQLite Ledger ----
        File dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().severe("Не удалось создать папку data/ — плагин отключён");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        File ledgerFile = new File(dataDir, "ledger.sqlite");
        this.ledger = new SQLiteLedger(this, ledgerFile);
        if (!ledger.initialize()) {
            getLogger().severe("Не удалось инициализировать SQLite — плагин отключён");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---- Domain services ----
        this.currencyRegistry = new CurrencyRegistry(this, ledger);
        this.walletService = new WalletService(this, ledger, currencyRegistry);
        this.ratesRegistry = new ExchangeRatesRegistry(this, ledger);
        this.exchangeService = new ExchangeService(this, walletService, ratesRegistry, ledger);

        // ---- Hooks (optional, fail-safe) ----
        this.essentialsHook = new EssentialsHook(this);
        if (!essentialsHook.hook()) {
            getLogger().severe("Essentials не найден — RaskolVault не может работать без глобальной валюты. Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.raskolCoreHook = new RaskolCoreHook(this);
        raskolCoreHook.hook();

        this.townyHook = new TownyHook(this);
        townyHook.hook();

        this.luckPermsHook = new LuckPermsHook(this);
        luckPermsHook.hook();

        this.placeholderExpansion = new PlaceholderExpansion(this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion.register();
        }

        // ---- Commands ----
        PluginCommand cmd = getCommand("rv");
        if (cmd != null) {
            RaskolVaultCommand executor = new RaskolVaultCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().log(Level.WARNING, "Команда /rv не зарегистрирована в plugin.yml");
        }

        getLogger().info("RaskolVault v" + getDescription().getVersion()
                + " включён · Paper/MC " + getServer().getVersion()
                + " · Essentials on"
                + " · RaskolCore " + (raskolCoreHook.isEnabled() ? "on" : "off")
                + " · Towny " + (townyHook.isEnabled() ? "on" : "off")
                + " · LP " + (luckPermsHook.isEnabled() ? "on" : "off")
                + " · PAPI " + (placeholderExpansion.isRegistered() ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        if (walletService != null) {
            walletService.saveAll();
        }
        if (ledger != null) {
            ledger.close();
        }
        getLogger().info("RaskolVault отключён.");
    }

    public static RaskolVault getInstance() {
        return instance;
    }

    public VaultConfig getVaultConfig() {
        return vaultConfig;
    }

    public MessagesConfig getMessagesConfig() {
        return messagesConfig;
    }

    public SQLiteLedger getLedger() {
        return ledger;
    }

    public CurrencyRegistry getCurrencyRegistry() {
        return currencyRegistry;
    }

    public WalletService getWalletService() {
        return walletService;
    }

    public ExchangeRatesRegistry getRatesRegistry() {
        return ratesRegistry;
    }

    public ExchangeService getExchangeService() {
        return exchangeService;
    }

    public EssentialsHook getEssentialsHook() {
        return essentialsHook;
    }

    public RaskolCoreHook getRaskolCoreHook() {
        return raskolCoreHook;
    }

    public TownyHook getTownyHook() {
        return townyHook;
    }

    public LuckPermsHook getLuckPermsHook() {
        return luckPermsHook;
    }
}
