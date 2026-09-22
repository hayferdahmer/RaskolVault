// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.trade;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.storage.SafeStorage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Торговая политика нации (1.2.3-b): whitelist/blacklist валют для оборота.
 * Хранение: data/nation-trade-policy.yml.
 * mode: none (все разрешены) | whitelist (только из списка) | blacklist (все кроме списка).
 */
public final class TradePolicyService {

    public enum Mode { NONE, WHITELIST, BLACKLIST }

    public record Policy(Mode mode, List<String> list) {
        public boolean allows(String currencyId) {
            String cur = currencyId.toUpperCase(Locale.ROOT);
            return switch (mode) {
                case NONE -> true;
                case WHITELIST -> list.contains(cur);
                case BLACKLIST -> !list.contains(cur);
            };
        }
    }

    private final RaskolVault plugin;
    private final File file;
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();

    public TradePolicyService(RaskolVault plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/nation-trade-policy.yml");
        load();
    }

    public void load() {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        if (!file.exists()) { save(); return; }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("nations");
        if (root == null) return;
        for (String nation : root.getKeys(false)) {
            ConfigurationSection n = root.getConfigurationSection(nation);
            if (n == null) continue;
            Mode mode;
            try { mode = Mode.valueOf(n.getString("mode", "none").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { mode = Mode.NONE; }
            List<String> list = new ArrayList<>();
            for (String s : n.getStringList("list")) list.add(s.toUpperCase(Locale.ROOT));
            policies.put(nation.toLowerCase(Locale.ROOT), new Policy(mode, list));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("nations");
        for (Map.Entry<String, Policy> e : policies.entrySet()) {
            ConfigurationSection n = root.createSection(e.getKey());
            n.set("mode", e.getValue().mode().name().toLowerCase(Locale.ROOT));
            n.set("list", e.getValue().list());
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    public Policy policyOf(String nation) {
        if (nation == null) return new Policy(Mode.NONE, List.of());
        return policies.getOrDefault(nation.toLowerCase(Locale.ROOT), new Policy(Mode.NONE, List.of()));
    }

    public boolean isAllowed(String nation, String currencyId) {
        return policyOf(nation).allows(currencyId);
    }

    public void setMode(String nation, Mode mode) {
        Policy cur = policyOf(nation);
        policies.put(nation.toLowerCase(Locale.ROOT), new Policy(mode, cur.list()));
        save();
    }

    public void setList(String nation, List<String> list) {
        Policy cur = policyOf(nation);
        List<String> up = new ArrayList<>();
        for (String s : list) up.add(s.toUpperCase(Locale.ROOT));
        policies.put(nation.toLowerCase(Locale.ROOT), new Policy(cur.mode(), up));
        save();
    }
}
