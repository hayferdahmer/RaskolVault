# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.0.7] — 2026-09-21 — Stress-suite, restore, документация

### Добавлено
- **`/rv admin stress <players> <txs>`**: стресс-тест с нагрузкой (демо-команда, требует Citizens для реальных ботов).
- **`/rv admin restore <file.sqlite>`**: восстановление БД из бекапа (выводит инструкцию для ручной замены файла после остановки сервера).
- **`NationTreasury.balance(nationId, currencyId)`**: convenience-метод для получения баланса казны нации.
- **`CurrencyRegistry.addCurrency()`/`removeCurrency()`**: методы для динамического управления валютами (используются `NationAutoCurrencyListener` и CLI).
- **`TownyHook.isKing(uuid, nationName)`**: проверка, является ли игрок королём указанной нации (через рефлексию).
- **Документация `MIGRATION.md`**: полное руководство по установке, эксплуатации, бекапам/восстановлению, нагрузочному тестированию, миграции на 1.1.

### Исправлено
- **`RaskolVaultCommand.balance()`**: переменная `player` теперь корректно доступна в `else`-ветке (pattern matching scope).
- **`NationSubcommand`**: добавлены недостающие сообщения в `messages.yml` (`error.console-cannot-nation`, `error.towny-unavailable`, `nation.not-in-nation`, `nation.header`).
- **`CurrencySubcommand`**: добавлено сообщение `error.currency.exists` в `messages.yml`.

### Изменено
- **`AdminSubcommand`**: добавлены подкоманды `stress` и `restore` в таб-комплит и help.
- **`RaskolVaultCommand`**: обновлён help с новыми командами.

### Документация
- **`MIGRATION.md`**: 6 разделов:
  1. Установка и первый запуск
  2. Ежедневная эксплуатация (мониторинг, PAPI, администрирование, инфляционный чекпоинт)
  3. Бекапы и восстановление (автоматические, ручные, restore, лечение кэша)
  4. Нагрузочное тестирование (синтетический тест, стресс-тест с ботами, критерии успеха)
  5. Миграция на 1.1 (автоконвертация при /pay, Parties-мост, кланы)
  6. Troubleshooting (частые проблемы и решения)

### Совместимость
- Схема БД **не менялась** (v1), миграции не нужны.
- Towny API: рефлексия работает с 0.100+ (проверено на 0.103.2.0).
- Конфиг: новых секций нет.

## [1.0.6] — 2026-09-21 — Offline-tab, Towny lifecycle, currency rename

### Добавлено
- **OfflinePlayerRegistry**: in-memory кэш всех известных ников (online+offline) с прогревом из `Bukkit.getOfflinePlayers()` (async на старте).
- **TownyNationLifecycleListener**: слушатель `RenameNationEvent` и `DeleteNationEvent` через рефлексию (без compile-зависимости от Towny API).
- **`/rv admin currency rename <old-id> <new-id>`**: атомарное переименование валюты в БД и в памяти.
- **PAPI-плейсхолдер `%raskolvault_offline_registry%`**.

### Изменено
- **Tab-complete**: `/rv balance/pay/admin give/take/set/audit` подтягивают и offline-игроков.
- **`SQLiteLedger`**: добавил `renameNationId(old, new)` и `renameCurrency(oldId, newId)`.
- **`CurrencyRegistry`**: добавил `countByNation`, `updateNationId`, `rename`.

## [1.0.5] — 2026-09-20 — Анти-дюп контур

### Добавлено
- **TokenBucket rate-limit** на `/rv pay` и `/rv convert`.
- **Оптимистичная блокировка** в SQL (`commitAbsoluteChecked`).
- **Инфляционный чекпоинт** (каждый час): инвариант `SUM(balances) == SIGNSUM(transactions)`.
- PAPI-плейсхолдеры: `%raskolvault_inflation_anomalies%`, `%raskolvault_rate_limited%`.

## [1.0.4] — 2026-09-20 — Observability

### Добавлено
- `/rv admin health`: TPS, память, SQLite pool, writer queue, cache hit-rate, tx/min, WAL size, last tx, arbitrage loops.
- PAPI-плейсхолдеры: tps/tps_5m/tps_15m, ledger_queue, cache_hit, tx_per_min, writer_applied, writer_failed, pool_idle, pool_wait.
- SparkHook: опциональные тайминги через рефлексию.

## [1.0.3] — 2026-09-20 — Асинхронность леджера (single-writer)

### Добавлено
- LedgerWriter: единственный упорядоченный поток записи, очередь 10000, backpressure.
- Async-API: depositAsync/withdrawAsync/transferAsync/exchangeAsync.
- `/rv pay`, `/rv convert` + `/rv confirm` не блокируют main-thread.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool (1..16, дефолт 5), PRAGMAs на соединение.
- WAL-checkpoint по расписанию и на выключении.
- Атомарный коммит «баланс+аудит» одной SQL-транзакцией.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер: tradeable-фильтр, пустой граф = информативный пропуск.
- Сводка старта без сырых section-кодов.
- Миграция legado-ID молчит при нулевом переносе.
- MessagesConfig: убран deprecated ChatColor.

## [1.0.0] — 2026-09-20 — Первый релиз

Многовалютный кошелёк (GLD/RAS/VLR), SQLite-леджер (WAL, схема v1),
рефлексия-хуки (EssentialsX, RaskolCore, Towny, PlaceholderAPI),
авто-создание национальных валют, казны наций, обмен с комиссией,
арбитражный сканер, PAPI-плейсхолдеры, бекапы, нагрузочный тест.

## [Unreleased] — запланировано
- **1.1.0**: автоконвертация при /pay, Parties-мост, кланы, RaskolCore 1.5.0 «Картографический слой».
