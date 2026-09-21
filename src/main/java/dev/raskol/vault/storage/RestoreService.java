// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

/**
 * Restore по флагу (1.1.5): если в папке плагина лежит restore.flag с именем
 * бэкапа — на старте (ДО инициализации леджера) копируем бэкап поверх ledger.sqlite,
 * удаляем -wal/-shm и удаляем флаг. Безопасно: выполняется до открытия пула.
 *
 * Использование: /rv admin restore <файл> создаёт restore.flag; рестарт сервера
 * применяет restore. Дрил описан в OPERATIONS.md.
 */
public final class RestoreService {

    private final Plugin plugin;

    public RestoreService(Plugin plugin) {
        this.plugin = plugin;
    }

    public File flagFile() {
        return new File(plugin.getDataFolder(), "restore.flag");
    }

    /** Запланировать restore на следующий старт. */
    public boolean schedule(String backupFileName) {
        File backup = new File(plugin.getDataFolder(), "backups/" + backupFileName);
        if (!backup.exists()) {
            return false;
        }
        try {
            Files.write(flagFile().toPath(), backupFileName.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("RaskolVault: не могу записать restore.flag: " + e.getMessage());
            return false;
        }
    }

    /** Применить restore, если флаг есть. Вызывать ДО SQLiteLedger.init(). */
    public boolean maybeRestore(File dbFile) {
        File flag = flagFile();
        if (!flag.exists()) {
            return false;
        }
        String name;
        try {
            name = Files.readString(flag.toPath(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            plugin.getLogger().severe("RaskolVault: restore.flag нечитаем, пропускаю restore: " + e.getMessage());
            return false;
        }
        File backup = new File(plugin.getDataFolder(), "backups/" + name);
        if (!backup.exists()) {
            plugin.getLogger().severe("RaskolVault: бэкап " + name + " не найден, restore отменён");
            flag.delete();
            return false;
        }
        try {
            File wal = new File(dbFile.getAbsolutePath() + "-wal");
            File shm = new File(dbFile.getAbsolutePath() + "-shm");
            Files.copy(backup.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (wal.exists()) wal.delete();
            if (shm.exists()) shm.delete();
            flag.delete();
            plugin.getLogger().warning("RaskolVault: RESTORE применён из backups/" + name
                    + " — база заменена до инициализации пула");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("RaskolVault: restore провален: " + e.getMessage());
            return false;
        }
    }
}
