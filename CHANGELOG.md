# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.0.3] — 2026-09-20 — Асинхронность леджера (single-writer)

### Добавлено
- LedgerWriter: единственный упорядоченный поток записи с ограниченной очередью
  (writer-queue-cap, дефолт 10000) и backpressure: переполнение очереди = синхронная
  запись на потоке вызывающего (порядок не теряется, память не растёт бесконечно).
- Async-API кошелька: depositAsync/withdrawAsync/transferAsync/exchangeAsync с callback;
  `/rv pay` и `/rv convert`+`/rv confirm` больше не блокируют main-thread на SQLite.
- Метрики писателя (queue/applied/failed) в `describeStats()` — база алертов 1.0.4.
- Конфиг-ключ `storage.sqlite.writer-queue-cap`.

### Изменено
- Модель записи: проекция кэша под projectionLock на потоке вызова + атомарный коммит
  абсолютных балансов и аудита одной SQL-транзакцией (commitAbsolute) в писателе.
- Синхронный контракт (Core-хук, админки, казна нации) = тот же путь + join с таймаутом
  borrow-timeout-ms; порядок операций глобально сохраняется (все записи через писателя).
- PAPI-плейсхолдеры читают только кэш/Essentials (SQLite не трогают) — резолв <1 мс.
- Graceful-stop: очередь писателя дренируется ДО закрытия пула — принятые записи не теряются.

### Отклонения от дорожной карты (осознанные)
- guava EventBus не добавлен: кэш актуален в момент проекции, pub/sub-инвалидация не нужна.
- Ошибка записи писателя = SEVERE с полными данными транзакции для ручного восстановления;
  автоматический откат кэша НЕ делается (откат затёр бы более поздние проекции).
  Окно durability: принятая, но не применённая запись теряется только при краше процесса
  между проекцией и коммитом (graceful-stop дренирует очередь).

### Версия
- 1.0.2 → 1.0.3 (pom.xml, plugin.yml).

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- ConnectionPool: фиксированный пул SQLite-соединений собственного производства
  (1..16, дефолт 5), без внешних зависимостей; PRAGMAs на соединение выставляются один раз.
- Метрика очереди пула: waiting/idle/size в `describeStats()` (лог старта и `/rv debug`).
- WAL-гигиена: `PRAGMA wal_checkpoint(TRUNCATE)` по расписанию
  (`checkpoint-interval-minutes`, дефолт 5) и финальный checkpoint на выключении.
- Конфиг-ключи: `storage.sqlite.pool-size`, `storage.sqlite.synchronous`,
  `storage.sqlite.borrow-timeout-ms`, `storage.sqlite.checkpoint-interval-minutes`.

### Изменено
- Атомарность аудита: каждая бизнес-операция (баланс + аудиторская запись) пишется
  ОДНОЙ SQL-транзакцией (BEGIN IMMEDIATE → upsert+insert → COMMIT).
- GLOBAL-путь: при провале аудит-коммита — компенсационный откат Essentials.
- Битое соединение после сбоя не возвращается в пул (discard вместо release).
- Уточнение: разный `synchronous` для разных таблиц в SQLite невозможен;
  durability вынесен в конфиг `synchronous: NORMAL|FULL`.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер не считает неторгуемые валюты в петлях; пустой граф — информативный пропуск.
- Стартовая сводка без сырых section-кодов; saveResource без WARN «already exists».
- Миграция legado-ID логируется только при фактическом переносе строк.
- MessagesConfig: убран deprecated ChatColor; отсутствующий ключ = одноразовый warning.

## [1.0.0] — 2026-09-20 — Первый релиз

### Добавлено
- Многовалютный кошелёк: GLD (глобальная, ⚜), RAS (Рассвет, ☀), VLR (Вальрадис, ☾).
- Стандарт ID валют: ровно 3 буквы капсом ([A-Z]{3}) с валидацией на уровне модели.
- SQLite-леджер: WAL, FK-каскады, миграции через PRAGMA user_version (схема v1).
- Рефлексия-хуки: EssentialsX, RaskolCore (Proxy EconomyProvider), Towny, PlaceholderAPI.
- Авто-создание национальной валюты при Towny NewNationEvent (nation-symbols).
- Казны наций через детерминированный UUID (nation:<id>); mint/burn только королём.
- Обмен валют с комиссией, preview и подтверждением /rv confirm (TTL 30 c).
- Арбитражный сканер: BFS по графу курсов до 4 шагов.
- PAPI-плейсхолдеры: balance/balance_raw/nation/treasury/symbol.
- Бекапы: ежедневный дамп SQLite 04:00 (ротация 7 дней) + ручной /rv admin backup.
- Нагрузочный тест /rv admin simulate-load + cleanup.
- Команды /rv balance|pay|convert|confirm|rates|nation|debug и полный admin-набор.
- Аудит всех движений средств в таблице transactions.

### Дизайн-решения
- GLOBAL проксируется в EssentialsX: единый источник правды без дублирования баланса.
- Обратные курсы задаются явно (антиарбитраж по построению).
- Null-safe контракт всех хуков и EconomyProvider.

### Известные ограничения
- Towny-хук рассчитан на пакет com.palmergames.bukkit.towny.*.
- Нагрузочный тест очищает кэш, но записи леджера от теста сохраняются.
- Автоконвертация при /pay выключена по умолчанию в 1.0.x.

## [Unreleased] — запланировано
- 1.0.4: /rv admin health, PAPI-метрики TPS/queue/cache-hit, DiscordSRV-алерты.
- 1.0.5: token bucket rate-limit, атомарный UPDATE с проверкой баланса в SQL, анти-инфляционный чекпоинт.
- 1.0.6: Tab-completion offline-игроков, TownyRename/DeleteNation-слушатели, миграция валют.
- 1.0.7: stress-suite, restore из бекапа, документация миграции на 1.1.
- 1.1: автоконвертация при /pay, Parties-мост, кланы, RaskolCore 1.5.0 «Картографический слой».
