// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Атомарное сохранение YAML: temp → backup → rename.
 * При неудаче финального перемещения старая версия восстанавливается из backup.
 */
public final class SafeStorage {

    private SafeStorage() {
    }

    public static void saveAtomic(YamlConfiguration yaml, File target, Plugin plugin) {
        saveAtomicChecked(yaml, target, plugin);
    }

    public static boolean saveAtomicChecked(YamlConfiguration yaml, File target, Plugin plugin) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().severe("SafeStorage: не могу создать папку " + parent.getAbsolutePath());
            return false;
        }

        File temp = new File(parent, target.getName() + ".tmp");
        File backup = new File(parent, target.getName() + ".bak");
        boolean movedOriginal = false;

        try {
            yaml.save(temp);
            if (target.exists()) {
                Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                movedOriginal = true;
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().severe("SafeStorage: не могу сохранить " + target.getName()
                    + ": " + exception.getMessage());

            if (movedOriginal && backup.exists() && !target.exists()) {
                try {
                    Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restoreError) {
                    plugin.getLogger().severe("SafeStorage: не удалось восстановить "
                            + target.getName() + " из backup: " + restoreError.getMessage());
                }
            }

            if (temp.exists() && !temp.delete()) {
                plugin.getLogger().warning("SafeStorage: не удался temp-файл " + temp.getAbsolutePath());
            }
            return false;
        }
    }
}
