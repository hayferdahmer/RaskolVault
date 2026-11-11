<div align="center">

<img src="banner.svg" alt="RaskolVault — золотой стандарт экономики РАСКОЛ" width="100%">

**Многовалютная финансовая инфраструктура для Paper 26.2**
*Сервер «РАСКОЛ | ДВЕ КОРОНЫ»*

`v1.2.6` · `Java 25` · `Paper 26.2` · `SQLite (WAL)` · `Proprietary`

</div>

---

## Обзор

RaskolVault заменяет одно-валютную модель на **систему национальных валют с золотым обеспечением**.
Глобальная валюта `GLD` проксируется в EssentialsX; каждая нация Towny может выпускать собственную валюту, обеспеченную резервом казны. Цена нацвалюты = `min(паритет, резерв / эмиссия)`; покрытие ниже 50% вызывает кризис.

## Модули

| Модуль | Сервис | Назначение |
|---|---|---|
| Кошелёк | `WalletService` | Балансы, переводы (≤6 блоков), история |
| Обмен | `ConvertEngine` | Конвертация валют с комиссией и налогом |
| Биржа | `ExchangeOrderService` | P2P-ордера на продажу/покупку валют |
| Аукцион | `AuctionService` | Лоты предметов: торги / buyout, снайпинг-кап, репутация |
| Банк | `BankService` | Вклады (процент), кредиты (необеспеченные / под залог) |
| Налоги | `TaxService` | convert / exchange / market / auction → казна нации |
| Казны | `NationTreasury`, `ReserveBank` | Резерв нации, золотой стандарт, покрытие |

Хранение: `SQLiteLedger` (WAL, пул соединений, single-writer, checked transactions и idempotency keys) — источник истины для балансов, банковских счетов, кредитов, пулов, залоговых активов и очереди возврата предметов. `SafeStorage` использует атомарную запись YAML для вспомогательных конфигураций и резервных копий.

## Команды

| Команда | Описание | Доступ |
|---|---|---|
| `/rv bank` | Банк и кошелёк (вклады, кредиты, переводы) | все |
| `/rv bank admin assets` | Реестр залоговых активов | `raskolvault.admin` |
| `/rv bank admin realize <lotId> <сумма>` | Подтверждение фактической реализации залога | `raskolvault.admin` |
| `/rv auction` | Аукцион (рынок, фильтры, мои лоты/ставки) | все |
| `/rv exchange` | Биржа ордеров | все |
| `/rv cabinet` | Кабинет правителя (резерв, налоги, ставки) | король/мэр |
| `/rv convert` + `/rv confirm` | Обмен валют | все |
| `/rv rates` | Таблица курсов | все |
| `/rv guide` | Справка | все |
| `/rv admin …` | Админ-блок | `raskolvault.admin` |

> `/rv wallet` работает, но скрыт из tab-комплита для не-админов (кошелёк живёт внутри `/rv bank`).

## Архитектура и гарантии\n\n- **Транзакционное ядро:** изменения кошельков, резервов, escrow и банковского снимка фиксируются одной SQLite-транзакцией.\n- **Идемпотентность:** операции получают ключ и fingerprint; повтор с тем же payload не применяет изменение повторно, а несовпадающий payload отклоняется.\n- **Банк и залоги:** валютные и предметные залоги учитываются отдельными asset lots. Оценка залога не считается денежным поступлением до подтверждения фактической реализации.\n- **Восстановление предметов:** durable intent и очередь возврата переживают рестарт; перед удалением очереди сохраняются данные игрока с квитанцией. Bukkit player data и SQLite не образуют общей ACID-транзакции, поэтому протокол не заявляет абсолютную exactly-once гарантию при любом типе отказа.\n- **Миграции:** схема SQLite версионируется; legacy YAML импортируется однократно и сохраняется как резервная копия.\n\nПодробности: [протокол залогов](docs/BANK_COLLATERAL_PROTOCOL.md), [миграция SQLite](docs/BANK_SQLITE_MIGRATION.md), [операционный регламент](OPERATIONS.md).\n\n## PlaceholderAPI

```
%raskolvault_balance_<CUR>%      баланс в валюте
%raskolvault_auction_active%     активных лотов
%raskolvault_auction_reputation% репутация продавца
%raskolvault_bank_deposits%      сумма вкладов
%raskolvault_bank_loans%         сумма остатков кредитов
%raskolvault_bank_credit_score%  кредитный скор
%raskolvault_nation%             нация (Towny) или «—»
```

## Установка

1. `mvn clean package` → `target/raskol-vault-1.2.6.jar`
2. Положить jar в `plugins/`
3. Soft-зависимости: EssentialsX, Towny, LuckPerms, PlaceholderAPI
4. Рестарт → `/rv guide`

## Визуальные материалы\n\n- [Основной баннер](banner.svg) · [тёмная версия](banner-dark.svg) · [светлая версия](banner-light.svg)\n- [Social Preview (1280 × 640)](docs/assets/social/social-preview.svg)\n\n## Документация

- [`OPERATIONS.md`](OPERATIONS.md) — ранбук: деплой, бэкапы, restore, инциденты
- [`CHANGELOG.md`](CHANGELOG.md) — история версий
- [`docs/FORMULAS.md`](docs/FORMULAS.md) — экономические формулы и инварианты
- [`docs/BANK_COLLATERAL_PROTOCOL.md`](docs/BANK_COLLATERAL_PROTOCOL.md) — учёт залоговых активов и восстановление предметного залога
- [`INTEGRATION.md`](INTEGRATION.md) — API для плагинов-партнёров

## Лицензия

**RASKOL Proprietary License v1.0** © 2026 hayferdahmer. См. [`LICENSE`](LICENSE).

---

<div align="center">

**РАСКОЛ · ДВЕ КОРОНЫ** — экономика, которая держится на золоте

</div>
