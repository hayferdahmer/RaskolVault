// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Банк нации (1.2.5-b fix): вклады, кредиты под залог, фракционное резервирование,
 * спред как прибыль нации, кредитная история, ликвидация.
 * FIX: дни срока вычисляются локальным switch по enum Term (не term.termDays()).
 */
public final class BankService {

    public record BankParams(
            double demandRate, double r7, double r30, double r90,
            double loanRate, double collateralRatio, double reserveMultiplier,
            double earlyPenaltyRate
    ) {}

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final ReserveBank bank;
    private final File accountsFile;
    private final File loansFile;
    private final File poolsFile;
    private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, BankLoan> loans = new ConcurrentHashMap<>();
    private final Map<String, Double> pools = new ConcurrentHashMap<>();
    private final Map<String, Double> interestReserves = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> repaidCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> defaultedCount = new ConcurrentHashMap<>();

    public BankService(RaskolVault plugin, WalletService wallets, ReserveBank bank) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.bank = bank;
        this.accountsFile = new File(plugin.getDataFolder(), "data/bank-accounts.yml");
        this.loansFile = new File(plugin.getDataFolder(), "data/bank-loans.yml");
        this.poolsFile = new File(plugin.getDataFolder(), "data/bank-pools.yml");
        load();
    }

    /** FIX: дни срока из enum Term локальным switch. */
    private static int termDaysOf(BankAccount.Term term) {
        return switch (term) {
            case DEMAND -> 0;
            case TERM_7 -> 7;
            case TERM_30 -> 30;
            case TERM_90 -> 90;
        };
    }

    // ---------- параметры нации ----------
    public BankParams params(String nation) {
        String n = nation == null ? "" : nation.toLowerCase();
        double def = plugin.getConfig().getDouble("bank.defaults.demand-rate", 0.01D);
        double d7 = plugin.getConfig().getDouble("bank.defaults.rate-7", 0.02D);
        double d30 = plugin.getConfig().getDouble("bank.defaults.rate-30", 0.035D);
        double d90 = plugin.getConfig().getDouble("bank.defaults.rate-90", 0.05D);
        double loan = plugin.getConfig().getDouble("bank.defaults.loan-rate", 0.10D);
        double coll = plugin.getConfig().getDouble("bank.defaults.collateral-ratio", 1.5D);
        double mult = plugin.getConfig().getDouble("bank.defaults.reserve-multiplier", 3.0D);
        double early = plugin.getConfig().getDouble("bank.defaults.early-penalty", 0.02D);
        def = plugin.getConfig().getDouble("bank.nations." + n + ".demand-rate", def);
        d7 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-7", d7);
        d30 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-30", d30);
        d90 = plugin.getConfig().getDouble("bank.nations." + n + ".rate-90", d90);
        loan = plugin.getConfig().getDouble("bank.nations." + n + ".loan-rate", loan);
        coll = plugin.getConfig().getDouble("bank.nations." + n + ".collateral-ratio", coll);
        mult = plugin.getConfig().getDouble("bank.nations." + n + ".reserve-multiplier", mult);
        early = plugin.getConfig().getDouble("bank.nations." + n + ".early-penalty", early);
        return new BankParams(def, d7, d30, d90, loan, coll, mult, early);
    }

    public void setParam(String nation, String key, double value) {
        String n = nation.toLowerCase();
        plugin.getConfig().set("bank.nations." + n + "." + key, value);
        plugin.saveConfig();
    }

    // ---------- пул ликвидности ----------
    private String poolKey(String nation, String currency) { return nation.toLowerCase() + "|" + currency; }
    public double pool(String nation, String currency) { return pools.getOrDefault(poolKey(nation, currency), 0.0D); }
    public double interestReserve(String nation, String currency) { return interestReserves.getOrDefault(poolKey(nation, currency), 0.0D); }
    private void addPool(String nation, String currency, double d) { pools.merge(poolKey(nation, currency), d, Double::sum); savePools(); }
    private void addInterestReserve(String nation, String currency, double d) { interestReserves.merge(poolKey(nation, currency), d, Double::sum); savePools(); }

    public double maxLoans(String nation) {
        return bank.reserveOf(nation) * params(nation).reserveMultiplier();
    }
    public double totalOutstandingLoans(String nation) {
        long now = System.currentTimeMillis();
        double sum = 0;
        for (BankLoan l : loans.values())
            if (l.nation().equalsIgnoreCase(nation) && l.isActive()) sum += l.outstanding(now);
        return sum;
    }
    public double totalDeposits(String nation) {
        double sum = 0;
        for (BankAccount a : accounts.values())
            if (a.nation().equalsIgnoreCase(nation) && a.isActive()) sum += a.principal();
        return sum;
    }

    // ---------- кредитная история ----------
    public int creditScore(UUID player) {
        return repaidCount.getOrDefault(player, 0) - 2 * defaultedCount.getOrDefault(player, 0);
    }
    private double creditRateBonus(UUID player) {
        int score = creditScore(player);
        return -Math.min(0.02D, score * 0.002D) + Math.max(0.0D, -score * 0.005D);
    }

    // ---------- вклады ----------
    public String openDeposit(UUID owner, String ownerName, String nation, String currency,
                              double amount, BankAccount.Term term) {
        if (!(amount > 0.0D)) return "сумма должна быть > 0";
        if (!wallets.has(owner, currency, amount)) return "недостаточно средств";
        BankParams p = params(nation);
        double rate = switch (term) {
            case DEMAND -> p.demandRate();
            case TERM_7 -> p.r7();
            case TERM_30 -> p.r30();
            case TERM_90 -> p.r90();
        };
        if (!wallets.withdraw(owner, currency, amount, TransactionType.PAY, "bank:deposit")) return "не удалось списать";
        long now = System.currentTimeMillis();
        // FIX: дни срока через локальный switch, а не term.termDays()
        int days = termDaysOf(term);
        long matures = (term == BankAccount.Term.DEMAND) ? 0L : now + days * 86_400_000L;
        String id = UUID.randomUUID().toString();
        accounts.put(id, new BankAccount(id, owner, ownerName, nation, currency, amount, term, rate, now, matures));
        addPool(nation, currency, amount);
        saveAccounts();
        return null;
    }

    public String closeDeposit(UUID owner, String accountId) {
        BankAccount a = accounts.get(accountId);
        if (a == null || !a.owner().equals(owner)) return "вклад не найден";
        if (!a.isActive()) return "вклад уже закрыт";
        long now = System.currentTimeMillis();
        a.accrue(now, params(a.nation()).demandRate());
        double payout;
        if (a.isDemand() || a.isMatured(now)) {
            addPool(a.nation(), a.currencyId(), -a.principal());
            double ir = interestReserve(a.nation(), a.currencyId());
            double interest = Math.min(a.accrued(), Math.max(0, ir));
            addInterestReserve(a.nation(), a.currencyId(), -interest);
            payout = a.principal() + interest;
        } else {
            double penalty = a.earlyPenalty(params(a.nation()).earlyPenaltyRate());
            payout = Math.max(0, a.principal() - penalty);
            addPool(a.nation(), a.currencyId(), -a.principal());
        }
        a.payOutAccrued();
        a.close();
        if (payout > 0) wallets.deposit(owner, a.currencyId(), payout, TransactionType.PAY, "bank:withdraw");
        saveAccounts();
        return null;
    }

    // ---------- кредиты ----------
    public String applyLoan(UUID borrower, String borrowerName, String nation, String currency,
                            double amount, int termDays,
                            BankLoan.CollateralType collateralType, String collateralCurrency,
                            ItemStack collateralItem) {
        if (!(amount > 0.0D)) return "сумма должна быть > 0";
        if (termDays != 7 && termDays != 30 && termDays != 90) return "срок: 7/30/90 дней";
        BankParams p = params(nation);
        if (totalOutstandingLoans(nation) + amount > maxLoans(nation)) return "банк исчерпал лимит выдачи (резерв)";
        if (pool(nation, currency) < amount) return "недостаточно ликвидности в пуле";
        double required = amount * p.collateralRatio();
        double collateralValue = 0;
        String itemB64 = null;
        if (collateralType == BankLoan.CollateralType.CURRENCY) {
            if (collateralCurrency == null) return "укажите валюту залога";
            if (!wallets.has(borrower, collateralCurrency, required)) return "недостаточно залога (" + fmt(required) + " " + collateralCurrency + ")";
            collateralValue = required;
            if (!wallets.withdraw(borrower, collateralCurrency, required, TransactionType.PAY, "bank:collateral:lock")) return "не удалось заблокировать залог";
        } else {
            if (collateralItem == null) return "предмет залога не передан";
            collateralValue = appraisal(collateralItem);
            if (collateralValue < required) return "оценка залога " + fmt(collateralValue) + " < требуемых " + fmt(required);
            itemB64 = serialize(collateralItem);
        }
        double rate = p.loanRate() + creditRateBonus(borrower);
        long now = System.currentTimeMillis();
        long due = now + termDays * 86_400_000L;
        String id = UUID.randomUUID().toString();
        loans.put(id, new BankLoan(id, borrower, borrowerName, nation, amount, currency, rate, termDays,
                now, due, collateralType, collateralCurrency, itemB64, collateralValue, creditScore(borrower)));
        addPool(nation, currency, -amount);
        wallets.deposit(borrower, currency, amount, TransactionType.PAY, "bank:loan:disburse");
        saveLoans();
        return null;
    }

    public String repayLoan(UUID borrower, String loanId, double amount) {
        BankLoan l = loans.get(loanId);
        if (l == null || !l.borrower().equals(borrower)) return "кредит не найден";
        if (!l.isActive()) return "кредит закрыт";
        long now = System.currentTimeMillis();
        double out = l.outstanding(now);
        double pay = Math.min(amount, out);
        if (!(pay > 0.0D)) return "нечего погашать";
        if (!wallets.has(borrower, l.currencyId(), pay)) return "недостаточно средств";
        if (!wallets.withdraw(borrower, l.currencyId(), pay, TransactionType.PAY, "bank:loan:repay")) return "не удалось списать";
        double interestPart = Math.min(pay, l.accruedInterest(now));
        double principalPart = pay - interestPart;
        addInterestReserve(l.nation(), l.currencyId(), interestPart);
        addPool(l.nation(), l.currencyId(), principalPart);
        l.addRepayment(pay);
        if (l.outstanding(now) <= 1e-6D) {
            l.markRepaid();
            repaidCount.merge(borrower, 1, Integer::sum);
            releaseCollateral(l);
        }
        saveLoans();
        return null;
    }

    public String liquidate(String loanId) {
        BankLoan l = loans.get(loanId);
        if (l == null) return "кредит не найден";
        if (!l.isActive()) return "кредит закрыт";
        long now = System.currentTimeMillis();
        double out = l.outstanding(now);
        double proceeds = l.collateralValue();
        double toInterest = Math.min(out, proceeds);
        double remainder = Math.max(0, proceeds - toInterest);
        addInterestReserve(l.nation(), l.currencyId(), toInterest);
        addPool(l.nation(), l.currencyId(), remainder);
        l.markLiquidated();
        defaultedCount.merge(l.borrower(), 1, Integer::sum);
        saveLoans();
        return null;
    }

    public int autoLiquidateOverdue() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (BankLoan l : new ArrayList<>(loans.values())) {
            if (l.isOverdue(now)) {
                liquidate(l.id());
                count++;
            }
        }
        return count;
    }

    public void accrueAll() {
        long now = System.currentTimeMillis();
        for (BankAccount a : accounts.values()) {
            if (a.isActive()) a.accrue(now, params(a.nation()).demandRate());
        }
        saveAccounts();
    }

    private void releaseCollateral(BankLoan l) {
        if (l.collateralType() == BankLoan.CollateralType.CURRENCY && l.collateralCurrency() != null) {
            wallets.deposit(l.borrower(), l.collateralCurrency(), l.collateralValue(),
                    TransactionType.PAY, "bank:collateral:release");
        }
        if (l.collateralType() == BankLoan.CollateralType.ITEM && l.collateralItemBase64() != null) {
            ItemStack item = deserialize(l.collateralItemBase64());
            if (item != null) {
                org.bukkit.entity.Player p = plugin.getServer().getPlayer(l.borrower());
                if (p != null) {
                    var overflow = p.getInventory().addItem(item);
                    for (ItemStack left : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
            }
        }
    }

    public double appraisal(ItemStack item) {
        if (item == null) return 0.0D;
        double unit = plugin.getConfig().getDouble("bank.appraisal." + item.getType().name(), 0.0D);
        return unit * item.getAmount();
    }

    // ---------- запросы ----------
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
    public void load() {
        for (File f : new File[]{accountsFile, loansFile, poolsFile})
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
        if (accountsFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(accountsFile);
            ConfigurationSection r = y.getConfigurationSection("accounts");
            if (r != null) for (String id : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(id);
                if (s == null) continue;
                BankAccount a = new BankAccount(id,
                        UUID.fromString(s.getString("owner")), s.getString("ownerName", ""),
                        s.getString("nation", ""), s.getString("currency", "GLD"),
                        s.getDouble("principal", 0), BankAccount.Term.valueOf(s.getString("term", "DEMAND")),
                        s.getDouble("rate", 0), s.getLong("openedAt", 0), s.getLong("maturesAt", 0));
                if ("CLOSED".equals(s.getString("status", "ACTIVE"))) a.close();
                accounts.put(id, a);
            }
        }
        if (loansFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(loansFile);
            ConfigurationSection r = y.getConfigurationSection("loans");
            if (r != null) for (String id : r.getKeys(false)) {
                ConfigurationSection s = r.getConfigurationSection(id);
                if (s == null) continue;
                BankLoan l = new BankLoan(id,
                        UUID.fromString(s.getString("borrower")), s.getString("borrowerName", ""),
                        s.getString("nation", ""), s.getDouble("principal", 0),
                        s.getString("currency", "GLD"), s.getDouble("rate", 0), s.getInt("termDays", 30),
                        s.getLong("openedAt", 0), s.getLong("dueAt", 0),
                        BankLoan.CollateralType.valueOf(s.getString("collateralType", "CURRENCY")),
                        s.getString("collateralCurrency", null), s.getString("collateralItem", null),
                        s.getDouble("collateralValue", 0), s.getInt("creditScore", 0));
                l.addRepayment(s.getDouble("repaid", 0));
                String st = s.getString("status", "ACTIVE");
                if ("REPAID".equals(st)) l.markRepaid();
                else if ("DEFAULTED".equals(st)) l.markDefaulted();
                else if ("LIQUIDATED".equals(st)) l.markLiquidated();
                loans.put(id, l);
            }
        }
        if (poolsFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(poolsFile);
            ConfigurationSection p = y.getConfigurationSection("pools");
            if (p != null) for (String k : p.getKeys(false)) pools.put(k, p.getDouble(k, 0));
            ConfigurationSection ir = y.getConfigurationSection("interest");
            if (ir != null) for (String k : ir.getKeys(false)) interestReserves.put(k, ir.getDouble(k, 0));
        }
    }

    public void saveAccounts() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection r = y.createSection("accounts");
        for (BankAccount a : accounts.values()) {
            ConfigurationSection s = r.createSection(a.id());
            s.set("owner", a.owner().toString());
            s.set("ownerName", a.ownerName());
            s.set("nation", a.nation());
            s.set("currency", a.currencyId());
            s.set("principal", a.principal());
            s.set("term", a.term().name());
            s.set("rate", a.rateAnnual());
            s.set("openedAt", a.openedAt());
            s.set("maturesAt", a.maturesAt());
            s.set("status", a.status().name());
        }
        SafeStorage.saveAtomic(y, accountsFile, plugin);
    }

    public void saveLoans() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection r = y.createSection("loans");
        for (BankLoan l : loans.values()) {
            ConfigurationSection s = r.createSection(l.id());
            s.set("borrower", l.borrower().toString());
            s.set("borrowerName", l.borrowerName());
            s.set("nation", l.nation());
            s.set("principal", l.principal());
            s.set("currency", l.currencyId());
            s.set("rate", l.rateAnnual());
            s.set("termDays", l.termDays());
            s.set("openedAt", l.openedAt());
            s.set("dueAt", l.dueAt());
            s.set("repaid", l.repaid());
            s.set("status", l.status().name());
            s.set("collateralType", l.collateralType().name());
            s.set("collateralCurrency", l.collateralCurrency());
            s.set("collateralItem", l.collateralItemBase64());
            s.set("collateralValue", l.collateralValue());
            s.set("creditScore", l.creditScoreAtOpen());
        }
        SafeStorage.saveAtomic(y, loansFile, plugin);
    }

    private void savePools() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection p = y.createSection("pools");
        for (Map.Entry<String, Double> e : pools.entrySet()) p.set(e.getKey(), e.getValue());
        ConfigurationSection ir = y.createSection("interest");
        for (Map.Entry<String, Double> e : interestReserves.entrySet()) ir.set(e.getKey(), e.getValue());
        SafeStorage.saveAtomic(y, poolsFile, plugin);
    }

    public void saveAll() { saveAccounts(); saveLoans(); savePools(); }

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
