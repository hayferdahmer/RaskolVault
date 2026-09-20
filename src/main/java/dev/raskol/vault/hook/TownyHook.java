// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Рефлексия-хук в Towny. Без compile-зависимости: классы и методы ищутся
 * по именам через ClassLoader Towny. Если Towny отсутствует или API дрейфует —
 * хук тихо деградирует (isAvailable=false), без падения сервера.
 *
 * Поддерживаемые операции:
 * - nationOf(UUID) — ID нации игрока (lower-case) или null
 * - kingOf(nationId) — UUID короля
 * - isKing(UUID, nationId) — проверка на короля
 * - residentsOf(nationId) — список UUID резидентов
 */
public final class TownyHook {

    private final Plugin plugin;
    private Object townyUniverse;
    private Method getResidentByUuid;
    private Method getTownOfResident;
    private Method getNationOfTown;
    private Method getName;
    private Method getKing;
    private Method getUuidOfResident;
    private Method getResidentsOfNation;
    private boolean available;

    public TownyHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin target = plugin.getServer().getPluginManager().getPlugin("Towny");
        if (target == null || !target.isEnabled()) {
            available = false;
            return;
        }
        ClassLoader cl = target.getClass().getClassLoader();
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse", true, cl);
            Method getInstance = universeClass.getMethod("getInstance");
            townyUniverse = getInstance.invoke(null);

            Class<?> residentClass = Class.forName("com.palmergames.bukkit.towny.object.Resident", true, cl);
            Class<?> townClass = Class.forName("com.palmergames.bukkit.towny.object.Town", true, cl);
            Class<?> nationClass = Class.forName("com.palmergames.bukkit.towny.object.Nation", true, cl);

            getResidentByUuid = universeClass.getMethod("getResident", UUID.class);
            getTownOfResident = residentClass.getMethod("getTownOrNull");
            getNationOfTown = townClass.getMethod("getNationOrNull");
            getName = nationClass.getMethod("getName");
            getKing = nationClass.getMethod("getKing");
            getUuidOfResident = residentClass.getMethod("getUUID");
            getResidentsOfNation = nationClass.getMethod("getResidents");

            available = true;
            plugin.getLogger().info("RaskolVault: Towny-хук активен (нации/короли/резиденты)");
        } catch (ReflectiveOperationException e) {
            available = false;
            plugin.getLogger().warning("RaskolVault: Towny API не узнан (" + e.getMessage()
                    + ") — национальные валюты недоступны до правки хука");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** ID нации (lower-case) или null, если игрок не в нации или Towny недоступен. */
    public String nationOf(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }
        try {
            Object resident = getResidentByUuid.invoke(townyUniverse, uuid);
            if (resident == null) {
                return null;
            }
            Object town = getTownOfResident.invoke(resident);
            if (town == null) {
                return null;
            }
            Object nation = getNationOfTown.invoke(town);
            if (nation == null) {
                return null;
            }
            Object name = getName.invoke(nation);
            return name == null ? null : name.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public UUID kingOf(String nationIdLower) {
        Object nation = findNation(nationIdLower);
        if (nation == null) {
            return null;
        }
        try {
            Object king = getKing.invoke(nation);
            if (king == null) {
                return null;
            }
            Object uuid = getUuidOfResident.invoke(king);
            return uuid instanceof UUID u ? u : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public boolean isKing(UUID uuid, String nationIdLower) {
        UUID king = kingOf(nationIdLower);
        return king != null && king.equals(uuid);
    }

    public List<UUID> residentsOf(String nationIdLower) {
        List<UUID> out = new ArrayList<>();
        Object nation = findNation(nationIdLower);
        if (nation == null) {
            return out;
        }
        try {
            Object residents = getResidentsOfNation.invoke(nation);
            if (residents instanceof Collection<?> coll) {
                for (Object resident : coll) {
                    Object uuid = getUuidOfResident.invoke(resident);
                    if (uuid instanceof UUID u) {
                        out.add(u);
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return out;
    }

    private Object findNation(String nationIdLower) {
        if (!available || nationIdLower == null) {
            return null;
        }
        // TownyUniverse.getNation(name) есть в публичном API, резолвим по имени.
        try {
            Method getNation = townyUniverse.getClass().getMethod("getNation", String.class);
            return getNation.invoke(townyUniverse, nationIdLower);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
