// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Банк нации (1.2.6 part2): реалистичные кредиты двух типов.
 *  - UNSECURED (NONE): лимит = unsecuredBase + max(0,score)*unsecuredPerScore; ставка = loanRate + unsecuredPremium + creditBonus.
 *  - SECURED (CURRENCY/ITEM): залог >= amount × collateralRatio (1.2); ставка = loanRate + creditBonus.
 * Деньги выдаются ИЗ ПУЛА ВКЛАДОВ (фракционное резервирование) + лимит maxLoans = reserve × multiplier.
 * Вклады: простой процент, cap 200% тела; снятие demand с проверкой ликвидности pool.
 */
public final class BankService implements Listener {

    public record BankParams(
            double demandRate, double r7, double r30, double r90,
            double loanRate, double collateralRatio, double reserveMultiplier, double earlyPenaltyRate,
            double unsecuredBase, double unsecuredPerScore, double unsecuredPremium
    ) {}

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final SQLiteLedger ledger;
    private final ReserveBank bank;
    private final File accountsFile;
    private final File loansFile;
    private final File poolsFile;
    private final File creditFile;
    private final File pendingFile;
    private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, BankLoan> loans = new ConcurrentHashMap<>();
    private final Map<String, Double> pools = new ConcurrentHashMap<>();
    private final Map<String, Double> interestReserves = new ConcurrentHashMap<>();
    private final Map<String, SQLiteLedger.BankAssetLotRow> assetLots = new ConcurrentHashMap<>();
    private final Map<String, SQLiteLedger.BankInterestPayableRow> interestPayables = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> repaidCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> defaultedCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<SQLiteLedger.BankPendingRow>> pendingReturns = new ConcurrentHashMap<>();
    private final NamespacedKey collateralReturnKey;
    private final NamespacedKey collateralIntentKey;

    public BankService(RaskolVault plugin, WalletService wallets, ReserveBank bank) {
        this.plugin = plugin;
        this.collateralReturnKey = new NamespacedKey(plugin, "collateral_return_id");
        this.collateralIntentKey = new NamespacedKey(plugin, "collateral_intent_id");
        this.wallets = wallets;
        this.ledger = plugin.getLedger();
        this.bank = bank;
        this.accountsFile = new File(plugin.getDataFolder(), "data/bank-accounts.yml");
        this.loansFile = new File(plugin.getDataFolder(), "data/bank-loans.yml");
        this.poolsFile = new File(plugin.getDataFolder(), "data/bank-pools.yml");
        this.creditFile = new File(plugin.getDataFolder(), "data/bank-credit.yml");
        this.pendingFile = new File(plugin.getDataFolder(), "data/bank-pending.yml");
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private static int termDaysOf(BankAccount.Term term) {
        return switch (term) { case DEMAND -> 0; case TERM_7 -> 7; case TERM_30 -> 30; case TERM_90 -> 90; };
    }

    public BankParams params(String nation) {
        String n = nation == null ? "" : nation.toLowerCase();
        double def = plugin.getConfig().getDouble("bank.defaults.demand-rate", 0.01D);
        double d7 = plugin.getConfig().getDouble("bank.defaults.rate-7", 0.02D);
        double d30 = plugin.getConfig().getDouble("bank.defaults.rate-30", 0.035D);
        double d90 = plugin.getConfig().getDouble("bank.defaults.rate-90", 0.05D);
        double loan = plugin.getConfig().getDouble("bank.defaults.loan-rate", 0.10D);
        double coll = plugin.getConfig().getDouble("bank.defaults.collateral-ratio", 1.2D);
        double mult = plugin.getConfig().getDouble("bank.defaults.reserve-multiplier", 3.0D);
        double early = plugin.getConfig().getDouble("bank.defaults.early-penalty", 0.02D);
        double uBase = plugin.getConfig().getDouble("bank.defaults.unsecured-base", 500.0D);
        double uPer = plugin.getConfig().getDouble("bank.defaults.unsecured-per-score", 100.0D);
        double uPrem = plugin.getConfig().getDouble("bank.defaults.unsecured-premium", 0.05D);
        def = plugin.getConfig().getDouble("bank.nations." + n + ".demand-rate", def);
        d7 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-7", d7);
        d30 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-30", d30);
        d90 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-90", d90);
        loan = plugin.getConfig().getDouble("bank.nations." + n + ".loan-rate", loan);
        coll = plugin.getConfig().getDouble("bank.nations." + n + ".collateral-ratio", coll);
        mult = plugin.getConfig().getDouble("bank.nations." + n + ".reserve-multiplier", mult);
        early = plugin.getConfig().getDouble("bank.nations." + n + ".early-penalty", early);
        uBase = plugin.getConfig().getDouble("bank.nations." + n + ".unsecured-base", uBase);
        uPer = plugin.getConfig().getDouble("bank.nations." + n + ".unsecured-per-score", uPer);
        uPrem = plugin.getConfig().getDouble("bank.nations." + n + ".unsecured-premium", uPrem);
        return new BankParams(def, d7, d30, d90, loan, coll, mult, early, uBase, uPer, uPrem);
    }

    public void setParam(String nation, String key, double value) {
        if (nation == null || nation.isBlank() || key == null || key.isBlank()
                || !Double.isFinite(value)) return;
        plugin.getConfig().set("bank.nations." + nation.toLowerCase(java.util.Locale.ROOT) + "." + key, value);
        plugin.saveConfig();
    }

    private String poolKey(String nation, String currency) { return nation.toLowerCase() + "|" + currency; }
    public double pool(String nation, String currency) { return pools.getOrDefault(poolKey(nation, currency), 0.0D); }
    public double interestReserve(String nation, String currency) { return interestReserves.getOrDefault(poolKey(nation, currency), 0.0D); }

    public double maxLoans(String nation) { return bank.reserveOf(nation) * params(nation).reserveMultiplier(); }

    private boolean hasUnresolvedRecovery(UUID borrower) {
        if (borrower == null) return false;
        return loans.values().stream().anyMatch(loan -> loan.borrower().equals(borrower)
                && loan.hasOutstandingBalance()
                && loan.status() != BankLoan.Status.ACTIVE);
    }

    public double totalOutstandingLoans(String nation) {
        long now = System.currentTimeMillis();
        double sum = 0;
        for (BankLoan l : loans.values())
            if (l.nation().equalsIgnoreCase(nation) && l.hasOutstandingBalance())
                sum += l.outstanding(now) * priceInGld(l.currencyId());
        return sum;
    }
    private double priceInGld(String currencyId) {
        Currency c = plugin.getCurrencies().get(currencyId).orElse(null);
        if (c == null) return 1.0D;
        double p = bank.priceOf(c);
        return p > 0 ? p : 1.0D;
    }
    public double totalDeposits(String nation) {
        double sum = 0;
        for (BankAccount a : accounts.values())
            if (a.nation().equalsIgnoreCase(nation) && a.isActive()) sum += a.principal();
        return sum;
    }

    public int creditScore(UUID player) {
        return repaidCount.getOrDefault(player, 0) - 2 * defaultedCount.getOrDefault(player, 0);
    }
    private double creditRateBonus(UUID player) {
        int score = creditScore(player);
        return -Math.min(0.02D, score * 0.002D) + Math.max(0.0D, -score * 0.005D);
    }
    /** Лимит необеспеченного кредита. */
    public double unsecuredMax(UUID player, String nation) {
        BankParams p = params(nation);
        return p.unsecuredBase() + Math.max(0, creditScore(player)) * p.unsecuredPerScore();
    }

    // ---------- вклады ----------
    public synchronized String openDeposit(UUID owner, String ownerName, String nation, String currency,
                              double amount, BankAccount.Term term) {
        if (owner == null || ownerName == null || nation == null || nation.isBlank()
                || currency == null || currency.isBlank() || term == null
                || !Double.isFinite(amount) || !(amount > 0.0D)) return "некорректные параметры вклада";
        if (!wallets.has(owner, currency, amount)) return "недостаточно средств";
        BankParams p = params(nation);
        double rate = switch (term) { case DEMAND -> p.demandRate(); case TERM_7 -> p.r7(); case TERM_30 -> p.r30(); case TERM_90 -> p.r90(); };
        long now = System.currentTimeMillis();
        long matures = (term == BankAccount.Term.DEMAND) ? 0L : now + termDaysOf(term) * 86_400_000L;
        String id = UUID.randomUUID().toString();
        BankAccount account = new BankAccount(id, owner, ownerName, nation, currency,
                amount, term, rate, now, matures);
        String key = poolKey(nation, currency);
        double oldPool = pools.getOrDefault(key, 0.0D);
        accounts.put(id, account);
        pools.put(key, oldPool + amount);
        Transaction transaction = new Transaction(-1L, now, owner, null, currency, amount,
                TransactionType.PAY, "bank:deposit:open:" + id, null);
        boolean committed = wallets.applyBatchOnce("bank:deposit:open:" + id,
                List.of(new WalletService.WalletDelta(owner, currency, -amount)),
                List.of(), List.of(), bankSnapshot(), List.of(transaction));
        if (!committed) {
            accounts.remove(id);
            if (oldPool == 0.0D) pools.remove(key); else pools.put(key, oldPool);
            return "не удалось открыть вклад: транзакция не подтверждена";
        }
        return null;
    }

    public synchronized String closeDeposit(UUID owner, String accountId) {
        if (owner == null || accountId == null || accountId.isBlank()) return "некорректный вклад";
        BankAccount a = accounts.get(accountId);
        if (a == null || !a.owner().equals(owner)) return "вклад не найден";
        if (!a.isActive()) return "вклад уже закрыт";
        long now = System.currentTimeMillis();
        BankAccount staged = new BankAccount(a.id(), a.owner(), a.ownerName(), a.nation(),
                a.currencyId(), a.principal(), a.term(), a.rateAnnual(), a.openedAt(), a.maturesAt());
        staged.setAccrued(a.accrued());
        staged.setLastAccrualAt(a.lastAccrualAt());
        staged.accrue(now, params(a.nation()).demandRate());

        boolean interestEligible = staged.isDemand() || staged.isMatured(now);
        double payout = 0.0D;
        double interest = 0.0D;
        if (interestEligible) {
            if (pool(a.nation(), a.currencyId()) < staged.principal()) {
                return "недостаточно ликвидности в банке — обратитесь к королю нации";
            }
        } else {
            double penalty = staged.earlyPenalty(params(a.nation()).earlyPenaltyRate());
            payout = Math.max(0.0D, staged.principal() - penalty);
            if (pool(a.nation(), a.currencyId()) < staged.principal()) {
                return "недостаточно ликвидности в банке — обратитесь к королю нации";
            }
        }

        String key = poolKey(staged.nation(), staged.currencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterestReserve = interestReserves.getOrDefault(key, 0.0D);
        Map<String, SQLiteLedger.BankInterestPayableRow> oldPayables = new HashMap<>(interestPayables);
        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        List<Transaction> transactions = new ArrayList<>();

        if (interestEligible) {
            // Старые требования вкладчиков обслуживаются раньше нового закрываемого вклада.
            settleInterestPayables(staged.nation(), staged.currencyId(), now, walletChanges, transactions);
            interest = Math.min(staged.accrued(), Math.max(0.0D, interestReserve(staged.nation(), staged.currencyId())));
            payout = staged.principal() + interest;
            double unpaid = Math.max(0.0D, staged.accrued() - interest);
            if (unpaid > 1.0E-8D) {
                String payableId = "deposit-interest:" + accountId;
                interestPayables.put(payableId, new SQLiteLedger.BankInterestPayableRow(
                        payableId, owner, staged.ownerName(), staged.nation(), staged.currencyId(), unpaid, now));
            }
        }

        pools.put(key, Math.max(0.0D, oldPool - staged.principal()));
        interestReserves.put(key, Math.max(0.0D,
                interestReserve(staged.nation(), staged.currencyId()) - interest));
        staged.payOutAccrued();
        staged.close();
        accounts.put(accountId, staged);

        if (payout > 0.0D) {
            walletChanges.add(new WalletService.WalletDelta(owner, staged.currencyId(), payout));
            transactions.add(new Transaction(-1L, now, null, owner, staged.currencyId(), payout,
                    TransactionType.PAY, "bank:deposit:close:" + accountId,
                    "{\"principal\":" + staged.principal() + ",\"interestPaid\":" + interest + "}"));
        }
        boolean committed = wallets.applyBatchOnce("bank:deposit:close:" + accountId,
                walletChanges, List.of(), List.of(), bankSnapshot(), transactions);
        if (!committed) {
            accounts.put(accountId, a);
            if (oldPool == 0.0D) pools.remove(key); else pools.put(key, oldPool);
            if (oldInterestReserve == 0.0D) interestReserves.remove(key);
            else interestReserves.put(key, oldInterestReserve);
            interestPayables.clear();
            interestPayables.putAll(oldPayables);
            return "не удалось закрыть вклад: транзакция не подтверждена";
        }
        return null;
    }

    // ---------- кредиты ----------
    /** Необеспеченный кредит (по скору). */
    public synchronized String applyLoanUnsecured(UUID borrower, String borrowerName, String nation, String currency,
                                     double amount, int termDays) {
        if (borrower == null || borrowerName == null || nation == null || nation.isBlank()
                || currency == null || currency.isBlank() || !Double.isFinite(amount)
                || !(amount > 0.0D)) return "некорректные параметры кредита";
        if (termDays != 7 && termDays != 30 && termDays != 90) return "срок: 7/30/90 дней";
        BankParams p = params(nation);
        if (hasUnresolvedRecovery(borrower))
            return "новый кредит недоступен: сначала погасите остаток задолженности после взыскания";
        if (amount > unsecuredMax(borrower, nation))
            return "лимит необеспеченного кредита: " + fmt(unsecuredMax(borrower, nation)) + " (повышайте кредитный скор)";
        if (totalOutstandingLoans(nation) + amount * priceInGld(currency) > maxLoans(nation))
            return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";
        double rate = p.loanRate() + p.unsecuredPremium() + creditRateBonus(borrower);
        String loanId = UUID.randomUUID().toString();
        return createLoan(loanId, borrower, borrowerName, nation, currency, amount, termDays, rate,
                BankLoan.CollateralType.NONE, null, null, 0.0D, 0.0D, 1.0D);
    }

    /**
     * Создаёт кредит под предметный залог через durable intent.
     * Сначала фиксирует сериализованный предмет в SQLite, затем сохраняет метку
     * в данных игрока, удаляет предмет и только после этого коммитит кредит.
     */
    public synchronized String applyItemCollateralLoan(Player player, String nation, String currency,
                                                        double amount, int termDays) {
        if (player == null || nation == null || nation.isBlank() || currency == null || currency.isBlank()
                || !Double.isFinite(amount) || amount <= 0.0D) return "некорректные параметры кредита";
        if (termDays != 7 && termDays != 30 && termDays != 90) return "срок: 7/30/90 дней";
        if (hasUnresolvedRecovery(player.getUniqueId()))
            return "новый кредит недоступен: сначала погасите остаток задолженности после взыскания";
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return "предмет залога не найден в руке";
        double required = amount * params(nation).collateralRatio();
        double value = appraisal(hand);
        if (!Double.isFinite(value) || value < required)
            return "оценка залога " + fmt(value) + " < требуемых " + fmt(required);
        if (totalOutstandingLoans(nation) + amount * priceInGld(currency) > maxLoans(nation))
            return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";

        String intentId = UUID.randomUUID().toString();
        String loanId = UUID.randomUUID().toString();
        String itemBase64 = serialize(hand.clone());
        if (itemBase64 == null || itemBase64.isBlank()) return "не удалось сериализовать предмет залога";
        long now = System.currentTimeMillis();
        SQLiteLedger.BankCollateralIntentRow intent = new SQLiteLedger.BankCollateralIntentRow(
                intentId, player.getUniqueId(), loanId, itemBase64, now);
        try {
            ledger.createBankCollateralIntent(intent);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось зарезервировать предметный залог: " + exception.getMessage());
            return "не удалось создать безопасное резервирование залога";
        }

        ItemStack marked = hand.clone();
        marked.editMeta(meta -> meta.getPersistentDataContainer().set(
                collateralIntentKey, PersistentDataType.STRING, intentId));
        player.getInventory().setItemInMainHand(marked);
        player.saveData();

        player.getInventory().setItemInMainHand(new ItemStack(org.bukkit.Material.AIR));
        player.saveData();

        double rate = params(nation).loanRate() + creditRateBonus(player.getUniqueId());
        String result = createLoan(loanId, player.getUniqueId(), player.getName(), nation, currency,
                amount, termDays, rate, BankLoan.CollateralType.ITEM, null, itemBase64, value, 0.0D, 1.0D);
        if (result == null) {
            try {
                ledger.deleteBankCollateralIntent(intentId);
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Кредит создан, но intent " + intentId
                        + " не удалён; запись будет сверена при входе игрока: " + exception.getMessage());
            }
            return null;
        }

        // Кредит не подтверждён: durable очередь возврата создаётся до удаления intent.
        pendingReturns.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>())
                .add(new SQLiteLedger.BankPendingRow(intentId, player.getUniqueId(), itemBase64));
        if (!savePending()) {
            plugin.getLogger().severe("Предметный залог " + intentId
                    + " не удалось перенести в очередь возврата; intent сохранён для восстановления.");
            return "кредит не создан; возврат предмета ожидает восстановления после перезапуска";
        }
        try {
            ledger.deleteBankCollateralIntent(intentId);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось удалить завершённый intent " + intentId + ": "
                    + exception.getMessage());
        }
        return result;
    }

    /** Обеспеченный кредит (залог предметом/валютой, ratio 1.2). */
    public synchronized String applyLoan(UUID borrower, String borrowerName, String nation, String currency,
                            double amount, int termDays,
                            BankLoan.CollateralType collateralType, String collateralCurrency,
                            ItemStack collateralItem) {
        if (borrower == null || borrowerName == null || nation == null || nation.isBlank()
                || currency == null || currency.isBlank() || !Double.isFinite(amount)
                || !(amount > 0.0D)) return "некорректные параметры кредита";
        if (termDays != 7 && termDays != 30 && termDays != 90) return "срок: 7/30/90 дней";
        if (hasUnresolvedRecovery(borrower))
            return "новый кредит недоступен: сначала погасите остаток задолженности после взыскания";
        // Валютный залог учитывается отдельным активом; при ликвидации он не
        // попадает в денежный пул до подтверждения фактической реализации.
        // Не допускаем выдачу новых залоговых кредитов, пока протоколы оценки и резервирования не стали crash-safe.
        if (collateralType == BankLoan.CollateralType.ITEM)
            return "предметный залог временно отключён: готовится безопасное резервирование с восстановлением после сбоя";
        BankParams p = params(nation);
        if (totalOutstandingLoans(nation) + amount * priceInGld(currency) > maxLoans(nation))
            return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";
        double required = amount * p.collateralRatio();
        double collateralValue = 0;
        double collateralAmount = 0;
        double collateralPrice = 1.0D;
        String itemB64 = null;
        String loanId = UUID.randomUUID().toString();
        if (collateralType == BankLoan.CollateralType.CURRENCY) {
            if (collateralCurrency == null || collateralCurrency.isBlank()) return "укажите валюту залога";
            if (collateralCurrency.equalsIgnoreCase(currency)) return "нельзя брать кредит под залог той же валюты";
            Currency collateralCurrencyDefinition = plugin.getCurrencies().get(collateralCurrency).orElse(null);
            Currency loanCurrencyDefinition = plugin.getCurrencies().get(currency).orElse(null);
            if (collateralCurrencyDefinition == null || loanCurrencyDefinition == null)
                return "валюта залога или кредита не зарегистрирована";
            double collateralGldPrice = bank.priceOf(collateralCurrencyDefinition);
            double loanGldPrice = bank.priceOf(loanCurrencyDefinition);
            if (!Double.isFinite(collateralGldPrice) || collateralGldPrice <= 0.0D
                    || !Double.isFinite(loanGldPrice) || loanGldPrice <= 0.0D)
                return "невозможно безопасно оценить валютный залог: курс отсутствует или некорректен";
            collateralPrice = collateralGldPrice / loanGldPrice;
            if (!Double.isFinite(collateralPrice) || collateralPrice <= 0.0D)
                return "некорректный курс валютного залога";
            double rawCollateralAmount = required / collateralPrice;
            double amountScale = Math.pow(10.0D, collateralCurrencyDefinition.decimals());
            if (!Double.isFinite(rawCollateralAmount) || rawCollateralAmount <= 0.0D
                    || !Double.isFinite(amountScale) || amountScale <= 0.0D
                    || rawCollateralAmount > Double.MAX_VALUE / amountScale)
                return "некорректное количество валютного залога";
            // Округляем залог вверх до точности валюты, чтобы не допустить недообеспечения.
            collateralAmount = Math.ceil(rawCollateralAmount * amountScale - 1.0E-10D) / amountScale;
            if (!Double.isFinite(collateralAmount) || collateralAmount <= 0.0D)
                return "некорректное количество валютного залога";
            collateralValue = collateralAmount * collateralPrice;
            if (!Double.isFinite(collateralValue) || collateralValue + 1.0E-8D < required)
                return "оценка залога ниже требуемой";
            if (!wallets.has(borrower, collateralCurrency, collateralAmount)) return "недостаточно залога";
        } else if (collateralType == BankLoan.CollateralType.ITEM) {
            if (collateralItem == null) return "предмет залога не передан";
            collateralValue = appraisal(collateralItem);
            if (collateralValue < required) return "оценка залога " + fmt(collateralValue) + " < требуемых " + fmt(required);
            itemB64 = serialize(collateralItem);
            if (itemB64 == null || itemB64.isBlank()) return "не удалось сериализовать предмет залога";
        } else {
            return "для обеспеченного кредита укажите залог";
        }
        double rate = p.loanRate() + creditRateBonus(borrower);
        return createLoan(loanId, borrower, borrowerName, nation, currency, amount, termDays, rate,
                collateralType, collateralCurrency, itemB64, collateralValue, collateralAmount, collateralPrice);
    }

    private synchronized String createLoan(String id, UUID borrower, String borrowerName,
                              String nation, String currency, double amount, int termDays, double rate,
                              BankLoan.CollateralType ctype, String ccur, String citem, double cvalue,
                              double camount, double cprice) {
        long now = System.currentTimeMillis();
        long due = now + termDays * 86_400_000L;
        if (id == null || id.isBlank() || !Double.isFinite(amount) || !(amount > 0.0D)
                || !Double.isFinite(rate) || rate < 0.0D) {
            return "некорректные параметры кредита";
        }
        if (ctype == BankLoan.CollateralType.CURRENCY) {
            if (ccur == null || ccur.isBlank() || ccur.equalsIgnoreCase(currency)
                    || !Double.isFinite(cvalue) || cvalue <= 0.0D
                    || !Double.isFinite(camount) || camount <= 0.0D
                    || !Double.isFinite(cprice) || cprice <= 0.0D
                    || Math.abs(camount * cprice - cvalue) > Math.max(1.0E-8D, cvalue * 1.0E-8D)) {
                return "некорректные параметры валютного залога";
            }
        } else if (ctype == BankLoan.CollateralType.NONE
                && (cvalue != 0.0D || camount != 0.0D)) {
            return "необеспеченный кредит содержит параметры залога";
        } else if (ctype == BankLoan.CollateralType.ITEM
                && (citem == null || citem.isBlank() || !Double.isFinite(cvalue) || cvalue <= 0.0D)) {
            return "некорректные параметры предметного залога";
        }
        String key = poolKey(nation, currency);
        double oldPool = pools.getOrDefault(key, 0.0D);
        if (oldPool < amount) return "недостаточно ликвидности в пуле";
        BankLoan loan = new BankLoan(id, borrower, borrowerName, nation, amount, currency, rate, termDays,
                now, due, ctype, ccur, citem, cvalue, camount, cprice, creditScore(borrower));
        loans.put(id, loan);
        if (ctype == BankLoan.CollateralType.CURRENCY && ccur != null && camount > 0.0D) {
            String lotId = "collateral-" + id;
            assetLots.put(lotId, new SQLiteLedger.BankAssetLotRow(lotId, "CURRENCY", nation,
                    borrower, id, ccur, camount, cprice, null, currency, cvalue, 0.0D, 0.0D, "PLEDGED"));
        } else if (ctype == BankLoan.CollateralType.ITEM && citem != null && !citem.isBlank()) {
            String lotId = "collateral-" + id;
            assetLots.put(lotId, new SQLiteLedger.BankAssetLotRow(lotId, "ITEM", nation,
                    borrower, id, null, 1.0D, 1.0D, citem, currency, cvalue, 0.0D, 0.0D, "PLEDGED"));
        }
        pools.put(key, oldPool - amount);

        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        walletChanges.add(new WalletService.WalletDelta(borrower, currency, amount));
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(new Transaction(-1L, now, null, borrower, currency, amount,
                TransactionType.PAY, "bank:loan:disburse:" + id, null));
        if (ctype == BankLoan.CollateralType.CURRENCY && ccur != null && camount > 0.0D) {
            walletChanges.add(new WalletService.WalletDelta(borrower, ccur, -camount));
            transactions.add(new Transaction(-1L, now, borrower, null, ccur, camount,
                    TransactionType.PAY, "bank:collateral:lock:" + id, null));
        }

        boolean committed = wallets.applyBatchOnce("bank:loan:create:" + id,
                walletChanges, List.of(), List.of(), bankSnapshot(), transactions);
        if (!committed) {
            loans.remove(id);
            assetLots.remove("collateral-" + id);
            pools.put(key, oldPool);
            return "не удалось создать кредит: транзакция не подтверждена";
        }
        return null;
    }

    public synchronized String repayLoan(UUID borrower, String loanId, double amount) {
        if (borrower == null || loanId == null || loanId.isBlank()
                || !Double.isFinite(amount) || !(amount > 0.0D)) return "некорректная сумма погашения";
        BankLoan l = loans.get(loanId);
        if (l == null || !l.borrower().equals(borrower)) return "кредит не найден";
        if (!l.isRepayable() || !l.hasOutstandingBalance()) return "кредит закрыт";
        long now = System.currentTimeMillis();
        BankLoan staged = new BankLoan(l.id(), l.borrower(), l.borrowerName(), l.nation(),
                l.principal(), l.currencyId(), l.rateAnnual(), l.termDays(), l.openedAt(), l.dueAt(),
                l.collateralType(), l.collateralCurrency(), l.collateralItemBase64(),
                l.collateralValue(), l.collateralAmount(), l.collateralPrice(), l.creditScoreAtOpen());
        staged.setRepaid(l.repaid());
        staged.setAccrued(l.accrued());
        staged.setLastAccrualAt(l.lastAccrualAt());
        if (l.status() == BankLoan.Status.RECOVERY || l.status() == BankLoan.Status.DEFAULTED
                || l.status() == BankLoan.Status.LIQUIDATED) {
            staged.markRecovery();
        }
        double out = staged.outstanding(now);
        double pay = Math.min(amount, out);
        if (!(pay > 0.0D)) return "нечего погашать";
        if (!wallets.has(borrower, l.currencyId(), pay)) return "недостаточно средств";

        double interestPart = Math.min(pay, staged.accrued());
        double principalPart = pay - interestPart;
        String key = poolKey(l.nation(), l.currencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterestReserve = interestReserves.getOrDefault(key, 0.0D);
        int oldRepaidCount = repaidCount.getOrDefault(borrower, 0);
        boolean fullyRepaid;

        staged.applyRepayment(pay);
        fullyRepaid = staged.outstanding(now) <= 1.0E-6D;
        if (fullyRepaid) {
            staged.markRepaid();
            repaidCount.put(borrower, oldRepaidCount + 1);
        }
        pools.put(key, oldPool + principalPart);
        interestReserves.put(key, oldInterestReserve + interestPart);
        loans.put(loanId, staged);
        String collateralLotId = "collateral-" + loanId;
        SQLiteLedger.BankAssetLotRow currentCollateralLot = assetLots.get(collateralLotId);
        boolean collateralHeld = currentCollateralLot != null && "HELD".equals(currentCollateralLot.status());
        boolean collateralAlreadyRealized = currentCollateralLot != null
                && "REALIZED".equals(currentCollateralLot.status());
        boolean collateralCanBeReturned = !collateralAlreadyRealized
                && (l.status() == BankLoan.Status.ACTIVE || collateralHeld);
        SQLiteLedger.BankAssetLotRow previousCollateralLot = null;
        if (fullyRepaid && collateralCanBeReturned) {
            previousCollateralLot = assetLots.remove(collateralLotId);
        } else if (!fullyRepaid && collateralHeld) {
            // Частичный платёж до продажи залога уменьшает сумму, которую продажа ещё может покрыть.
            previousCollateralLot = currentCollateralLot;
            assetLots.put(collateralLotId, new SQLiteLedger.BankAssetLotRow(
                    currentCollateralLot.lotId(), currentCollateralLot.assetType(), currentCollateralLot.nation(),
                    currentCollateralLot.sourceBorrower(), currentCollateralLot.sourceLoanId(),
                    currentCollateralLot.currencyId(), currentCollateralLot.amount(), currentCollateralLot.lockedPrice(),
                    currentCollateralLot.itemBase64(), currentCollateralLot.valuationCurrencyId(),
                    currentCollateralLot.lockedValue(), staged.accrued(), staged.principal(), "HELD"));
        }

        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        walletChanges.add(new WalletService.WalletDelta(borrower, l.currencyId(), -pay));
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(new Transaction(-1L, now, borrower, null, l.currencyId(), pay,
                TransactionType.PAY, "bank:loan:repay:" + loanId, null));
        if (fullyRepaid && collateralCanBeReturned && l.collateralType() == BankLoan.CollateralType.CURRENCY
                && l.collateralCurrency() != null && l.collateralAmount() > 0.0D) {
            walletChanges.add(new WalletService.WalletDelta(borrower, l.collateralCurrency(), l.collateralAmount()));
            transactions.add(new Transaction(-1L, now, null, borrower, l.collateralCurrency(),
                    l.collateralAmount(), TransactionType.PAY, "bank:collateral:release:" + loanId, null));
        }

        boolean pendingCollateralReturn = false;
        List<SQLiteLedger.BankPendingRow> previousPending = null;
        if (fullyRepaid && collateralCanBeReturned && l.collateralType() == BankLoan.CollateralType.ITEM) {
            // Возврат предмета сначала попадает в тот же SQLite-коммит, что и закрытие кредита.
            // Доставка выполняется после фиксации, чтобы сбой сервера не потерял залог между операциями.
            previousPending = new ArrayList<>(pendingReturns.getOrDefault(borrower, List.of()));
            pendingReturns.computeIfAbsent(borrower, ignored -> new ArrayList<>())
                    .add(new SQLiteLedger.BankPendingRow(UUID.randomUUID().toString(),
                            borrower, l.collateralItemBase64()));
            pendingCollateralReturn = true;
        }

        String operationId = "bank:loan:repay:" + loanId + ":" + UUID.randomUUID();
        boolean committed = wallets.applyBatchOnce(operationId, walletChanges, List.of(),
                List.of(), bankSnapshot(), transactions);
        if (!committed) {
            loans.put(loanId, l);
            if (previousCollateralLot != null) assetLots.put(previousCollateralLot.lotId(), previousCollateralLot);
            pools.put(key, oldPool);
            if (oldInterestReserve == 0.0D) interestReserves.remove(key);
            else interestReserves.put(key, oldInterestReserve);
            if (oldRepaidCount == 0) repaidCount.remove(borrower);
            else repaidCount.put(borrower, oldRepaidCount);
            if (pendingCollateralReturn) {
                if (previousPending == null || previousPending.isEmpty()) pendingReturns.remove(borrower);
                else pendingReturns.put(borrower, previousPending);
            }
            return "не удалось погасить кредит: транзакция не подтверждена";
        }

        return null;
    }

    public synchronized String liquidate(String loanId) {
        BankLoan l = loans.get(loanId);
        if (l == null) return "кредит не найден";
        if (!l.isActive()) return "кредит закрыт";
        long now = System.currentTimeMillis();

        BankLoan staged = new BankLoan(l.id(), l.borrower(), l.borrowerName(), l.nation(),
                l.principal(), l.currencyId(), l.rateAnnual(), l.termDays(), l.openedAt(), l.dueAt(),
                l.collateralType(), l.collateralCurrency(), l.collateralItemBase64(),
                l.collateralValue(), l.collateralAmount(), l.collateralPrice(), l.creditScoreAtOpen());
        staged.setRepaid(l.repaid());
        staged.setAccrued(l.accrued());
        staged.setLastAccrualAt(l.lastAccrualAt());
        staged.outstanding(now);
        // Оценка залога — не денежное поступление. До подтверждённой реализации
        // актив хранится отдельным лотом, а погашение долга денежным пулом не отражается.
        double proceeds = 0.0D;
        BankLoan.LiquidationSettlement settlement = staged.liquidationSettlement(proceeds);
        double toInterest = settlement.interestRecovered();
        double principalRecovery = settlement.principalRecovered();
        double borrowerSurplus = settlement.borrowerSurplus();
        String key = poolKey(l.nation(), l.currencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterestReserve = interestReserves.getOrDefault(key, 0.0D);
        int oldDefaultedCount = defaultedCount.getOrDefault(l.borrower(), 0);

        staged.markRecovery();
        if (l.collateralType() == BankLoan.CollateralType.CURRENCY
                || l.collateralType() == BankLoan.CollateralType.ITEM) {
            String lotId = "collateral-" + loanId;
            SQLiteLedger.BankAssetLotRow pledged = assetLots.get(lotId);
            if (pledged == null) {
                pledged = l.collateralType() == BankLoan.CollateralType.CURRENCY
                        ? new SQLiteLedger.BankAssetLotRow(lotId, "CURRENCY", l.nation(), l.borrower(),
                                l.id(), l.collateralCurrency(), l.collateralAmount(), l.collateralPrice(),
                                null, l.currencyId(), l.collateralValue(), 0.0D, 0.0D, "PLEDGED")
                        : new SQLiteLedger.BankAssetLotRow(lotId, "ITEM", l.nation(), l.borrower(),
                                l.id(), null, 1.0D, 1.0D, l.collateralItemBase64(),
                                l.currencyId(), l.collateralValue(), 0.0D, 0.0D, "PLEDGED");
            }
            assetLots.put(lotId, new SQLiteLedger.BankAssetLotRow(lotId, pledged.assetType(),
                    pledged.nation(), pledged.sourceBorrower(), pledged.sourceLoanId(), pledged.currencyId(),
                    pledged.amount(), pledged.lockedPrice(), pledged.itemBase64(), pledged.valuationCurrencyId(),
                    pledged.lockedValue(), staged.accrued(), staged.principal(), "HELD"));
        }
        loans.put(loanId, staged);
        // Банк удерживает только сумму, направленную на погашение долга.
        pools.put(key, oldPool + principalRecovery);
        interestReserves.put(key, oldInterestReserve + toInterest);
        defaultedCount.put(l.borrower(), oldDefaultedCount + 1);

        List<WalletService.WalletDelta> liquidationWalletChanges = borrowerSurplus > 0.0D
                ? List.of(new WalletService.WalletDelta(l.borrower(), l.currencyId(), borrowerSurplus))
                : List.of();
        List<Transaction> liquidationJournal = new ArrayList<>();
        if (l.collateralType() == BankLoan.CollateralType.CURRENCY
                && l.collateralCurrency() != null && l.collateralAmount() > 0.0D) {
            // Фиксируем изъятие актива. Денежное поступление появится только
            // после отдельного подтверждения фактической реализации лота.
            liquidationJournal.add(new Transaction(-1L, now, l.borrower(), null, l.collateralCurrency(),
                    l.collateralAmount(), TransactionType.PAY, "bank:collateral:seize:" + loanId,
                    "{\"lockedPrice\":" + l.collateralPrice() + ",\"loanValue\":" + l.collateralValue() + "}"));
        }
        if (proceeds > 0.0D) {
            liquidationJournal.add(new Transaction(-1L, now, l.borrower(), null, l.currencyId(), proceeds,
                    TransactionType.PAY, "bank:loan:liquidate:" + loanId, null));
        }
        if (borrowerSurplus > 0.0D) {
            liquidationJournal.add(new Transaction(-1L, now, null, l.borrower(), l.currencyId(), borrowerSurplus,
                    TransactionType.PAY, "bank:loan:liquidation-surplus:" + loanId, null));
        }
        boolean committed = wallets.applyBatchOnce("bank:loan:liquidate:" + loanId,
                liquidationWalletChanges, List.of(), List.of(), bankSnapshot(), liquidationJournal);
        if (!committed) {
            loans.put(loanId, l);
            SQLiteLedger.BankAssetLotRow rollbackLot = assetLots.get("collateral-" + loanId);
            if (rollbackLot != null) assetLots.put(rollbackLot.lotId(), new SQLiteLedger.BankAssetLotRow(
                    rollbackLot.lotId(), rollbackLot.assetType(), rollbackLot.nation(), rollbackLot.sourceBorrower(),
                    rollbackLot.sourceLoanId(), rollbackLot.currencyId(), rollbackLot.amount(),
                    rollbackLot.lockedPrice(), rollbackLot.itemBase64(), rollbackLot.valuationCurrencyId(),
                    rollbackLot.lockedValue(), 0.0D, 0.0D, "PLEDGED"));
            pools.put(key, oldPool);
            if (oldInterestReserve == 0.0D) interestReserves.remove(key);
            else interestReserves.put(key, oldInterestReserve);
            if (oldDefaultedCount == 0) defaultedCount.remove(l.borrower());
            else defaultedCount.put(l.borrower(), oldDefaultedCount);
            return "не удалось ликвидировать кредит: транзакция не подтверждена";
        }
        return null;
    }

    /**
     * Подтверждает фактическую реализацию залогового актива.
     * proceeds — реально полученная сумма в валюте оценки лота, а не оценочная стоимость.
     */
    public synchronized String realizeCollateralAsset(String lotId, double proceeds) {
        if (lotId == null || lotId.isBlank() || !Double.isFinite(proceeds) || proceeds < 0.0D)
            return "некорректные параметры реализации залога";
        SQLiteLedger.BankAssetLotRow lot = assetLots.get(lotId);
        if (lot == null) return "лот залога не найден";
        if (!"HELD".equals(lot.status())) return "лот уже реализован или закрыт";

        double interestRecovered = Math.min(lot.recoverableInterest(), proceeds);
        double principalRecovered = Math.min(lot.recoverablePrincipal(),
                Math.max(0.0D, proceeds - interestRecovered));
        double surplus = Math.max(0.0D, proceeds - interestRecovered - principalRecovered);
        BankLoan sourceLoan = loans.get(lot.sourceLoanId());
        if (sourceLoan == null || !sourceLoan.hasOutstandingBalance()
                || !sourceLoan.borrower().equals(lot.sourceBorrower())) {
            return "связанный кредит не найден или не содержит взыскиваемого долга";
        }
        BankLoan stagedLoan = new BankLoan(sourceLoan.id(), sourceLoan.borrower(), sourceLoan.borrowerName(),
                sourceLoan.nation(), sourceLoan.principal(), sourceLoan.currencyId(), sourceLoan.rateAnnual(),
                sourceLoan.termDays(), sourceLoan.openedAt(), sourceLoan.dueAt(), sourceLoan.collateralType(),
                sourceLoan.collateralCurrency(), sourceLoan.collateralItemBase64(), sourceLoan.collateralValue(),
                sourceLoan.collateralAmount(), sourceLoan.collateralPrice(), sourceLoan.creditScoreAtOpen());
        stagedLoan.setRepaid(sourceLoan.repaid());
        stagedLoan.setAccrued(sourceLoan.accrued());
        stagedLoan.setLastAccrualAt(sourceLoan.lastAccrualAt());
        stagedLoan.markRecovery();
        stagedLoan.applyRepayment(interestRecovered + principalRecovered);
        boolean debtSettled = !stagedLoan.hasOutstandingBalance();
        if (debtSettled) stagedLoan.markLiquidated();

        String key = poolKey(lot.nation(), lot.valuationCurrencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterest = interestReserves.getOrDefault(key, 0.0D);
        pools.put(key, oldPool + principalRecovered);
        interestReserves.put(key, oldInterest + interestRecovered);
        SQLiteLedger.BankAssetLotRow realized = new SQLiteLedger.BankAssetLotRow(
                lot.lotId(), lot.assetType(), lot.nation(), lot.sourceBorrower(), lot.sourceLoanId(),
                lot.currencyId(), lot.amount(), lot.lockedPrice(), lot.itemBase64(),
                lot.valuationCurrencyId(), lot.lockedValue(), lot.recoverableInterest(),
                lot.recoverablePrincipal(), "REALIZED");
        assetLots.put(lotId, realized);
        loans.put(stagedLoan.id(), stagedLoan);

        List<WalletService.WalletDelta> walletChanges = surplus > 0.0D
                ? List.of(new WalletService.WalletDelta(lot.sourceBorrower(), lot.valuationCurrencyId(), surplus))
                : List.of();
        List<Transaction> journal = proceeds > 0.0D
                ? List.of(new Transaction(-1L, System.currentTimeMillis(),
                        null, null, lot.valuationCurrencyId(), proceeds,
                        TransactionType.PAY, "bank:collateral:realize:" + lotId,
                        "{\"proceeds\":" + proceeds + ",\"interest\":" + interestRecovered
                                + ",\"principal\":" + principalRecovered + ",\"surplus\":" + surplus + "}"))
                : List.of();
        boolean committed = wallets.applyBatchOnce("bank:collateral:realize:" + lotId,
                walletChanges, List.of(), List.of(), bankSnapshot(), journal);
        if (!committed) {
            assetLots.put(lotId, lot);
            loans.put(sourceLoan.id(), sourceLoan);
            pools.put(key, oldPool);
            if (oldInterest == 0.0D) interestReserves.remove(key); else interestReserves.put(key, oldInterest);
            return "реализация не подтверждена: состояние лота восстановлено";
        }
        return null;
    }

    public List<SQLiteLedger.BankAssetLotRow> collateralAssetLots() {
        return assetLots.values().stream()
                .sorted(java.util.Comparator.comparing(SQLiteLedger.BankAssetLotRow::lotId)).toList();
    }

    public synchronized int autoLiquidateOverdue() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (BankLoan l : new ArrayList<>(loans.values())) {
            if (l.isOverdue(now) && liquidate(l.id()) == null) count++;
        }
        return count;
    }

    private boolean settleInterestPayables(String nation, String currency, long now,
                                           List<WalletService.WalletDelta> walletChanges,
                                           List<Transaction> transactions) {
        String key = poolKey(nation, currency);
        double available = Math.max(0.0D, interestReserves.getOrDefault(key, 0.0D));
        if (available <= 1.0E-8D) return false;
        List<SQLiteLedger.BankInterestPayableRow> claims = interestPayables.values().stream()
                .filter(row -> row.nation().equalsIgnoreCase(nation) && row.currencyId().equalsIgnoreCase(currency))
                .sorted(java.util.Comparator.comparingLong(SQLiteLedger.BankInterestPayableRow::createdAt)
                        .thenComparing(SQLiteLedger.BankInterestPayableRow::id)).toList();
        boolean changed = false;
        for (SQLiteLedger.BankInterestPayableRow claim : claims) {
            if (available <= 1.0E-8D) break;
            double paid = Math.min(available, claim.amount());
            if (paid <= 1.0E-8D) continue;
            available -= paid;
            double remaining = claim.amount() - paid;
            if (remaining <= 1.0E-8D) {
                interestPayables.remove(claim.id());
            } else {
                interestPayables.put(claim.id(), new SQLiteLedger.BankInterestPayableRow(
                        claim.id(), claim.owner(), claim.ownerName(), claim.nation(),
                        claim.currencyId(), remaining, claim.createdAt()));
            }
            walletChanges.add(new WalletService.WalletDelta(claim.owner(), claim.currencyId(), paid));
            transactions.add(new Transaction(-1L, now, null, claim.owner(), claim.currencyId(), paid,
                    TransactionType.PAY, "bank:deposit:interest-payable:" + claim.id(),
                    "{\"claim\":\"" + claim.id() + "\",\"remaining\":" + Math.max(0.0D, remaining) + "}"));
            changed = true;
        }
        if (changed) interestReserves.put(key, Math.max(0.0D, available));
        return changed;
    }

    public List<SQLiteLedger.BankInterestPayableRow> interestPayables(UUID owner) {
        if (owner == null) return List.of();
        return interestPayables.values().stream().filter(row -> row.owner().equals(owner))
                .sorted(java.util.Comparator.comparingLong(SQLiteLedger.BankInterestPayableRow::createdAt)
                        .thenComparing(SQLiteLedger.BankInterestPayableRow::id)).toList();
    }

    public synchronized void accrueAll() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        List<Transaction> journal = new ArrayList<>();
        for (BankAccount account : accounts.values()) {
            if (account.isActive()) {
                account.accrue(now, params(account.nation()).demandRate());
                changed = true;
            }
        }
        for (BankLoan loan : loans.values()) {
            if (loan.isActive()) {
                loan.accrueTo(now);
                changed = true;
            }
        }
        java.util.Set<String> payablePools = new java.util.TreeSet<>();
        for (SQLiteLedger.BankInterestPayableRow claim : interestPayables.values()) {
            payablePools.add(poolKey(claim.nation(), claim.currencyId()));
        }
        for (String key : payablePools) {
            int split = key.indexOf('|');
            if (split > 0 && settleInterestPayables(key.substring(0, split), key.substring(split + 1),
                    now, walletChanges, journal)) changed = true;
        }
        if (!changed) return;

        // Начисления и выплаты ранее признанных обязательств фиксируются одним SQLite-коммитом.
        boolean committed;
        try {
            committed = wallets.applyBatchOnce("bank:accrual:" + now,
                    walletChanges, List.of(), List.of(), bankSnapshot(), journal);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось атомарно сохранить начисления и выплаты банка: "
                    + exception.getMessage());
            committed = false;
        }
        if (!committed) {
            // Восстанавливаем состояние из SQLite: оно является источником истины,
            // а начисления, не прошедшие коммит, не должны оставаться в оперативной памяти.
            accounts.clear();
            loans.clear();
            pools.clear();
            interestReserves.clear();
            repaidCount.clear();
            defaultedCount.clear();
            pendingReturns.clear();
            assetLots.clear();
            interestPayables.clear();
            loadFromSqlite();
            plugin.getLogger().severe("Начисления банка отменены: состояние восстановлено из SQLite.");
        }
    }

    private void releaseCollateral(BankLoan loan) {
        if (loan.collateralType() != BankLoan.CollateralType.ITEM
                || loan.collateralItemBase64() == null) return;
        pendingReturns.computeIfAbsent(loan.borrower(), ignored -> new ArrayList<>()).add(
                new SQLiteLedger.BankPendingRow(UUID.randomUUID().toString(),
                        loan.borrower(), loan.collateralItemBase64()));
        if (!savePending()) {
            plugin.getLogger().severe("Не удалось сохранить очередь возврата предметного залога кредита "
                    + loan.id());
        }
    }

    @EventHandler
    public synchronized void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID owner = player.getUniqueId();
        recoverItemCollateralIntents(player);
        List<SQLiteLedger.BankPendingRow> rows = pendingReturns.get(owner);
        if (rows == null || rows.isEmpty()) return;

        int delivered = 0;
        for (SQLiteLedger.BankPendingRow row : new ArrayList<>(rows)) {
            if (hasDeliveredCollateral(player, row.returnId())) {
                markCollateralReceipt(player, row.returnId());
                // Сначала фиксируем квитанцию и состояние инвентаря игрока на диск,
                // и только затем удаляем durable-запись возврата из SQLite.
                player.saveData();
                acknowledgeCollateralReturn(owner, row);
                continue;
            }

            ItemStack item = deserialize(row.itemBase64());
            if (item == null) {
                plugin.getLogger().severe("Не удалось прочитать предметный залог " + row.returnId()
                        + " игрока " + owner + "; запись оставлена в очереди.");
                continue;
            }
            item = item.clone();
            item.editMeta(meta -> meta.getPersistentDataContainer().set(
                    collateralReturnKey, PersistentDataType.STRING, row.returnId()));

            if (!canFit(player, item)) {
                player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        plugin.getMessages().prefix() + "&eОсвободите место в инвентаре: предметный залог ожидает выдачи."));
                continue;
            }

            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            if (!leftovers.isEmpty()) {
                // Предварительная проверка вместимости должна исключать этот случай.
                // При неожиданном отказе не подтверждаем возврат и не выбрасываем предмет в мир.
                plugin.getLogger().severe("Не удалось полностью выдать предметный залог " + row.returnId()
                        + "; запись оставлена в очереди.");
                continue;
            }
            // Квитанция хранится в данных игрока и остаётся с ним, даже если предмет переложен в сундук.
            markCollateralReceipt(player, row.returnId());
            // Подтверждение в SQLite допустимо только после сохранения предмета и квитанции.
            player.saveData();
            delivered++;
            acknowledgeCollateralReturn(owner, row);
        }

        if (delivered > 0) {
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    plugin.getMessages().prefix() + "&aВозвращён предметный залог по погашенному кредиту."));
        }
    }

    /**
     * Восстанавливает незавершённые резервы предметов.
     * Наличие кредита в SQLite означает, что залог уже принят; отсутствие —
     * что предмет нужно вернуть либо оставить в durable очереди возврата.
     */
    private void recoverItemCollateralIntents(Player player) {
        UUID owner = player.getUniqueId();
        for (SQLiteLedger.BankCollateralIntentRow intent : ledger.bankCollateralIntentsAll()) {
            if (!owner.equals(intent.player())) continue;
            boolean loanCommitted = loans.containsKey(intent.loanId());
            boolean markerFound = false;
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item == null || item.getType().isAir()) continue;
                String marker = item.getPersistentDataContainer().get(
                        collateralIntentKey, PersistentDataType.STRING);
                if (!intent.intentId().equals(marker)) continue;
                markerFound = true;
                if (loanCommitted) {
                    player.getInventory().setItem(slot, new ItemStack(org.bukkit.Material.AIR));
                } else {
                    ItemStack restored = item.clone();
                    restored.editMeta(meta -> meta.getPersistentDataContainer().remove(collateralIntentKey));
                    player.getInventory().setItem(slot, restored);
                }
            }

            if (!loanCommitted && !markerFound) {
                ItemStack original = deserialize(intent.itemBase64());
                ItemStack reservedSlotItem = player.getInventory().getItemInMainHand();
                // Предмет резервируется именно из основной руки. Поиск похожего стака
                // во всём инвентаре опасен: чужой идентичный предмет можно принять за залог.
                boolean originalStillPresent = original != null
                        && reservedSlotItem != null && !reservedSlotItem.getType().isAir()
                        && reservedSlotItem.isSimilar(original)
                        && reservedSlotItem.getAmount() >= original.getAmount();
                List<SQLiteLedger.BankPendingRow> playerPending =
                        pendingReturns.computeIfAbsent(owner, ignored -> new ArrayList<>());
                boolean pendingAlreadyExists = playerPending.stream()
                        .anyMatch(row -> row.returnId().equals(intent.intentId()));
                if (originalStillPresent && pendingAlreadyExists) {
                    playerPending.removeIf(row -> row.returnId().equals(intent.intentId()));
                    if (playerPending.isEmpty()) pendingReturns.remove(owner);
                    if (!savePending()) {
                        plugin.getLogger().severe("Не удалось отменить дублирующую очередь возврата "
                                + intent.intentId() + "; intent оставлен для повторной сверки.");
                        continue;
                    }
                } else if (!originalStillPresent && !pendingAlreadyExists) {
                    playerPending.add(new SQLiteLedger.BankPendingRow(
                            intent.intentId(), owner, intent.itemBase64()));
                    if (!savePending()) {
                        playerPending.removeIf(row -> row.returnId().equals(intent.intentId()));
                        if (playerPending.isEmpty()) pendingReturns.remove(owner);
                        plugin.getLogger().severe("Не удалось поставить предмет " + intent.intentId()
                                + " в очередь восстановления; intent сохранён.");
                        continue;
                    }
                }
            }
            player.saveData();
            try {
                ledger.deleteBankCollateralIntent(intent.intentId());
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Не удалось подтвердить восстановление intent "
                        + intent.intentId() + ": " + exception.getMessage());
            }
        }
    }

    private NamespacedKey collateralReceiptKey(String returnId) {
        String safeId = returnId.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return new NamespacedKey(plugin, "collateral_receipt_" + safeId);
    }

    private void markCollateralReceipt(Player player, String returnId) {
        player.getPersistentDataContainer().set(
                collateralReceiptKey(returnId), PersistentDataType.BYTE, (byte) 1);
    }

    private boolean hasDeliveredCollateral(Player player, String returnId) {
        if (player.getPersistentDataContainer().has(
                collateralReceiptKey(returnId), PersistentDataType.BYTE)) return true;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            String marker = item.getPersistentDataContainer().get(
                    collateralReturnKey, PersistentDataType.STRING);
            if (returnId.equals(marker)) return true;
        }
        return false;
    }

    private boolean canFit(Player player, ItemStack item) {
        int remaining = item.getAmount();
        int maxStack = item.getMaxStackSize();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= maxStack;
            } else if (existing.isSimilar(item)) {
                remaining -= Math.max(0, maxStack - existing.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }

    private void acknowledgeCollateralReturn(UUID owner, SQLiteLedger.BankPendingRow row) {
        List<SQLiteLedger.BankPendingRow> current = pendingReturns.get(owner);
        if (current == null || !current.removeIf(entry -> entry.returnId().equals(row.returnId()))) return;
        if (current.isEmpty()) pendingReturns.remove(owner);
        if (!savePending()) {
            // Предмет уже помечен в инвентаре. При следующем входе метка позволит
            // безопасно повторить подтверждение без повторной выдачи.
            pendingReturns.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(row);
            plugin.getLogger().severe("Не удалось подтвердить возврат предметного залога "
                    + row.returnId() + "; повторная выдача будет предотвращена меткой предмета.");
        }
    }

    public double appraisal(ItemStack item) {
        if (item == null) return 0.0D;
        double unit = plugin.getConfig().getDouble("bank.appraisal." + item.getType().name(), 0.0D);
        return unit * item.getAmount();
    }

    public List<BankAccount> myDeposits(UUID owner) {
        List<BankAccount> out = new ArrayList<>();
        for (BankAccount a : accounts.values()) if (a.owner().equals(owner)) out.add(a);
        return out;
    }
    public List<BankLoan> myLoans(UUID borrower) {
        List<BankLoan> out = new ArrayList<>();
        for (BankLoan l : loans.values()) if (l.borrower().equals(borrower)) out.add(l);
        return out;
    }
    public BankAccount getAccount(String id) { return accounts.get(id); }
    public BankLoan getLoan(String id) { return loans.get(id); }
    public List<BankLoan> activeLoans(String nation) {
        List<BankLoan> out = new ArrayList<>();
        for (BankLoan l : loans.values()) if (l.nation().equalsIgnoreCase(nation) && l.isActive()) out.add(l);
        return out;
    }

    public List<BankLoan> recoveryLoans(String nation) {
        List<BankLoan> out = new ArrayList<>();
        for (BankLoan l : loans.values()) {
            if (l.nation().equalsIgnoreCase(nation)
                    && l.status() == BankLoan.Status.RECOVERY && l.hasOutstandingBalance()) {
                out.add(l);
            }
        }
        return out;
    }

    // ---------- персистентность ----------
    public synchronized void load() {
        accounts.clear(); loans.clear(); pools.clear(); interestReserves.clear(); assetLots.clear();
        repaidCount.clear(); defaultedCount.clear(); pendingReturns.clear();

        if (ledger.bankStorageInitialized()) {
            loadFromSqlite();
            return;
        }

        // Однократный импорт старых YAML-файлов. Исходные файлы остаются на месте
        // как резервная копия и больше не используются для рабочих записей.
        loadLegacyYaml();
        if (!saveAccounts() || !saveLoans() || !savePools() || !saveCredit() || !savePending()
                || !verifyImportedBankData()) {
            throw new IllegalStateException("Миграция банковских данных в SQLite не прошла проверку; YAML-файлы сохранены.");
        }
        ledger.markBankStorageInitialized();
        plugin.getLogger().info("Банковские данные импортированы в SQLite. YAML-файлы сохранены как резервные копии.");
    }

    private YamlConfiguration loadYamlStrict(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Не удалось безопасно прочитать " + file.getName()
                    + "; импорт остановлен, исходный файл не изменён.", exception);
        }
    }

    private boolean verifyImportedBankData() {
        List<SQLiteLedger.BankAccountRow> storedAccounts = ledger.bankAccountsAll();
        List<SQLiteLedger.BankLoanRow> storedLoans = ledger.bankLoansAll();
        List<SQLiteLedger.BankPoolRow> storedPools = ledger.bankPoolsAll();
        List<SQLiteLedger.BankCreditRow> storedCredit = ledger.bankCreditAll();
        List<SQLiteLedger.BankPendingRow> storedPending = ledger.bankPendingAll();

        double expectedDepositPrincipal = accounts.values().stream()
                .mapToDouble(BankAccount::principal).sum();
        double storedDepositPrincipal = storedAccounts.stream()
                .mapToDouble(SQLiteLedger.BankAccountRow::principal).sum();
        double expectedLoanPrincipal = loans.values().stream()
                .mapToDouble(BankLoan::principal).sum();
        double storedLoanPrincipal = storedLoans.stream()
                .mapToDouble(SQLiteLedger.BankLoanRow::principal).sum();
        double expectedPool = pools.values().stream().mapToDouble(Double::doubleValue).sum();
        double storedPool = storedPools.stream().mapToDouble(SQLiteLedger.BankPoolRow::pool).sum();
        double expectedInterest = interestReserves.values().stream().mapToDouble(Double::doubleValue).sum();
        double storedInterest = storedPools.stream().mapToDouble(SQLiteLedger.BankPoolRow::interestReserve).sum();

        java.util.Set<String> expectedPoolKeys = new java.util.HashSet<>(pools.keySet());
        expectedPoolKeys.addAll(interestReserves.keySet());
        java.util.Set<UUID> expectedCreditPlayers = new java.util.HashSet<>(repaidCount.keySet());
        expectedCreditPlayers.addAll(defaultedCount.keySet());
        boolean valid = storedAccounts.size() == accounts.size()
                && storedLoans.size() == loans.size()
                && storedPools.size() == expectedPoolKeys.size()
                && storedPending.size() == pendingReturns.values().stream().mapToInt(List::size).sum()
                && storedCredit.size() == expectedCreditPlayers.size()
                && Double.isFinite(expectedDepositPrincipal) && Double.isFinite(storedDepositPrincipal)
                && Double.isFinite(expectedLoanPrincipal) && Double.isFinite(storedLoanPrincipal)
                && Double.isFinite(expectedPool) && Double.isFinite(storedPool)
                && Double.isFinite(expectedInterest) && Double.isFinite(storedInterest)
                && Math.abs(expectedDepositPrincipal - storedDepositPrincipal) < 1.0E-8D
                && Math.abs(expectedLoanPrincipal - storedLoanPrincipal) < 1.0E-8D
                && Math.abs(expectedPool - storedPool) < 1.0E-8D
                && Math.abs(expectedInterest - storedInterest) < 1.0E-8D;
        if (!valid) {
            plugin.getLogger().severe("Проверка импорта банка не пройдена: записи или контрольные суммы не совпадают.");
        }
        return valid;
    }

    private void loadFromSqlite() {
        for (SQLiteLedger.BankAccountRow row : ledger.bankAccountsAll()) {
            BankAccount account = new BankAccount(row.id(), row.owner(), row.ownerName(), row.nation(),
                    row.currencyId(), row.principal(), BankAccount.Term.valueOf(row.term()),
                    row.rateAnnual(), row.openedAt(), row.maturesAt());
            if ("CLOSED".equals(row.status())) account.close();
            account.setAccrued(row.accrued());
            account.setLastAccrualAt(row.lastAccrualAt());
            accounts.put(account.id(), account);
        }
        for (SQLiteLedger.BankLoanRow row : ledger.bankLoansAll()) {
            BankLoan loan = new BankLoan(row.id(), row.borrower(), row.borrowerName(), row.nation(),
                    row.principal(), row.currencyId(), row.rateAnnual(), row.termDays(), row.openedAt(),
                    row.dueAt(), BankLoan.CollateralType.valueOf(row.collateralType()),
                    row.collateralCurrency(), row.collateralItem(), row.collateralValue(),
                    row.collateralAmount(), row.collateralPrice(), row.creditScoreAtOpen());
            loan.setRepaid(row.repaid());
            loan.setAccrued(row.accrued());
            loan.setLastAccrualAt(row.lastAccrualAt());
            switch (row.status()) {
                case "REPAID" -> loan.markRepaid();
                case "RECOVERY", "DEFAULTED" -> loan.markRecovery();
                case "LIQUIDATED" -> {
                    if (loan.hasOutstandingBalance()) loan.markRecovery();
                    else loan.markLiquidated();
                }
                default -> { }
            }
            loans.put(loan.id(), loan);
        }
        for (SQLiteLedger.BankPoolRow row : ledger.bankPoolsAll()) {
            String key = poolKey(row.nation(), row.currencyId());
            pools.put(key, row.pool());
            interestReserves.put(key, row.interestReserve());
        }
        for (SQLiteLedger.BankCreditRow row : ledger.bankCreditAll()) {
            repaidCount.put(row.player(), row.repaidCount());
            defaultedCount.put(row.player(), row.defaultedCount());
        }
        for (SQLiteLedger.BankPendingRow row : ledger.bankPendingAll()) {
            pendingReturns.computeIfAbsent(row.player(), ignored -> new ArrayList<>()).add(row);
        }
        for (SQLiteLedger.BankAssetLotRow row : ledger.bankAssetLotsAll()) assetLots.put(row.lotId(), row);
        for (SQLiteLedger.BankInterestPayableRow row : ledger.bankInterestPayablesAll()) {
            interestPayables.put(row.id(), row);
        }
    }

    private void loadLegacyYaml() {
        for (File file : new File[]{accountsFile, loansFile, poolsFile, creditFile, pendingFile}) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        }
        if (accountsFile.exists()) {
            YamlConfiguration y = loadYamlStrict(accountsFile);
            ConfigurationSection root = y.getConfigurationSection("accounts");
            if (root != null) for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                BankAccount account = new BankAccount(id, UUID.fromString(section.getString("owner")),
                        section.getString("ownerName", ""), section.getString("nation", ""),
                        section.getString("currency", "GLD"), section.getDouble("principal", 0.0D),
                        BankAccount.Term.valueOf(section.getString("term", "DEMAND")),
                        section.getDouble("rate", 0.0D), section.getLong("openedAt", 0L),
                        section.getLong("maturesAt", 0L));
                if ("CLOSED".equals(section.getString("status", "ACTIVE"))) account.close();
                account.setAccrued(section.getDouble("accrued", 0.0D));
                account.setLastAccrualAt(section.getLong("lastAccrualAt", account.openedAt()));
                accounts.put(id, account);
            }
        }
        if (loansFile.exists()) {
            YamlConfiguration y = loadYamlStrict(loansFile);
            ConfigurationSection root = y.getConfigurationSection("loans");
            if (root != null) for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                BankLoan loan = new BankLoan(id, UUID.fromString(section.getString("borrower")),
                        section.getString("borrowerName", ""), section.getString("nation", ""),
                        section.getDouble("principal", 0.0D), section.getString("currency", "GLD"),
                        section.getDouble("rate", 0.0D), section.getInt("termDays", 30),
                        section.getLong("openedAt", 0L), section.getLong("dueAt", 0L),
                        BankLoan.CollateralType.valueOf(section.getString("collateralType", "NONE")),
                        section.getString("collateralCurrency", null), section.getString("collateralItem", null),
                        section.getDouble("collateralValue", 0.0D),
                        section.getDouble("collateralAmount",
                                "CURRENCY".equals(section.getString("collateralType", "NONE"))
                                        ? section.getDouble("collateralValue", 0.0D) : 0.0D),
                        section.getDouble("collateralPrice", 1.0D), section.getInt("creditScore", 0));
                loan.setRepaid(section.getDouble("repaid", 0.0D));
                loan.setAccrued(section.getDouble("accrued", 0.0D));
                loan.setLastAccrualAt(section.getLong("lastAccrualAt", loan.openedAt()));
                switch (section.getString("status", "ACTIVE")) {
                    case "REPAID" -> loan.markRepaid();
                    case "RECOVERY", "DEFAULTED" -> loan.markRecovery();
                    case "LIQUIDATED" -> {
                        if (loan.hasOutstandingBalance()) loan.markRecovery();
                        else loan.markLiquidated();
                    }
                    default -> { }
                }
                loans.put(id, loan);
            }
        }
        if (poolsFile.exists()) {
            YamlConfiguration y = loadYamlStrict(poolsFile);
            ConfigurationSection poolSection = y.getConfigurationSection("pools");
            if (poolSection != null) for (String key : poolSection.getKeys(false)) {
                pools.put(key, poolSection.getDouble(key, 0.0D));
            }
            ConfigurationSection interestSection = y.getConfigurationSection("interest");
            if (interestSection != null) for (String key : interestSection.getKeys(false)) {
                interestReserves.put(key, interestSection.getDouble(key, 0.0D));
            }
        }
        if (creditFile.exists()) {
            YamlConfiguration y = loadYamlStrict(creditFile);
            ConfigurationSection repaidSection = y.getConfigurationSection("repaid");
            if (repaidSection != null) for (String key : repaidSection.getKeys(false)) {
                repaidCount.put(UUID.fromString(key), repaidSection.getInt(key, 0));
            }
            ConfigurationSection defaultedSection = y.getConfigurationSection("defaulted");
            if (defaultedSection != null) for (String key : defaultedSection.getKeys(false)) {
                defaultedCount.put(UUID.fromString(key), defaultedSection.getInt(key, 0));
            }
        }
        if (pendingFile.exists()) {
            YamlConfiguration y = loadYamlStrict(pendingFile);
            ConfigurationSection pendingSection = y.getConfigurationSection("pending");
            if (pendingSection != null) for (String key : pendingSection.getKeys(false)) {
                List<SQLiteLedger.BankPendingRow> legacyRows = new ArrayList<>();
                for (String item : pendingSection.getStringList(key)) {
                    legacyRows.add(new SQLiteLedger.BankPendingRow(UUID.randomUUID().toString(),
                            UUID.fromString(key), item));
                }
                pendingReturns.put(UUID.fromString(key), legacyRows);
            }
        }
    }

    /**
     * Создаёт детерминированный полный снимок банковского состояния для одного SQLite-коммита.
     * Вызывается только из синхронизированных операций BankService.
     */
    private SQLiteLedger.BankSnapshot bankSnapshot() {
        List<SQLiteLedger.BankAccountRow> accountRows = new ArrayList<>();
        for (BankAccount a : accounts.values()) {
            accountRows.add(new SQLiteLedger.BankAccountRow(a.id(), a.owner(), a.ownerName(), a.nation(),
                    a.currencyId(), a.principal(), a.term().name(), a.rateAnnual(), a.openedAt(),
                    a.maturesAt(), a.accrued(), a.lastAccrualAt(), a.status().name()));
        }
        accountRows.sort(java.util.Comparator.comparing(SQLiteLedger.BankAccountRow::id));

        List<SQLiteLedger.BankLoanRow> loanRows = new ArrayList<>();
        for (BankLoan l : loans.values()) {
            loanRows.add(new SQLiteLedger.BankLoanRow(l.id(), l.borrower(), l.borrowerName(), l.nation(),
                    l.principal(), l.currencyId(), l.rateAnnual(), l.termDays(), l.openedAt(), l.dueAt(),
                    l.repaid(), l.accrued(), l.lastAccrualAt(), l.status().name(), l.collateralType().name(),
                    l.collateralCurrency(), l.collateralItemBase64(), l.collateralValue(),
                    l.collateralAmount(), l.collateralPrice(), l.creditScoreAtOpen()));
        }
        loanRows.sort(java.util.Comparator.comparing(SQLiteLedger.BankLoanRow::id));

        java.util.Set<String> poolKeys = new java.util.TreeSet<>();
        poolKeys.addAll(pools.keySet());
        poolKeys.addAll(interestReserves.keySet());
        List<SQLiteLedger.BankPoolRow> poolRows = new ArrayList<>();
        for (String key : poolKeys) {
            int split = key.indexOf('|');
            if (split <= 0 || split == key.length() - 1) continue;
            String nation = key.substring(0, split);
            String currency = key.substring(split + 1);
            poolRows.add(new SQLiteLedger.BankPoolRow(nation, currency,
                    pools.getOrDefault(key, 0.0D), interestReserves.getOrDefault(key, 0.0D)));
        }

        java.util.Set<UUID> players = new java.util.TreeSet<>(java.util.Comparator.comparing(UUID::toString));
        players.addAll(repaidCount.keySet());
        players.addAll(defaultedCount.keySet());
        List<SQLiteLedger.BankCreditRow> creditRows = new ArrayList<>();
        for (UUID player : players) {
            creditRows.add(new SQLiteLedger.BankCreditRow(player, repaidCount.getOrDefault(player, 0),
                    defaultedCount.getOrDefault(player, 0)));
        }

        List<SQLiteLedger.BankPendingRow> pendingRows = new ArrayList<>();
        List<UUID> pendingPlayers = new ArrayList<>(pendingReturns.keySet());
        pendingPlayers.sort(java.util.Comparator.comparing(UUID::toString));
        for (UUID player : pendingPlayers) {
            pendingRows.addAll(pendingReturns.getOrDefault(player, List.of()));
        }
        List<SQLiteLedger.BankAssetLotRow> assetRows = new ArrayList<>(assetLots.values());
        assetRows.sort(java.util.Comparator.comparing(SQLiteLedger.BankAssetLotRow::lotId));
        List<SQLiteLedger.BankInterestPayableRow> payableRows = new ArrayList<>(interestPayables.values());
        payableRows.sort(java.util.Comparator.comparingLong(SQLiteLedger.BankInterestPayableRow::createdAt)
                .thenComparing(SQLiteLedger.BankInterestPayableRow::id));
        return new SQLiteLedger.BankSnapshot(accountRows, loanRows, poolRows, creditRows,
                pendingRows, assetRows, payableRows);
    }

    public synchronized boolean saveAccounts() {
        List<SQLiteLedger.BankAccountRow> rows = new ArrayList<>();
        for (BankAccount a : accounts.values()) rows.add(new SQLiteLedger.BankAccountRow(
                a.id(), a.owner(), a.ownerName(), a.nation(), a.currencyId(), a.principal(), a.term().name(),
                a.rateAnnual(), a.openedAt(), a.maturesAt(), a.accrued(), a.lastAccrualAt(), a.status().name()));
        try { ledger.replaceBankAccounts(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить банковские вклады в SQLite: " + exception.getMessage());
            return false;
        }
    }

    public synchronized boolean saveLoans() {
        List<SQLiteLedger.BankLoanRow> rows = new ArrayList<>();
        for (BankLoan l : loans.values()) rows.add(new SQLiteLedger.BankLoanRow(
                l.id(), l.borrower(), l.borrowerName(), l.nation(), l.principal(), l.currencyId(), l.rateAnnual(),
                l.termDays(), l.openedAt(), l.dueAt(), l.repaid(), l.accrued(), l.lastAccrualAt(), l.status().name(),
                l.collateralType().name(), l.collateralCurrency(), l.collateralItemBase64(),
                l.collateralValue(), l.collateralAmount(), l.collateralPrice(), l.creditScoreAtOpen()));
        try { ledger.replaceBankLoans(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить банковские кредиты в SQLite: " + exception.getMessage());
            return false;
        }
    }

    private synchronized boolean savePools() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        keys.addAll(pools.keySet()); keys.addAll(interestReserves.keySet());
        List<SQLiteLedger.BankPoolRow> rows = new ArrayList<>();
        for (String key : keys) {
            int split = key.indexOf('|');
            if (split <= 0 || split == key.length() - 1) continue;
            String nation = key.substring(0, split);
            String currency = key.substring(split + 1);
            rows.add(new SQLiteLedger.BankPoolRow(nation, currency,
                    pools.getOrDefault(key, 0.0D), interestReserves.getOrDefault(key, 0.0D)));
        }
        try { ledger.replaceBankPools(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить банковские пулы в SQLite: " + exception.getMessage());
            return false;
        }
    }

    private synchronized boolean saveCredit() {
        java.util.Set<UUID> players = new java.util.HashSet<>();
        players.addAll(repaidCount.keySet()); players.addAll(defaultedCount.keySet());
        List<SQLiteLedger.BankCreditRow> rows = new ArrayList<>();
        for (UUID player : players) rows.add(new SQLiteLedger.BankCreditRow(player,
                repaidCount.getOrDefault(player, 0), defaultedCount.getOrDefault(player, 0)));
        try { ledger.replaceBankCredit(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить кредитную историю в SQLite: " + exception.getMessage());
            return false;
        }
    }

    private synchronized boolean savePending() {
        List<SQLiteLedger.BankPendingRow> rows = new ArrayList<>();
        for (List<SQLiteLedger.BankPendingRow> playerRows : pendingReturns.values()) {
            rows.addAll(playerRows);
        }
        try { ledger.replaceBankPending(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить ожидающие возвраты в SQLite: " + exception.getMessage());
            return false;
        }
    }

    public synchronized boolean saveAll() {
        // Все банковские операции уже сохраняются атомарными snapshot-коммитами.
        // При выключении повторно фиксируем целостный снимок одной SQLite-транзакцией,
        // не перезаписывая отдельные таблицы последовательно и не теряя asset lots.
        try {
            return wallets.applyBatchOnce("bank:shutdown-snapshot:" + UUID.randomUUID(),
                    List.of(), List.of(), List.of(), bankSnapshot(), List.of());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось атомарно сохранить банковский snapshot при выключении: "
                    + exception.getMessage());
            return false;
        }
    }

    private static String serialize(ItemStack item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) { return null; }
    }
    private static ItemStack deserialize(String b64) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(b64));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            return (ItemStack) ois.readObject();
        } catch (Exception e) { return null; }
    }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
