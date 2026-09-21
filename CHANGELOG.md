# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.2.1] — 2026-09-21 — Стабилизация биржи + документы

### Исправлено
- `ExchangeOrderService`: конфликт record-аксессора (`ok()/fail()` → `success()/failure()`), ломавший компиляцию.
- `WalletGui.openMain/openCabinet`: приведены к сигнатуре `(RaskolVault, Player)`.
- Каскадные compile-фиксы в `ExchangeSubcommand`, `ReserveGui`, `RaskolVaultCommand`.

### Изменено
- Полная переработка `README.md` (бейджи, архитектура, команды, права, плейсхолдеры, интеграция, roadmap).
- `CHANGELOG.md` приведён к формату Keep a Changelog для всей истории 1.0.0 → 1.2.1.
- Версия поднята до 1.2.1 (pom.xml, plugin.yml).

## [1.2.0] — 2026-09-21 — Фундамент межгосударственной биржи

### Добавлено
- Схема БД v3: таблица `exchange_orders` (статусы OPEN/MATCHED/CANCELLED) + индексы.
- `ExchangeOrderService`: createSell/createBuy/cancel/take с заморозкой средств через escrow; исполнение all-or-nothing.
- `/rv exchange list|my|sell|buy|cancel|take` — стакан биржи; ордера выставляют/забирают ТОЛЬКО короли наций (временное ограничение).
- `ReserveGui` — GUI «Ячейки резерва»: 1 ячейка = 1000 GLD, депозит/вывод через чат-захват суммы.
- King-gate через Towny (`isKing`), rollback-ветки при частичном сбое исполнения.

## [1.1.0] — 2026-09-21 — Интеграционный слой

### Добавлено
- `RaskolVaultAPI` — публичный API для плагинов-друзей (балансы, конвертация, нации, резервы, права).
- `LuckPermsHook` (cached data) + `loadbefore` для RaskolMarket/RaskolCaravans/RaskolCharters/ESGUI/ChestShop.
- `ConvertResult` record + `EscrowService` (escrow-примитив: hold/release/refund).
- `INTEGRATION.md` — руководство для разработчиков друзей-плагинов.

## [1.0.5] — 2026-09-20 — Анти-дюп контур

### Добавлено
- Rate-limit (token bucket) на `/rv pay` и `/rv convert`.
- Оптимистичная блокировка (`commitAbsoluteChecked`).
- Инфляционный чекпоинт (сверка SUM(balances) ↔ SIGNSUM(transactions)).
- PAPI: `%raskolvault_inflation_anomalies%`, `%raskolvault_rate_limited%`.

## [1.0.4] — 2026-09-20 — Наблюдаемость

### Добавлено
- `/rv admin health` (TPS, память, пул, writer, cache, WAL, last tx).
- PAPI-метрики: tps, ledger_queue, cache_hit, tx_per_min, writer_*, pool_*.
- SparkHook (тайминги через рефлексию).

## [1.0.3] — 2026-09-20 — Асинхронный леджер

### Добавлено
- `LedgerWriter`: single-writer очередь (10k), backpressure, graceful-stop.
- Async-API: depositAsync/withdrawAsync/transferAsync/exchangeAsync.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool (1..16, дефолт 5), PRAGMAs на соединение.
- WAL-checkpoint по расписанию и на выключении.
- Атомарный коммит «баланс + аудит» одной SQL-транзакцией.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер: tradeable-фильтр, пустой граф = информативный пропуск.
- Сводка старта без сырых section-кодов; MessagesConfig без deprecated ChatColor.

## [1.0.0] — 2026-09-20 — Первый релиз

- Многовалютный кошелёк (GLD/RAS/VLR), SQLite-леджер (WAL, схема v1).
- Рефлексия-хуки: EssentialsX, RaskolCore, Towny, PlaceholderAPI.
- Авто-создание национальных валют, казны наций, обмен с комиссией.
- Арбитражный сканер, PAPI-плейсхолдеры, бекапы, нагрузочный тест.
