// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.exchange.ConvertResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Публичный API RaskolVault (1.2.0) для плагинов-друзей:
 * RaskolMarket, RaskolCaravans, RaskolCharters, EconomyShopGUI, ChestShop и т.д.
 *
 * Получение:
 *   RaskolVault plugin = (RaskolVault) Bukkit.getPluginManager().getPlugin("RaskolVault");
 *   RaskolVaultAPI api = plugin.getAPI();
 *
 * Потокобезопасность определяется контрактом конкретного делегата. Операции
 * кошелька подтверждаются после фиксации в SQLite; методы внешних провайдеров
 * (например, EssentialsX) ограничены возможностями этих провайдеров.
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
        return plugin.getWallets().getBalance(player, currencyId.toUpperCase(Locale.ROOT));
    }

    /** Достаточно ли средств у игрока. */
    public boolean hasBalance(UUID player, String currencyId, double amount) {
        return amount >= 0.0D && getBalance(player, currencyId) >= amount - 1.0E-9D;
    }

    /** Списать amount с игрока. reason попадает в аудит-лог транзакций. */
    public boolean withdraw(UUID player, String currencyId, double amount, String reason) {
        if (player == null || currencyId == null || !(amount > 0.0D)) return false;
        return plugin.getWallets().withdraw(player, currencyId.toUpperCase(Locale.ROOT), amount,
                TransactionType.PAY, reason == null ? "api" : reason);
    }

    /** Начислить amount игроку. reason попадает в аудит-лог транзакций. */
    public boolean deposit(UUID player, String currencyId, double amount, String reason) {
        if (player == null || currencyId == null || !(amount > 0.0D)) return false;
        return plugin.getWallets().deposit(player, currencyId.toUpperCase(Locale.ROOT), amount,
                TransactionType.PAY, reason == null ? "api" : reason);
    }

    /**
     * Начисление с защитой от повтора. operationId должен оставаться одинаковым
     * при повторной отправке одного и того же запроса.
     * Для GLOBAL/EssentialsX гарантия идемпотентности не предоставляется внешним бэкендом.
     */
    public boolean depositOnce(UUID player, String currencyId, double amount,
                               String reason, String operationId) {
        if (player == null || currencyId == null || !(amount > 0.0D)
                || operationId == null || operationId.isBlank()) return false;
        return plugin.getWallets().depositOnce(player, currencyId.toUpperCase(Locale.ROOT),
                amount, TransactionType.PAY, reason == null ? "api" : reason, operationId);
    }

    /**
     * Списание с защитой от повтора. operationId должен оставаться одинаковым
     * при повторной отправке одного и того же запроса.
     * Для GLOBAL/EssentialsX гарантия идемпотентности не предоставляется внешним бэкендом.
     */
    public boolean withdrawOnce(UUID player, String currencyId, double amount,
                                String reason, String operationId) {
        if (player == null || currencyId == null || !(amount > 0.0D)
                || operationId == null || operationId.isBlank()) return false;
        return plugin.getWallets().withdrawOnce(player, currencyId.toUpperCase(Locale.ROOT),
                amount, TransactionType.PAY, reason == null ? "api" : reason, operationId);
    }

    /** Перевод между игроками. */
    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        if (from == null || to == null || currencyId == null || !(amount > 0.0D)) return false;
        return plugin.getWallets().transfer(from, to, currencyId.toUpperCase(Locale.ROOT),
                amount, reason == null ? "api" : reason);
    }

    /**
     * Перевод с защитой от повтора. operationId должен оставаться одинаковым
     * при повторной отправке одного и того же запроса.
     * Для GLOBAL/EssentialsX гарантия идемпотентности не предоставляется внешним бэкендом.
     */
    public boolean transferOnce(UUID from, UUID to, String currencyId, double amount,
                                String reason, String operationId) {
        if (from == null || to == null || currencyId == null || !(amount > 0.0D)
                || operationId == null || operationId.isBlank()) return false;
        return plugin.getWallets().transferOnce(from, to, currencyId.toUpperCase(Locale.ROOT),
                amount, reason == null ? "api" : reason, operationId);
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
        String from = fromCurrency.toUpperCase(Locale.ROOT);
        String to = toCurrency.toUpperCase(Locale.ROOT);
        
        // Проверка блокировки
        String blockReason = plugin.getConvertEngine().blockReason(player, from, to, amount);
        if (!blockReason.isEmpty()) {
            return ConvertResult.failure(blockReason);
        }
        
        // Выполнение конвертации
        var quoteOpt = plugin.getConvertEngine().execute(player, from, to, amount);
        if (quoteOpt.isEmpty()) {
            return ConvertResult.failure("conversion failed: course or balance changed");
        }
        
        return ConvertResult.fromQuote(quoteOpt.get());
    }

    /**
     * Идемпотентная конвертация. Для конвертаций с GLOBAL/EssentialsX действует
     * компенсационный сценарий, поэтому полная идемпотентность пока не гарантируется.
     */
    public ConvertResult convertOnce(UUID player, String fromCurrency, String toCurrency,
                                     double amount, String operationId) {
        if (player == null || fromCurrency == null || toCurrency == null || !(amount > 0.0D)
                || operationId == null || operationId.isBlank()) {
            return ConvertResult.failure("invalid arguments");
        }
        String from = fromCurrency.toUpperCase(Locale.ROOT);
        String to = toCurrency.toUpperCase(Locale.ROOT);

        String blockReason = plugin.getConvertEngine().blockReason(player, from, to, amount);
        if (!blockReason.isEmpty()) {
            return ConvertResult.failure(blockReason);
        }

        var quoteOpt = plugin.getConvertEngine().execute(player, from, to, amount, operationId);
        if (quoteOpt.isEmpty()) {
            return ConvertResult.failure("conversion failed: course or balance changed");
        }
        return ConvertResult.fromQuote(quoteOpt.get());
    }

    /** Актуальный курс from→to (без комиссий). 0 если пара эмбарго или нет резерва. */
    public double rate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) return 0.0D;
        return plugin.getConvertEngine().rate(fromCurrency.toUpperCase(Locale.ROOT), toCurrency.toUpperCase(Locale.ROOT));
    }

    /** Базовая комиссия обмена (сейчас 2% по умолчанию). */
    public double baseFeeRate() {
        return plugin.getConfig().getDouble("exchange.default-fee", 0.02D);
    }

    /** Налог нации-цели (если есть). 0 по умолчанию. */
    public double taxRate(String toCurrencyId) {
        if (toCurrencyId == null) return 0.0D;
        Currency cur = plugin.getCurrencies().get(toCurrencyId.toUpperCase(Locale.ROOT)).orElse(null);
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
        Currency cur = plugin.getCurrencies().get(currencyId.toUpperCase(Locale.ROOT)).orElse(null);
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
        return currencyId == null ? 0.0D : plugin.getReserveBank().supplyOf(currencyId.toUpperCase(Locale.ROOT));
    }

    /** Паритет нации (установленный королём). */
    public double parityOf(String nation) {
        return nation == null ? 0.0D : plugin.getReserveBank().parityOf(nation);
    }

    /** Максимум, который можно доминтить при текущем резерве/покрытии. */
    public double maxMint(String nation, String currencyId) {
        if (nation == null || currencyId == null) return 0.0D;
        return plugin.getReserveBank().maxMint(nation, currencyId.toUpperCase(Locale.ROOT));
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
        return currencyId != null && plugin.getCurrencies().get(currencyId.toUpperCase(Locale.ROOT)).isPresent();
    }

    /** Торгуется ли валюта (не заблокирована эмбарго/удалением нации). */
    public boolean isTradeable(String currencyId) {
        if (currencyId == null) return false;
        Currency c = plugin.getCurrencies().get(currencyId.toUpperCase(Locale.ROOT)).orElse(null);
        return c != null && c.tradeable();
    }

    /** Пара эмбарго (в rates.yml). */
    public boolean isEmbargo(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) return false;
        return plugin.getRates().isEmbargoed(fromCurrency.toUpperCase(Locale.ROOT), toCurrency.toUpperCase(Locale.ROOT));
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
