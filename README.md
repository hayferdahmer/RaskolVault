<div align="center">

# ⚜ RASKOL VAULT

**Многовалютное экономическое ядро для Paper 1.21**
*Сервер «РАСКОЛ | ДВЕ КОРОНЫ»*

`v1.2.6` · `Java 21` · `Paper 1.21.4` · `SQLite (WAL)` · `Proprietary`

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

Хранение: `SQLiteLedger` (WAL, пул, single-writer) + `SafeStorage` (атомарный YAML).

## Команды

| Команда | Описание | Доступ |
|---|---|---|
| `/rv bank` | Банк и кошелёк (вклады, кредиты, переводы) | все |
| `/rv auction` | Аукцион (рынок, фильтры, мои лоты/ставки) | все |
| `/rv exchange` | Биржа ордеров | все |
| `/rv cabinet` | Кабинет правителя (резерв, налоги, ставки) | король/мэр |
| `/rv convert` + `/rv confirm` | Обмен валют | все |
| `/rv rates` | Таблица курсов | все |
| `/rv guide` | Справка | все |
| `/rv admin …` | Админ-блок | `raskolvault.admin` |

> `/rv wallet` работает, но скрыт из tab-комплита для не-админов (кошелёк живёт внутри `/rv bank`).

## PlaceholderAPI

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

## Документация

- [`OPERATIONS.md`](OPERATIONS.md) — ранбук: деплой, бэкапы, restore, инциденты
- [`CHANGELOG.md`](CHANGELOG.md) — история версий
- [`docs/FORMULAS.md`](docs/FORMULAS.md) — экономические формулы и инварианты
- [`INTEGRATION.md`](INTEGRATION.md) — API для плагинов-партнёров

## Лицензия

**RASKOL Proprietary License v1.0** © 2026 hayferdahmer. См. [`LICENSE`](LICENSE).

---

<div align="center">

**РАСКОЛ · ДВЕ КОРОНЫ** — экономика, которая держится на золоте

</div>
