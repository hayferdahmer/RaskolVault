// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Ежедневный бекап SQLite-файла + ручной /rv admin backup.
 *
 * Бекап = простая копия файла с суффиксом времени в имени.
 * Ротация: удаляем файлы старше keepDays.
 *
 * Планирование: вычисляем задержку до следующего 04:00 (или заданного времени),
 * запускаем repeating-task на 24 часа.
 */
public final class BackupService {

    private static final DateTimeFormatter NAME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Plugin plugin;
    private final File sourceFile;
    private final File backupDir;
    private final LocalTime dailyTime;
    private final int keepDays;
    private BukkitTask task;

    public BackupService(Plugin plugin, File sourceFile, String dailyTime, int keepDays) {
        this.plugin = plugin;
        this.sourceFile = sourceFile;
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        this.dailyTime = parseTime(dailyTime, LocalTime.of(4, 0));
        this.keepDays = Math.max(1, keepDays);
    }

    public void start() {
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            plugin.getLogger().warning("RaskolVault: не могу создать папку backups: " + backupDir);
            return;
        }
        long delayTicks = computeDelayTicks();
        long periodTicks = 24L * 60L * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runBackup, delayTicks, periodTicks);
        plugin.getLogger().info("RaskolVault: ежедневный бекап SQLite запланирован на "
                + dailyTime + " (первый запуск через " + (delayTicks / 20L / 60L) + " мин)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Ручной бекап по команде /rv admin backup. */
    public File runBackupNow() {
        return runBackup();
    }

    private synchronized File runBackup() {
        if (!sourceFile.exists()) {
            plugin.getLogger().warning("RaskolVault: бекап пропущен, исходный файл отсутствует: " + sourceFile);
            return null;
        }
        String stamp = NAME_FMT.format(Instant.now().atZone(ZoneId.systemDefault()));
        File target = new File(backupDir, sourceFile.getName() + "." + stamp + ".bak");
        try {
            Files.copy(sourceFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("RaskolVault: бекап создан " + target.getName()
                    + " (" + humanSize(target.length()) + ")");
            // WAL-соседи тоже копируем, если есть (иначе бекап неполный)
            copySiblingIfExists(sourceFile.toPath().resolveSibling(sourceFile.getName() + "-wal"), target);
            copySiblingIfExists(sourceFile.toPath().resolveSibling(sourceFile.getName() + "-shm"), target);
        } catch (IOException e) {
            plugin.getLogger().severe("RaskolVault: бекап не удался: " + e.getMessage());
            return null;
        }
        pruneOldBackups();
        return target;
    }

    private void copySiblingIfExists(Path sibling, File baseTarget) {
        if (!Files.exists(sibling)) return;
        try {
            File target = new File(backupDir, sibling.getFileName().toString()
                    + "." + NAME_FMT.format(Instant.now().atZone(ZoneId.systemDefault())) + ".bak");
            Files.copy(sibling, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void pruneOldBackups() {
        File[] files = backupDir.listFiles();
        if (files == null) return;
        long cutoffMillis = System.currentTimeMillis() - (long) keepDays * 24L * 60L * 60L * 1000L;
        int removed = 0;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoffMillis && f.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("RaskolVault: ротация бекапов — удалено " + removed
                    + " файлов старше " + keepDays + " дн.");
        }
    }

    private long computeDelayTicks() {
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        LocalTime nowTime = LocalTime.now(zone);
        long seconds;
        if (nowTime.isBefore(dailyTime)) {
            seconds = ChronoUnit.SECONDS.between(nowTime, dailyTime);
        } else {
            seconds = ChronoUnit.SECONDS.between(nowTime, dailyTime) + 24L * 60L * 60L;
        }
        return Math.max(20L, seconds * 20L);
    }

    private LocalTime parseTime(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LocalTime.parse(raw);
        } catch (Exception e) {
            plugin.getLogger().warning("RaskolVault: некорректное daily-backup.time '" + raw
                    + "', используется " + fallback);
            return fallback;
        }
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
