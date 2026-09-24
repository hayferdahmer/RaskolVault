# RaskolVault — OPERATIONS (ранбук)

Документ для эксплуатации: деплой, бэкапы, restore, откат, инциденты.
Версия: 1.2.6 · Paper 1.21.4 · Java 21

---

## 1. Деплой

1. **Стоп** сервера (полный, не `/reload`).
2. Собери артефакт: `mvn clean package`.
3. Скопируй `target/RaskolVault-1.2.6.jar` в `plugins/`.
4. Удали старый `RaskolVault-*.jar` (иной версии).
5. **Старт** сервера.
6. Проверка: `/rv admin health`, `/spark tps` = 20.0, консоль без `ERROR`.

> `/reload` **не использовать** — плагин держит пул SQLite и фоновые таски.

---

## 2. Файлы данных (что бэкапить)

```
plugins/RaskolVault/
├── config.yml
├── currencies.yml
├── rates.yml
├── messages.yml
└── data/
    ├── ledger.sqlite          ← ГЛАВНОЕ (балансы, транзакции)
    ├── ledger.sqlite-wal      ← WAL (не удалять при живом сервере)
    ├── balances.yml           ← yaml-бэкап балансов
    ├── bank-accounts.yml
    ├── bank-loans.yml
    ├── bank-pools.yml
    ├── bank-credit.yml
    ├── bank-pending.yml
    ├── auction-lots.yml
    ├── auction-items.yml
    ├── auction-reputation.yml
    ├── auction-bans.yml
    ├── auction-stats.yml
    ├── nation-taxes.yml
    └── nation-trade-policy.yml
```

---

## 3. Бэкапы

### Автоматические
- **Ежедневный** бэкап мира: `storage.daily-backup.time: '04:00'`, хранить `keep-days: 7`.
- **yaml-бэкап балансов**: пишется при каждом `onDisable` и периодически.
- **WAL-checkpoint**: каждые `checkpoint-interval-minutes: 5` (сливает WAL в основной файл).

### Ручной (перед риском)
1. Стоп сервера.
2. Скопируй всю папку `plugins/RaskolVault/` в `backup_RaskolVault_<дата>/`.
3. Старт.

---

## 4. Restore

1. Стоп сервера.
2. Удали `plugins/RaskolVault/data/` целиком.
3. Скопируй `data/` из бэкапа.
4. Старт. Плагин подхватит `ledger.sqlite` и yaml-состояние.

### Restore из yaml-бэкапа (если sqlite повреждён)
1. Стоп.
2. Удали `ledger.sqlite*`.
3. Оставь `balances.yml` — при старте `RestoreService.maybeRestore()` восстановит балансы из yaml.
4. Старт. Транзакции старше бэкапа будут утеряны (баланси — нет).

---

## 5. Откат версии

1. Стоп.
2. Удали новый jar, верни старый jar.
3. **Проверь совместимость схемы:** даунгрейд через мажорный скачок (1.2.x → 1.1.x) может не прочитать новые таблицы. Делай даунгрейд только внутри одной мажорной линии ИЛИ из полного бэкапа `data/`.
4. Старт.

---

## 6. Фоновые таски (мониторинг)

| Таск | Интервал | Что делает |
|---|---|---|
| WAL-checkpoint | 5 мин | слив WAL в sqlite |
| reconcile | 30 мин | сверка кэш↔леджер |
| tax-save | 10 мин | сохранение налогов |
| auction-expire | 60 сек | истечение лотов |
| bank-accrual | 60 мин | начисление процентов |
| bank-liquidation | 60 мин | ликвидация просрочки |
| inflation-check | 60 мин | проверка покрытия / кризис |
| auditor | 60 мин | самодиагностика инвариантов |

---

## 7. Инциденты

### Красный лог / плагин не встал
1. Читай первые `ERROR` в `logs/latest.log`.
2. Частые причины: повреждён `ledger.sqlite` (см. Restore), нет зависимостей (Essentials/Towny), конфликт версий Java.
3. `/rv admin health` — если команда не отвечает, плагин не включился.

### writer queue растёт (>500)
- Признак: WARN `writer queue N`.
- Причина: диск медленный / блокировки sqlite.
- Действие: проверь диск, увеличь `borrow-timeout-ms`, снизь нагрузку.

### Кризис покрытия (broadcast)
- Признак: broadcast «КРИЗИС», цена нацвалюты упала.
- Действие: король — пополнить резерв (`/rv cabinet` → Резерв → Депозит) или снизить паритет.

### Дюп / эксплойт
1. Немедленно `/rv admin reload` не поможет — нужен стоп.
2. Стоп, бэкап `data/`, разбор `ledger.sqlite` (таблица transactions).
3. Сообщить разработчику с логами.

---

## 8. Гигиена

- Не редактируй `data/*.yml` на запущенном сервере (перетрётся при save).
- Не удаляй `ledger.sqlite-wal` при живом сервере.
- Держи `keep-days` бэкапов ≥ 7.
- Перед любым риском — ручной бэкап (п.3).

---

*Конец ранбука. Вопросы — разработчику (hayferdahmer).*
