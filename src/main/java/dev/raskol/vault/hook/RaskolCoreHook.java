// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

public final class RaskolCoreHook {

    private final Plugin plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;

    private Object registry;
    private Object proxy;
    private Method unregisterMethod;
    private boolean registered;

    public RaskolCoreHook(Plugin plugin, WalletService wallets, CurrencyRegistry currencies) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
    }

    public void init() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("RaskolCore")) {
            return;
        }
        Plugin corePlugin = plugin.getServer().getPluginManager().getPlugin("RaskolCore");
        if (corePlugin == null) {
            return;
        }
        ClassLoader coreCl = corePlugin.getClass().getClassLoader();
        try {
            Class<?> apiClass = Class.forName("dev.raskol.core.RaskolCoreAPI", true, coreCl);
            Class<?> providerIface = Class.forName("dev.raskol.core.api.economy.EconomyProvider", true, coreCl);
            Class<?> registryClass = Class.forName("dev.raskol.core.api.economy.EconomyRegistry", true, coreCl);

            Method economyMethod = apiClass.getMethod("economy");
            registry = economyMethod.invoke(null);
            if (registry == null) {
                plugin.getLogger().warning("RaskolVault: RaskolCoreAPI.economy() вернул null — "
                        + "регистрация провайдера пропущена (Core ещё не готов?)");
                return;
            }

            proxy = Proxy.newProxyInstance(coreCl, new Class<?>[]{providerIface},
                    new EconomyProviderHandler(wallets, currencies.globalId()));
            Method registerMethod = registryClass.getMethod("register", providerIface);
            registerMethod.invoke(registry, proxy);
            unregisterMethod = registryClass.getMethod("unregister", providerIface);
            registered = true;
            plugin.getLogger().info("RaskolVault: зарегистрирован EconomyProvider в RaskolCore "
                    + "(global=" + currencies.globalId() + ")");
        } catch (ReflectiveOperationException e) {
            registered = false;
            proxy = null;
            registry = null;
            plugin.getLogger().warning("RaskolVault: не удалось зарегистрироваться в RaskolCore "
                    + "(" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") — раскол-плагины продолжат ходить в Vault/Essentials напрямую");
        }
    }

    public void shutdown() {
        if (registered && registry != null && proxy != null && unregisterMethod != null) {
            try {
                unregisterMethod.invoke(registry, proxy);
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("RaskolVault: не удалось снять EconomyProvider из RaskolCore: "
                        + e.getMessage());
            }
        }
        registered = false;
        proxy = null;
        registry = null;
    }

    public boolean isRegistered() {
        return registered;
    }

    private static final class EconomyProviderHandler implements InvocationHandler {

        private final WalletService wallets;
        private final String globalId;

        EconomyProviderHandler(WalletService wallets, String globalId) {
            this.wallets = wallets;
            this.globalId = globalId;
        }

        @Override
        public Object invoke(Object proxyObj, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "currencyOf":
                    return globalId;
                case "balance":
                    if (args == null || args.length != 1 || args[0] == null) {
                        return 0.0D;
                    }
                    return wallets.getBalance((UUID) args[0], globalId);
                case "withdraw":
                    if (args == null || args.length != 2 || args[0] == null || !(args[1] instanceof Number)) {
                        return false;
                    }
                    return wallets.withdraw((UUID) args[0], globalId,
                            ((Number) args[1]).doubleValue(),
                            TransactionType.ADMIN_TAKE, "core-provider");
                case "deposit":
                    if (args == null || args.length != 2 || args[0] == null || !(args[1] instanceof Number)) {
                        return false;
                    }
                    return wallets.deposit((UUID) args[0], globalId,
                            ((Number) args[1]).doubleValue(),
                            TransactionType.ADMIN_GIVE, "core-provider");
                case "transfer":
                    if (args == null || args.length != 3
                            || args[0] == null || args[1] == null || !(args[2] instanceof Number)) {
                        return false;
                    }
                    return wallets.transfer((UUID) args[0], (UUID) args[1], globalId,
                            ((Number) args[2]).doubleValue(), "core-provider");
                case "isAvailable":
                    return true;
                case "toString":
                    return "RaskolVaultEconomyProvider[global=" + globalId + "]";
                case "hashCode":
                    return System.identityHashCode(proxyObj);
                case "equals":
                    return proxyObj == args[0];
                default:
                    return null;
            }
        }
    }
}
