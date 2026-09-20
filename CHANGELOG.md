# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.0.5] — 2026-09-20 — Анти-дюп контур

### Добавлено
- **TokenBucket rate-limit** (`security.rate-limit`): per-player ведро токенов
  (capacity 8, refill 2/с) на `/rv pay` и `/rv convert` (preview).
  Счётчик отклонений: `%raskolvault_rate_limited%`.
- **Оптимистичная блокировка в SQL** (`commitAbsoluteChecked`): коммит балансов
  применяется только если текущее amount в БД совпадает с ожидаемым старым значением
  (`ON CONFLICT DO UPDATE ... WHERE balances.amount=?`). Гонка/ручная правка БД/restore
  во время работы = откат транзакции целиком + SEVERE + лечение кэша из БД (heal).
- **Инфляционный чекпоинт** (`security.inflation-check`, каждый час, async):
  инвариант `SUM(balances) == SIGNSUM(transactions)` для каждой НЕ-глобальной валюты
  (SIGNSUM: +amount для from=NULL/to!=NULL, −amount для from!=NULL/to=NULL, 0 для PAY).
  Расхождение > 0.01 = SEVERE + `%raskolvault_inflation_anomalies%`.
  GLOBAL не проверяется (источник правды — Essentials). Побочно: purgeIdle для TokenBucket.
- PAPI-плейсхолдеры: `%raskolvault_inflation_anomalies%`, `%raskolvault_rate_limited%`.
- messages.yml: `error.rate-limited`.

### Изменено
- **GLOBAL-мутации под projectionLock** (WalletService): чтение+запись Essentials
  атомарны относительно параллельных вызовов — закрыта гонка lost-update 1.0.4.
- Неблобальные коммиты переведены с `commitAbsolute` на `commitAbsoluteChecked`;
  при отказе коммита кэш лечится чтением из БД, операция отклоняется с SEVERE-логом
  (в логе явно указано, что проверить: ручные правки БД / restore).
- `compensateGlobal` при отказе convert-коммита тоже под локом.

### Совместимость
- Схема БД не менялась (schema v1), миграции не нужны.
- Конфиг: новые секции `security.rate-limit` и `security.inflation-check`
  (дефолты включены; capacity 0 или enabled false = выключено).

## [1.0.4] — 2026-09-20 — Observability (без DiscordSRV-алертов)

### Добавлено
- `/rv admin health`: TPS 1/5/15m, online, JVM memory, SQLite pool idle/wait,
  writer queue/applied/failed, cache rows/hit-rate/H/M, tx/min, WAL size,
  last tx, arbitrage loops.
- PAPI-плейсхолдеры observability: tps/tps_5m/tps_15m, ledger_queue, cache_hit,
  tx_per_min, writer_applied, writer_failed, pool_idle, pool_wait,
  currencies_count, rates_count.
- SparkHook: опциональные тайминги проекций (rv.deposit.projection и т.п.)
  через рефлексию, без compile-зависимости от Spark.
- Cache hit/miss счётчики в WalletService.
- Permission `raskolvault.admin.health`.

### Отклонения от дорожной карты
- DiscordSRV-алерты не реализованы (по запросу владельца).

## [1.0.3] — 2026-09-20 — Асинхронность леджера (single-writer)

### Добавлено
- LedgerWriter: единственный упорядоченный поток записи, очередь 10000, backpressure.
- Async-API: depositAsync/withdrawAsync/transferAsync/exchangeAsync.
- `/rv pay`, `/rv convert` + `/rv confirm` не блокируют main-thread на SQLite.

### Изменено
- Модель записи: проекция кэша под projectionLock на потоке вызова +
  атомарный коммит в писателе; sync-контракт = join с таймаутом.
- Graceful-stop: очередь дренируется до закрытия пула.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool (1..16, дефолт 5), PRAGMAs на соединение.
- WAL-checkpoint по расписанию и на выключении.
- Атомарный коммит «баланс+аудит» одной SQL-транзакцией.
- SQLiteLedger: lastTransactionTimestamp, dbFile, pool-геттеры, attachTxCounter.

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
- 1.0.6: Tab-completion offline-игроков, TownyRename/DeleteNation-слушатели, миграция валют.
- 1.0.7: stress-suite, restore из бекапа, документация миграции на 1.1.
- 1.1: автоконвертация при /pay, Parties-мост, кланы, RaskolCore 1.5.0 «Картографический слой».
