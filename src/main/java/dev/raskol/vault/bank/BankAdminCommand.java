// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.RaskolVault;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /rv bank admin — админка банка (1.2.5-b).
 *   setparam <нация> <key> <value>
 *   liquidate <loanId>
 *   stats [нация]
 */
public final class BankAdminCommand {

    private final RaskolVault plugin;

    public BankAdminCommand(RaskolVault plugin) { this.plugin = plugin; }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void send(CommandSender s, String raw) { s.sendMessage(c(raw)); }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) {
            send(sender, plugin.getMessages().get("error.no-permission", null));
            return;
        }
        if (args.length < 2) { help(sender); return; }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setparam" -> setparam(sender, args);
            case "liquidate" -> liquidate(sender, args);
            case "stats" -> stats(sender, args);
            case "assets" -> assets(sender);
            case "realize" -> realize(sender, args);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== /rv bank admin ===");
        send(sender, "&f/rv bank admin setparam <нация> <key> <value>");
        send(sender, "&7  keys: demand-rate, rate-7, rate-30, rate-90, loan-rate, collateral-ratio, reserve-multiplier, early-penalty");
        send(sender, "&f/rv bank admin liquidate <loanId>");
        send(sender, "&f/rv bank admin stats [нация]");
        send(sender, "&f/rv bank admin assets");
        send(sender, "&f/rv bank admin realize <lotId> <фактическая сумма>");
    }

    private void setparam(CommandSender sender, String[] args) {
        if (args.length < 5) { help(sender); return; }
        String nation = args[2];
        String key = args[3];
        double v;
        try { v = Double.parseDouble(args[4]); } catch (NumberFormatException e) { send(sender, "&cЧисло"); return; }
        plugin.getBankService().setParam(nation, key, v);
        send(sender, "&a" + nation + " · " + key + " = &f" + v);
    }

    private void liquidate(CommandSender sender, String[] args) {
        if (args.length < 3) { send(sender, "&f/rv bank admin liquidate <loanId>"); return; }
        String err = plugin.getBankService().liquidate(args[2]);
        send(sender, err == null ? "&aЗалог ликвидирован" : "&c" + err);
    }

    private void assets(CommandSender sender) {
        List<dev.raskol.vault.storage.SQLiteLedger.BankAssetLotRow> lots =
                plugin.getBankService().collateralAssetLots();
        send(sender, "&6=== Реестр залоговых лотов банка: " + lots.size() + " ===");
        if (lots.isEmpty()) {
            send(sender, "&7Залоговых лотов нет.");
            return;
        }
        for (var lot : lots) {
            String asset = "CURRENCY".equals(lot.assetType())
                    ? fmt(lot.amount()) + " " + lot.currencyId()
                    : "ITEM · " + lot.sourceLoanId();
            send(sender, "&f" + lot.lotId() + " &7| " + lot.status() + " &7| "
                    + asset + " &7| оценка " + fmt(lot.lockedValue()) + " " + lot.valuationCurrencyId());
        }
        send(sender, "&7Реализация: /rv bank admin realize <lotId> <фактическая сумма>");
    }

    private void realize(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "&f/rv bank admin realize <lotId> <фактическая сумма>");
            return;
        }
        double proceeds;
        try { proceeds = Double.parseDouble(args[3]); }
        catch (NumberFormatException exception) { send(sender, "&cСумма должна быть числом."); return; }
        String error = plugin.getBankService().realizeCollateralAsset(args[2], proceeds);
        send(sender, error == null
                ? "&aФактическая реализация залога учтена в банковском балансе."
                : "&c" + error);
    }

    private void stats(CommandSender sender, String[] args) {
        String nation = args.length >= 3 ? args[2] : null;
        if (nation == null) {
            send(sender, "&6=== Банк: сводка ===");
            send(sender, "&7Укажите нацию: &f/rv bank admin stats <нация>");
            return;
        }
        BankService b = plugin.getBankService();
        String glb = plugin.getCurrencies().globalId();
        send(sender, "&6=== Банк · " + nation + " ===");
        send(sender, "&7Лимит кредитного портфеля (GLD): &f" + fmt(b.maxLoans(nation)) + " " + glb);
        send(sender, "&7Кредитный портфель по текущему курсу (GLD): &f"
                + fmt(b.totalOutstandingLoans(nation)) + " " + glb);
        send(sender, "&7Активных кредитов: &f" + b.activeLoans(nation).size());
        send(sender, "&7Кредитов во взыскании: &c" + b.recoveryLoans(nation).size());
        send(sender, "&6Баланс банка по валютам (сверка):");
        for (BankBalanceSheet sheet : b.balanceSheets(nation)) {
            String currency = sheet.currencyId();
            send(sender, "&e" + currency + "&7 — ликвидность: &f" + fmt(sheet.cashAssets())
                    + " &7(пул " + fmt(sheet.liquidityPool()) + " + процентный резерв "
                    + fmt(sheet.interestReserve()) + ")");
            send(sender, "&7  Кредиты: &f" + fmt(sheet.loanReceivables())
                    + " &7(тело " + fmt(sheet.loanPrincipal()) + " + проценты "
                    + fmt(sheet.loanAccruedInterest()) + ")");
            send(sender, "&7  Обязательства: &f" + fmt(sheet.totalLiabilities())
                    + " &7(вклады " + fmt(sheet.depositLiabilities()) + " + требования процентов "
                    + fmt(sheet.unpaidInterestClaims()) + ")");
            send(sender, "&7  Активы: &f" + fmt(sheet.totalAssets())
                    + " &7| собственный капитал: " + (sheet.equity() < -0.000001D ? "&c" : "&a")
                    + fmt(sheet.equity()) + " " + currency);
            if (sheet.heldCollateralLots() > 0) {
                send(sender, "&7  Удерживаемый залог (справочно, не актив): &f"
                        + fmt(sheet.heldCollateralAppraisal()) + " " + currency
                        + " &7· лотов: " + sheet.heldCollateralLots());
            }
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) return Collections.emptyList();
        if (args.length == 2) return filter(Arrays.asList("setparam", "liquidate", "stats", "assets", "realize"), args[1]);
        if (args.length == 4 && args[1].equalsIgnoreCase("setparam"))
            return filter(Arrays.asList("demand-rate", "rate-7", "rate-30", "rate-90",
                    "loan-rate", "collateral-ratio", "reserve-multiplier", "early-penalty"), args[3]);
        return Collections.emptyList();
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    private List<String> filter(List<String> cand, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();
        for (String s : cand) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
