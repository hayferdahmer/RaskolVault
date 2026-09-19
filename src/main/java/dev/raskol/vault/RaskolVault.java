package dev.raskol.vault;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * RaskolVault — multi-currency economy layer over EssentialsX.
 * Stage 0: skeleton only. Proves build pipeline and lib/ dependency wiring.
 *
 * © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
 */
public final class RaskolVault extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("RaskolVault v" + getDescription().getVersion()
                + " skeleton started · Paper/MC " + getServer().getVersion());
        getLogger().info("Stage 0 OK: configs, ledger and hooks arrive in Stages 1-3.");
    }

    @Override
    public void onDisable() {
        getLogger().info("RaskolVault disabled.");
    }
}
