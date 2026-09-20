# Changelog

Формат: [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/).
Версионирование: [SemVer](https://semver.org/lang/ru/).
Лицензия: RASKOL Proprietary License v1.0 (см. LICENSE).

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
- Все мутации кошелька под одним монитором + синхронная запись в леджер: атомарность
  «списал → начислил → записал транзакцию» важнее параллельности на пре-лаунч объёмах.
- Откат обмена при сбое deposit: средства возвращаются, инцидент логируется как SEVERE.
- Null-safe контракт всех хуков и EconomyProvider (null uuid = 0/false без исключений).

### Известные ограничения
- Пул SQLite-соединений = 1 (synchronized); асинхронные операции отложены до 1.1.
- Towny-хук рассчитан на пакет com.palmergames.bukkit.towny.* (актуальные релизы Towny).
- Нагрузочный тест очищает кэш, но записи леджера от теста сохраняются (аудит неприкосновенен).
- Автоконвертация при /pay (auto-convert) выключена по умолчанию в 1.0.0.
