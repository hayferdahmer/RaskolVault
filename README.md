# RaskolVault

[![Build](https://github.com/hayferdahmer/RaskolVault/actions/workflows/build.yml/badge.svg)](https://github.com/hayferdahmer/RaskolVault/actions)
[![Release](https://img.shields.io/github/v/release/hayferdahmer/RaskolVault?include_prereleases&label=release)](https://github.com/hayferdahmer/RaskolVault/releases)
[![License](https://img.shields.io/badge/license-RASKOL%20Proprietary%20v1.0-red)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20--%2026.x-brightgreen)](https://www.papermc.io)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)
[![SQLite](https://img.shields.io/badge/SQLite-WAL-blue)](https://www.sqlite.org)

> Многовалютный экономический слой поверх **EssentialsX** для сервера **«РАСКОЛ | ДВЕ КОРОНЫ»**.
> Национальные валюты с **золотым покрытием**, межгосударственная **биржа королей**, escrow-сделки,
>SQLite-аудит каждой операции и полная наблюдаемость.

---

## Возможности

- **3-буквенные ID валют** (`GLD`, `RAS`, `VLR`) — единый стандарт бренда.
- **Глобальная валюта** проксируется в EssentialsX (единый источник правды для ESGUI/ChestShop/Towny).
- **Золотой стандарт**: национальная валюта обеспечена резервом нации.
  - `цена = min(паритет, резерв / эмиссия)`; `покрытие = резерв / (эмиссия × паритет)`.
  - Покрытие < 100% → валюта дешевеет; < 50% → КРИЗИС + broadcast.
- **Биржа королей** (`/rv exchange`): стакан ордеров, заморозка средств через escrow, исполнение all-or-nothing.
- **Escrow-примитив**: hold / release / refund для безопасных сделок.
- **Анти-дюп**: rate-limit (token bucket), оптимистичная блокировка, инфляционный чекпоинт.
- **Наблюдаемость**: `/rv admin health`, PAPI-плейсхолдеры, spark-тайминги, WAL-метрики.
- **Надёжность**: SQLite WAL + connection pool + single-writer + ежедневные бекапы + restore.
- **Хуки**: EssentialsX, Towny, LuckPerms, PlaceholderAPI, RaskolCore, spark.
- **Публичный API** для плагинов-друзей (RaskolMarket, RaskolCaravans, RaskolCharters).

## Требования

| Компонент | Версия |
|---|---|
| Ядро | Paper 1.21.x – 26.x |
| Java | 21+ |
| EssentialsX | 2.22.x (обязательно) |
| Vault | 1.7.x (обязательно) |
| Towny | 0.103.x (опционально, нации/биржа) |
| LuckPerms | 5.x (опционально, права) |
| PlaceholderAPI | 2.11.x (опционально) |

## Установка

1. Собери: `mvn clean package` → `target/raskol-vault-<ver>.jar` (или скачай релиз).
2. Положи jar в `plugins/`.
3. Перезапусти сервер (не `/reload`).
4. Настрой `plugins/RaskolVault/currencies.yml` и `rates.yml`.
5. Проверь: `/rv admin health`.

## Команды

| Команда | Описание | Доступ |
|---|---|---|
| `/rv wallet` | GUI кошелька (балансы, конверт, курсы, история) | все |
| `/rv pay <ник> <валюта> <сумма> [причина]` | Перевод | все |
| `/rv convert <из> <в> <сумма>` + `/rv confirm` | Обмен с подтверждением | все |
| `/rv rates` | Таблица курсов | все |
| `/rv exchange list\|my` | Стакан биржи / мои ордера | все |
| `/rv exchange sell\|buy <валюта> <кол-во> <цена>` | Выставить ордер | короли |
| `/rv exchange cancel\|take <id>` | Отменить / исполнить ордер | владелец / короли |
| `/rv nation` | Нация, роль, казна | все |
| `/rv admin …` | Админ-блок (balance, give, mint, audit, backup…) | op |

## Права

| Узел | По умолчанию | Описание |
|---|---|---|
| `raskolvault.use` | true | Кошелёк, pay |
| `raskolvault.convert` | true | Обмен |
| `raskolvault.exchange` | true | Просмотр стакана |
| `raskolvault.admin.*` | op | Админ-блок |
| `raskolvault.api.use` | true | Публичный API |
| `raskolvault.api.admin` | op | API без лимитов |

## PAPI-плейсхолдеры

| Плейсхолдер | Значение |
|---|---|
| `%raskolvault_balance_<CUR>%` | Баланс игрока |
| `%raskolvault_price_<CUR>%` | Цена валюты в GLD |
| `%raskolvault_coverage_<nation>%` | Покрытие резерва нации |
| `%raskolvault_reserve_<nation>%` | Резерв нации в GLD |
| `%raskolvault_rate_<A>_<B>%` | Курс пары |
| `%raskolvault_tps%` / `%raskolvault_ledger_queue%` / `%raskolvault_cache_hit%` | Метрики |

## Конфиг (фрагмент)

```yaml
global-currency:
  id: GLD
  display-name: 'Золото'
  symbol: 'GLD'
  essentials-sync: true

reserve:
  coverage-floor: 0.5      # кризис ниже 50% покрытия
  parity-min: 0.5
  parity-max: 2.0
  tax-max: 0.05
  withdraw-daily-share: 0.25

exchange:
  default-fee: 0.02
  require-confirm: true
  confirm-timeout-seconds: 30
