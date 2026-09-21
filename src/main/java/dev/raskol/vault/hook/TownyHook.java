// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Towny-хук (1.2.0): прямое API Towny через jitpack-зависимость.
 * Без рефлексии — типобезопасно, IDE-автокомплит, быстрое выполнение.
 * Король нации = мэр её столицы (Nation.getCapital().getMayor()).
 */
public final class TownyHook {

    private final Plugin plugin;
    private boolean available;

    public TownyHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin towny = Bukkit.getPluginManager().getPlugin("Towny");
        if (towny == null || !towny.isEnabled()) {
            available = false;
            plugin.getLogger().info("RaskolVault: Towny не найден, nation-хук отключён");
            return;
        }
        TownyAPI api = TownyAPI.getInstance();
        if (api == null) {
            available = false;
            plugin.getLogger().warning("RaskolVault: Towny API недоступен — nation-хук отключён");
            return;
        }
        available = true;
        plugin.getLogger().info("RaskolVault: Towny-хук активен (прямое API 0.103.x, король = мэр столицы)");
    }

    public boolean isAvailable() {
        return available;
    }

    /** Имя нации игрока или null. */
    public String nationOf(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }
        Resident resident = TownyAPI.getInstance().getResident(uuid);
        if (resident == null) {
            return null;
        }
        Town town = resident.getTownOrNull();
        if (town == null) {
            return null;
        }
        Nation nation = town.getNationOrNull();
        if (nation == null) {
            return null;
        }
        return nation.getName();
    }

    /** true, если игрок — король указанной нации (мэр её столицы). */
    public boolean isKing(UUID playerUuid, String nationName) {
        if (!available || playerUuid == null || nationName == null) {
            return false;
        }
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            return false;
        }
        Resident king = kingResident(nation);
        return king != null && playerUuid.equals(king.getUUID());
    }

    /** true, если игрок — король любой нации. */
    public boolean isKing(UUID playerUuid) {
        if (!available || playerUuid == null) {
            return false;
        }
        Resident resident = TownyAPI.getInstance().getResident(playerUuid);
        if (resident == null) {
            return false;
        }
        Town town = resident.getTownOrNull();
        if (town == null) {
            return false;
        }
        Nation nation = town.getNationOrNull();
        if (nation == null) {
            return false;
        }
        Resident king = kingResident(nation);
        return king != null && playerUuid.equals(king.getUUID());
    }

    /** UUID короля нации или null (если столица без мэра). */
    public UUID kingOf(String nationName) {
        if (!available || nationName == null) {
            return null;
        }
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            return null;
        }
        Resident king = kingResident(nation);
        return king == null ? null : king.getUUID();
    }

    /** Имя столицы нации или null. */
    public String capitalTownOf(String nationName) {
        if (!available || nationName == null) {
            return null;
        }
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            return null;
        }
        Town capital = nation.getCapital();
        return capital == null ? null : capital.getName();
    }

    /** Все UUID резидентов нации. */
    public List<UUID> residentsOf(String nationName) {
        List<UUID> out = new ArrayList<>();
        if (!available || nationName == null) {
            return out;
        }
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            return out;
        }
        for (Resident r : nation.getResidents()) {
            UUID u = r.getUUID();
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }

    /** Все имена наций на сервере. */
    public List<String> allNations() {
        List<String> out = new ArrayList<>();
        if (!available) {
            return out;
        }
        for (Nation n : TownyAPI.getInstance().getNations()) {
            out.add(n.getName());
        }
        return out;
    }

    /** Существует ли нация с таким именем. */
    public boolean nationExists(String nationName) {
        if (!available || nationName == null) {
            return false;
        }
        return TownyAPI.getInstance().getNation(nationName) != null;
    }

    /** Объект Nation или null (для внутренних нужд плагина). */
    public Nation getNation(String nationName) {
        if (!available || nationName == null) {
            return null;
        }
        return TownyAPI.getInstance().getNation(nationName);
    }

    /** Внутренний helper: король нации = мэр её столицы. */
    private Resident kingResident(Nation nation) {
        Town capital = nation.getCapital();
        if (capital == null) {
            return null;
        }
        return capital.getMayor();
    }
}
