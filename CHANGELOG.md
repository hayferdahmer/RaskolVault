# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.0.6] — 2026-09-21 — Offline-tab, Towny lifecycle, currency rename

### Добавлено
- **OfflinePlayerRegistry**: in-memory кэш всех известных ников (online+offline)
  с прогревом из `Bukkit.getOfflinePlayers()` (async на старте) и обновлением на
  `PlayerJoinEvent`. Используется таб-комплитом для `/rv balance/pay/admin give/take/set/audit`.
  Автодополнение теперь подтягивает и offline-игроков (важно для аудита истории).
- **TownyNationLifecycleListener**: слушатель `RenameNationEvent` (атомарно
  обновляет nation_id в БД и в реестре) и `DeleteNationEvent` (warning + число
  оставшихся orphan-валют, без деструктивного удаления — решение принимает админ).
- **`/rv admin currency rename <old-id> <new-id>`**: атомарное переименование
  валюты — обновляет `currencies`, `balances`, `transactions` одной SQL-транзакцией
  и запись в памяти. Проверка на существование нового ID.
- **PAPI-плейсхолдер `%raskolvault_offline_registry%`**: размер кэша offline-registry
  (диагностика).
- **`OfflinePlayerRegistry.size()`** в `/rv admin health` и в startup-сводке.

### Изменено
- **Tab-complete**: `/rv balance <prefix>`, `/rv pay <prefix>`, `/rv admin give|take|set|audit <prefix>`
  теперь возвращают и offline-игроков через OfflinePlayerRegistry (раньше только онлайн).
- **`/rv admin currency`**: добавил подкоманду `rename`, оставил `list` и пометку
  «create/remove — правь currencies.yml».
- **`AdminSubcommand.shortUuid`**: если игрок offline, сначала ищет ник в OfflinePlayerRegistry,
  и только при промахе показывает первые 8 символов UUID.
- **`SQLiteLedger`**: добавил `renameNationId(old, new)` и `renameCurrency(oldId, newId)`
  (атомарно в трёх таблицах).
- **`CurrencyRegistry`**: добавил `countByNation`, `updateNationId`, `rename`.
- **`pom.xml`**: добавил GlareMasters repo + `com.palmergames.bukkit.towny:towny:0.103.1.0`
  в scope `provided` (compile-time зависимость для Towny-слушателя; runtime — softdepend).
- **Сводка старта**: добавил `· OfflineRegistry on` и `· Towny hook`-статус.

### Совместимость
- Схема БД **не менялась** (v1), миграции не нужны.
- Towny API: используются стабильные `RenameNationEvent`/`DeleteNationEvent`
  (Towny 0.103.x, совместимо с 0.104+).
- Конфиг: новых секций нет (всё под существующими `hooks.towny.enabled`).

### Риски и защита
- `RenameNation`: если БД-обновление упало — SEVERE в лог, реестр остаётся консистентным
  с Towny (но nation_id может быть устаревшим; решается повторным `/rv admin reload`).
- `DeleteNation`: валюты **не удаляются** намеренно (иначе потеряется история
  транзакций нации); админ получает warning и решает через `currency rename`.
- `currency rename`: запрещает переименование в уже существующий ID; если
  SQL-транзакция упала, реестр в памяти остаётся нетронутым.

## [1.0.5] — 2026-09-20 — Анти-дюп контур

### Добавлено
- **TokenBucket rate-limit** на `/rv pay` и `/rv convert` (preview).
- **Оптимистичная блокировка** в SQL (`commitAbsoluteChecked`): коммит балансов
  применяется только если текущее amount в БД совпадает с ожидаемым.
  Гонка/ручная правка БД/restore = откат + SEVERE + лечение кэша из БД.
- **Инфляционный чекпоинт** (каждый час): инвариант `SUM(balances) == SIGNSUM(transactions)`
  для каждой неглобальной валюты. Расхождение > 0.01 = SEVERE + `%raskolvault_inflation_anomalies%`.
- PAPI-плейсхолдеры: `%raskolvault_inflation_anomalies%`, `%raskolvault_rate_limited%`.
- GLOBAL-мутации под projectionLock (закрытие гонки lost-update 1.0.4).

## [1.0.4] — 2026-09-20 — Observability (без DiscordSRV-алертов)

### Добавлено
- `/rv admin health`: TPS 1/5/15m, online, JVM memory, SQLite pool idle/wait,
  writer queue/applied/failed, cache rows/hit-rate/H/M, tx/min, WAL size, last tx,
  arbitrage loops.
- PAPI-плейсхолдеры: tps/tps_5m/tps_15m, ledger_queue, cache_hit, tx_per_min,
  writer_applied, writer_failed, pool_idle, pool_wait, currencies_count, rates_count.
- SparkHook: опциональные тайминги проекций через рефлексию (без compile-зависимости).
- Cache hit/miss счётчики в WalletService.
- Permission `raskolvault.admin.health`.

## [1.0.3] — 2026-09-20 — Асинхронность леджера (single-writer)

### Добавлено
- LedgerWriter: единственный упорядоченный поток записи, очередь 10000, backpressure.
- Async-API: depositAsync/withdrawAsync/transferAsync/exchangeAsync.
- `/rv pay`, `/rv convert` + `/rv confirm` не блокируют main-thread на SQLite.
- Graceful-stop: очередь дренируется до закрытия пула.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool (1..16, дефолт 5), PRAGMAs на соединение.
- WAL-checkpoint по расписанию и на выключении.
- Атомарный коммит «баланс+аудит» одной SQL-транзакцией.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер: tradeable-фильтр, пустой граф = информативный пропуск.
- Сводка старта без сырых section-кодов; saveResource без WARN «already exists».
- Миграция legado-ID молчит при нулевом переносе.
- MessagesConfig: убран deprecated ChatColor.

## [1.0.0] — 2026-09-20 — Первый релиз

Многовалютный кошелёк (GLD/RAS/VLR), SQLite-леджер (WAL, схема v1),
рефлексия-хуки (EssentialsX, RaskolCore, Towny, PlaceholderAPI),
авто-создание национальных валют, казны наций, обмен с комиссией,
арбитражный сканер, PAPI-плейсхолдеры, бекапы, нагрузочный тест.

## [Unreleased] — запланировано
- 1.0.7: stress-suite, restore из бекапа, документация миграции на 1.1.
- 1.1: автоконвертация при /pay, Parties-мост, кланы, RaskolCore 1.5.0 «Картографический слой».
