# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

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
  Баланс и аудит не могут разойтись даже при краше между записями.
- GLOBAL-путь: при провале аудит-коммита — компенсационный откат Essentials
  (деньги не двигаются без записи в журнал); перевод откатывается с обеих сторон.
- Кэш неблобальных балансов обновляется только после успешного коммита.
- Миграция legado-ID переведена на одну SQL-транзакцию на прогон.
- Уточнение дорожной карты: разный `synchronous` для разных таблиц в SQLite невозможен
  (PRAGMA живёт на соединении); durability вынесен в конфиг `synchronous: NORMAL|FULL`.
- Битое соединение после сбоя не возвращается в пул (discard вместо release).

### Версия
- 1.0.1 → 1.0.2 (pom.xml, plugin.yml).

## [1.0.1] — 2026-09-20 — Hotfix + гигиена лога

### Исправлено
- Арбитражный сканер больше не считает неторгуемые валюты (`tradeable: false`) в петлях.
- Пустой граф курсов / отсутствие торгуемых пар → информативный пропуск вместо ложного отчёта.
- Стартовая сводка в лог больше не содержит сырых section-кодов (§a/§c) — плоский текст.
- `saveResource` больше не шумит WARN'ами «already exists»: ресурсы пишутся только при отсутствии файла.
- Миграция legado-ID логируется только при фактическом переносе строк (идемпотентный тихий прогон).
- `MessagesConfig`: убран deprecated `ChatColor` (собственный colorize); отсутствующий ключ
  messages.yml даёт одноразовый warning с именем ключа вместо технической строки игроку.

## [1.0.0] — 2026-09-20 — Первый релиз

### Добавлено
- Многовалютный кошелёк: GLD (глобальная, ⚜), RAS (Рассвет, ☀), VLR (Вальрадис, ☾).
- Стандарт ID валют: ровно 3 буквы капсом ([A-Z]{3}) с валидацией на уровне модели.
- SQLite-леджер: WAL-режим, FK-каскады, миграции через PRAGMA user_version (схема v1).
- Рефлексия-хуки без compile-зависимостей: EssentialsX (global-баланс),
  RaskolCore (Proxy-регистрация EconomyProvider в EconomyRegistry), Towny, PlaceholderAPI.
- Авто-создание национальной валюты при Towny NewNationEvent;
  символы по точной карте nation-symbols (rassvet → ☀, valradis → ☾).
- Казны наций через детерминированный UUID (nation:<id>); mint/burn только королём нации.
- Обмен валют с комиссией (default + per-pair), preview и подтверждением /rv confirm (TTL 30 c).
- Арбитражный сканер: BFS по графу курсов до 4 шагов, отчёт прибыльных петель в лог и чат.
- PAPI-плейсхолдеры: %raskolvault_balance_<ID>%, %raskolvault_balance_raw_<ID>%,
  %raskolvault_nation%, %raskolvault_treasury_<ID>%, %raskolvault_symbol_<ID>%.
- Бекапы: ежедневный дамп SQLite в 04:00 с ротацией 7 дней + ручной /rv admin backup
  (копируются WAL-соседи), атомарный YAML-бекап балансов при выключении.
- Нагрузочный тест /rv admin simulate-load [игроки] [транзакции] + cleanup.
- Команды: /rv balance|pay|convert|confirm|rates|nation|debug;
  /rv admin give|take|set|mint|burn|currency|audit|simulate|simulate-load|backup|reload.
- Аудит всех движений средств в таблице transactions (PAY/CONVERT/MINT/BURN/ADMIN_*).
- Миграция legado-ID: gold→GLD, denarius→RAS, crown→VLR (balances + transactions).

### Дизайн-решения
- GLOBAL-валюта проксируется в EssentialsX: единый источник правды, без дублирования баланса.
- Обратные курсы задаются явно (GLD_RAS ≠ авто-инверсия RAS_GLD) — антиарбитраж по построению.
- Откат обмена при сбое deposit: средства возвращаются, инцидент логируется как SEVERE.
- Null-safe контракт всех хуков и EconomyProvider (null uuid = 0/false без исключений).

### Известные ограничения
- Асинхронные операции леджера — патч 1.0.3 (сейчас мутации под монитором сервиса).
- Towny-хук рассчитан на пакет com.palmergames.bukkit.towny.* (актуальные релизы Towny).
- Нагрузочный тест очищает кэш, но записи леджера от теста сохраняются (аудит неприкосновенен).
- Автоконвертация при /pay (auto-convert) выключена по умолчанию в 1.0.x.

## [Unreleased] — запланировано на 1.1
- Асинхронные операции леджера и pub/sub-инвалидация кэша (1.0.3).
- Observability: /rv admin health, PAPI-метрики TPS/queue/cache-hit (1.0.4).
- Анти-дюп: token bucket, атомарный UPDATE с проверкой баланса в SQL (1.0.5).
- Автоконвертация при /pay с предпочтениями игрока.
- Плагины-мосты: Parties (временные союзники), кланы (пост-запуск).
- RaskolCore 1.5.0 «Картографический слой»: HOI4-заливка территорий на BlueMap.
