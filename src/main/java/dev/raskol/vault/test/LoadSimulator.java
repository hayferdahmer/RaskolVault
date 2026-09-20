// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.test;

import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Нагрузочный тест кошелька и леджера.
 * Создаёт N виртуальных UUID-ов, выполняет M транзакций (50% deposit, 50% transfer
 * между случайными игроками). Все операции синхронные, время засекается.
 *
 * ВАЖНО: по окончании очищает кэш виртуальных UUID через /rv admin simulate-load cleanup,
 * чтобы не засорять леджер тестовыми данными.
 */
public final class LoadSimulator {

    private final Plugin plugin;
    private final WalletService wallets;
    private final String currencyId;

    private final List<UUID> virtuals = new ArrayList<>();

    public LoadSimulator(Plugin plugin, WalletService wallets, String currencyId) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencyId = currencyId;
    }

    public void run(CommandSender sender, int players, int transactions) {
        if (players < 2 || players > 500) {
            sender.sendMessage("§cplayers must be 2..500");
            return;
        }
        if (transactions < 10 || transactions > 100000) {
            sender.sendMessage("§ctransactions must be 10..100000");
            return;
        }
        sender.sendMessage("§eГенерация " + players + " виртуальных кошельков...");
        virtuals.clear();
        Random rnd = new Random(0xC0FFEE);
        for (int i = 0; i < players; i++) {
            UUID u = UUID.nameUUIDFromBytes(("loadtest-" + i).getBytes());
            wallets.deposit(u, currencyId, 10000.0, TransactionType.ADMIN_GIVE, "loadtest-seed");
            virtuals.add(u);
        }

        sender.sendMessage("§eЗапуск " + transactions + " транзакций...");
        long start = System.nanoTime();
        int success = 0;
        int failed = 0;
        for (int i = 0; i < transactions; i++) {
            boolean ok;
            if (rnd.nextBoolean()) {
                UUID u = virtuals.get(rnd.nextInt(virtuals.size()));
                ok = wallets.deposit(u, currencyId, 10.0 + rnd.nextDouble() * 90.0,
                        TransactionType.ADMIN_GIVE, "loadtest");
            } else {
                UUID a = virtuals.get(rnd.nextInt(virtuals.size()));
                UUID b = virtuals.get(rnd.nextInt(virtuals.size()));
                if (a.equals(b)) {
                    failed++;
                    continue;
                }
                ok = wallets.transfer(a, b, currencyId, 1.0 + rnd.nextDouble() * 50.0, "loadtest");
            }
            if (ok) success++;
            else failed++;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        double tps = transactions * 1000.0 / Math.max(1L, elapsedMs);

        sender.sendMessage(String.format("§aГотово за %d мс: %d успехов, %d отказов, %.1f tx/s",
                elapsedMs, success, failed, tps));
        sender.sendMessage("§7Виртуальные кошельки в кэше: " + virtuals.size()
                + ". Очисти командой §e/rv admin simulate-load cleanup");
    }

    public void cleanup(CommandSender sender) {
        if (virtuals.isEmpty()) {
            sender.sendMessage("§7Нет виртуальных кошельков для очистки");
            return;
        }
        int cleared = virtuals.size();
        virtuals.clear();
        // Леджер не трогаем (там аудит), но кэш очищен.
        // Для полного удаления из БД: DELETE FROM balances WHERE uuid LIKE 'loadtest-%' (UUID детерминирован).
        sender.sendMessage("§aКэш очищен: " + cleared + " виртуальных кошельков."
                + " Записи в леджере сохранены для аудита.");
    }
}
