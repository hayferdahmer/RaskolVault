// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Локализация messages.yml (RU в 1.0.x, структура готова к EN-блоку).
 * Плейсхолдеры {…} подставляются до покраски; & — цветовые коды.
 *
 * 1.0.1: собственный colorize() вместо deprecated ChatColor;
 * одноразовый warning в лог при отсутствующем ключе (ловит опечатки в messages.yml).
 */
public final class MessagesConfig {

    private static final String COLOR_CODES = "0123456789aAbBcCdDeEfFkKlLmMnNoOrR";

    private final Plugin plugin;
    private final Set<String> warnedMissing = new HashSet<>();
    private YamlConfiguration yaml;
    private String prefix;

    public MessagesConfig(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load(File file) {
        yaml = YamlConfiguration.loadConfiguration(file);
        warnedMissing.clear();
        prefix = color(yaml.getString("ru.prefix", "&8[&6RaskolVault&8]&r "));
    }

    public String prefix() {
        return prefix;
    }

    public String get(String path, Map<String, String> args) {
        String key = "ru." + path;
        if (!yaml.contains(key)) {
            if (warnedMissing.add(path)) {
                plugin.getLogger().warning("RaskolVault: в messages.yml нет ключа '" + path
                        + "' — добавь его, иначе игрок увидит техническую строку");
            }
            return path;
        }
        String raw = yaml.getString(key, path);
        if (args != null) {
            for (Map.Entry<String, String> entry : args.entrySet()) {
                raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return color(raw);
    }

    private String color(String raw) {
        return colorize(raw);
    }

    /** '&' + код → section-код. Без deprecated API, без внешних зависимостей. */
    private static String colorize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        char[] out = raw.toCharArray();
        for (int i = 0; i < out.length - 1; i++) {
            if (out[i] == '&' && COLOR_CODES.indexOf(out[i + 1]) >= 0) {
                out[i] = '§';
                out[i + 1] = Character.toLowerCase(out[i + 1]);
            }
        }
        return new String(out);
    }
}
