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
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== /rv bank admin ===");
        send(sender, "&f/rv bank admin setparam <нация> <key> <value>");
        send(sender, "&7  keys: demand-rate, rate-7, rate-30, rate-90, loan-rate, collateral-ratio, reserve-multiplier, early-penalty");
        send(sender, "&f/rv bank admin liquidate <loanId>");
        send(sender, "&f/rv bank admin stats [нация]");
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
        send(sender, "&7Пул ликвидности: &f" + fmt(b.pool(nation, glb)) + " " + glb);
        send(sender, "&7Процентный резерв: &f" + fmt(b.interestReserve(nation, glb)));
        send(sender, "&7Вклады (тело): &f" + fmt(b.totalDeposits(nation)));
        send(sender, "&7Выдано кредитов: &f" + fmt(b.totalOutstandingLoans(nation)));
        send(sender, "&7Лимит выдачи: &f" + fmt(b.maxLoans(nation)));
        send(sender, "&7Активных кредитов: &f" + b.activeLoans(nation).size());
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) return Collections.emptyList();
        if (args.length == 2) return filter(Arrays.asList("setparam", "liquidate", "stats"), args[1]);
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
