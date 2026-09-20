// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Опциональный хук к Spark profiler (https://spark.lucko.me).
 *
 * Работает через рефлексию: Spark НЕ добавлен в compile-dependencies,
 * поэтому плагин загружается и работает без Spark. Если Spark установлен
 * и включён — регистрируем таймеры в начале/конце критичных синхронных
 * операций (проекция баланса); если нет — все вызовы time() возвращают
 * no-op AutoCloseable без ошибок в лог.
 *
 * Имена таймеров в Spark UI:
 *   rv.deposit.projection
 *   rv.withdraw.projection
 *   rv.transfer.projection
 *   rv.convert.projection
 */
public final class SparkHook {

    private static final String SPARK_PLUGIN_NAME = "spark";
    private static final String PROVIDER_CLASS = "me.lucko.spark.api.SparkProvider";

    private final Plugin plugin;
    private final boolean available;
    private Object timingsFactory;
    private Method ofMethod;

    public SparkHook(Plugin plugin) {
        this.plugin = plugin;
        this.available = tryInit();
    }

    private boolean tryInit() {
        Plugin sparkPlugin = plugin.getServer().getPluginManager().getPlugin(SPARK_PLUGIN_NAME);
        if (sparkPlugin == null || !sparkPlugin.isEnabled()) {
            return false;
        }
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS);
            Object spark = providerClass.getMethod("get").invoke(null);
            if (spark == null) {
                return false;
            }
            this.timingsFactory = spark.getClass().getMethod("createTimings").invoke(spark);
            if (this.timingsFactory == null) {
                return false;
            }
            this.ofMethod = this.timingsFactory.getClass().getMethod("of", String.class);
            plugin.getLogger().info("RaskolVault: Spark-тайминги подключены (метки rv.*.projection)");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("RaskolVault: Spark найден, но API недоступен: "
                    + t.getClass().getSimpleName() + " — тайминги отключены");
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Запустить таймер с именем `rv.<name>`.
     * Возвращает AutoCloseable — используй в try-with-resources.
     * Если Spark недоступен — возвращается no-op таймер (пустое close).
     */
    public AutoCloseable time(String name) {
        if (!available) {
            return NoOpTimer.INSTANCE;
        }
        try {
            Object timer = ofMethod.invoke(timingsFactory, "rv." + name);
            if (timer == null) {
                return NoOpTimer.INSTANCE;
            }
            Object started = timer.getClass().getMethod("startTimer").invoke(timer);
            if (started instanceof AutoCloseable ac) {
                return ac;
            }
            return NoOpTimer.INSTANCE;
        } catch (Throwable t) {
            return NoOpTimer.INSTANCE;
        }
    }

    private enum NoOpTimer implements AutoCloseable {
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }
}
