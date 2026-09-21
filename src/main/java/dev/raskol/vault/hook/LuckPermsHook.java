// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * LuckPerms-хук (1.2.0): проверка прав и групп через публичное LuckPerms API.
 * Использует cached data — не делает запросы в БД, работает мгновенно.
 *
 * Для RaskolVault используется для:
 * - проверки `raskolvault.api.use` из плагинов-друзей
 * - контекстных проверок в будущем (нация/город/группа)
 * - отдачи в PAPI (%raskolvault_group%)
 */
public final class LuckPermsHook {

    private final Plugin plugin;
    private LuckPerms luckPerms;
    private boolean available;

    public LuckPermsHook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (lp == null || !lp.isEnabled()) {
            available = false;
            plugin.getLogger().info("RaskolVault: LuckPerms не найден, LP-хук отключён");
            return;
        }
        RegisteredServiceProvider<LuckPerms> rsp = Bukkit.getServicesManager()
                .getRegistration(LuckPerms.class);
        if (rsp == null || rsp.getProvider() == null) {
            available = false;
            plugin.getLogger().warning("RaskolVault: LuckPerms API не зарегистрирован в ServicesManager");
            return;
        }
        luckPerms = rsp.getProvider();
        available = true;
        plugin.getLogger().info("RaskolVault: LuckPerms-хук активен (API 5.4, cached data)");
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Проверка права через кэшированные данные LuckPerms.
     * Быстро (без запросов в БД).
     */
    public boolean hasPermission(UUID player, String permission) {
        if (!available || player == null || permission == null) return false;
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) {
            // fallback: если LP ещё не загрузил пользователя (первый вход), проверяем Op
            OfflinePlayer op = Bukkit.getOfflinePlayer(player);
            return op.isOp();
        }
        QueryOptions qo = luckPerms.getContextManager().getQueryOptions(user)
                .orElse(luckPerms.getContextManager().getStaticQueryOptions());
        return user.getCachedData().getPermissionData(qo).checkPermission(permission).asBoolean();
    }

    /** Первичная группа игрока (та, что стоит в поле primary_group). */
    public String primaryGroup(UUID player) {
        if (!available || player == null) return null;
        User user = luckPerms.getUserManager().getUser(player);
        return user == null ? null : user.getPrimaryGroup();
    }

    /** Находится ли игрок в указанной группе (прямое или наследованное членство). */
    public boolean isInGroup(UUID player, String group) {
        if (!available || player == null || group == null) return false;
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) return false;
        // наследованные группы: проверяем через inherited groups
        return user.getInheritedGroups(
                luckPerms.getContextManager().getQueryOptions(user)
                        .orElse(luckPerms.getContextManager().getStaticQueryOptions()))
                .stream()
                .anyMatch(g -> group.equalsIgnoreCase(g.getName()));
    }

    /** Все наследованные группы игрока (имена). */
    public java.util.Set<String> inheritedGroups(UUID player) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!available || player == null) return out;
        User user = luckPerms.getUserManager().getUser(player);
        if (user == null) return out;
        for (var g : user.getInheritedGroups(
                luckPerms.getContextManager().getQueryOptions(user)
                        .orElse(luckPerms.getContextManager().getStaticQueryOptions()))) {
            out.add(g.getName());
        }
        return out;
    }
}
