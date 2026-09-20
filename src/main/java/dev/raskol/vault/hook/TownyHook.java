// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Рефлексия-хук для Towny (1.0.6: убрана compile-зависимость).
 *
 * Работает без Towny API в classpath: все вызовы через рефлексию.
 * Если Towny не установлен — isAvailable()=false, nationOf()=null.
 */
public final class TownyHook {

    private final JavaPlugin plugin;
    private boolean available;
    private Object townyUniverse;
    private Method getResidentMethod;
    private Method getTownMethod;
    private Method getNationMethod;
    private Method getNameMethod;

    public TownyHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin towny = Bukkit.getPluginManager().getPlugin("Towny");
        if (towny == null || !towny.isEnabled()) {
            available = false;
            plugin.getLogger().info("RaskolVault: Towny не найден, nation-хук отключён");
            return;
        }
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            townyUniverse = getInstance.invoke(null);

            getResidentMethod = universeClass.getMethod("getResident", UUID.class);
            
            Class<?> residentClass = Class.forName("com.palmergames.bukkit.towny.object.Resident");
            getTownMethod = residentClass.getMethod("getTownOrNull");
            
            Class<?> townClass = Class.forName("com.palmergames.bukkit.towny.object.Town");
            getNationMethod = townClass.getMethod("getNationOrNull");
            
            Class<?> nationClass = Class.forName("com.palmergames.bukkit.towny.object.Nation");
            getNameMethod = nationClass.getMethod("getName");

            available = true;
            plugin.getLogger().info("RaskolVault: Towny-хук активен (через рефлексию)");
        } catch (Exception e) {
            available = false;
            plugin.getLogger().warning("RaskolVault: Towny-хук не инициализирован: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String nationOf(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }
        try {
            Object resident = getResidentMethod.invoke(townyUniverse, uuid);
            if (resident == null) {
                return null;
            }
            Object town = getTownMethod.invoke(resident);
            if (town == null) {
                return null;
            }
            Object nation = getNationMethod.invoke(town);
            if (nation == null) {
                return null;
            }
            return (String) getNameMethod.invoke(nation);
        } catch (Exception e) {
            return null;
        }
    }
}
