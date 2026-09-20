// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Атомарный сейв YAML по образцу RaskolCore (RaskolIdService.save):
 * temp → backup(.bak) → rename. Половинчатых файлов не бывает даже при краше
 * контейнера посреди записи; предыдущая версия всегда жива в .bak.
 */
public final class SafeStorage {

    private SafeStorage() {
    }

    public static void saveAtomic(YamlConfiguration yaml, File target, Plugin plugin) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().severe("SafeStorage: не могу создать папку " + parent.getAbsolutePath());
            return;
        }
        File temp = new File(parent, target.getName() + ".tmp");
        File backup = new File(parent, target.getName() + ".bak");
        try {
            yaml.save(temp);
            if (target.exists()) {
                Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().severe("SafeStorage: не могу сохранить " + target.getName() + ": " + e.getMessage());
            if (temp.exists() && !temp.delete()) {
                plugin.getLogger().warning("SafeStorage: не удался temp-файл " + temp.getAbsolutePath());
            }
        }
    }
}
