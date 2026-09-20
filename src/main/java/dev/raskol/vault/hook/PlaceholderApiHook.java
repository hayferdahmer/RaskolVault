// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.hook;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.util.Formatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PlaceholderAPI-экспаншн RaskolVault.
 *
 * Плейсхолдеры:
 *   %raskolvault_balance_<ID>%      — баланс игрока (с символом)
 *   %raskolvault_balance_raw_<ID>%  — баланс без символа (для вычислений)
 *   %raskolvault_nation%            — ID текущей нации игрока или ""
 *   %raskolvault_treasury_<ID>%     — баланс казны национальной валюты
 *   %raskolvault_symbol_<ID>%       — символ валюты
 */
public final class PlaceholderApiHook extends PlaceholderExpansion {

    private final RaskolVault plugin;

    public PlaceholderApiHook(RaskolVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "raskolvault";
    }

    @Override
    public @NotNull String getAuthor() {
        return "hayferdahmer";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        String lower = params.toLowerCase(Locale.ROOT);

        if (lower.equals("nation")) {
            String nation = plugin.getTownyHook().isAvailable()
                    ? plugin.getTownyHook().nationOf(player.getUniqueId()) : null;
            return nation == null ? "" : nation;
        }

        if (lower.startsWith("balance_")) {
            String rest = lower.substring("balance_".length());
            boolean raw = rest.startsWith("raw_");
            String currencyId = (raw ? rest.substring(4) : rest).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
            if (currency == null) {
                return "?";
            }
            double amount = plugin.getWallets().getBalance(player.getUniqueId(), currency.id());
            return raw
                    ? Formatter.amount(amount, currency.decimals())
                    : Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        }

        if (lower.startsWith("treasury_")) {
            String currencyId = lower.substring("treasury_".length()).toUpperCase(Locale.ROOT);
            Currency currency = plugin.getCurrencies().get(currencyId).orElse(null);
            if (currency == null || currency.type() != CurrencyType.NATIONAL) {
                return "?";
            }
            double amount = plugin.getTreasury().balance(currency.nationId(), currency.id());
            return Formatter.withSymbol(amount, currency.decimals(), currency.symbol());
        }

        if (lower.startsWith("symbol_")) {
            String currencyId = lower.substring("symbol_".length()).toUpperCase(Locale.ROOT);
            return plugin.getCurrencies().get(currencyId)
                    .map(Currency::symbol).orElse("?");
        }

        return null;
    }
}
