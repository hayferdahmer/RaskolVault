// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.storage.SafeStorage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Баны и чёрный список предметов аукциона (1.2.5-a.3).
 * - bannedPlayers: нельзя выставлять лоты
 * - blacklistMaterials: нельзя выставлять предметы этих типов
 * Хранение: data/auction-bans.yml. Чёрный список читается из конфига.
 */
public final class AuctionBanService {

    public record BanEntry(UUID player, String playerName, String reason, long bannedAt, String bannedBy) {}

    private final RaskolVault plugin;
    private final File file;
    private final Set<UUID> bannedPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, BanEntry> banDetails = new ConcurrentHashMap<>();

    public AuctionBanService(RaskolVault plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/auction-bans.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bans");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                UUID uuid = UUID.fromString(key);
                bannedPlayers.add(uuid);
                banDetails.put(uuid, new BanEntry(
                        uuid,
                        s.getString("name", ""),
                        s.getString("reason", ""),
                        s.getLong("at", 0L),
                        s.getString("by", "")));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("bans");
        for (BanEntry b : banDetails.values()) {
            ConfigurationSection s = root.createSection(b.player().toString());
            s.set("name", b.playerName());
            s.set("reason", b.reason());
            s.set("at", b.bannedAt());
            s.set("by", b.bannedBy());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public boolean isBanned(UUID player) { return bannedPlayers.contains(player); }
    public BanEntry banInfo(UUID player) { return banDetails.get(player); }
    public int bannedCount() { return bannedPlayers.size(); }
    public Set<BanEntry> allBans() { return new HashSet<>(banDetails.values()); }

    public void ban(UUID player, String playerName, String reason, String bannedBy) {
        bannedPlayers.add(player);
        banDetails.put(player, new BanEntry(player, playerName, reason, System.currentTimeMillis(), bannedBy));
        save();
    }

    public void unban(UUID player) {
        bannedPlayers.remove(player);
        banDetails.remove(player);
        save();
    }

    /** Чёрный список материалов из конфига (auction.blacklist-items). */
    public boolean isBlacklisted(Material material) {
        if (material == null) return false;
        var list = plugin.getConfig().getStringList("auction.blacklist-items");
        if (list == null || list.isEmpty()) return false;
        String name = material.name();
        for (String entry : list) {
            if (entry == null || entry.isEmpty()) continue;
            if (name.equalsIgnoreCase(entry.trim())) return true;
        }
        return false;
    }

    public java.util.List<String> blacklistConfig() {
        var list = plugin.getConfig().getStringList("auction.blacklist-items");
        return list == null ? java.util.List.of() : list;
    }
}
