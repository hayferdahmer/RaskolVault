# RaskolVault — OPERATIONS (ранбук)

Деплой, бэкапы, restore, откат, инциденты.
Версия: 1.2.6 · Paper 1.21.4 · Java 21

---

## 1. Деплой

1. **Стоп** сервера (полный, не `/reload`).
2. Собери: `mvn clean package` → `target/raskol-vault-1.2.6.jar`.
3. Скопируй jar в `plugins/`; удали старый `raskol-vault-*.jar` иной версии.
4. **Старт**. Проверка: `/rv admin health`, TPS 20.0, консоль без ERROR.

> `/reload` не использовать — плагин держит пул SQLite и фоновые таски.

---

## 2. Файлы данных (что бэкапить)

```
plugins/RaskolVault/
├── config.yml · currencies.yml · rates.yml · messages.yml
└── data/
    ├── ledger.sqlite · ledger.sqlite-wal   ← ГЛАВНОЕ
    ├── balances.yml
    ├── bank-accounts.yml · bank-loans.yml · bank-pools.yml
    ├── bank-credit.yml · bank-pending.yml
    ├── auction-lots.yml · auction-items.yml
    ├── auction-reputation.yml · auction-bans.yml · auction-stats.yml
    ├── nation-taxes.yml · nation-trade-policy.yml
    └── shares.yml · bonds.yml
```

---

## 3. Бэкапы

- **Ежедневный** бэкап мира: `storage.daily-backup.time: '04:00'`, хранить `keep-days: 7`.
- **yaml-бэкап балансов**: при каждом `onDisable` + периодически.
- **WAL-checkpoint**: каждые `checkpoint-interval-minutes: 5`.
- **Ручной** перед риском: стоп → копия всей папки `plugins/RaskolVault/` → старт.

---

## 4. Restore

1. Стоп. Удали `plugins/RaskolVault/data/` целиком.
2. Скопируй `data/` из бэкапа. Старт.
3. Если `ledger.sqlite` повреждён: удали `ledger.sqlite*`, оставь `balances.yml` — `RestoreService` восстановит балансы из yaml (транзакции старше бэкапа утеряны, балансы — нет).

---

## 5. Откат версии

1. Стоп; удали новый jar, верни старый.
2. Даунгрейд через мажорный скачок (1.2.x → 1.1.x) может не прочитать новые таблицы — делай даунгрейд только внутри мажорной линии ИЛИ из полного бэкапа `data/`.
3. Старт.

---

## 6. Фоновые таски

| Таск | Интервал | Действие |
|---|---|---|
| WAL-checkpoint | 5 мин | слив WAL в sqlite |
| reconcile | 30 мин | сверка кэш↔леджер |
| tax-save | 10 мин | сохранение налогов |
| auction-expire | 60 сек | истечение лотов |
| bank-accrual | 60 мин | начисление процентов по вкладам/кредитам |
| bank-liquidation | 60 мин | ликвидация просроченных кредитов |
| inflation-check | 60 мин | проверка покрытия / кризис-broadcast |
| auditor (selftest) | 60 мин | самодиагностика инвариантов |

---

## 7. Инциденты

- **Плагин не встал**: читай первые ERROR в `logs/latest.log`; частые причины — повреждён `ledger.sqlite`, нет зависимостей, конфликт версий Java.
- **writer queue > 500**: медленный диск/блокировки; проверь диск, увеличишь `borrow-timeout-ms`.
- **Кризис покрытия**: broadcast «КРИЗИС» — король пополняет резерв (`/rv cabinet` → Резерв → Депозит) или снижает паритет.
- **Дюп/эксплойт**: стоп → бэкап `data/` → разбор таблицы `transactions` → сообщить разработчику с логами.

---

## 8. Гигиена

- Не редактируй `data/*.yml` на запущенном сервере (перетрётся при save).
- Не удаляй `ledger.sqlite-wal` при живом сервере.
- Держи `keep-days` бэкапов ≥ 7.
- Перед любым риском — ручной бэкап (п.3).

---

*Конец ранбука. Вопросы — разработчику (hayferdahmer).*
