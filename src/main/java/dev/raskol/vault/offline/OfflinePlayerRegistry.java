// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.offline;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Кэш имен игроков (online + offline) для таб-комплита в /rv pay, /rv admin give/take/set/audit (1.0.6).
 *
 * - При старте: прогревается асинхронно через Bukkit.getOfflinePlayers() (медленно, но вне main-thread).
 * - При PlayerJoinEvent/QuitEvent: обновляется in-memory.
 * - matchNames(prefix, limit): O(N) scan с startsWith-фильтром, возвращает актуальные имена.
 *
 * Хранит последний известный ник даже после выхода (offline-игроки нужны для /rv admin audit).
 */
public final class OfflinePlayerRegistry implements Listener {

    private final Plugin plugin;
    private final ConcurrentHashMap<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> uuidToName = new ConcurrentHashMap<>();

    public OfflinePlayerRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int loaded = 0;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                String name = op.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                nameToUuid.put(name.toLowerCase(), op.getUniqueId());
                uuidToName.put(op.getUniqueId(), name);
                loaded++;
            }
            plugin.getLogger().info("RaskolVault: offline-registry прогрет, игроков: " + loaded);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        UUID uuid = p.getUniqueId();
        nameToUuid.put(name.toLowerCase(), uuid);
        uuidToName.put(uuid, name);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        // Имя остаётся в кэше: offline-игроки нужны для таб-комплита и аудита
    }

    public List<String> matchNames(String prefix, int limit) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        int cap = Math.max(1, limit);
        return nameToUuid.entrySet().stream()
                .filter(e -> e.getKey().startsWith(lower))
                .map(e -> uuidToName.getOrDefault(e.getValue(), e.getKey()))
                .limit(cap)
                .collect(Collectors.toList());
    }

    public UUID resolveUuid(String name) {
        if (name == null) {
            return null;
        }
        return nameToUuid.get(name.toLowerCase());
    }

    public String resolveName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return uuidToName.get(uuid);
    }

    public int size() {
        return nameToUuid.size();
    }
}
