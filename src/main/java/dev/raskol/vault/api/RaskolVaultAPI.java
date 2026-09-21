// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.exchange.ConvertResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Публичный API RaskolVault (1.2.0) для плагинов-друзей:
 * RaskolMarket, RaskolCaravans, RaskolCharters, EconomyShopGUI, ChestShop и т.д.
 *
 * Получение:
 *   RaskolVault plugin = (RaskolVault) Bukkit.getPluginManager().getPlugin("RaskolVault");
 *   RaskolVaultAPI api = plugin.getAPI();
 *
 * Все методы потокобезопасны (делегаты в WalletService/ConvertEngine).
 * Валюта идентифицируется по ID в uppercase (GLD, RAS, OZR, ...).
 */
public final class RaskolVaultAPI {

    private final RaskolVault plugin;

    public RaskolVaultAPI(RaskolVault plugin) {
        this.plugin = plugin;
    }

    // ============ Балансы ============

    /** Баланс игрока в указанной валюте. 0.0 если игрока/валюты нет. */
    public double getBalance(UUID player, String currencyId) {
        if (player == null || currencyId == null) return 0.0D;
        return plugin.getWallets().getBalance(player, currencyId.toUpperCase());
    }

    /** Достаточно ли средств у игрока. */
    public boolean hasBalance(UUID player, String currencyId, double amount) {
        return amount >= 0.0D && getBalance(player, currencyId) >= amount - 1.0E-9D;
    }

    /** Списать amount с игрока. reason попадает в аудит-лог транзакций. */
    public boolean withdraw(UUID player, String currencyId, double amount, String reason) {
        if (player == null || currencyId == null || !(amount > 0.0D)) return false;
        return plugin.getWallets().withdraw(player, currencyId.toUpperCase(), amount,
                TransactionType.PAY, reason == null ? "api" : reason);
    }

    /** Начислить amount игроку. reason попадает в аудит-лог транзакций. */
    public boolean deposit(UUID player, String currencyId, double amount, String reason) {
        if (player == null || currencyId == null || !(amount > 0.0D)) return false;
        return plugin.getWallets().deposit(player, currencyId.toUpperCase(), amount,
                TransactionType.PAY, reason == null ? "api" : reason);
    }

    /** Перевод между игроками. */
    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        if (from == null || to == null || currencyId == null || !(amount > 0.0D)) return false;
        if (!withdraw(from, currencyId, amount, reason)) return false;
        if (!deposit(to, currencyId, amount, reason)) {
            // откат, если депозит не прошёл (теоретически не должно случиться)
            deposit(from, currencyId, amount, reason + ":rollback");
            return false;
        }
        return true;
    }

    // ============ Конвертация ============

    /**
     * Конвертировать amount из fromCurrency в toCurrency.
     * Возвращает ConvertResult (успех, выход, комиссии, курс).
     * Использует ту же логику, что и /rv convert.
     */
    public ConvertResult convert(UUID player, String fromCurrency, String toCurrency, double amount) {
        if (player == null || fromCurrency == null || toCurrency == null || !(amount > 0.0D)) {
            return ConvertResult.failure("invalid arguments");
        }
        return plugin.getConvertEngine().convert(player, fromCurrency.toUpperCase(),
                toCurrency.toUpperCase(), amount, "api");
    }

    /** Актуальный курс from→to (без комиссий). 0 если пара эмбарго или нет резерва. */
    public double rate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) return 0.0D;
        return plugin.getConvertEngine().rate(fromCurrency.toUpperCase(), toCurrency.toUpperCase());
    }

    /** Базовая комиссия обмена (сейчас 2% по умолчанию). */
    public double baseFeeRate() {
        return plugin.getConfig().getDouble("exchange.default-fee", 0.02D);
    }

    /** Налог нации-цели (если есть). 0 по умолчанию. */
    public double taxRate(String toCurrencyId) {
        if (toCurrencyId == null) return 0.0D;
        Currency cur = plugin.getCurrencies().get(toCurrencyId.toUpperCase()).orElse(null);
        if (cur == null || cur.nationId() == null) return 0.0D;
        return plugin.getReserveBank().taxOf(cur.nationId());
    }

    // ============ Нации (делегаты TownyHook) ============

    /** Имя нации игрока или null. */
    public String nationOf(UUID player) {
        return plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().nationOf(player) : null;
    }

    /** Является ли игрок королём любой нации. */
    public boolean isKing(UUID player) {
        return plugin.getTownyHook().isAvailable() && plugin.getTownyHook().isKing(player);
    }

    /** Является ли игрок королём конкретной нации. */
    public boolean isKing(UUID player, String nationName) {
        return plugin.getTownyHook().isAvailable() && plugin.getTownyHook().isKing(player, nationName);
    }

    /** UUID короля нации или null. */
    public UUID kingOf(String nationName) {
        return plugin.getTownyHook().isAvailable() ? plugin.getTownyHook().kingOf(nationName) : null;
    }

    /** UUID всех резидентов нации. */
    public List<UUID> residentsOf(String nationName) {
        return plugin.getTownyHook().isAvailable()
                ? plugin.getTownyHook().residentsOf(nationName)
                : new ArrayList<>();
    }

    /** Список всех имён наций на сервере. */
    public List<String> allNations() {
        return plugin.getTownyHook().isAvailable()
                ? plugin.getTownyHook().allNations()
                : new ArrayList<>();
    }

    // ============ Экономика нации (делегаты ReserveBank) ============

    /** Резерв нации в GLD. */
    public double reserveOf(String nation) {
        return nation == null ? 0.0D : plugin.getReserveBank().reserveOf(nation);
    }

    /** Цена валюты в GLD. */
    public double priceOf(String currencyId) {
        if (currencyId == null) return 0.0D;
        Currency cur = plugin.getCurrencies().get(currencyId.toUpperCase()).orElse(null);
        return cur == null ? 0.0D : plugin.getReserveBank().priceOf(cur);
    }

    /** Покрытие нации (0..1, где 1 = 100%). */
    public double coverageOf(String nation) {
        if (nation == null) return 0.0D;
        Currency national = nationalCurrencyOf(nation);
        if (national == null) return 0.0D;
        return plugin.getReserveBank().coverageOf(nation, national.id());
    }

    /** Общая эмиссия валюты (сумма всех балансов игроков). */
    public double supplyOf(String currencyId) {
        return currencyId == null ? 0.0D : plugin.getReserveBank().supplyOf(currencyId.toUpperCase());
    }

    /** Паритет нации (установленный королём). */
    public double parityOf(String nation) {
        return nation == null ? 0.0D : plugin.getReserveBank().parityOf(nation);
    }

    /** Максимум, который можно доминтить при текущем резерве/покрытии. */
    public double maxMint(String nation, String currencyId) {
        if (nation == null || currencyId == null) return 0.0D;
        return plugin.getReserveBank().maxMint(nation, currencyId.toUpperCase());
    }

    // ============ Валюты ============

    /** Все ID валют. */
    public List<String> currencies() {
        List<String> out = new ArrayList<>();
        for (Currency c : plugin.getCurrencies().all()) {
            out.add(c.id());
        }
        return out;
    }

    /** Существует ли валюта. */
    public boolean currencyExists(String currencyId) {
        return currencyId != null && plugin.getCurrencies().get(currencyId.toUpperCase()).isPresent();
    }

    /** Торгуется ли валюта (не заблокирована эмбарго/удалением нации). */
    public boolean isTradeable(String currencyId) {
        if (currencyId == null) return false;
        Currency c = plugin.getCurrencies().get(currencyId.toUpperCase()).orElse(null);
        return c != null && c.tradeable();
    }

    /** Пара эмбарго (в rates.yml). */
    public boolean isEmbargo(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) return false;
        return plugin.getRates().isEmbargo(fromCurrency.toUpperCase(), toCurrency.toUpperCase());
    }

    // ============ Права (делегат LuckPermsHook) ============

    /** Есть ли у игрока указанное право (через LuckPerms cached data). */
    public boolean hasPermission(UUID player, String permission) {
        return plugin.getLuckPermsHook().isAvailable()
                && plugin.getLuckPermsHook().hasPermission(player, permission);
    }

    /** Первичная группа игрока в LuckPerms или null. */
    public String primaryGroup(UUID player) {
        return plugin.getLuckPermsHook().isAvailable()
                ? plugin.getLuckPermsHook().primaryGroup(player)
                : null;
    }

    /** Находится ли игрок в группе LuckPerms. */
    public boolean isInGroup(UUID player, String group) {
        return plugin.getLuckPermsHook().isAvailable()
                && plugin.getLuckPermsHook().isInGroup(player, group);
    }

    // ============ Внутреннее ============

    private Currency nationalCurrencyOf(String nation) {
        for (Currency c : plugin.getCurrencies().all()) {
            if (c.type() == dev.raskol.vault.api.currency.CurrencyType.NATIONAL
                    && nation.equalsIgnoreCase(c.nationId())) {
                return c;
            }
        }
        return null;
    }
}
