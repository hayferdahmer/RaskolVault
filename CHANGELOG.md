# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

## [1.1.0] — 2026-09-21 — «Валютный совет» (финальный релиз)

### Добавлено
- **GUI `/rv wallet`**: 6 страниц (кошелёк, конверт-мастер, курсы, история, кабинет, кодекс).
- **WalletGui + GuiListener**: собственный мини-фреймворк на `InventoryHolder`.
- **Конверт-мастер**: выбор FROM → TO → сумма (1/10/64/100/весь/ввод в чат) → подтверждение.
- **Кабинет правителя**: резерв, покрытие, цена, паритет ±, налог ±, депозит/вывод/минт/бёрн.
- **Кодекс правителя**: 6 страниц инструкций (резерв, паритет, интервенции, конвертация, кризис, команды).
- **`/rv cabinet`**: отдельная команда для GUI кабинета правителя (только король).
- **`/rv guide`**: команда для открытия кодекса правителя (инструкции).
- **ReserveBank**: золотое покрытие национальных валют (резерв/паритет/налог).
- **ConvertEngine**: конвертация по курсам покрытия (цена = min(паритет, резерв/эмиссия)).
- **Интервенции**: `/rv bank deposit/withdraw` (король вносит/выводит золото в резерв).
- **Регулирование**: `/rv bank parity/tax` (король ставит паритет и налог).
- **RateLimiter**: token-bucket на игрока (анти-спам конвертаций).
- **TxCounter**: транзакций в минуту (сэмплирование леджера).
- **InflationCheckpoint**: почасовая проверка покрытия, кризисный broadcast.
- **Комиссия конвертации**: база (сжигается) + налог нации-цели (в казну).
- **PAPI-плейсхолдеры курсов**: `%raskolvault_rate_<from>_<to>%` — курс между валютами.
- **PAPI-плейсхолдеры цены**: `%raskolvault_price_<currency>%` — цена валюты в золоте.
- **PAPI-плейсхолдеры покрытия**: `%raskolvault_coverage_<nation>%` — покрытие резервом.
- **Бренд**: Золото (GLD), Динар Рассвета (RAS), Вальрадисская Крона (VLR).
- **Буквенные коды**: везде (сообщения, GUI, аудит) вместо символов.
- **`/rv admin balance <ник>`**: смотрелка балансов (онлайн и оффлайн).
- **`/rv rates`**: команда возвращена в корневой роутер.
- **Merge валют из БД**: валюты, созданные в рантайме, переживают рестарт.

### Изменено
- **`/rv balance`**: только для админов (через `admin.view`).
- **`/rv convert`**: использует ConvertEngine вместо ExchangeService.
- **messages.yml**: строгий стиль (тёмная рамка + золото, без глифов валют).
- **WalletGui**: добавлены разделители (`GRAY_STAINED_GLASS_PANE`), улучшены подсказки в lore, иконка кабинета — `GOLDEN_CHESTPLATE` вместо `CHEST`.
- **plugin.yml**: добавлены описания команд `cabinet` и `guide` в usage.

### Исправлено
- **Импорт SparkHook**: класс импортируется из `dev.raskol.vault.observability` (фактический пакет).
- **Импорт OfflinePlayerRegistry**: восстановлен в `RaskolVault.java`.
- **ConvertEngine**: добавлен импорт `CurrencyType` (используется в логике конвертации).
- **InflationCheckpoint**: тип параметра конструктора изменён с `Plugin` на `RaskolVault`.
- **NationBankSubcommand**: добавлен импорт `CurrencyType`.
- **PaySubcommand**: сигнатура `transfer` из 5 аргументов (без `TransactionType`).
- **PlaceholderApiHook**: убраны обращения к несуществующим геттерам (`getTxCounter`, `getInflationCheckpoint`, `getRateLimiter`).

### Совместимость
- Схема БД **не менялась** (v1), миграции не нужны.
- Конфиги совместимы с 1.0.7.
- PAPI-плейсхолдеры: 3 новых (rate/price/coverage) + все старые (tps, ledger_queue, cache_hit, balance, treasury).
- Towny API: рефлексия работает с 0.100+ (проверено на 0.103.2.0).

## [1.0.7] — 2026-09-21 — Stress-suite, restore, документация

### Добавлено
- **`/rv admin stress <players> <txs>`**: стресс-тест с нагрузкой.
- **`/rv admin restore <file.sqlite>`**: восстановление БД из бекапа.
- **`NationTreasury.balance()`**: convenience-метод.
- **`CurrencyRegistry.addCurrency()/removeCurrency()`**: динамическое управление.
- **`TownyHook.isKing()`**: проверка короля нации.
- **`MIGRATION.md`**: полное руководство по установке и эксплуатации.

## [1.0.6] — 2026-09-21 — Offline-tab, Towny lifecycle, currency rename

### Добавлено
- **OfflinePlayerRegistry**: кэш ников для таб-комплита.
- **TownyNationLifecycleListener**: слушатель переименования/удаления наций.
- **`/rv admin currency rename <old-id> <new-id>`**: атомарное переименование.

## [1.0.5] — 2026-09-20 — Анти-дюп контур

### Добавлено
- **TokenBucket rate-limit** на `/rv pay` и `/rv convert`.
- **Оптимистичная блокировка** в SQL.
- **Инфляционный чекпоинт** (каждый час).

## [1.0.4] — 2026-09-20 — Observability

### Добавлено
- **`/rv admin health`**: TPS, память, SQLite pool, writer queue, cache.
- **PAPI-плейсхолдеры**: tps, ledger_queue, cache_hit, tx_per_min.
- **SparkHook**: опциональные тайминги через рефлексию.

## [1.0.3] — 2026-09-20 — Асинхронность леджера

### Добавлено
- **LedgerWriter**: единственный поток записи, очередь 10000.
- **Async-API**: depositAsync/withdrawAsync/transferAsync/exchangeAsync.

## [1.0.2] — 2026-09-20 — SQLite-пул и WAL-гигиена

### Добавлено
- **ConnectionPool** (1..16, дефолт 5).
- **WAL-checkpoint** по расписанию.

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер: tradeable-фильтр.
- MessagesConfig: убран deprecated ChatColor.

## [1.0.0] — 2026-09-20 — Первый релиз

Многовалютный кошелёк (GLD/RAS/VLR), SQLite-леджер (WAL, схема v1),
рефлексия-хуки (EssentialsX, RaskolCore, Towny, PlaceholderAPI),
авто-создание национальных валют, казны наций, обмен с комиссией,
арбитражный сканер, PAPI-плейсхолдеры, бекапы, нагрузочный тест.
