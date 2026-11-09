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
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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
    private final Map<UUID, Integer> repaidCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> defaultedCount = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> pendingReturns = new ConcurrentHashMap<>();

    public BankService(RaskolVault plugin, WalletService wallets, ReserveBank bank) {
        this.plugin = plugin;
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

    public double totalOutstandingLoans(String nation) {
        long now = System.currentTimeMillis();
        double sum = 0;
        for (BankLoan l : loans.values())
            if (l.nation().equalsIgnoreCase(nation) && l.isActive()) sum += l.outstanding(now) * priceInGld(l.currencyId());
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

        double payout;
        double interest = 0.0D;
        if (staged.isDemand() || staged.isMatured(now)) {
            if (pool(a.nation(), a.currencyId()) < staged.principal()) {
                return "недостаточно ликвидности в банке — обратитесь к королю нации";
            }
            double availableInterest = interestReserve(a.nation(), a.currencyId());
            interest = Math.min(staged.accrued(), Math.max(0.0D, availableInterest));
            payout = staged.principal() + interest;
        } else {
            double penalty = staged.earlyPenalty(params(a.nation()).earlyPenaltyRate());
            payout = Math.max(0.0D, staged.principal() - penalty);
        }

        String key = poolKey(staged.nation(), staged.currencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterestReserve = interestReserves.getOrDefault(key, 0.0D);
        pools.put(key, Math.max(0.0D, oldPool - staged.principal()));
        interestReserves.put(key, Math.max(0.0D, oldInterestReserve - interest));
        staged.payOutAccrued();
        staged.close();
        accounts.put(accountId, staged);

        List<WalletService.WalletDelta> walletChanges = payout > 0.0D
                ? List.of(new WalletService.WalletDelta(owner, staged.currencyId(), payout)) : List.of();
        List<Transaction> transactions = payout > 0.0D
                ? List.of(new Transaction(-1L, now, null, owner, staged.currencyId(), payout,
                        TransactionType.PAY, "bank:deposit:close:" + accountId, null)) : List.of();
        boolean committed = wallets.applyBatchOnce("bank:deposit:close:" + accountId,
                walletChanges, List.of(), List.of(), bankSnapshot(), transactions);
        if (!committed) {
            accounts.put(accountId, a);
            if (oldPool == 0.0D) pools.remove(key); else pools.put(key, oldPool);
            if (oldInterestReserve == 0.0D) interestReserves.remove(key);
            else interestReserves.put(key, oldInterestReserve);
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
        if (amount > unsecuredMax(borrower, nation))
            return "лимит необеспеченного кредита: " + fmt(unsecuredMax(borrower, nation)) + " (повышайте кредитный скор)";
        if (totalOutstandingLoans(nation) + amount * priceInGld(currency) > maxLoans(nation))
            return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";
        double rate = p.loanRate() + p.unsecuredPremium() + creditRateBonus(borrower);
        String loanId = UUID.randomUUID().toString();
        return createLoan(loanId, borrower, borrowerName, nation, currency, amount, termDays, rate,
                BankLoan.CollateralType.NONE, null, null, 0.0D);
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
        BankParams p = params(nation);
        if (totalOutstandingLoans(nation) + amount * priceInGld(currency) > maxLoans(nation))
            return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";
        double required = amount * p.collateralRatio();
        double collateralValue = 0;
        String itemB64 = null;
        String loanId = UUID.randomUUID().toString();
        if (collateralType == BankLoan.CollateralType.CURRENCY) {
            if (collateralCurrency == null) return "укажите валюту залога";
            if (collateralCurrency.equalsIgnoreCase(currency)) return "нельзя брать кредит под залог той же валюты";
            if (!wallets.has(borrower, collateralCurrency, required)) return "недостаточно залога";
            collateralValue = required;
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
                collateralType, collateralCurrency, itemB64, collateralValue);
    }

    private synchronized String createLoan(String id, UUID borrower, String borrowerName,
                              String nation, String currency, double amount, int termDays, double rate,
                              BankLoan.CollateralType ctype, String ccur, String citem, double cvalue) {
        long now = System.currentTimeMillis();
        long due = now + termDays * 86_400_000L;
        if (id == null || id.isBlank() || !Double.isFinite(amount) || !(amount > 0.0D)
                || !Double.isFinite(rate) || rate < 0.0D) {
            return "некорректные параметры кредита";
        }
        String key = poolKey(nation, currency);
        double oldPool = pools.getOrDefault(key, 0.0D);
        if (oldPool < amount) return "недостаточно ликвидности в пуле";
        BankLoan loan = new BankLoan(id, borrower, borrowerName, nation, amount, currency, rate, termDays,
                now, due, ctype, ccur, citem, cvalue, creditScore(borrower));
        loans.put(id, loan);
        pools.put(key, oldPool - amount);

        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        walletChanges.add(new WalletService.WalletDelta(borrower, currency, amount));
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(new Transaction(-1L, now, null, borrower, currency, amount,
                TransactionType.PAY, "bank:loan:disburse:" + id, null));
        if (ctype == BankLoan.CollateralType.CURRENCY && ccur != null && cvalue > 0.0D) {
            walletChanges.add(new WalletService.WalletDelta(borrower, ccur, -cvalue));
            transactions.add(new Transaction(-1L, now, borrower, null, ccur, cvalue,
                    TransactionType.PAY, "bank:collateral:lock:" + id, null));
        }

        boolean committed = wallets.applyBatchOnce("bank:loan:create:" + id,
                walletChanges, List.of(), List.of(), bankSnapshot(), transactions);
        if (!committed) {
            loans.remove(id);
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
        if (!l.isActive()) return "кредит закрыт";
        long now = System.currentTimeMillis();
        BankLoan staged = new BankLoan(l.id(), l.borrower(), l.borrowerName(), l.nation(),
                l.principal(), l.currencyId(), l.rateAnnual(), l.termDays(), l.openedAt(), l.dueAt(),
                l.collateralType(), l.collateralCurrency(), l.collateralItemBase64(),
                l.collateralValue(), l.creditScoreAtOpen());
        staged.setRepaid(l.repaid());
        staged.setAccrued(l.accrued());
        staged.setLastAccrualAt(l.lastAccrualAt());
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

        List<WalletService.WalletDelta> walletChanges = new ArrayList<>();
        walletChanges.add(new WalletService.WalletDelta(borrower, l.currencyId(), -pay));
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(new Transaction(-1L, now, borrower, null, l.currencyId(), pay,
                TransactionType.PAY, "bank:loan:repay:" + loanId, null));
        if (fullyRepaid && l.collateralType() == BankLoan.CollateralType.CURRENCY
                && l.collateralCurrency() != null && l.collateralValue() > 0.0D) {
            walletChanges.add(new WalletService.WalletDelta(borrower, l.collateralCurrency(), l.collateralValue()));
            transactions.add(new Transaction(-1L, now, null, borrower, l.collateralCurrency(),
                    l.collateralValue(), TransactionType.PAY, "bank:collateral:release:" + loanId, null));
        }

        boolean pendingCollateralReturn = false;
        List<String> previousPending = null;
        boolean onlineCollateralOwner = false;
        if (fullyRepaid && l.collateralType() == BankLoan.CollateralType.ITEM) {
            Player collateralOwner = plugin.getServer().getPlayer(borrower);
            onlineCollateralOwner = collateralOwner != null && collateralOwner.isOnline();
            if (!onlineCollateralOwner) {
                previousPending = new ArrayList<>(pendingReturns.getOrDefault(borrower, List.of()));
                pendingReturns.computeIfAbsent(borrower, ignored -> new ArrayList<>())
                        .add(l.collateralItemBase64());
                pendingCollateralReturn = true;
            }
        }

        String operationId = "bank:loan:repay:" + loanId + ":" + UUID.randomUUID();
        boolean committed = wallets.applyBatchOnce(operationId, walletChanges, List.of(),
                List.of(), bankSnapshot(), transactions);
        if (!committed) {
            loans.put(loanId, l);
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

        if (fullyRepaid && l.collateralType() == BankLoan.CollateralType.ITEM && onlineCollateralOwner) {
            releaseCollateral(l);
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
                l.collateralValue(), l.creditScoreAtOpen());
        staged.setRepaid(l.repaid());
        staged.setAccrued(l.accrued());
        staged.setLastAccrualAt(l.lastAccrualAt());
        staged.outstanding(now);
        double proceeds = l.collateralValue();
        // Сначала покрываем начисленные проценты, затем основной долг.
        double toInterest = Math.min(staged.accrued(), proceeds);
        double afterInterest = Math.max(0.0D, proceeds - toInterest);
        double principalRecovery = Math.min(staged.principal(), afterInterest);
        double remainder = Math.max(0.0D, afterInterest - principalRecovery);
        String key = poolKey(l.nation(), l.currencyId());
        double oldPool = pools.getOrDefault(key, 0.0D);
        double oldInterestReserve = interestReserves.getOrDefault(key, 0.0D);
        int oldDefaultedCount = defaultedCount.getOrDefault(l.borrower(), 0);

        staged.markLiquidated();
        loans.put(loanId, staged);
        pools.put(key, oldPool + principalRecovery + remainder);
        interestReserves.put(key, oldInterestReserve + toInterest);
        defaultedCount.put(l.borrower(), oldDefaultedCount + 1);

        boolean committed = wallets.applyBatchOnce("bank:loan:liquidate:" + loanId,
                List.of(), List.of(), List.of(), bankSnapshot(), List.of());
        if (!committed) {
            loans.put(loanId, l);
            pools.put(key, oldPool);
            if (oldInterestReserve == 0.0D) interestReserves.remove(key);
            else interestReserves.put(key, oldInterestReserve);
            if (oldDefaultedCount == 0) defaultedCount.remove(l.borrower());
            else defaultedCount.put(l.borrower(), oldDefaultedCount);
            return "не удалось ликвидировать кредит: транзакция не подтверждена";
        }
        return null;
    }

    public synchronized int autoLiquidateOverdue() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (BankLoan l : new ArrayList<>(loans.values())) {
            if (l.isOverdue(now)) { liquidate(l.id()); count++; }
        }
        return count;
    }

    public synchronized void accrueAll() {
        long now = System.currentTimeMillis();
        for (BankAccount a : accounts.values()) if (a.isActive()) a.accrue(now, params(a.nation()).demandRate());
        for (BankLoan l : loans.values()) if (l.isActive()) l.accrueTo(now);
        saveAccounts();
        saveLoans();
    }

    private void releaseCollateral(BankLoan l) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> releaseCollateral(l));
            return;
        }
        // Валютный залог возвращается внутри основного SQLite-коммита погашения.
        // Здесь остаётся только Bukkit-доставка предметного залога.
        if (l.collateralType() == BankLoan.CollateralType.ITEM && l.collateralItemBase64() != null) {
            Player p = plugin.getServer().getPlayer(l.borrower());
            if (p != null && p.isOnline()) {
                ItemStack item = deserialize(l.collateralItemBase64());
                if (item != null) {
                    var ov = p.getInventory().addItem(item);
                    for (ItemStack left : ov.values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
            } else {
                pendingReturns.computeIfAbsent(l.borrower(), k -> new ArrayList<>()).add(l.collateralItemBase64());
                savePending();
            }
        }
    }

    @EventHandler
    public synchronized void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        List<String> b64 = pendingReturns.remove(u);
        if (b64 == null || b64.isEmpty()) return;
        for (String b : b64) {
            ItemStack it = deserialize(b);
            if (it != null) {
                var ov = e.getPlayer().getInventory().addItem(it);
                for (ItemStack left : ov.values()) e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), left);
            }
        }
        savePending();
        e.getPlayer().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                plugin.getMessages().prefix() + "&aВам возвращён залог по погашенному кредиту"));
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

    // ---------- персистентность ----------
    public synchronized void load() {
        accounts.clear(); loans.clear(); pools.clear(); interestReserves.clear();
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
                    row.collateralCurrency(), row.collateralItem(), row.collateralValue(), row.creditScoreAtOpen());
            loan.setRepaid(row.repaid());
            loan.setAccrued(row.accrued());
            loan.setLastAccrualAt(row.lastAccrualAt());
            switch (row.status()) {
                case "REPAID" -> loan.markRepaid();
                case "DEFAULTED" -> loan.markDefaulted();
                case "LIQUIDATED" -> loan.markLiquidated();
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
            pendingReturns.computeIfAbsent(row.player(), ignored -> new ArrayList<>()).add(row.itemBase64());
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
                        section.getDouble("collateralValue", 0.0D), section.getInt("creditScore", 0));
                loan.setRepaid(section.getDouble("repaid", 0.0D));
                loan.setAccrued(section.getDouble("accrued", 0.0D));
                loan.setLastAccrualAt(section.getLong("lastAccrualAt", loan.openedAt()));
                switch (section.getString("status", "ACTIVE")) {
                    case "REPAID" -> loan.markRepaid();
                    case "DEFAULTED" -> loan.markDefaulted();
                    case "LIQUIDATED" -> loan.markLiquidated();
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
                pendingReturns.put(UUID.fromString(key), new ArrayList<>(pendingSection.getStringList(key)));
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
                    l.collateralCurrency(), l.collateralItemBase64(), l.collateralValue(), l.creditScoreAtOpen()));
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
            for (String item : pendingReturns.getOrDefault(player, List.of())) {
                pendingRows.add(new SQLiteLedger.BankPendingRow(player, item));
            }
        }
        return new SQLiteLedger.BankSnapshot(accountRows, loanRows, poolRows, creditRows, pendingRows);
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
                l.collateralValue(), l.creditScoreAtOpen()));
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
        for (Map.Entry<UUID, List<String>> entry : pendingReturns.entrySet()) {
            for (String item : entry.getValue()) rows.add(new SQLiteLedger.BankPendingRow(entry.getKey(), item));
        }
        try { ledger.replaceBankPending(rows); return true; }
        catch (RuntimeException exception) {
            plugin.getLogger().severe("Не удалось сохранить ожидающие возвраты в SQLite: " + exception.getMessage());
            return false;
        }
    }

    public synchronized boolean saveAll() {
        return saveAccounts() && saveLoans() && savePools() && saveCredit() && savePending();
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
