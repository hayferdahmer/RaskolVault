# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.0.4] — 2026-09-21 — Observability (без DiscordSRV-алертов)

### Добавлено
- **`/rv admin health`** — живая сводка: TPS (1/5/15 мин), online, JVM memory,
  SQLite pool idle/wait, writer queue/applied/failed, cache rows/hit-rate/H/M,
  tx/min (60s sliding window), WAL size, последняя транзакция, arbitrage loops.
- **PAPI-плейсхолдеры observability:**
  `%raskolvault_tps%`, `%raskolvault_tps_5m%`, `%raskolvault_tps_15m%`,
  `%raskolvault_ledger_queue%`, `%raskolvault_cache_hit%`,
  `%raskolvault_tx_per_min%`, `%raskolvault_writer_applied%`,
  `%raskolvault_writer_failed%`, `%raskolvault_pool_idle%`,
  `%raskolvault_pool_wait%`, `%raskolvault_currencies_count%`,
  `%raskolvault_rates_count%`.
- **`TxPerMinuteCounter`** — sliding window 60 сек на уровне леджера
  (считает записанные транзакции, а не API-вызовы).
- **`SparkHook`** — опциональный хук к Spark profiler через рефлексию.
  Без compile-зависимости: если Spark не установлен — молча no-op; если есть —
  регистрирует таймеры `rv.deposit.projection`, `rv.withdraw.projection`,
  `rv.transfer.projection`, `rv.convert.projection` на синхронной проекции кэша.
- **Cache hit/miss счётчики** в WalletService (для метрики coverage валюты
  среди игроков; `cache_hit` = hit/(hit+miss)).
- **`SQLiteLedger.lastTransactionTimestamp()`** и `dbFile()` — для `/rv admin health`.
- Permission `raskolvault.admin.health` (включён в группу `raskolvault.admin`).

### Изменено
- `plugin.yml`: `spark` добавлен в `softdepend` (без него плагин работает,
  просто без таймеров).
- Лог старта: добавил `Spark on/off` в сводку.

### Отклонения от дорожной карты (осознанные)
- **DiscordSRV-алерты не реализованы** (по запросу). Материал для алертов
  (`writer_failed > 0`, `arbitrage_loop > 0`, `pool_wait > 0`) доступен
  через `/rv admin health` и PAPI-плейсхолдеры.
- **Spark-тайминги** только для синхронной проекции кэша (основной вклад
  в main-thread latency); асинхронный коммит леджера Spark не замеряет
  (он вне main-thread).

### Версия
- 1.0.3 → 1.0.4 (pom.xml, plugin.yml).

## [1.0.3] — 2026-09-20 — Асинхронность леджера (single-writer)

### Добавлено
- LedgerWriter: единственный упорядоченный поток записи с ограниченной очередью
  (writer-queue-cap, дефолт 10000) и backpressure.
- Async-API кошелька: depositAsync/withdrawAsync/transferAsync/exchangeAsync.
- `/rv pay` и `/rv convert`+`/rv confirm` больше не блокируют main-thread на SQLite.
- Метрики писателя (queue/applied/failed) в `describeStats()`.

### Изменено
- Модель записи: проекция кэша на потоке вызова + атомарный коммит в писателе.
- Синхронный контракт (Core-хук, админки, казна) = тот же путь + join с таймаутом.
- Graceful-stop: очередь писателя дренируется ДО закрытия пула.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool: фиксированный пул SQLite-соединений (1..16, дефолт 5).
- WAL-гигиена: `PRAGMA wal_checkpoint(TRUNCATE)` по расписанию и на выключении.
- Атомарный коммит «баланс+аудит» одной SQL-транзакцией.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер: tradeable-фильтр, пустой граф = информативный пропуск.
- Стартовая сводка без сырых section-кодов.
- `saveResource` без WARN «already exists».
- Миграция legado-ID молчит при нулевом переносе.
- MessagesConfig: убран deprecated ChatColor.

## [1.0.0] — 2026-09-20 — Первый релиз

Многовалютный кошелёк (GLD/RAS/VLR), SQLite-леджер (WAL, схема v1),
рефлексия-хуки (EssentialsX, RaskolCore, Towny, PlaceholderAPI),
авто-создание национальных валют, казны наций, обмен с комиссией,
арбитражный сканер, PAPI-плейсхолдеры, бекапы, нагрузочный тест.

## [Unreleased] — запланировано
- 1.0.5: token bucket rate-limit, атомарный UPDATE с проверкой баланса в SQL, анти-инфляционный чекпоинт.
- 1.0.6: Tab-completion offline-игроков, TownyRename/DeleteNation-слушатели, миграция валют.
- 1.0.7: stress-suite, restore из бекапа, документация миграции на 1.1.
- 1.1: автоконвертация при /pay, Parties-мост, кланы, RaskolCore 1.5.0 «Картографический слой».
