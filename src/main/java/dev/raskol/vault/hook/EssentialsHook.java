// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Рефлексия-хук в EssentialsX: глобальная валюта ⚜ НЕ дублируется в RaskolVault —
 * единственный источник правды её баланса это Essentials (/bal, /eco).
 * RaskolVault читает и пишет её через IEssentials.getUser(UUID) + getMoney/setMoney.
 *
 * Без compile-зависимости: всё по именам методов. Дрейф сигнатур = громкая деградация
 * (хук выключается, операции с global возвращают false), а не тихая порча денег.
 */
public final class EssentialsHook {

    private final Plugin plugin;
    private Object essentials;
    private Method getUser;
    private Method getMoney;
    private Method setMoney;
    private boolean available;

    public EssentialsHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin target = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (target == null || !target.isEnabled()) {
            available = false;
            return;
        }
        try {
            Class<?> iface = Class.forName("com.earth2me.essentials.IEssentials");
            essentials = iface.cast(target);
            try {
                getUser = iface.getMethod("getUser", UUID.class);
            } catch (NoSuchMethodException fallback) {
                getUser = iface.getMethod("getUser", Object.class);
            }
            available = true;
            plugin.getLogger().info("RaskolVault: Essentials-хук активен (global = баланс Essentials)");
        } catch (ReflectiveOperationException | ClassCastException e) {
            available = false;
            essentials = null;
            plugin.getLogger().severe("RaskolVault: Essentials API не узнан (" + e.getMessage()
                    + ") — операции с global-валютой отключены до правки хука");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public double getBalance(UUID uuid) {
        if (!available) {
            return 0.0D;
        }
        try {
            Object user = getUser.invoke(essentials, uuid);
            if (user == null) {
                return 0.0D;
            }
            if (getMoney == null) {
                getMoney = user.getClass().getMethod("getMoney");
            }
            Object money = getMoney.invoke(user);
            return money instanceof BigDecimal decimal ? decimal.doubleValue() : 0.0D;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("RaskolVault: Essentials getMoney сбой: " + e.getMessage());
            return 0.0D;
        }
    }

    public boolean setBalance(UUID uuid, double amount) {
        if (!available) {
            return false;
        }
        try {
            Object user = getUser.invoke(essentials, uuid);
            if (user == null) {
                return false;
            }
            if (setMoney == null) {
                setMoney = user.getClass().getMethod("setMoney", BigDecimal.class);
            }
            setMoney.invoke(user, BigDecimal.valueOf(amount));
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("RaskolVault: Essentials setMoney сбой: " + e.getMessage());
            return false;
        }
    }
}
