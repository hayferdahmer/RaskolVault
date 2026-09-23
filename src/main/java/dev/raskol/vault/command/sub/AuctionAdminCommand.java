// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.command.sub;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionBanService;
import dev.raskol.vault.auction.AuctionLot;
import dev.raskol.vault.auction.AuctionStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /rv auction admin — модерация аукциона (1.2.5-a.3).
 * Подкоманды:
 *   remove <lotId> <причина> — снять лот
 *   ban <ник> <причина> — запретить игроку выставлять лоты
 *   unban <ник> — снять бан
 *   bans — список банов
 *   stats — статистика (топы, объём)
 *   blacklist — показать чёрный список материалов
 */
public final class AuctionAdminCommand {

    private final RaskolVault plugin;

    public AuctionAdminCommand(RaskolVault plugin) { this.plugin = plugin; }

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
            case "remove" -> remove(sender, args);
            case "ban" -> ban(sender, args);
            case "unban" -> unban(sender, args);
            case "bans" -> listBans(sender);
            case "stats" -> stats(sender, args);
            case "blacklist" -> blacklist(sender);
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        send(sender, "&6=== /rv auction admin ===");
        send(sender, "&f/rv auction admin remove <lotId> <причина>");
        send(sender, "&f/rv auction admin ban <ник> <причина>");
        send(sender, "&f/rv auction admin unban <ник>");
        send(sender, "&f/rv auction admin bans &7— список банов");
        send(sender, "&f/rv auction admin stats [top|recent|sellers]");
        send(sender, "&f/rv auction admin blacklist");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 4) { send(sender, "&f/rv auction admin remove <lotId> <причина...>"); return; }
        String lotId = findLotId(args[2]);
        if (lotId == null) { send(sender, "&cЛот не найден (попробуйте полный UUID)"); return; }
        StringBuilder reason = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
            if (reason.length() > 0) reason.append(' ');
            reason.append(args[i]);
        }
        UUID admin = (sender instanceof Player p) ? p.getUniqueId() : new UUID(0, 0);
        String r = plugin.getAuctionService().adminRemove(admin, lotId, reason.toString());
        if (r != null) send(sender, "&c" + r);
    }

    private void ban(CommandSender sender, String[] args) {
        if (args.length < 4) { send(sender, "&f/rv auction admin ban <ник> <причина...>"); return; }
        Player target = Bukkit.getPlayerExact(args[2]);
        UUID uuid;
        String name;
        if (target != null) {
            uuid = target.getUniqueId();
            name = target.getName();
        } else {
            var reg = plugin.getOfflinePlayerRegistry();
            uuid = reg == null ? null : reg.resolveUuid(args[2]);
            if (uuid == null) { send(sender, "&cИгрок не найден"); return; }
            name = args[2];
        }
        StringBuilder reason = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
            if (reason.length() > 0) reason.append(' ');
            reason.append(args[i]);
        }
        String bannedBy = sender.getName();
        plugin.getAuctionService().getBans().ban(uuid, name, reason.toString(), bannedBy);
        send(sender, "&aИгрок &f" + name + " &aзабанен на аукционе. Причина: &7" + reason);
        if (target != null) target.sendMessage(c(plugin.getMessages().prefix()
                + "&cВам запрещено выставлять лоты. Причина: &7" + reason));
    }

    private void unban(CommandSender sender, String[] args) {
        if (args.length < 3) { send(sender, "&f/rv auction admin unban <ник>"); return; }
        UUID uuid = resolveUuid(args[2]);
        if (uuid == null) { send(sender, "&cИгрок не найден"); return; }
        plugin.getAuctionService().getBans().unban(uuid);
        send(sender, "&aБан снят с &f" + args[2]);
    }

    private void listBans(CommandSender sender) {
        var bans = plugin.getAuctionService().getBans().allBans();
        if (bans.isEmpty()) { send(sender, "&7Банов нет"); return; }
        send(sender, "&6=== Баны аукциона (" + bans.size() + ") ===");
        for (AuctionBanService.BanEntry b : bans) {
            send(sender, "&7- &f" + b.playerName() + " &7| причина: &f" + b.reason()
                    + " &7| модератор: &f" + b.bannedBy());
        }
    }

    private void stats(CommandSender sender, String[] args) {
        AuctionStats s = plugin.getAuctionService().getStats();
        String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "overview";
        switch (mode) {
            case "sellers" -> {
                var list = s.topSellers(10);
                send(sender, "&6=== Топ-10 продавцов по объёму ===");
                if (list.isEmpty()) { send(sender, "&7Пока нет продаж"); return; }
                int i = 1;
                for (AuctionStats.SellerTotal t : list) {
                    send(sender, "&f" + (i++) + ". &6" + t.name() + " &7— &f" + fmt(t.totalVolume())
                            + " GLD &7(сделок: &f" + t.salesCount() + "&7)");
                }
            }
            case "top" -> {
                var list = s.topLots(10);
                send(sender, "&6=== Топ-10 самых дорогих сделок ===");
                if (list.isEmpty()) { send(sender, "&7Пока нет продаж"); return; }
                int i = 1;
                for (AuctionStats.SaleRecord r : list) {
                    send(sender, "&f" + (i++) + ". &6" + r.itemName() + " &7— &f" + fmt(r.amount())
                            + " " + r.currencyId() + " &7(" + r.sellerName() + " → " + r.buyerName() + ")");
                }
            }
            case "recent" -> {
                var list = s.recentSales(10);
                send(sender, "&6=== 10 последних сделок ===");
                if (list.isEmpty()) { send(sender, "&7Пока нет продаж"); return; }
                for (AuctionStats.SaleRecord r : list) {
                    send(sender, "&7- &6" + r.itemName() + " &7— &f" + fmt(r.amount())
                            + " " + r.currencyId() + " &7(" + r.sellerName() + " → " + r.buyerName() + ")");
                }
            }
            default -> {
                send(sender, "&6=== Статистика аукциона ===");
                send(sender, "&7Всего сделок: &f" + s.totalSales());
                send(sender, "&7Общий объём: &f" + fmt(s.totalVolume()) + " GLD");
                send(sender, "&7Активных лотов: &f" + plugin.getAuctionService().activeCount());
                send(sender, "&7Забанено игроков: &f" + plugin.getAuctionService().getBans().bannedCount());
                send(sender, "&7Подсказка: используйте &fstats sellers/top/recent");
            }
        }
    }

    private void blacklist(CommandSender sender) {
        var list = plugin.getAuctionService().getBans().blacklistConfig();
        send(sender, "&6=== Чёрный список предметов ===");
        if (list.isEmpty()) {
            send(sender, "&7Список пуст (настраивается в &fauction.blacklist-items&7 в config.yml)");
            return;
        }
        for (String m : list) send(sender, "&7- &f" + m);
    }

    /** Ищет лот по полному UUID или по префиксу (≥4 символов). */
    private String findLotId(String input) {
        AuctionLot exact = plugin.getAuctionService().get(input);
        if (exact != null) return exact.id();
        if (input.length() < 4) return null;
        String lower = input.toLowerCase(Locale.ROOT);
        String found = null;
        int count = 0;
        for (AuctionLot l : plugin.getAuctionService().listActive()) {
            if (l.id().toLowerCase(Locale.ROOT).startsWith(lower)) {
                found = l.id(); count++;
            }
        }
        if (count == 1) return found;
        if (count > 1) return null; // неоднозначно
        return null;
    }

    private UUID resolveUuid(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        var reg = plugin.getOfflinePlayerRegistry();
        return reg == null ? null : reg.resolveUuid(name);
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("raskolvault.admin")) return Collections.emptyList();
        if (args.length == 2) {
            return filter(Arrays.asList("remove", "ban", "unban", "bans", "stats", "blacklist"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            if (sub.equals("remove")) {
                List<String> ids = new ArrayList<>();
                for (AuctionLot l : plugin.getAuctionService().listActive())
                    ids.add(l.id().substring(0, 8));
                return filter(ids, args[2]);
            }
            if (sub.equals("stats")) return filter(Arrays.asList("overview", "sellers", "top", "recent"), args[2]);
            if (sub.equals("ban") || sub.equals("unban")) {
                var reg = plugin.getOfflinePlayerRegistry();
                return reg == null ? Collections.emptyList() : reg.matchNames(args[2], 50);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> cand, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : cand) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
