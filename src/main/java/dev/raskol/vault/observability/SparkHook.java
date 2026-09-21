// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Детект spark-профайлера (1.1.4): если spark установлен и включён —
 * метрики RaskolVault видны в /spark profiler. Рефлексия API spark
 * не используется: spark автоматически агрегирует timings всех плагинов.
 */
public final class SparkHook {

    private final Plugin plugin;
    private final boolean available;

    public SparkHook(Plugin plugin) {
        this.plugin = plugin;
        Plugin spark = Bukkit.getPluginManager().getPlugin("spark");
        this.available = spark != null && spark.isEnabled();
    }

    public boolean isAvailable() {
        return available;
    }

    public String describe() {
        return available ? "spark detected" : "spark not detected";
    }
}
