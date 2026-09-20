// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;

/**
 * Локализация messages.yml (RU в 1.0.0, структура готова к EN-блоку).
 * Плейсхолдеры вида {amount} подставляются до покраски.
 */
public final class MessagesConfig {

    private final Plugin plugin;
    private YamlConfiguration yaml;
    private String prefix;

    public MessagesConfig(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(File file) {
        yaml = YamlConfiguration.loadConfiguration(file);
        prefix = color(yaml.getString("ru.prefix", "&8[&6RaskolVault&8]&r "));
    }

    public String prefix() {
        return prefix;
    }

    public String get(String path, Map<String, String> args) {
        String raw = yaml.getString("ru." + path, path);
        if (args != null) {
            for (Map.Entry<String, String> entry : args.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return color(raw);
    }

    private String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
