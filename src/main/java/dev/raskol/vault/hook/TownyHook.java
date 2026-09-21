// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Рефлексия-хук Towny (фикс 1.1.0.1): в Towny 0.103 нет Town.isKing();
 * король нации = мэр её столицы (Nation.getCapital().getMayor()).
 * Сравнение по UUID мэра, фолбэк — по имени оффлайн-игрока.
 */
public final class TownyHook {

    private final JavaPlugin plugin;
    private boolean available;
    private Object townyUniverse;
    private Method getResidentMethod;
    private Method getTownMethod;
    private Method getNationMethod;
    private Method getNameMethod;
    private Method getCapitalMethod;
    private Method getMayorMethod;

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
            getMayorMethod = townClass.getMethod("getMayor");
            Class<?> nationClass = getNationMethod.getReturnType();
            getCapitalMethod = nationClass.getMethod("getCapital");
            getNameMethod = nationClass.getMethod("getName");
            available = true;
            plugin.getLogger().info("RaskolVault: Towny-хук активен (рефлексия 0.103+: король = мэр столицы)");
        } catch (Exception e) {
            available = false;
            plugin.getLogger().warning("RaskolVault: Towny-хук не инициализирован: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Имя нации игрока или null. */
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

    /** true, если игрок — король указанной нации (мэр её столицы). */
    public boolean isKing(UUID playerUuid, String nationName) {
        if (!available || playerUuid == null || nationName == null) {
            return false;
        }
        try {
            Object resident = getResidentMethod.invoke(townyUniverse, playerUuid);
            if (resident == null) {
                return false;
            }
            Object town = getTownMethod.invoke(resident);
            if (town == null) {
                return false;
            }
            Object nation = getNationMethod.invoke(town);
            if (nation == null) {
                return false;
            }
            String actual = (String) getNameMethod.invoke(nation);
            if (actual == null || !nationName.equalsIgnoreCase(actual)) {
                return false;
            }
            Object capital = getCapitalMethod.invoke(nation);
            if (capital == null) {
                return false;
            }
            Object mayor = getMayorMethod.invoke(capital);
            if (mayor == null) {
                return false;
            }
            UUID mayorUuid = extractUuid(mayor);
            if (mayorUuid != null) {
                return mayorUuid.equals(playerUuid);
            }
            String mayorName = (String) getNameMethod.invoke(mayor);
            OfflinePlayer op = Bukkit.getOfflinePlayer(playerUuid);
            return op.getName() != null && op.getName().equalsIgnoreCase(mayorName);
        } catch (Exception e) {
            return false;
        }
    }

    private UUID extractUuid(Object resident) {
        try {
            Method m = resident.getClass().getMethod("getUUID");
            Object v = m.invoke(resident);
            return v instanceof UUID u ? u : null;
        } catch (Exception e) {
            return null;
        }
    }
}
