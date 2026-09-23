// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.storage.SafeStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Репутация продавцов аукциона (1.2.5-a.2).
 * successCount: количество успешных продаж (без отмен/истечений).
 * Хранение: data/auction-reputation.yml.
 */
public final class AuctionReputation {

    private final RaskolVault plugin;
    private final File file;
    private final Map<UUID, Integer> successCounts = new ConcurrentHashMap<>();

    public AuctionReputation(RaskolVault plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/auction-reputation.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("reputation");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                int count = root.getInt(key, 0);
                successCounts.put(uuid, count);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("reputation");
        for (Map.Entry<UUID, Integer> e : successCounts.entrySet()) {
            root.set(e.getKey().toString(), e.getValue());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public int getSuccessCount(UUID seller) {
        return successCounts.getOrDefault(seller, 0);
    }

    public void incrementSuccess(UUID seller) {
        successCounts.merge(seller, 1, Integer::sum);
        save();
    }
}
