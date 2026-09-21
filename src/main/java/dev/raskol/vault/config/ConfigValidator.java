// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Валидация конфига с клампами (1.1.5): на старте приводим опасные значения
 * к безопасным границам и логируем WARN-отчёт. Не сохраняем файл — клампы
 * действуют в памяти на эту сессию.
 */
public final class ConfigValidator {

    private final Plugin plugin;

    public ConfigValidator(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Валидирует и клампит конфиг; возвращает список предупреждений. */
    public List<String> validate(FileConfiguration cfg) {
        List<String> warnings = new ArrayList<>();

        warnings.addAll(clamp(cfg, "storage.sqlite.pool-size", 1, 16));
        warnings.addAll(clamp(cfg, "storage.sqlite.borrow-timeout-ms", 500, 60000));
        warnings.addAll(clamp(cfg, "storage.sqlite.writer-queue-cap", 100, 100000));
        warnings.addAll(clamp(cfg, "storage.sqlite.checkpoint-interval-minutes", 1, 1440));
        warnings.addAll(clamp(cfg, "storage.reconcile-interval-minutes", 0, 1440));

        warnings.addAll(clampDouble(cfg, "reserve.coverage-floor", 0.1D, 1.0D));
        warnings.addAll(clampDouble(cfg, "reserve.parity-min", 0.01D, 1.0D));
        warnings.addAll(clampDouble(cfg, "reserve.parity-max", 1.0D, 100.0D));
        warnings.addAll(clampDouble(cfg, "reserve.tax-max", 0.0D, 0.2D));
        warnings.addAll(clampDouble(cfg, "reserve.seigniorage", 0.0D, 0.5D));
        warnings.addAll(clampDouble(cfg, "reserve.withdraw-daily-share", 0.0D, 1.0D));
        warnings.addAll(clamp(cfg, "reserve.check-interval-minutes", 1, 10080));
        warnings.addAll(clamp(cfg, "reserve.bulletin.interval-minutes", 1, 10080));

        warnings.addAll(clampDouble(cfg, "exchange.default-fee", 0.0D, 0.5D));
        warnings.addAll(clamp(cfg, "exchange.confirm-timeout-seconds", 5, 600));

        warnings.addAll(clampDouble(cfg, "safety.rate-limit.capacity", 0.0D, 1000.0D));
        warnings.addAll(clampDouble(cfg, "safety.rate-limit.refill-per-second", 0.0D, 100.0D));
        warnings.addAll(clampDouble(cfg, "safety.pay-rate-limit.capacity", 0.0D, 1000.0D));
        warnings.addAll(clampDouble(cfg, "safety.pay-rate-limit.refill-per-second", 0.0D, 100.0D));

        // Логическая связность: parity-min <= parity-max
        double pmin = cfg.getDouble("reserve.parity-min", 0.5D);
        double pmax = cfg.getDouble("reserve.parity-max", 2.0D);
        if (pmin > pmax) {
            cfg.set("reserve.parity-max", pmin);
            warnings.add("reserve.parity-max < parity-min → parity-max приведён к " + pmin);
        }
        return warnings;
    }

    public void report(List<String> warnings) {
        if (warnings.isEmpty()) {
            plugin.getLogger().info("RaskolVault: конфиг валиден, клампы не потребовались");
            return;
        }
        plugin.getLogger().warning("RaskolVault: конфиг-валидатор внёс " + warnings.size() + " клампов:");
        for (String w : warnings) {
            plugin.getLogger().warning("  · " + w);
        }
    }

    private List<String> clamp(FileConfiguration cfg, String path, int min, int max) {
        List<String> out = new ArrayList<>();
        int v = cfg.getInt(path, min);
        if (v < min || v > max) {
            int clamped = Math.max(min, Math.min(max, v));
            cfg.set(path, clamped);
            out.add(path + ": " + v + " → " + clamped + " (границы " + min + ".." + max + ")");
        }
        return out;
    }

    private List<String> clampDouble(FileConfiguration cfg, String path, double min, double max) {
        List<String> out = new ArrayList<>();
        double v = cfg.getDouble(path, min);
        if (v < min || v > max) {
            double clamped = Math.max(min, Math.min(max, v));
            cfg.set(path, clamped);
            out.add(path + ": " + v + " → " + clamped + " (границы " + min + ".." + max + ")");
        }
        return out;
    }
}
